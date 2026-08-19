import { defineConfig } from '@playwright/test'

/**
 * Frontend E2E configuration.
 *
 * E2E runs against the REAL local backend (explicit test-profile fake model
 * gateway — zero public
 * OpenCode requests) and the Vite dev server on port 5174:
 *
 *   terminal 1: cd E:\spec-agent && docker compose up -d
 *   terminal 2: cd E:\spec-agent\backend && .\gradlew.bat bootRun
 *   terminal 3: cd E:\spec-agent\frontend && npm run test:e2e
 *
 * The backend must already be running on http://localhost:8080 with the
 * `SPRING_PROFILES_ACTIVE=test SPEC_AGENT_MODEL_GATEWAY=fake`. Playwright only starts the Vite dev
 * server (port 5174) through webServer; it never starts the backend, and no
 * OpenCode key is required. Port 5174 is used instead of the default 5173 so
 * an unrelated local dev server can never be mistaken for this app.
 */
export default defineConfig({
  testDir: './e2e',
  fullyParallel: false,
  workers: 1,
  timeout: 120_000,
  expect: {
    timeout: 15_000,
  },
  reporter: [['list']],
  use: {
    baseURL: 'http://localhost:5174',
    testIdAttribute: 'data-test',
    trace: 'retain-on-failure',
    screenshot: 'only-on-failure',
  },
  webServer: {
    command: 'npm run dev -- --port 5174 --strictPort',
    url: 'http://localhost:5174',
    reuseExistingServer: true,
    timeout: 60_000,
  },
})
