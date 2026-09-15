import { act, cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";

import { AccessPanel } from "@/features/access/access-panel";
import {
  paperAccessLocationFixture,
  paperAccessResponseFixture,
  testIds,
} from "@/test/fixtures";

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

function unresolvedAccess() {
  return paperAccessResponseFixture({
    status: "UNKNOWN",
    cacheDisposition: "NOT_YET_RESOLVED",
    checkedAt: null,
    freshUntil: null,
    bestLocationId: null,
    providerCoverage: [],
    locations: [],
  });
}

describe("AccessPanel", () => {
  const initialNow = "2026-08-18T05:00:00Z";

  it("never exposes an unverified external URL as a link", () => {
    const unverifiedPdf = "https://unverified.example/paper.pdf";
    const access = paperAccessResponseFixture({
      bestLocationId: null,
      locations: [
        paperAccessLocationFixture({
          best: false,
          pdfUrl: unverifiedPdf,
          verificationStatus: "UNVERIFIED",
          verificationHttpStatus: null,
          verificationContentType: null,
          verificationFailureCode: "NOT_PROBED",
          verifiedAt: null,
        }),
      ],
    });

    render(
      <AccessPanel
        initialAccess={access}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );

    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(
      screen.getByText(
        "This source does not currently have a link OpenScholar can safely open.",
      ),
    ).toBeVisible();
    expect(screen.queryByText(/Opens from the original source/)).not.toBeInTheDocument();
    expect(document.querySelector(`[href="${unverifiedPdf}"]`)).toBeNull();
  });

  it("exposes a verified PDF with safe new-tab link attributes", () => {
    const verifiedPdf = "https://repository.example.edu/items/paper-42.pdf";
    const access = paperAccessResponseFixture({
      locations: [paperAccessLocationFixture({ pdfUrl: verifiedPdf })],
    });

    render(
      <AccessPanel
        initialAccess={access}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );

    const primaryReaderLink = screen.getByRole("link", {
      name: "View PDF",
    });
    expect(primaryReaderLink).toHaveAttribute(
      "href",
      `/papers/${testIds.paper}/read/${testIds.location}`,
    );
    expect(primaryReaderLink).not.toHaveAttribute("target");

    expect(
      screen.queryByRole("link", { name: "View this PDF" }),
    ).not.toBeInTheDocument();

    const externalLink = screen.getByRole("link", {
      name: /View original PDF/,
    });
    expect(externalLink).toHaveAttribute("href", verifiedPdf);
    expect(externalLink).toHaveAttribute("target", "_blank");
    expect(externalLink).toHaveAttribute("rel", "noopener noreferrer");
    expect(screen.getByText("Unpaywall")).toBeVisible();
    expect(screen.queryByText("UNPAYWALL")).not.toBeInTheDocument();
    expect(screen.getByText(/Opens from the original source/)).toBeVisible();
    expect(screen.getByRole("link", { name: "Download PDF" })).toHaveAttribute(
      "href",
      `/papers/${testIds.paper}/read/${testIds.location}?download=1`,
    );
    expect(
      screen.queryByRole("link", { name: "Download this PDF" }),
    ).not.toBeInTheDocument();
    expect(externalLink).toHaveClass("textLink");
    expect(externalLink).not.toHaveClass("button--primary");
  });

  it("keeps one primary PDF pair and offers alternate versions as secondary actions", () => {
    const alternateId = "72cc70a9-76ea-4719-b571-a244594f63d4";
    const access = paperAccessResponseFixture({
      locations: [
        paperAccessLocationFixture(),
        paperAccessLocationFixture({
          id: alternateId,
          best: false,
          hostDomain: "alternate.example.edu",
          pdfUrl: "https://alternate.example.edu/paper.pdf",
        }),
      ],
    });

    const { container } = render(
      <AccessPanel
        initialAccess={access}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );

    expect(screen.getAllByRole("link", { name: "View PDF" })).toHaveLength(1);
    expect(screen.getAllByRole("link", { name: "Download PDF" })).toHaveLength(1);
    expect(container.querySelectorAll(".button--primary")).toHaveLength(1);
    const alternate = screen
      .getByRole("heading", { name: "alternate.example.edu" })
      .closest("article");
    expect(alternate).not.toBeNull();
    const alternateReader = within(alternate!).getByRole("link", {
      name: "Read this version",
    });
    expect(alternateReader).toHaveAttribute(
      "href",
      `/papers/${testIds.paper}/read/${alternateId}`,
    );
    expect(alternateReader).toHaveClass("button--secondary");
    expect(
      within(alternate!).queryByRole("link", { name: /Download/ }),
    ).not.toBeInTheDocument();
  });

  it("does not offer the reader for a verified landing-page-only location", () => {
    const access = paperAccessResponseFixture({
      status: "OPEN_LANDING_PAGE",
      locations: [
        paperAccessLocationFixture({
          accessStatus: "OPEN_LANDING_PAGE",
          pdfUrl: null,
        }),
      ],
    });

    render(
      <AccessPanel
        initialAccess={access}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );

    expect(
      screen.queryByRole("link", { name: "View PDF" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Open full-text page/ }),
    ).toBeVisible();
  });

  it("renders a cached unavailable result without exposing a reader or external link", () => {
    const access = paperAccessResponseFixture({
      status: "UNAVAILABLE",
      cacheDisposition: "RESOLVED",
      bestLocationId: null,
      locations: [],
    });

    render(
      <AccessPanel
        initialAccess={access}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );

    expect(screen.getByText("No free version")).toBeVisible();
    expect(screen.getByText("No free full text found yet.")).toBeVisible();
    expect(
      screen.queryByRole("link", { name: "View PDF" }),
    ).not.toBeInTheDocument();
    expect(screen.queryByRole("link")).not.toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: "Check again" }),
    ).toBeVisible();
  });

  it("keeps provider diagnostics and raw warning codes out of the reader view", () => {
    render(
      <AccessPanel
        initialAccess={paperAccessResponseFixture({
          bestLocationId: null,
          locations: [],
          providerCoverage: [
            { provider: "UNPAYWALL", status: "NOT_CONFIGURED", candidateCount: 0 },
          ],
          warnings: ["UNPAYWALL_NOT_CONFIGURED"],
        })}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );

    expect(screen.getByRole("heading", { name: "Full-text options" })).toBeVisible();
    expect(screen.queryByText("UNPAYWALL_NOT_CONFIGURED")).not.toBeInTheDocument();
    expect(screen.queryByText("Access provider coverage")).not.toBeInTheDocument();
    expect(screen.queryByText("Cache state")).not.toBeInTheDocument();
    expect(screen.queryByText(/candidates/i)).not.toBeInTheDocument();
  });

  it("keeps a stale verified PDF external-only until access is refreshed", () => {
    const access = paperAccessResponseFixture({
      cacheDisposition: "STALE_FALLBACK",
      freshUntil: "2026-08-19T14:31:00Z",
    });

    render(
      <AccessPanel
        initialAccess={access}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );

    expect(
      screen.queryByRole("link", { name: "View PDF" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /View original PDF/ }),
    ).toBeVisible();
    expect(
      screen.getByText(
        "Check access again to open this PDF in the reader.",
      ),
    ).toBeVisible();
  });

  it("expires the reader action while an open paper page remains mounted", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date(initialNow));
    const access = paperAccessResponseFixture({
      freshUntil: "2026-08-18T05:00:01Z",
    });

    render(
      <AccessPanel
        initialAccess={access}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );
    expect(
      screen.getByRole("link", { name: "View PDF" }),
    ).toBeVisible();

    await act(async () => vi.advanceTimersByTimeAsync(1_001));

    expect(
      screen.queryByRole("link", { name: "View PDF" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /View original PDF/ }),
    ).toBeVisible();
  });

  it("adds the reader action after a valid access check", async () => {
    const user = userEvent.setup();
    const access = paperAccessResponseFixture({
      freshUntil: "2099-08-19T14:31:00Z",
    });
    const fetchMock = vi.fn().mockResolvedValue({
      ok: true,
      json: vi.fn().mockResolvedValue(access),
    });
    vi.stubGlobal("fetch", fetchMock);

    render(
      <AccessPanel
        initialAccess={unresolvedAccess()}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );
    await user.click(
      screen.getByRole("button", { name: "Check for free full text" }),
    );

    expect(
      await screen.findByRole("link", { name: "View PDF" }),
    ).toHaveAttribute(
      "href",
      `/papers/${testIds.paper}/read/${testIds.location}`,
    );
    expect(fetchMock).toHaveBeenCalledWith(
      `/api/papers/${testIds.paper}/access`,
      expect.objectContaining({ method: "POST" }),
    );
  });

  it("rejects an access response for a different paper", async () => {
    const user = userEvent.setup();
    const mismatched = paperAccessResponseFixture({
      paperId: "4a0f4958-e2a2-48a2-926d-43e8cb163810",
      freshUntil: "2099-08-19T14:31:00Z",
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue({
        ok: true,
        json: vi.fn().mockResolvedValue(mismatched),
      }),
    );

    render(
      <AccessPanel
        initialAccess={unresolvedAccess()}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );
    await user.click(
      screen.getByRole("button", { name: "Check for free full text" }),
    );

    expect(
      await screen.findByText(
        "Access could not be checked right now. Please try again.",
      ),
    ).toBeVisible();
    expect(
      screen.queryByRole("link", { name: "View PDF" }),
    ).not.toBeInTheDocument();
  });

  it("uses the response-level best location instead of a provider best flag", () => {
    const canonicalLocationId = "72cc70a9-76ea-4719-b571-a244594f63d4";
    const access = paperAccessResponseFixture({
      bestLocationId: canonicalLocationId,
      locations: [
        paperAccessLocationFixture({
          best: true,
          hostDomain: "provider-preferred.example",
        }),
        paperAccessLocationFixture({
          id: canonicalLocationId,
          best: false,
          hostDomain: "canonical-best.example",
          pdfUrl: "https://canonical-best.example/paper.pdf",
        }),
      ],
    });

    const { container } = render(
      <AccessPanel
        initialAccess={access}
        initialNow={initialNow}
        paperId={testIds.paper}
      />,
    );

    expect(container.querySelector(".accessLocation--best")).toHaveTextContent(
      "canonical-best.example",
    );
  });
});
