import { describe, expect, it } from "vitest";

import {
  selectReaderSource,
  selectVerifiedPdfLocation,
} from "@/features/reader/reader-source";
import {
  paperAccessLocationFixture,
  paperAccessResponseFixture,
  testIds,
} from "@/test/fixtures";

const now = new Date("2026-08-18T12:00:00Z");

describe("reader source selection", () => {
  it("selects an exact, fresh, verified HTTPS PDF location", () => {
    const access = paperAccessResponseFixture({
      freshUntil: "2026-08-19T12:00:00Z",
    });

    expect(selectReaderSource(access, testIds.paper, testIds.location, now)).toMatchObject({
      paperId: testIds.paper,
      locationId: testIds.location,
      pdfUrl: "https://repository.example.edu/items/paper-42.pdf",
      source: "UNPAYWALL",
    });
  });

  it.each(["UNVERIFIED", "FAILED"] as const)(
    "rejects a %s location",
    (verificationStatus) => {
      const access = paperAccessResponseFixture({
        freshUntil: "2026-08-19T12:00:00Z",
        locations: [
          paperAccessLocationFixture({
            verificationStatus,
            verifiedAt: null,
          }),
        ],
      });

      expect(
        selectReaderSource(access, testIds.paper, testIds.location, now),
      ).toBeNull();
    },
  );

  it("rejects paper and location ID mismatches", () => {
    const access = paperAccessResponseFixture({
      freshUntil: "2026-08-19T12:00:00Z",
    });

    expect(
      selectReaderSource(
        access,
        "4a0f4958-e2a2-48a2-926d-43e8cb163810",
        testIds.location,
        now,
      ),
    ).toBeNull();
    expect(
      selectReaderSource(
        access,
        testIds.paper,
        "cd30143b-8e07-4a29-a979-ecf09c70bf6c",
        now,
      ),
    ).toBeNull();
  });

  it("rejects landing-page-only and non-PDF access records", () => {
    const access = paperAccessResponseFixture({
      freshUntil: "2026-08-19T12:00:00Z",
      locations: [
        paperAccessLocationFixture({
          accessStatus: "OPEN_LANDING_PAGE",
          pdfUrl: null,
        }),
      ],
    });

    expect(
      selectVerifiedPdfLocation(access, testIds.paper, testIds.location),
    ).toBeNull();
  });

  it.each([
    "http://repository.example.edu/paper.pdf",
    "https://repository.example.edu:8443/paper.pdf",
    "https://user:secret@repository.example.edu/paper.pdf",
    "https://repository.example.edu/paper.pdf#page=2",
  ])("rejects a PDF URL that is unsafe for automatic browser loading: %s", (pdfUrl) => {
    const access = paperAccessResponseFixture({
      freshUntil: "2026-08-19T12:00:00Z",
      locations: [paperAccessLocationFixture({ pdfUrl })],
    });

    expect(
      selectReaderSource(access, testIds.paper, testIds.location, now),
    ).toBeNull();
  });

  it("rejects expired and explicit stale-fallback resolutions", () => {
    const expired = paperAccessResponseFixture({
      freshUntil: "2026-08-18T11:59:59Z",
    });
    const staleFallback = paperAccessResponseFixture({
      cacheDisposition: "STALE_FALLBACK",
      freshUntil: "2026-08-19T12:00:00Z",
    });

    expect(
      selectReaderSource(expired, testIds.paper, testIds.location, now),
    ).toBeNull();
    expect(
      selectReaderSource(staleFallback, testIds.paper, testIds.location, now),
    ).toBeNull();
  });

  it("rejects an invalid freshness timestamp and the exact expiry boundary", () => {
    const invalid = paperAccessResponseFixture({ freshUntil: "not-an-instant" });
    const boundary = paperAccessResponseFixture({
      freshUntil: "2026-08-18T12:00:00Z",
    });

    expect(
      selectReaderSource(invalid, testIds.paper, testIds.location, now),
    ).toBeNull();
    expect(
      selectReaderSource(boundary, testIds.paper, testIds.location, now),
    ).toBeNull();
  });

  it("derives displayed host provenance from the selected PDF URL", () => {
    const access = paperAccessResponseFixture({
      freshUntil: "2026-08-19T12:00:00Z",
      locations: [
        paperAccessLocationFixture({
          hostDomain: "inconsistent.example",
          pdfUrl: "https://canonical.example/paper.pdf",
        }),
      ],
    });

    expect(
      selectReaderSource(access, testIds.paper, testIds.location, now)
        ?.hostDomain,
    ).toBe("canonical.example");
  });

  it("uses the requested location rather than the provider best flag", () => {
    const requestedLocationId = "72cc70a9-76ea-4719-b571-a244594f63d4";
    const access = paperAccessResponseFixture({
      bestLocationId: requestedLocationId,
      freshUntil: "2026-08-19T12:00:00Z",
      locations: [
        paperAccessLocationFixture({ best: true }),
        paperAccessLocationFixture({
          best: false,
          id: requestedLocationId,
          pdfUrl: "https://canonical.example/paper.pdf",
        }),
      ],
    });

    expect(
      selectReaderSource(access, testIds.paper, requestedLocationId, now)?.pdfUrl,
    ).toBe("https://canonical.example/paper.pdf");
  });
});
