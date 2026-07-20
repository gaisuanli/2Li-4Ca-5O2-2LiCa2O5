import { defineConfig, devices } from '@playwright/test'
import path from 'node:path'
import { fileURLToPath } from 'node:url'

const frontendDirectory = path.dirname(fileURLToPath(import.meta.url))
const apiPort = Number.parseInt(process.env.E2E_API_PORT || '8080', 10)
const webPort = Number.parseInt(process.env.E2E_WEB_PORT || '5173', 10)
const suppliedBaseUrl = process.env.PLAYWRIGHT_BASE_URL?.trim()
const baseURL = suppliedBaseUrl || `http://127.0.0.1:${webPort}`
const reuseExistingServer = process.env.E2E_REUSE_SERVICES === 'true'
const browserChannel = process.env.E2E_BROWSER_CHANNEL?.trim()
  || (process.platform === 'win32' ? 'msedge' : undefined)
const nodeCommand = `"${process.execPath.replaceAll('"', '""')}"`

const webServer = suppliedBaseUrl ? undefined : [
  {
    name: 'e2e-api',
    command: `${nodeCommand} tests/e2e/start-backend.mjs`,
    cwd: frontendDirectory,
    url: `http://127.0.0.1:${apiPort}/api/health`,
    reuseExistingServer,
    timeout: 120_000,
    stdout: 'pipe',
    stderr: 'pipe',
    env: {
      ...process.env,
      SERVER_PORT: String(apiPort),
      TCP_ENABLED: 'false',
      DEMO_DATA_ENABLED: 'true',
      DEVICE_OFFLINE_MONITOR_ENABLED: 'false',
      SPRINKLER_TIMEOUT_SCAN_ENABLED: 'false',
      AI_AGENT_MODE: 'DEMO',
      DB_URL: 'jdbc:h2:mem:sitesafe-e2e;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1',
      DB_USERNAME: 'sa',
      DB_PASSWORD: '',
      DB_DRIVER: 'org.h2.Driver'
    }
  },
  {
    name: 'e2e-web',
    command: `${nodeCommand} node_modules/vite/bin/vite.js --configLoader runner --host 127.0.0.1 --port ${webPort} --strictPort`,
    cwd: frontendDirectory,
    url: baseURL,
    reuseExistingServer,
    timeout: 60_000,
    stdout: 'pipe',
    stderr: 'pipe',
    env: {
      ...process.env,
      SERVER_PORT: String(apiPort)
    }
  }
]

export default defineConfig({
  testDir: './tests/e2e',
  testMatch: '**/*.spec.js',
  fullyParallel: false,
  workers: 1,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 2 : 1,
  timeout: 45_000,
  expect: { timeout: 10_000 },
  outputDir: './test-results',
  reporter: [
    ['list'],
    ['html', { outputFolder: 'playwright-report', open: 'never' }]
  ],
  use: {
    baseURL,
    ...devices['Desktop Chrome'],
    viewport: { width: 1440, height: 900 },
    deviceScaleFactor: 1,
    colorScheme: 'light',
    locale: 'zh-CN',
    timezoneId: 'Asia/Shanghai',
    actionTimeout: 10_000,
    navigationTimeout: 30_000,
    trace: 'on-first-retry',
    screenshot: 'only-on-failure'
  },
  projects: [
    {
      name: 'chromium-pc',
      use: {
        browserName: 'chromium',
        ...(browserChannel ? { channel: browserChannel } : {})
      }
    }
  ],
  webServer
})
