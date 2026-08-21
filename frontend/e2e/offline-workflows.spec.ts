import AxeBuilder from "@axe-core/playwright";
import {
  expect,
  test,
  type APIRequestContext,
  type Page,
} from "@playwright/test";

const fixtureOrigin = `http://127.0.0.1:${process.env.PLAYWRIGHT_FIXTURE_PORT ?? "4100"}`;
const fixturePdfHost = "papers.openscholar.test";

const ids = {
  search: "550e8400-e29b-41d4-a716-446655440000",
  nextSearch: "6ce18ca9-4d49-45e7-aa2b-b5208dfa1c3c",
  verifiedPaper: "22c1800e-77f4-4aa9-98d7-5f79fa9a8a1c",
  restrictedPaper: "4a0f4958-e2a2-48a2-926d-43e8cb163810",
  verifiedLocation: "ac3fb646-3b77-4d36-bb44-2c46c66a7202",
} as const;

const unexpectedExternalRequests = new WeakMap<Page, string[]>();

async function installOfflineNetwork(
  page: Page,
  request: APIRequestContext,
): Promise<void> {
  const reset = await request.post(`${fixtureOrigin}/__fixture/reset`);
  expect(reset.ok()).toBe(true);

  const fixturePdf = await request.get(`${fixtureOrigin}/fixtures/paper.pdf`);
  expect(fixturePdf.ok()).toBe(true);
  const pdfBody = await fixturePdf.body();
  const unexpected: string[] = [];
  unexpectedExternalRequests.set(page, unexpected);

  await page.route("**/*", async (route) => {
    const requestUrl = new URL(route.request().url());
    if (requestUrl.hostname === fixturePdfHost) {
      if (requestUrl.pathname === "/offline-paper.pdf") {
        await route.fulfill({
          body: pdfBody,
          contentType: "application/pdf",
          headers: {
            "access-control-allow-origin": "*",
            "cache-control": "no-store",
          },
          status: 200,
        });
      } else {
        await route.abort("blockedbyclient");
      }
      return;
    }

    if (
      (requestUrl.protocol === "http:" || requestUrl.protocol === "https:") &&
      requestUrl.hostname !== "127.0.0.1" &&
      requestUrl.hostname !== "localhost"
    ) {
      unexpected.push(requestUrl.toString());
      await route.abort("blockedbyclient");
      return;
    }
    await route.continue();
  });
}

async function expectNoSeriousAccessibilityViolations(page: Page): Promise<void> {
  const scan = await new AxeBuilder({ page })
    .withTags(["wcag2a", "wcag2aa", "wcag21a", "wcag21aa"])
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

test.beforeEach(async ({ page, request }) => {
  await installOfflineNetwork(page, request);
});

test.afterEach(async ({ page }) => {
  expect(unexpectedExternalRequests.get(page) ?? []).toEqual([]);
});

test("search exposes cache state, provider coverage, and immutable next-page context", async ({
  page,
}) => {
  await page.goto("/");
  await expect(page.getByText("Backend connected")).toBeVisible();

  await page
    .getByRole("searchbox", { name: "Research topic" })
    .fill("graph neural networks for drug discovery");
  await page.getByRole("button", { name: "Search papers" }).click();

  await expect(page).toHaveURL(`/searches/${ids.search}`);
  await expect(
    page.getByRole("heading", {
      level: 1,
      name: "graph neural networks for drug discovery",
    }),
  ).toBeVisible();
  await expect(page.getByText("Exact Hit", { exact: true })).toBeVisible();
  await expect(page.getByLabel("Provider coverage")).toContainText(
    "OPENALEX",
  );
  await expect(page.getByLabel("Provider coverage")).toContainText("SUCCESS");
  await expect(page.getByLabel("Search warnings")).toContainText(
    "Crossref fixture intentionally reports degraded coverage.",
  );

  await page.getByText("Why this result?").click();
  await expect(page.getByText(/Record from OPENALEX/)).toBeVisible();
  await expect(page.getByText(/provider-reported/)).toBeVisible();
  await expectNoSeriousAccessibilityViolations(page);

  await page.getByRole("button", { name: "Next results" }).click();
  await expect(page).toHaveURL(`/searches/${ids.nextSearch}`);
  await expect(
    page.getByRole("link", {
      name: "A publisher-restricted molecular benchmark",
    }),
  ).toBeVisible();
  await expect(page.getByText("Miss Fetched", { exact: true })).toBeVisible();
});

test("paper provenance distinguishes verified reading from restricted access", async ({
  page,
}) => {
  await page.goto(`/papers/${ids.verifiedPaper}`);
  await expect(
    page.getByRole("heading", {
      level: 1,
      name: "Graph neural networks for molecular property prediction",
    }),
  ).toBeVisible();
  await expect(page.getByRole("heading", { name: "Provenance" })).toBeVisible();
  await expect(page.getByText("Authorship source")).toBeVisible();
  await expect(page.getByText("Open Pdf", { exact: true })).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Read in OpenScholar" }),
  ).toBeVisible();
  await expect(page.getByText("CC-BY-4.0")).toBeVisible();
  await expectNoSeriousAccessibilityViolations(page);

  await page.goto(`/papers/${ids.restrictedPaper}`);
  await expect(
    page.getByRole("heading", {
      level: 1,
      name: "A publisher-restricted molecular benchmark",
    }),
  ).toBeVisible();
  await expect(page.getByText("Restricted", { exact: true })).toBeVisible();
  await expect(page.getByText("Failed", { exact: true })).toBeVisible();
  await expect(
    page.getByText(
      "No independently verified external link is available for this record.",
    ),
  ).toBeVisible();
  await expect(
    page.getByRole("link", { name: "Read in OpenScholar" }),
  ).toHaveCount(0);
  await expectNoSeriousAccessibilityViolations(page);
});

test("reader supports the skip link, focus-preserving page keys, zoom, and visible text", async ({
  page,
}) => {
  await page.goto(
    `/papers/${ids.verifiedPaper}/read/${ids.verifiedLocation}`,
  );

  await page.keyboard.press("Tab");
  const skipLink = page.getByRole("link", { name: "Skip to main content" });
  await expect(skipLink).toBeFocused();
  await expect(skipLink).toBeVisible();
  await skipLink.press("Enter");
  await expect(page).toHaveURL(/#main-content$/);

  const viewport = page.getByRole("region", {
    name: "PDF page viewport for Graph neural networks for molecular property prediction",
  });
  await expect(viewport).toHaveAttribute("data-reader-state", "ready");
  await expect(page.locator("canvas.readerCanvas")).toBeVisible();
  await expect(
    page.getByText("Page 1 of 2", { exact: true }),
  ).toBeVisible();

  await viewport.focus();
  await page.keyboard.press("End");
  await expect(
    page.getByText("Page 2 of 2", { exact: true }),
  ).toBeVisible();
  await expect(viewport).toBeFocused();

  await page.keyboard.press("=");
  await expect(page.getByText("125%", { exact: true })).toBeVisible();
  await expect(viewport).toBeFocused();
  await page.keyboard.press("0");
  await expect(page.getByText("100%", { exact: true })).toBeVisible();

  const pageNumber = page.getByRole("spinbutton", { name: "Page" });
  await pageNumber.fill("1");
  await page.getByRole("button", { name: "Go" }).click();
  await expect(
    page.getByText("Page 1 of 2", { exact: true }),
  ).toBeVisible();
  await expect(viewport).toBeFocused();

  const showText = page.getByRole("button", { name: "Show page text" });
  await expect(showText).toBeVisible();
  await showText.click();
  await expect(
    page.getByRole("region", { name: "Accessible text for page 1" }),
  ).toContainText("verified research is readable without provider traffic");
  await expectNoSeriousAccessibilityViolations(page);
});

test("paper save and individual citation actions stay inside the offline stack", async ({
  page,
}) => {
  await page.goto(`/papers/${ids.verifiedPaper}`);
  await page.getByRole("button", { name: "Save to collection" }).click();
  await expect(page.getByLabel("Collection")).toHaveValue(
    "76fb2843-407a-4499-b3ac-59935440e928",
  );
  await page.getByLabel("Reading status").selectOption("READING");
  await page.getByLabel("Tags (optional)").fill("methods, priority");
  await page.getByRole("button", { name: "Save paper" }).click();
  await expect(
    page
      .getByRole("region", { name: "Save to your library" })
      .getByRole("status"),
  ).toContainText("Saved to “Thesis foundations”.");

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("link", { name: /Download BibTeX/ }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("openscholar-paper.bib");
  expect(await download.failure()).toBeNull();
});

test("library creates collections and exports selected citations", async ({
  page,
}) => {
  await page.goto("/library");
  await expect(
    page.getByRole("heading", { level: 1, name: "Research library" }),
  ).toBeVisible();

  await page.getByLabel("Collection name").fill("Review shortlist");
  await page
    .getByLabel("Description (optional)")
    .fill("Papers selected by the offline browser suite.");
  await page.getByRole("button", { name: "Create collection" }).click();
  await expect(
    page.getByRole("link", { name: "Review shortlist" }),
  ).toBeVisible();

  await page
    .getByRole("checkbox", {
      name: /Select Graph neural networks for molecular property prediction/,
    })
    .check();
  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export BibTeX" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("offline-library.bib");
  expect(await download.failure()).toBeNull();
  await expect(
    page.getByRole("region", { name: "Saved papers" }).getByRole("status"),
  ).toContainText("Exported 1 selected papers.");
  await expectNoSeriousAccessibilityViolations(page);
});
