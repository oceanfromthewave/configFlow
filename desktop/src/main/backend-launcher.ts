/**
 * Spring Boot backend sidecar lifecycle.
 *
 * Responsibilities:
 *  - allocate a free loopback port + generate a per-session token
 *  - locate a Java runtime (CONFIGFLOW_JAVA_HOME > JAVA_HOME > bundled JRE > PATH)
 *  - spawn `java -jar bootstrap*.jar --server.port=<port> --configflow.token=<token>`
 *  - pipe stdout/stderr to <userData>/logs/backend.log (and console in dev)
 *  - poll GET /api/v1/health until 200 {status:"UP"} (250ms interval, 30s timeout)
 *  - kill the whole JVM process tree on app quit (taskkill /T fallback on Windows)
 */

import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import * as fs from 'node:fs';
import * as net from 'node:net';
import * as path from 'node:path';
import { app } from 'electron';
import type { BootstrapInfo } from '../shared/ipc';

const HEALTH_POLL_INTERVAL_MS = 250;
const HEALTH_TIMEOUT_MS = 30_000;

export class BackendLaunchError extends Error {
  constructor(
    readonly title: string,
    message: string,
  ) {
    super(message);
    this.name = 'BackendLaunchError';
  }
}

export interface BackendHandle {
  readonly info: BootstrapInfo;
  /** Absolute path of the log file (undefined when attached to an external backend). */
  readonly logFile?: string;
  /** Kills the backend process tree. No-op when attached to an external backend. */
  stop(): void;
}

/** Finds a free TCP port on the loopback interface. */
export function findFreePort(): Promise<number> {
  return new Promise((resolve, reject) => {
    const server = net.createServer();
    server.unref();
    server.once('error', reject);
    server.listen(0, '127.0.0.1', () => {
      const address = server.address();
      if (address === null || typeof address === 'string') {
        server.close();
        reject(new Error('Could not determine a free port'));
        return;
      }
      const port = address.port;
      server.close(() => resolve(port));
    });
  });
}

export function generateToken(): string {
  return randomBytes(32).toString('hex');
}

/** CONFIGFLOW_JAVA_HOME > JAVA_HOME > bundled JRE (packaged) > `java` on PATH. */
export function locateJava(): string {
  const exe = process.platform === 'win32' ? 'java.exe' : 'java';

  for (const envVar of ['CONFIGFLOW_JAVA_HOME', 'JAVA_HOME'] as const) {
    const home = process.env[envVar];
    if (home) {
      const candidate = path.join(home, 'bin', exe);
      if (fs.existsSync(candidate)) return candidate;
      throw new BackendLaunchError(
        'Java runtime not found',
        `${envVar} is set to "${home}" but "${candidate}" does not exist.`,
      );
    }
  }

  if (app.isPackaged) {
    // Bundled JRE, provided via electron-builder extraResources (M5).
    const bundled = path.join(process.resourcesPath, 'jre', 'bin', exe);
    if (fs.existsSync(bundled)) return bundled;
  }

  // Fall back to PATH; verify it is actually invocable.
  const probe = spawnSync(exe, ['-version'], { stdio: 'ignore', shell: false });
  if (probe.error || probe.status !== 0) {
    throw new BackendLaunchError(
      'Java runtime not found',
      'ConfigFlow could not find a Java runtime.\n\n' +
        'Set CONFIGFLOW_JAVA_HOME or JAVA_HOME to a JRE/JDK 21+ installation, ' +
        'or make sure "java" is available on your PATH.',
    );
  }
  return exe;
}

/** Resolves the backend jar via glob `bootstrap*.jar`. */
export function locateBackendJar(): string {
  const libsDir = app.isPackaged
    ? // Placed by electron-builder extraResources (M5).
      path.join(process.resourcesPath, 'backend')
    : // Monorepo layout: <repo>/desktop and <repo>/backend are siblings.
      path.resolve(app.getAppPath(), '..', 'backend', 'bootstrap', 'build', 'libs');

  let entries: string[] = [];
  try {
    entries = fs.readdirSync(libsDir);
  } catch {
    // handled below
  }
  const jars = entries
    .filter((f) => /^bootstrap.*\.jar$/i.test(f) && !/-plain\.jar$/i.test(f))
    .sort();
  const jar = jars[jars.length - 1];
  if (!jar) {
    throw new BackendLaunchError(
      'Backend not found',
      `No bootstrap*.jar found in:\n${libsDir}\n\n` +
        'Build the backend first (backend/: gradlew :bootstrap:bootJar) ' +
        'or start ConfigFlow in dev mode attached to an external backend ' +
        '(CONFIGFLOW_DEV=1 with CONFIGFLOW_BACKEND_URL + CONFIGFLOW_TOKEN).',
    );
  }
  return path.join(libsDir, jar);
}

function openLogStream(): { file: string; stream: fs.WriteStream } {
  const logsDir = path.join(app.getPath('userData'), 'logs');
  fs.mkdirSync(logsDir, { recursive: true });
  const file = path.join(logsDir, 'backend.log');
  const stream = fs.createWriteStream(file, { flags: 'a' });
  stream.write(`\n===== ConfigFlow backend session ${new Date().toISOString()} =====\n`);
  return { file, stream };
}

async function waitForHealth(info: BootstrapInfo, child: ChildProcess): Promise<void> {
  const healthUrl = `${info.apiBaseUrl}/health`;
  const deadline = Date.now() + HEALTH_TIMEOUT_MS;

  while (Date.now() < deadline) {
    if (child.exitCode !== null) {
      throw new BackendLaunchError(
        'Backend failed to start',
        `The ConfigFlow backend exited with code ${child.exitCode} before becoming healthy.\n` +
          'See the backend log for details.',
      );
    }
    try {
      const res = await fetch(healthUrl, {
        headers: { 'X-ConfigFlow-Token': info.token },
        signal: AbortSignal.timeout(HEALTH_POLL_INTERVAL_MS * 4),
      });
      if (res.ok) return;
    } catch {
      // not up yet — keep polling
    }
    await new Promise((r) => setTimeout(r, HEALTH_POLL_INTERVAL_MS));
  }

  throw new BackendLaunchError(
    'Backend did not become healthy',
    `The ConfigFlow backend did not answer ${healthUrl} within ${HEALTH_TIMEOUT_MS / 1000}s.\n` +
      'See the backend log for details.',
  );
}

function killProcessTree(child: ChildProcess): void {
  if (child.exitCode !== null || child.pid === undefined) return;
  if (process.platform === 'win32') {
    // child.kill() only terminates the direct child; the JVM may have spawned
    // helpers. taskkill /T kills the whole tree.
    spawnSync('taskkill', ['/PID', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
  } else {
    child.kill('SIGTERM');
  }
}

/**
 * Attaches to an externally started backend (dev workflow: backend run from
 * the IDE). No process is spawned or killed.
 */
export function attachToExternalBackend(backendUrl: string, token: string): BackendHandle {
  const apiBaseUrl = `${backendUrl.replace(/\/+$/, '')}/api/v1`;
  return { info: { apiBaseUrl, token }, stop: () => undefined };
}

/**
 * Spawns the backend jar and resolves once the health endpoint reports UP.
 * Throws BackendLaunchError with a user-presentable message on failure.
 */
export async function launchBackend(options: { dev: boolean }): Promise<BackendHandle> {
  const java = locateJava();
  const jar = locateBackendJar();
  const port = await findFreePort();
  const token = generateToken();
  const info: BootstrapInfo = { apiBaseUrl: `http://127.0.0.1:${port}/api/v1`, token };

  const { file: logFile, stream: log } = openLogStream();

  const child = spawn(java, [`-jar`, jar, `--server.port=${port}`, `--configflow.token=${token}`], {
    cwd: path.dirname(jar),
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
    shell: false,
  });

  child.stdout.setEncoding('utf8');
  child.stderr.setEncoding('utf8');
  child.stdout.on('data', (chunk: string) => {
    log.write(chunk);
    if (options.dev) process.stdout.write(`[backend] ${chunk}`);
  });
  child.stderr.on('data', (chunk: string) => {
    log.write(chunk);
    if (options.dev) process.stderr.write(`[backend] ${chunk}`);
  });
  child.on('exit', (code) => {
    log.write(`\n===== backend exited (code ${code}) =====\n`);
    log.end();
  });

  const stop = () => killProcessTree(child);

  // Safety net: if Electron dies hard, still try to take the JVM down.
  process.once('exit', stop);

  try {
    await waitForHealth(info, child);
  } catch (err) {
    stop();
    throw err;
  }

  return { info, logFile, stop };
}
