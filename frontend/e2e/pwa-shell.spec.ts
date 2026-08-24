import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";

const publicShellPaths = new Set([
  "/apple-touch-icon.png",
  "/icon.svg",
  "/icon-192.png",
  "/icon-512.png",
  "/manifest.webmanifest",
  "/offline.html",
]);

test("installs an account-neutral shell and falls back safely when disconnected", async ({
  context,
  page,
}) => {
  await page.goto("/");
  await page.waitForFunction(async () => {
    if (!("serviceWorker" in navigator)) return false;
    const registration = await navigator.serviceWorker.ready;
    return registration.active !== null;
  });

  if (!(await page.evaluate(() => navigator.serviceWorker.controller !== null))) {
    await page.reload();
  }
  await page.waitForFunction(() => navigator.serviceWorker.controller !== null);

  await page.goto("/library");
  await expect(
    page.getByRole("heading", { level: 1, name: "Research library" }),
  ).toBeVisible();

  const cachedUrls = await page.evaluate(async () => {
    const urls: string[] = [];
    for (const name of await caches.keys()) {
      if (!name.startsWith("openscholar-shell-")) continue;
      const cache = await caches.open(name);
      urls.push(...(await cache.keys()).map((request) => request.url));
    }
    return urls;
  });

  expect(cachedUrls.length).toBeGreaterThan(0);
  for (const value of cachedUrls) {
    const url = new URL(value);
    expect(url.origin).toBe(new URL(page.url()).origin);
    expect(
      publicShellPaths.has(url.pathname) ||
        (url.search === "" && url.pathname.startsWith("/_next/static/")),
    ).toBe(true);
  }
  expect(cachedUrls.some((value) => new URL(value).pathname === "/library")).toBe(
    false,
  );

  await page.goto("/");
  await context.setOffline(true);
  await expect(page.getByRole("status")).toContainText(
    "OpenScholar can't be reached.",
  );
  await expect(page.getByRole("button", { name: "Search papers" })).toBeDisabled();
  await expect(page.getByRole("button", { name: "Search locally" })).toBeDisabled();

  await page.goto("/library?offline-check=1");
  await expect(
    page.getByRole("heading", {
      level: 1,
      name: "OpenScholar can’t be reached.",
    }),
  ).toBeVisible();
  await expect(page.getByText("Research library", { exact: true })).toHaveCount(0);
  await expect(page.getByText("No paper documents are stored")).toBeVisible();

  const scan = await new AxeBuilder({ page }).analyze();
  expect(
    scan.violations
      .filter(
        (violation) =>
          violation.impact === "critical" || violation.impact === "serious",
      )
      .map((violation) => violation.id),
  ).toEqual([]);

  await context.setOffline(false);
  await page.getByRole("link", { name: "Try again" }).click();
  await expect(
    page.getByRole("heading", { level: 1, name: /Find the paper/ }),
  ).toBeVisible();
});
