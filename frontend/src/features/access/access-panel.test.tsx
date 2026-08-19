import { act, cleanup, render, screen } from "@testing-library/react";
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
        "No independently verified external link is available for this record.",
      ),
    ).toBeVisible();
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

    const readerLink = screen.getByRole("link", {
      name: "Read in OpenScholar",
    });
    expect(readerLink).toHaveAttribute(
      "href",
      `/papers/${testIds.paper}/read/${testIds.location}`,
    );
    expect(readerLink).not.toHaveAttribute("target");

    const externalLink = screen.getByRole("link", {
      name: /Open verified PDF externally/,
    });
    expect(externalLink).toHaveAttribute("href", verifiedPdf);
    expect(externalLink).toHaveAttribute("target", "_blank");
    expect(externalLink).toHaveAttribute("rel", "noopener noreferrer");
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
      screen.queryByRole("link", { name: "Read in OpenScholar" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Open verified repository page/ }),
    ).toBeVisible();
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
      screen.queryByRole("link", { name: "Read in OpenScholar" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Open verified PDF externally/ }),
    ).toBeVisible();
    expect(
      screen.getByText(
        "In-app reading requires a fresh, verified HTTPS PDF source.",
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
      screen.getByRole("link", { name: "Read in OpenScholar" }),
    ).toBeVisible();

    await act(async () => vi.advanceTimersByTimeAsync(1_001));

    expect(
      screen.queryByRole("link", { name: "Read in OpenScholar" }),
    ).not.toBeInTheDocument();
    expect(
      screen.getByRole("link", { name: /Open verified PDF externally/ }),
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
      screen.getByRole("button", { name: "Check legal access" }),
    );

    expect(
      await screen.findByRole("link", { name: "Read in OpenScholar" }),
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
      screen.getByRole("button", { name: "Check legal access" }),
    );

    expect(
      await screen.findByText(
        "The backend returned an unexpected access response.",
      ),
    ).toBeVisible();
    expect(
      screen.queryByRole("link", { name: "Read in OpenScholar" }),
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
