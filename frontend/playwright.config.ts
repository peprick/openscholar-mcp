import { defineConfig, devices } from "@playwright/test";

function loopbackPort(name: string, fallback: number): number {
  const value = Number(process.env[name] ?? fallback);
  if (!Number.isInteger(value) || value < 1 || value > 65_535) {
    throw new Error(`${name} must be an integer from 1 to 65535.`);
  }
  return value;
}

const host = "127.0.0.1";
const appPort = loopbackPort("PLAYWRIGHT_APP_PORT", 3_100);
const fixturePort = loopbackPort("PLAYWRIGHT_FIXTURE_PORT", 4_100);
const appOrigin = `http://${host}:${appPort}`;
const fixtureOrigin = `http://${host}:${fixturePort}`;
const appCommand = process.env.CI
  ? "node e2e/support/standalone-server.mjs"
  : `pnpm dev --hostname ${host} --port ${appPort}`;

export default defineConfig({
  testDir: "./e2e",
  outputDir: "test-results/playwright",
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  timeout: 45_000,
  expect: {
    timeout: 12_000,
  },
  reporter: process.env.CI
    ? [
        ["line"],
        ["html", { open: "never", outputFolder: "playwright-report" }],
      ]
    : [["list"]],
  use: {
    baseURL: appOrigin,
    locale: "en-US",
    timezoneId: "UTC",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
    video: "off",
  },
  projects: [
    {
      name: "chromium",
      use: { ...devices["Desktop Chrome"] },
    },
  ],
  webServer: [
    {
      command: "node e2e/support/fixture-server.mjs",
      env: {
        ...process.env,
        PLAYWRIGHT_FIXTURE_PORT: String(fixturePort),
      },
      reuseExistingServer: !process.env.CI,
      stderr: "pipe",
      stdout: "pipe",
      timeout: 30_000,
      url: `${fixtureOrigin}/__fixture/health`,
    },
    {
      command: appCommand,
      env: {
        ...process.env,
        OPENSCHOLAR_API_BASE_URL: fixtureOrigin,
        PLAYWRIGHT_APP_HOST: host,
        PLAYWRIGHT_APP_PORT: String(appPort),
      },
      reuseExistingServer: !process.env.CI,
      stderr: "pipe",
      stdout: "pipe",
      timeout: process.env.CI ? 60_000 : 120_000,
      url: appOrigin,
    },
  ],
});
