import { readFile } from "node:fs/promises";

import AxeBuilder from "@axe-core/playwright";
import { expect, test, type Page } from "@playwright/test";

const fixtureOrigin = `http://127.0.0.1:${process.env.PLAYWRIGHT_FIXTURE_PORT ?? "4100"}`;

const ids = {
  user: "3b46419d-3bdf-42c8-82b2-6fac9cd8dd2d",
  search: "550e8400-e29b-41d4-a716-446655440000",
  paper: "22c1800e-77f4-4aa9-98d7-5f79fa9a8a1c",
  collection: "76fb2843-407a-4499-b3ac-59935440e928",
} as const;

test.use({ serviceWorkers: "block" });

test.beforeEach(async ({ request }) => {
  const reset = await request.post(`${fixtureOrigin}/__fixture/reset`);
  expect(reset.ok()).toBe(true);
});

async function expectNoSeriousAccessibilityViolations(
  page: Page,
): Promise<void> {
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
  const violations = scan.violations
    .filter(
      (violation) =>
        violation.impact === "critical" || violation.impact === "serious",
    )
    .map((violation) => ({
      help: violation.help,
      id: violation.id,
      targets: violation.nodes.flatMap((node) => node.target),
    }));
  expect(violations).toEqual([]);
}

test("privacy export downloads only the current owner's deterministic data", async ({
  context,
  page,
}) => {
  await page.goto("/data");
  await expect(
    page.getByRole("heading", { level: 1, name: "Your data & privacy" }),
  ).toBeVisible();

  const exportResponsePromise = context.waitForEvent(
    "response",
    (response) =>
      response.url().endsWith("/api/privacy/export") &&
      response.request().method() === "GET",
  );
  const downloadPromise = page.waitForEvent("download");
  const exportLink = page.getByRole("link", { name: "Download my data" });
  await expect(exportLink).toHaveAttribute("href", "/api/privacy/export");
  await expect(exportLink).toHaveAttribute("target", "_blank");
  await expect(exportLink).toHaveAttribute("rel", "noopener");
  await exportLink.click();

  const [exportResponse, download] = await Promise.all([
    exportResponsePromise,
    downloadPromise,
  ]);
  expect(exportResponse.status()).toBe(200);
  expect(exportResponse.headers()["cache-control"]).toContain("no-store");
  expect(exportResponse.headers()["content-type"]).toContain("application/json");
  expect(download.suggestedFilename()).toBe("openscholar-personal-data.json");
  expect(await download.failure()).toBeNull();
  await expect(
    page
      .getByRole("status")
      .filter({ hasText: "data export download started" }),
  ).toBeVisible();

  const downloadPath = await download.path();
  if (downloadPath === null) throw new Error("Privacy export did not create a file.");
  const exported = JSON.parse(await readFile(downloadPath, "utf8"));
  expect(exported).toMatchObject({
    userId: ids.user,
    displayName: "Offline Researcher",
    accountCreatedAt: "2026-08-01T08:00:00Z",
    generatedAt: "2026-08-20T12:00:00Z",
    searches: [
      {
        searchId: ids.search,
        query: "graph neural networks for drug discovery",
        requestedMode: "AUTO",
        executionSource: "EXACT_CACHE",
        resultCount: 1,
      },
    ],
    collections: [
      {
        collectionId: ids.collection,
        name: "Thesis foundations",
      },
    ],
    savedPapers: [
      {
        collectionId: ids.collection,
        paperId: ids.paper,
        readingStatus: "UNREAD",
        tags: ["methods"],
      },
    ],
  });
  await expectNoSeriousAccessibilityViolations(page);
});

test("a failed privacy export keeps the privacy center available", async ({
  context,
  page,
}) => {
  await context.route("**/api/privacy/export", async (route) => {
    await route.fulfill({
      body: JSON.stringify({
        title: "Export unavailable",
        status: 503,
        detail: "The export could not be prepared.",
        code: "BACKEND_UNAVAILABLE",
      }),
      contentType: "application/problem+json",
      status: 503,
    });
  });
  await page.goto("/data");

  const errorPagePromise = context.waitForEvent("page");
  await page.getByRole("link", { name: "Download my data" }).click();
  const errorPage = await errorPagePromise;
  await errorPage.waitForLoadState("domcontentloaded");

  await expect(
    page.getByRole("heading", { level: 1, name: "Your data & privacy" }),
  ).toBeVisible();
  await expect(
    page.getByRole("status").filter({ hasText: "download started" }),
  ).toBeVisible();
  await expect(errorPage.locator("body")).toContainText("Export unavailable");
  await errorPage.close();
});

test("account deletion requires the exact phrase and leaves an empty workspace", async ({
  page,
  request,
}) => {
  await page.goto("/data");

  const confirmation = page.getByLabel("Type DELETE_MY_DATA to confirm", {
    exact: true,
  });
  const deleteButton = page.getByRole("button", {
    name: "Delete my OpenScholar data",
    exact: true,
  });

  await expect(deleteButton).toBeDisabled();
  await confirmation.fill("DELETE_MY_DAT");
  await expect(deleteButton).toBeDisabled();

  await confirmation.fill("DELETE_MY_DATA");
  await expect(deleteButton).toBeEnabled();

  const deleteRequestPromise = page.waitForRequest(
    (candidate) =>
      candidate.url().endsWith("/api/privacy/account") &&
      candidate.method() === "DELETE",
  );
  const deleteResponsePromise = page.waitForResponse(
    (response) =>
      response.url().endsWith("/api/privacy/account") &&
      response.request().method() === "DELETE",
  );
  await deleteButton.click();

  const [deleteRequest, deleteResponse] = await Promise.all([
    deleteRequestPromise,
    deleteResponsePromise,
  ]);
  expect(deleteRequest.postDataJSON()).toEqual({
    confirmation: "DELETE_MY_DATA",
  });
  expect(deleteResponse.status()).toBe(204);
  await expect(
    page
      .getByRole("status")
      .filter({ hasText: "Your OpenScholar data was deleted." }),
  ).toBeVisible();
  await expect(
    page.getByText("You can start again with an empty research workspace."),
  ).toBeVisible();

  const emptyExportResponse = await request.get("/api/privacy/export");
  expect(emptyExportResponse.status()).toBe(200);
  expect(await emptyExportResponse.json()).toMatchObject({
    userId: ids.user,
    searches: [],
    collections: [],
    savedPapers: [],
  });
  await expectNoSeriousAccessibilityViolations(page);
});
