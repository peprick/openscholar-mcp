import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const fixtureOrigin = `http://127.0.0.1:${process.env.PLAYWRIGHT_FIXTURE_PORT ?? "4100"}`;
const paperId = "22c1800e-77f4-4aa9-98d7-5f79fa9a8a1c";
const locationId = "ac3fb646-3b77-4d36-bb44-2c46c66a7202";
const title = "Graph neural networks for molecular property prediction";
const readerPath = `/papers/${paperId}/read/${locationId}`;
const unexpectedRequests = new WeakMap<Page, string[]>();

test.use({ serviceWorkers: "block" });

async function expectFittedPage(page: Page): Promise<void> {
  await expect(page.locator(".readerViewport")).toHaveCount(1);
  await expect(page.locator(".readerViewport")).toHaveAttribute(
    "data-reader-state",
    "ready",
  );
  await expect
    .poll(() =>
      page.locator(".readerViewport").evaluate((viewport) => {
        const canvas = viewport.querySelector("canvas");
        const styles = getComputedStyle(viewport);
        const available =
          viewport.clientWidth -
          Number.parseFloat(styles.paddingLeft) -
          Number.parseFloat(styles.paddingRight);
        return (canvas?.getBoundingClientRect().width ?? Infinity) - available;
      }),
    )
    .toBeLessThanOrEqual(1);
  await expect
    .poll(() =>
      page.evaluate(
        () =>
          document.documentElement.scrollWidth -
          document.documentElement.clientWidth,
      ),
    )
    .toBeLessThanOrEqual(1);
}

test.beforeEach(async ({ page, request }) => {
  expect((await request.post(`${fixtureOrigin}/__fixture/reset`)).ok()).toBe(true);
  const pdf = await request.get(`${fixtureOrigin}/fixtures/paper.pdf`);
  expect(pdf.ok()).toBe(true);
  const pdfBody = await pdf.body();
  const unexpected: string[] = [];
  unexpectedRequests.set(page, unexpected);
  await page.route("**/*", async (route) => {
    const url = new URL(route.request().url());
    if (
      url.hostname === "papers.openscholar.test" &&
      url.pathname === "/offline-paper.pdf"
    ) {
      await route.fulfill({
        body: pdfBody,
        contentType: "application/pdf",
        headers: {
          "access-control-allow-origin": "*",
          "cache-control": "no-store",
        },
        status: 200,
      });
    } else if (
      !["localhost", "127.0.0.1"].includes(url.hostname) &&
      ["http:", "https:"].includes(url.protocol)
    ) {
      unexpected.push(url.toString());
      await route.abort("blockedbyclient");
    } else {
      await route.continue();
    }
  });
});

test.afterEach(async ({ page }) => {
  expect(unexpectedRequests.get(page) ?? []).toEqual([]);
});

test("mobile search exposes the PDF filter, opens the reader, and downloads the loaded document", async ({ page }, testInfo) => {
  await page.goto("/");
  await page
    .getByRole("searchbox", { name: "Research topic" })
    .fill("graph neural networks for drug discovery");
  await page.getByRole("checkbox", { name: "PDF link reported only" }).check();
  const searchRequest = page.waitForRequest(
    (request) =>
      request.url().endsWith("/api/searches") && request.method() === "POST",
  );
  await page.getByRole("button", { name: "Search papers" }).click();
  expect((await searchRequest).postDataJSON()).toMatchObject({
    filters: { pdfAvailableOnly: true },
  });
  await expect(
    page.getByRole("button", { name: `Download PDF: ${title}` }),
  ).toBeVisible();
  await page.getByRole("button", { name: `View PDF: ${title}` }).click();
  await expect(page).toHaveURL(readerPath);
  await expectFittedPage(page);
  await expect(page.getByRole("link", { name: /^View PDF/ })).toHaveCount(1);
  const downloadEvent = page.waitForEvent("download");
  await page.getByRole("button", { name: "Download PDF", exact: true }).click();
  const download = await downloadEvent;
  expect(download.suggestedFilename()).toBe(
    "Graph-neural-networks-for-molecular-property-prediction.pdf",
  );
  expect(await download.failure()).toBeNull();
  const scan = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21aa", "wcag22aa"])
    .analyze();
  expect(
    scan.violations.filter((violation) =>
      ["critical", "serious"].includes(violation.impact ?? ""),
    ),
  ).toEqual([]);
  await page.screenshot({
    fullPage: true,
    path: testInfo.outputPath("mobile-pdf-reader.png"),
  });
});

test("fit width follows container resizing without overriding a chosen manual zoom", async ({ page }) => {
  await page.setViewportSize({ width: 320, height: 800 });
  await page.goto(readerPath);
  await expectFittedPage(page);
  await expect(page.getByRole("button", { name: "Fit width" })).toHaveAttribute(
    "aria-pressed",
    "true",
  );
  await page.getByRole("button", { name: "Zoom in" }).click();
  await expect(page.locator(".readerViewport")).toHaveAttribute(
    "data-reader-state",
    "ready",
  );
  const manualWidth = await page
    .locator("canvas.readerCanvas")
    .evaluate((canvas) => canvas.getBoundingClientRect().width);
  await expect(page.getByRole("button", { name: "Fit width" })).toHaveAttribute(
    "aria-pressed",
    "false",
  );
  await page.setViewportSize({ width: 430, height: 800 });
  await expect
    .poll(() =>
      page
        .locator("canvas.readerCanvas")
        .evaluate((canvas) => canvas.getBoundingClientRect().width),
    )
    .toBe(manualWidth);
  await page.getByRole("button", { name: "Fit width" }).click();
  await expectFittedPage(page);
  await page.setViewportSize({ width: 360, height: 800 });
  await expectFittedPage(page);
});

test("connectivity notices remain above sticky reader controls and paper metadata", async ({ page }) => {
  await page.setViewportSize({ width: 1280, height: 800 });
  await page.addInitScript(() =>
    Object.defineProperty(navigator, "onLine", {
      configurable: true,
      get: () => false,
    }),
  );
  await page.goto(readerPath);
  await expectFittedPage(page);
  const notice = page.locator(".connectivityRegion--offline");
  await expect(notice).toBeVisible();
  await page.locator(".readerViewport").scrollIntoViewIfNeeded();
  await expect
    .poll(async () => {
      const banner = await notice.boundingBox();
      const controls = await page.locator(".readerControls").boundingBox();
      return (
        (controls?.y ?? -Infinity) -
        ((banner?.y ?? 0) + (banner?.height ?? 0))
      );
    })
    .toBeGreaterThanOrEqual(0);

  await page.goto(`/papers/${paperId}`);
  await expect(notice).toBeVisible();
  await expect(page.locator(".paperLayout")).toHaveCount(1);
  // The metadata sidebar is sticky only while its paper-layout container is in view.
  await page.locator(".paperLayout").evaluate((layout) =>
    window.scrollTo({
      behavior: "instant",
      top: window.scrollY + layout.getBoundingClientRect().top + 100,
    }),
  );
  await expect
    .poll(async () => {
      const banner = await notice.boundingBox();
      const metadata = await page.locator(".metadataCard").boundingBox();
      return (
        (metadata?.y ?? -Infinity) -
        ((banner?.y ?? 0) + (banner?.height ?? 0))
      );
    })
    .toBeGreaterThanOrEqual(0);
  await expect(
    page.getByRole("link", { name: "View PDF", exact: true }),
  ).toHaveCount(1);
  await expect(
    page.getByRole("link", { name: "Download PDF", exact: true }),
  ).toHaveCount(1);
  await expect(
    page.getByRole("link", { name: "Download this PDF" }),
  ).toHaveCount(0);
});
