# ConfigFlow Desktop (Electron shell)

The desktop shell for ConfigFlow. Its only responsibilities (per
`docs/03-architecture.md`) are:

1. hosting the web UI (`frontend/`) in a hardened BrowserWindow,
2. managing the Spring Boot backend sidecar's lifecycle, and
3. exposing a minimal set of native capabilities (dialogs, shell) over IPC.

No business or UI logic lives here.

## Layout

```
desktop/
├── src/
│   ├── main/
│   │   ├── index.ts             # app lifecycle, window, menu, navigation hardening
│   │   ├── backend-launcher.ts  # port/token, java lookup, spawn, health poll, kill tree
│   │   └── ipc/                 # native IPC handlers (dialog, shell)
│   ├── preload/
│   │   └── index.ts             # sandboxed contextBridge (see contract below)
│   └── shared/
│       ├── ipc.ts               # IPC channel names + BootstrapInfo type
│       └── bridge.d.ts          # window.configflow / window.configflowNative types
├── electron-builder.yml         # packaging skeleton (real bundling is M5)
├── tsconfig.json
└── package.json
```

Build output goes to `dist/` (plain `tsc`, CommonJS). There is no bundler:
the renderer is a separate Vite project (`frontend/`), and main/preload are
plain Node-target code, so `tsc` is all the tooling this package needs.

## Backend handshake contract

On startup the main process:

1. finds a free loopback port (`net.createServer` on port 0),
2. generates a session token: `crypto.randomBytes(32).toString('hex')`,
3. locates Java (`CONFIGFLOW_JAVA_HOME` → `JAVA_HOME` → bundled JRE when
   packaged → `java` on PATH),
4. resolves the jar via glob `bootstrap*.jar` in
   `backend/bootstrap/build/libs/` (dev) or `<resources>/backend/` (packaged),
5. spawns:

   ```
   java -jar <jar> --server.port=<port> --configflow.token=<token>
   ```

6. polls `GET http://127.0.0.1:<port>/api/v1/health` with header
   `X-ConfigFlow-Token: <token>` every 250 ms (30 s timeout) and only creates
   the window once it returns 200,
7. kills the JVM process tree on quit (`taskkill /PID <pid> /T /F` on Windows).

Backend stdout/stderr is appended to `<userData>/logs/backend.log`
(`%APPDATA%/configflow-desktop/logs/backend.log` on Windows) and echoed to the
console in dev mode.

### What the renderer receives

The preload script performs a **synchronous IPC handshake**
(`ipcRenderer.sendSync('configflow:bootstrap')`) before any renderer code
runs, then exposes via `contextBridge`:

```ts
window.configflow = {
  apiBaseUrl: 'http://127.0.0.1:<port>/api/v1',
  token: '<64-hex-char session token>',
};

window.configflowNative = {
  selectDirectory(): Promise<string | null>;   // OS folder picker
  openExternal(url: string): Promise<void>;    // http/https only
  showItemInFolder(path: string): Promise<void>;
};
```

Type declarations: `src/shared/bridge.d.ts` (the frontend should reference
this file). Every backend request must carry the
`X-ConfigFlow-Token: <token>` header.

Why sendSync instead of `additionalArguments`: command-line arguments of the
renderer process are visible to other local processes (e.g.
`Win32_Process.CommandLine`), which would leak the token; the synchronous
handshake keeps it in-process and still guarantees `window.configflow` is set
before the first renderer script executes. Main only answers the handshake
for its own window's main frame.

## Security posture

- Backend binds `127.0.0.1` only; token is required on every request.
- `contextIsolation: true`, `nodeIntegration: false`, `sandbox: true`.
  The preload must therefore stay **self-contained** — it may only
  `require('electron')`; imports from `src/shared` must be type-only.
- `window.open` / navigation to foreign origins is denied; http/https URLs
  are handed to the default browser instead.
- IPC handlers reject senders other than the app's own window.

## Dev workflow

```powershell
cd desktop
npm install
npm run typecheck        # tsc --noEmit
npm run build            # tsc -> dist/
npm run dev              # tsc + electron in dev mode (loads http://localhost:5173)
```

Dev mode is enabled by `CONFIGFLOW_DEV=1` **or** the `--dev` argument (the
`dev` script passes `--dev` so no env var juggling is needed on Windows).

Modes:

| Mode | Renderer | Backend |
|---|---|---|
| dev (default) | `http://localhost:5173` (Vite; friendly error page if it's down) | spawned from `backend/bootstrap/build/libs/bootstrap*.jar` |
| dev, attached | `http://localhost:5173` | **not spawned** — set `CONFIGFLOW_BACKEND_URL` and `CONFIGFLOW_TOKEN` to attach to a backend you started yourself (e.g. from the IDE) |
| production (`npm start`) | `../frontend/dist/index.html` | spawned |

Attach example:

```powershell
$env:CONFIGFLOW_DEV = '1'
$env:CONFIGFLOW_BACKEND_URL = 'http://127.0.0.1:8080'
$env:CONFIGFLOW_TOKEN = 'dev-token-matching-the-backend'
npm run dev
```

## Packaging (M5)

```powershell
backend: .\gradlew.bat :bootstrap:bootJar
frontend: npm run build
installer: .\build-jre.ps1     # jlink; module list measured via jdeps, see the script header
desktop: npm run package       # tsc + electron-builder -> release/ConfigFlow Setup <version>.exe
```

`electron-builder.yml` bundles all three: the frontend `dist/` into the
app (`files`), the backend fat jar and the jlinked JRE into `extraResources`
(resolved at runtime the same way as the dev/unpackaged paths above — see
`backend-launcher.ts`). Icons and code signing are still open (need project
assets/decisions, not just config).
