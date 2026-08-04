# ConfigFlow E2E (Playwright)

Drives the real Electron shell end to end (docs/03 architecture table:
"Playwright (Electron)"). Not a browser test — `_electron.launch()` starts
the actual app, which spawns the actual backend jar and loads the actual
built frontend, same as a user would get.

## Prerequisites

Build the three pieces the app needs at runtime (there's no watch/dev mode
here — this exercises the production load path, `file://` + spawned jar):

```powershell
backend: .\gradlew.bat :bootstrap:bootJar
frontend: npm run build
desktop: npm run build
```

`tests/golden-path.spec.ts` checks for these on startup and fails with a
clear message (not a timeout) if any is missing.

Also needs a JDK 21+ on `JAVA_HOME` or `CONFIGFLOW_JAVA_HOME` — same
resolution `backend-launcher.ts` uses.

## Run

```powershell
cd e2e
npm install
npm test
```

Each run gets its own temp `CONFIGFLOW_DB_PATH` and repo folder (see the
spec) so it never touches your real `~/.configflow` or registered
repositories.

## Scope

`golden-path.spec.ts` covers init → working-tree watcher picks up a new
file → stage → commit → shows up in history. Clone/push are out of scope
for now — they need a real remote, which is a separate (and flakier)
concern from proving the app boots and the core local flow works.

The UI renders in Korean by default here (no language switcher exists yet),
so locators use the `ko.json` strings, not the English ones.
