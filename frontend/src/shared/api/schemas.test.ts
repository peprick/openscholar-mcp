import { describe, expect, it } from "vitest";

import {
  createSearchRequestSchema,
  paperAccessResponseSchema,
  paperDetailsResponseSchema,
  paperIdentifierLookupRequestSchema,
  paperIdentifierResolutionSchema,
  relatedPapersResponseSchema,
  searchResponseSchema,
} from "@/shared/api/schemas";
import {
  paperAccessResponseFixture,
  paperDetailsResponseFixture,
  relatedPapersResponseFixture,
  searchResponseFixture,
} from "@/test/fixtures";

describe("backend response schemas", () => {
  it("defaults search mode to AUTO and accepts every supported mode", () => {
    const automatic = createSearchRequestSchema.parse({
      query: "graph neural networks",
    });
    expect(automatic.mode).toBe("AUTO");
    expect(automatic.filters.pdfAvailableOnly).toBe(false);

    for (const mode of ["AUTO", "ONLINE", "LOCAL"] as const) {
      expect(
        createSearchRequestSchema.safeParse({
          query: "graph neural networks",
          mode,
        }).success,
      ).toBe(true);
    }

    expect(
      createSearchRequestSchema.safeParse({
        query: "graph neural networks",
        mode: "OFFLINE",
      }).success,
    ).toBe(false);
    expect(
      createSearchRequestSchema.safeParse({
        query: "graph neural networks",
        mode: "LOCAL",
        forceRefresh: true,
      }).success,
    ).toBe(false);
  });

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
    expect(
      relatedPapersResponseSchema.safeParse(relatedPapersResponseFixture()).success,
    ).toBe(true);
  });

  it("validates the bounded exact-identifier request and strict resolution", () => {
    expect(
      paperIdentifierLookupRequestSchema.parse({
        identifier: "  doi:10.1000/example  ",
      }),
    ).toEqual({ identifier: "doi:10.1000/example" });
    expect(
      paperIdentifierLookupRequestSchema.safeParse({ identifier: "line\nbreak" })
        .success,
    ).toBe(false);
    expect(
      paperIdentifierResolutionSchema.safeParse({
        paperId: "11111111-1111-4111-8111-111111111111",
        identifierType: "DOI",
        normalizedValue: "10.1000/example",
      }).success,
    ).toBe(true);
    expect(
      paperIdentifierResolutionSchema.safeParse({
        paperId: "11111111-1111-4111-8111-111111111111",
        identifierType: "PMID",
        normalizedValue: "1234",
      }).success,
    ).toBe(false);
    expect(
      paperIdentifierResolutionSchema.safeParse({
        paperId: "11111111-1111-4111-8111-111111111111",
        identifierType: "ARXIV",
        normalizedValue: "2401.12345",
        title: "Unexpected field",
      }).success,
    ).toBe(false);
  });

  it("rejects unknown search execution sources", () => {
    const search = {
      ...searchResponseFixture(),
      executionSource: "BROWSER_CACHE",
    };

    expect(searchResponseSchema.safeParse(search).success).toBe(false);
  });

  it("strictly rejects unexpected related-paper response fields", () => {
    const extraEnvelopeField = {
      ...relatedPapersResponseFixture(),
      cacheDisposition: "EXACT_HIT",
    };
    const extraResultField = relatedPapersResponseFixture();
    extraResultField.results[0] = {
      ...extraResultField.results[0]!,
      reportedOpenAccess: true,
    } as (typeof extraResultField.results)[number];

    expect(relatedPapersResponseSchema.safeParse(extraEnvelopeField).success).toBe(
      false,
    );
    expect(relatedPapersResponseSchema.safeParse(extraResultField).success).toBe(
      false,
    );
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
