import { defineConfig } from '@playwright/test'

// The Electron app itself is the "browser" here — no `use.baseURL`/webServer,
// each test launches it directly via `_electron.launch()`. See
// tests/golden-path.spec.ts for the prerequisites (backend jar + frontend
// dist must already be built).
export default defineConfig({
  testDir: './tests',
  fullyParallel: false, // one Electron instance (and one backend port) at a time
  retries: 0,
  reporter: 'list',
  timeout: 60_000,
})
