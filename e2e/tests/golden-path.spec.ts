import * as fs from 'node:fs'
import * as os from 'node:os'
import * as path from 'node:path'
import { test, expect, _electron as electron, type ElectronApplication } from '@playwright/test'

// Golden path (docs/03 architecture table): init a repo, make a change,
// stage + commit it, see it in history. Push is scoped out for now — it
// needs a real remote, which is its own can of worms; this already
// exercises the queue, the working-tree watcher (M5), and the commit flow
// end to end against a real backend and a real git repo on disk.

const repoRoot = path.resolve(__dirname, '..', '..')
const backendLibs = path.join(repoRoot, 'backend', 'bootstrap', 'build', 'libs')
const frontendDist = path.join(repoRoot, 'frontend', 'dist', 'index.html')
const desktopDir = path.join(repoRoot, 'desktop')
const desktopMain = path.join(desktopDir, 'dist', 'main', 'index.js')
// `electron` lives in desktop/node_modules, not e2e/node_modules — Playwright
// needs the binary path explicitly rather than resolving it from here.
// eslint-disable-next-line @typescript-eslint/no-var-requires
const electronExecutable: string = require(path.join(desktopDir, 'node_modules', 'electron')) as string

function findBackendJar(): string {
  const jars = fs
    .readdirSync(backendLibs)
    .filter((f) => /^bootstrap.*\.jar$/i.test(f) && !/-plain\.jar$/i.test(f))
  if (jars.length === 0) {
    throw new Error(
      `No bootstrap*.jar in ${backendLibs} — build it first: ` +
        `backend: gradlew.bat :bootstrap:bootJar`,
    )
  }
  return jars[0]
}

test.beforeAll(() => {
  findBackendJar()
  if (!fs.existsSync(frontendDist)) {
    throw new Error(`Frontend not built — run: frontend: npm run build (missing ${frontendDist})`)
  }
  if (!fs.existsSync(desktopMain)) {
    throw new Error(`Desktop not built — run: desktop: npm run build (missing ${desktopMain})`)
  }
})

test('init a repo, stage and commit a file, see it in history', async () => {
  const scratch = fs.mkdtempSync(path.join(os.tmpdir(), 'configflow-e2e-'))
  const repoDir = path.join(scratch, 'repo')
  fs.mkdirSync(repoDir)

  let app: ElectronApplication | undefined
  try {
    app = await electron.launch({
      executablePath: electronExecutable,
      // `.` (not the resolved index.js path) so Electron reads
      // desktop/package.json and app.getAppPath() resolves to desktopDir —
      // locateBackendJar()'s unpackaged fallback depends on that.
      args: ['.'],
      cwd: desktopDir,
      env: {
        ...process.env,
        // Isolates this run's repository list/credentials from the
        // developer's real ~/.configflow — see application.properties.
        CONFIGFLOW_DB_PATH: path.join(scratch, 'configflow.db'),
        // backend-launcher.ts falls back to `java` on PATH otherwise, which
        // this shell doesn't necessarily have set up.
        CONFIGFLOW_JAVA_HOME: process.env.CONFIGFLOW_JAVA_HOME ?? process.env.JAVA_HOME ?? '',
      },
    })
    const window = await app.firstWindow()
    await window.waitForLoadState('domcontentloaded')

    // The app renders in Korean by default in this environment (no language
    // switcher UI exists yet) — locators below use the ko.json strings.

    // Electron's native folder picker is outside Chromium's automation
    // surface; stub it in the main process instead of driving an OS dialog.
    await app.evaluate(({ dialog }, dir) => {
      dialog.showOpenDialog = (async () => ({ canceled: false, filePaths: [dir] })) as never
    }, repoDir)

    await window.getByRole('button', { name: 'Init' }).click()
    // Init registers the repo (it shows up under Recent) but doesn't open
    // it — that's a separate click, same as any other repo in the list.
    await window.getByRole('button', { name: /repo/i }).click()
    const workingTreeTab = window.getByRole('tab', { name: '작업 트리' })
    await expect(workingTreeTab).toBeVisible({ timeout: 15_000 })
    await workingTreeTab.click()

    fs.writeFileSync(path.join(repoDir, 'hello.txt'), 'hello from e2e\n')

    // The working-tree watcher (M5) picks this up off the filesystem —
    // no manual refresh action exists in the UI.
    await expect(window.locator('[title="hello.txt"]')).toBeVisible({ timeout: 10_000 })

    await window.getByRole('button', { name: '모두 스테이지' }).click()
    await expect(window.getByRole('button', { name: '모두 해제' })).toBeVisible()

    await window.getByPlaceholder('커밋 메시지를 입력하세요').fill('e2e: add hello.txt')
    await window.getByRole('button', { name: '커밋', exact: true }).click()

    await expect(window.getByText('작업 트리가 깨끗합니다.')).toBeVisible({ timeout: 10_000 })

    await window.getByRole('tab', { name: '히스토리' }).click()
    await expect(window.getByText('e2e: add hello.txt')).toBeVisible()
  } finally {
    await app?.close()
    fs.rmSync(scratch, { recursive: true, force: true })
  }
})
