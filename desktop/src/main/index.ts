/**
 * Electron main entry.
 *
 * Startup sequence:
 *   1. resolve run mode (dev vs production)
 *   2. start (or attach to) the Spring Boot backend and wait for health
 *   3. register native IPC handlers with the port/token bootstrap info
 *   4. create the BrowserWindow (renderer gets the bootstrap via preload sendSync)
 */

import * as fs from 'node:fs';
import * as path from 'node:path';
import { app, BrowserWindow, dialog, Menu, shell, type WebContents } from 'electron';
import {
  attachToExternalBackend,
  BackendLaunchError,
  launchBackend,
  type BackendHandle,
} from './backend-launcher';
import { registerIpcHandlers } from './ipc';
import { isSafeExternalUrl } from './ipc/shell';

const isDev = process.env.CONFIGFLOW_DEV === '1' || process.argv.includes('--dev');
const DEV_SERVER_URL = 'http://localhost:5173';

let mainWindow: BrowserWindow | null = null;
let backend: BackendHandle | null = null;

function createMenu(): void {
  const template: Electron.MenuItemConstructorOptions[] = [
    {
      label: 'File',
      submenu: [{ role: 'quit' }],
    },
    {
      label: 'View',
      submenu: [
        { role: 'reload' },
        { role: 'toggleDevTools' },
        { type: 'separator' },
        { role: 'resetZoom' },
        { role: 'zoomIn' },
        { role: 'zoomOut' },
        { type: 'separator' },
        { role: 'togglefullscreen' },
      ],
    },
    {
      label: 'Help',
      submenu: [
        {
          label: 'ConfigFlow on GitHub',
          click: () => void shell.openExternal('https://github.com/configflow'),
        },
      ],
    },
  ];
  Menu.setApplicationMenu(Menu.buildFromTemplate(template));
}

/** Any navigation/window.open to a foreign origin goes to the default browser. */
function hardenNavigation(contents: WebContents): void {
  contents.setWindowOpenHandler(({ url }) => {
    if (isSafeExternalUrl(url)) void shell.openExternal(url);
    return { action: 'deny' };
  });

  contents.on('will-navigate', (event, url) => {
    const allowed = isDev ? url.startsWith(DEV_SERVER_URL) : url.startsWith('file:');
    if (!allowed) {
      event.preventDefault();
      if (isSafeExternalUrl(url)) void shell.openExternal(url);
    }
  });
}

function renderErrorPage(title: string, detail: string): string {
  const esc = (s: string) =>
    s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
  return (
    'data:text/html;charset=utf-8,' +
    encodeURIComponent(
      `<!doctype html><html><body style="background:#1e1f24;color:#c9cbd1;` +
        `font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;height:100vh;margin:0">` +
        `<div style="max-width:560px;text-align:center"><h2 style="color:#e6e7ea">${esc(title)}</h2>` +
        `<p style="line-height:1.6;white-space:pre-wrap">${esc(detail)}</p></div></body></html>`,
    )
  );
}

function resolveProductionIndex(): string {
  // Unpackaged production run: monorepo sibling layout.
  // TODO(M5): when packaged, frontend/dist is bundled into the asar via
  // electron-builder `files` and this resolves inside app.getAppPath().
  return app.isPackaged
    ? path.join(app.getAppPath(), 'frontend', 'dist', 'index.html')
    : path.resolve(app.getAppPath(), '..', 'frontend', 'dist', 'index.html');
}

async function loadRenderer(win: BrowserWindow): Promise<void> {
  if (isDev) {
    try {
      await win.loadURL(DEV_SERVER_URL);
    } catch {
      await win.loadURL(
        renderErrorPage(
          'Vite dev server not running',
          `Could not reach ${DEV_SERVER_URL}.\n\n` +
            `Start the frontend dev server first:\n  cd frontend && npm run dev\n\n` +
            `Then press Ctrl+R to reload.`,
        ),
      );
    }
    return;
  }

  const index = resolveProductionIndex();
  if (!fs.existsSync(index)) {
    await win.loadURL(
      renderErrorPage(
        'Frontend build not found',
        `Expected the built frontend at:\n${index}\n\nBuild it first: cd frontend && npm run build`,
      ),
    );
    return;
  }
  await win.loadFile(index);
}

function createWindow(): BrowserWindow {
  const win = new BrowserWindow({
    width: 1400,
    height: 900,
    minWidth: 960,
    minHeight: 600,
    backgroundColor: '#1e1f24',
    show: false,
    webPreferences: {
      preload: path.join(__dirname, '..', 'preload', 'index.js'),
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true, // preload is self-contained (only requires 'electron'), so sandbox is safe
    },
  });

  win.once('ready-to-show', () => win.show());
  hardenNavigation(win.webContents);
  win.on('closed', () => {
    mainWindow = null;
  });
  return win;
}

async function startBackend(): Promise<BackendHandle> {
  const externalUrl = process.env.CONFIGFLOW_BACKEND_URL;
  const externalToken = process.env.CONFIGFLOW_TOKEN;
  if (isDev && externalUrl && externalToken) {
    console.log(`[configflow] attaching to external backend at ${externalUrl}`);
    return attachToExternalBackend(externalUrl, externalToken);
  }
  const handle = await launchBackend({ dev: isDev });
  console.log(`[configflow] backend healthy at ${handle.info.apiBaseUrl} (log: ${handle.logFile})`);
  return handle;
}

async function bootstrap(): Promise<void> {
  createMenu();

  try {
    backend = await startBackend();
  } catch (err) {
    const title = err instanceof BackendLaunchError ? err.title : 'Failed to start backend';
    const message = err instanceof Error ? err.message : String(err);
    dialog.showErrorBox(title, message);
    app.quit();
    return;
  }

  registerIpcHandlers({
    bootstrap: backend.info,
    isTrustedSender: (sender) => mainWindow !== null && sender === mainWindow.webContents,
  });

  mainWindow = createWindow();
  await loadRenderer(mainWindow);
}

const gotLock = app.requestSingleInstanceLock();
if (!gotLock) {
  app.quit();
} else {
  app.on('second-instance', () => {
    if (mainWindow) {
      if (mainWindow.isMinimized()) mainWindow.restore();
      mainWindow.focus();
    }
  });

  app.whenReady().then(bootstrap);

  app.on('window-all-closed', () => {
    // Single-window utility app: quit on all platforms (revisit for macOS in M5).
    app.quit();
  });

  app.on('before-quit', () => {
    backend?.stop();
    backend = null;
  });
}
