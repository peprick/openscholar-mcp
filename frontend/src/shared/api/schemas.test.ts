import { describe, expect, it } from "vitest";

import {
  paperAccessResponseSchema,
  paperDetailsResponseSchema,
  searchResponseSchema,
} from "@/shared/api/schemas";
import {
  paperAccessResponseFixture,
  paperDetailsResponseFixture,
  searchResponseFixture,
} from "@/test/fixtures";

describe("backend response schemas", () => {
  it("accepts representative search, paper-details, and access payloads", () => {
    expect(searchResponseSchema.safeParse(searchResponseFixture()).success).toBe(
      true,
    );
    expect(
      paperDetailsResponseSchema.safeParse(paperDetailsResponseFixture()).success,
    ).toBe(true);
    expect(
      paperAccessResponseSchema.safeParse(paperAccessResponseFixture()).success,
    ).toBe(true);
  });

  it("accepts both HTTP and HTTPS external URLs", () => {
    const search = searchResponseFixture();
    search.results[0]!.landingPageUrl =
      "http://repository.example.edu/items/paper-42";

    expect(searchResponseSchema.safeParse(search).success).toBe(true);
  });

  it("rejects non-HTTP URLs in every externally navigable response field", () => {
    const unsafeSearchLandingPage = searchResponseFixture();
    unsafeSearchLandingPage.results[0]!.landingPageUrl =
      "javascript:alert(document.domain)";

    const unsafeSearchPdf = searchResponseFixture();
    unsafeSearchPdf.results[0]!.reportedPdfUrl =
      "data:application/pdf;base64,JVBERi0xLjQ=";

    const unsafeProvenance = paperDetailsResponseFixture();
    unsafeProvenance.provenance[0]!.sourceUrl =
      "file:///Users/researcher/private-paper.pdf";

    const unsafeAccessLandingPage = paperAccessResponseFixture();
    unsafeAccessLandingPage.locations[0]!.landingPageUrl =
      "javascript:alert(document.domain)";

    const unsafeAccessPdf = paperAccessResponseFixture();
    unsafeAccessPdf.locations[0]!.pdfUrl =
      "data:application/pdf;base64,JVBERi0xLjQ=";

    expect(
      searchResponseSchema.safeParse(unsafeSearchLandingPage).success,
    ).toBe(false);
    expect(searchResponseSchema.safeParse(unsafeSearchPdf).success).toBe(false);
    expect(
      paperDetailsResponseSchema.safeParse(unsafeProvenance).success,
    ).toBe(false);
    expect(
      paperAccessResponseSchema.safeParse(unsafeAccessLandingPage).success,
    ).toBe(false);
    expect(
      paperAccessResponseSchema.safeParse(unsafeAccessPdf).success,
    ).toBe(false);
  });
});
