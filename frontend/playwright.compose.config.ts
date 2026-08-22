import { defineConfig, devices } from "@playwright/test";

const baseURL = process.env.PLAYWRIGHT_COMPOSE_ORIGIN ?? "http://127.0.0.1:3300";

export default defineConfig({
  testDir: "./e2e",
  testMatch: "compose-workflow.spec.ts",
  outputDir: "test-results/playwright-compose",
  fullyParallel: false,
  forbidOnly: Boolean(process.env.CI),
  retries: process.env.CI ? 1 : 0,
  workers: 1,
  timeout: 60_000,
  expect: { timeout: 15_000 },
  reporter: process.env.CI
    ? [
        ["line"],
        [
          "html",
          { open: "never", outputFolder: "playwright-compose-report" },
        ],
      ]
    : [["list"]],
  use: {
    baseURL,
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
});
