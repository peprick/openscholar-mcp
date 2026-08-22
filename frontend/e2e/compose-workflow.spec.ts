import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";
import { mkdir } from "node:fs/promises";
import path from "node:path";

const runKey = process.env.PLAYWRIGHT_RUN_KEY ?? `${Date.now()}-${process.pid}`;
const topic = `compose backed graph retrieval ${runKey}`;
const collectionName = `Compose evidence ${runKey}`;

async function capturePortfolioScreenshot(
  page: Page,
  filename: string,
): Promise<void> {
  const configuredDirectory = process.env.PORTFOLIO_SCREENSHOT_DIR;
  if (configuredDirectory === undefined) return;
  const outputDirectory = path.resolve(configuredDirectory);
  await mkdir(outputDirectory, { recursive: true });
  await page.addStyleTag({
    content: [
      ".siteHeader { position: static !important; }",
      ".metadataCard { position: static !important; }",
      ".readerToolbar { position: static !important; }",
      ".skipLink { display: none !important; }",
    ].join("\n"),
  });
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({
    animations: "disabled",
    fullPage: true,
    path: path.join(outputDirectory, filename),
  });
}

async function expectNoSeriousAccessibilityViolations(page: Page): Promise<void> {
  const scan = await new AxeBuilder({ page })
    .withTags([
      "wcag2a",
      "wcag2aa",
      "wcag21a",
      "wcag21aa",
      "wcag22a",
      "wcag22aa",
    ])
    .analyze();
  expect(
    scan.violations
      .filter(
        (violation) =>
          violation.impact === "critical" || violation.impact === "serious",
      )
      .map((violation) => ({
        id: violation.id,
        help: violation.help,
        targets: violation.nodes.flatMap((node) => node.target),
      })),
  ).toEqual([]);
}

test("Compose stack persists, deduplicates, caches, saves, and exports research", async ({
  page,
}) => {
  await page.goto("/library");
  await expect(
    page.getByRole("heading", { level: 1, name: "Research library" }),
  ).toBeVisible();
  await page.getByLabel("Collection name").fill(collectionName);
  await page
    .getByLabel("Description (optional)")
    .fill("Created through the real Next.js, Spring Boot, and PostgreSQL stack.");
  await page.getByRole("button", { name: "Create collection" }).click();
  const createdCollectionLink = page.getByRole("link", { name: collectionName });
  await expect(createdCollectionLink).toBeVisible();
  const collectionHref = await createdCollectionLink.getAttribute("href");
  if (collectionHref === null) throw new Error("Created collection link has no href");

  await page.goto("/");
  await expect(page.getByText("Backend connected")).toBeVisible();
  await page.getByRole("searchbox", { name: "Research topic" }).fill(topic);
  const coldSearchResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/searches") &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Search papers" }).click();
  const coldSearch = await coldSearchResponse;
  expect(coldSearch.status()).toBe(201);
  await expect(coldSearch.json()).resolves.toMatchObject({
    cacheDisposition: "MISS_FETCHED",
  });

  await expect(page.getByRole("heading", { level: 1, name: topic })).toBeVisible();
  await expect(page.getByText("Exact Hit", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Compose-backed graph retrieval study" }),
  ).toHaveCount(1);
  await expect(
    page.getByRole("link", { name: "Compose-backed restricted benchmark" }),
  ).toBeVisible();
  await expect(page.getByLabel("Provider coverage")).toContainText("3 returned");
  await expect(page.getByLabel("Provider coverage")).toContainText("3 matches");
  const firstSearchUrl = page.url();
  await expectNoSeriousAccessibilityViolations(page);
  await capturePortfolioScreenshot(page, "search-results.png");

  await page.goto("/");
  await page.getByRole("searchbox", { name: "Research topic" }).fill(topic);
  const cachedSearchResponse = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/searches") &&
      response.request().method() === "POST",
  );
  await page.getByRole("button", { name: "Search papers" }).click();
  const cachedSearch = await cachedSearchResponse;
  expect(cachedSearch.status()).toBe(200);
  await expect(cachedSearch.json()).resolves.toMatchObject({
    cacheDisposition: "EXACT_HIT",
  });
  await expect(page.getByText("Exact Hit", { exact: true })).toBeVisible();
  expect(page.url()).toBe(firstSearchUrl);

  await page
    .getByRole("link", { name: "Compose-backed graph retrieval study" })
    .click();
  await expect(
    page.getByRole("heading", {
      level: 1,
      name: "Compose-backed graph retrieval study",
    }),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: "Provenance" })).toBeVisible();
  await page.getByRole("button", { name: "Save to collection" }).click();
  await page
    .getByLabel("Collection", { exact: true })
    .selectOption({ label: collectionName });
  await page.getByLabel("Reading status").selectOption("READING");
  await page.getByLabel("Tags (optional)").fill("compose, verified");
  await page.getByRole("button", { name: "Save paper" }).click();
  await expect(
    page
      .getByRole("region", { name: "Save to your library" })
      .getByRole("status"),
  ).toContainText(`Saved to “${collectionName}”.`);

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("link", { name: /Download BibTeX/ }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(
    /^openscholar_[0-9a-f]{32}\.bib$/,
  );
  expect(await download.failure()).toBeNull();
  await expectNoSeriousAccessibilityViolations(page);
  await capturePortfolioScreenshot(page, "paper-details.png");

  await page.goto("/library");
  await page
    .getByLabel("Collection", { exact: true })
    .selectOption({ label: collectionName });
  await page.getByRole("button", { name: "Filter library" }).click();
  await expect(
    page.getByRole("link", { name: "Compose-backed graph retrieval study" }),
  ).toHaveCount(1);
  await expect(page.getByText("compose", { exact: true })).toBeVisible();
  await expectNoSeriousAccessibilityViolations(page);

  await page.goto(collectionHref);
  await expect(page.getByRole("heading", { level: 1, name: collectionName })).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Compose-backed graph retrieval study" }),
  ).toHaveCount(1);
  await expectNoSeriousAccessibilityViolations(page);
  await capturePortfolioScreenshot(page, "saved-collection.png");
});
