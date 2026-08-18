import type {
  PaperAccessLocation,
  PaperAccessResponse,
  PaperDetailsResponse,
  SearchResponse,
} from "@/shared/api/schemas";

export const testIds = {
  search: "550e8400-e29b-41d4-a716-446655440000",
  paper: "22c1800e-77f4-4aa9-98d7-5f79fa9a8a1c",
  location: "ac3fb646-3b77-4d36-bb44-2c46c66a7202",
} as const;

export function searchResponseFixture(): SearchResponse {
  return {
    searchId: testIds.search,
    query: "graph neural networks for drug discovery",
    queryFingerprint:
      "90ff4c90bc8c9f06583d33a443f923f65d28ac04147b9ad8cf9a64221759c0de",
    cacheDisposition: "MISS_FETCHED",
    searchedAt: "2026-08-17T14:30:00Z",
    freshUntil: "2026-08-17T15:30:00Z",
    nextCursor: "eyJvZmZzZXQiOjIwfQ",
    providerCoverage: [
      {
        provider: "OPENALEX",
        status: "SUCCESS",
        returnedCount: 1,
        totalMatches: 428,
      },
    ],
    warnings: [],
    results: [
      {
        rank: 1,
        paperId: testIds.paper,
        title: "Graph neural networks for molecular property prediction",
        abstractText:
          "A reproducible evaluation of graph representations for molecules.",
        authors: [
          {
            name: "Ada Researcher",
            orcid: "0000-0002-1825-0097",
            openAlexId: "A5012345678",
          },
        ],
        publicationDate: "2025-06-12",
        publicationYear: 2025,
        documentType: "ARTICLE",
        language: "en",
        venue: "Journal of Molecular Machine Learning",
        citationCount: 37,
        identifiers: {
          doi: "10.5555/openscholar.2025.42",
          arxiv: "2501.01234",
          openAlex: "W4400123456",
        },
        reportedOpenAccess: true,
        landingPageUrl: "https://doi.org/10.5555/openscholar.2025.42",
        reportedPdfUrl: "https://arxiv.org/pdf/2501.01234",
        score: 0.94,
        rankingReasons: [
          { feature: "TEXT_RELEVANCE", value: 0.91 },
          { feature: "CITATION_SIGNAL", value: 0.63 },
        ],
        provenance: [
          {
            provider: "OPENALEX",
            providerRecordId: "W4400123456",
            retrievedAt: "2026-08-17T14:29:58Z",
          },
        ],
      },
    ],
  };
}

export function paperDetailsResponseFixture(): PaperDetailsResponse {
  return {
    paperId: testIds.paper,
    title: "Graph neural networks for molecular property prediction",
    abstractText:
      "A reproducible evaluation of graph representations for molecules.",
    authors: [
      {
        name: "Ada Researcher",
        orcid: "0000-0002-1825-0097",
        openAlexId: "A5012345678",
        position: 0,
        corresponding: true,
      },
    ],
    publicationDate: "2025-06-12",
    publicationYear: 2025,
    documentType: "ARTICLE",
    language: "en",
    venueName: "Journal of Molecular Machine Learning",
    citationCount: 37,
    citationCountAsOf: "2026-08-17T14:29:58Z",
    identifiers: [
      {
        type: "DOI",
        namespace: "doi",
        value: "10.5555/openscholar.2025.42",
      },
      {
        type: "OPENALEX",
        namespace: "openalex",
        value: "W4400123456",
      },
    ],
    metadataCompleteness: 0.94,
    metadataUpdatedAt: "2026-08-17T14:29:58Z",
    provenance: [
      {
        provider: "OPENALEX",
        providerRecordId: "W4400123456",
        sourceUrl: "https://api.openalex.org/works/W4400123456",
        providerUpdatedAt: "2026-08-16T09:15:00Z",
        retrievedAt: "2026-08-17T14:29:58Z",
        reportedOpenAccess: true,
        authorshipSource: true,
      },
    ],
    access: {
      status: "OPEN_PDF",
      cacheDisposition: "CACHE_HIT",
      checkedAt: "2026-08-17T14:31:00Z",
      freshUntil: "2026-08-18T14:31:00Z",
      bestLocationId: testIds.location,
      locationCount: 1,
      warnings: [],
    },
  };
}

export function paperAccessLocationFixture(
  overrides: Partial<PaperAccessLocation> = {},
): PaperAccessLocation {
  return {
    id: testIds.location,
    source: "UNPAYWALL",
    best: true,
    accessStatus: "OPEN_PDF",
    versionType: "ACCEPTED_MANUSCRIPT",
    hostType: "REPOSITORY",
    landingPageUrl: "https://repository.example.edu/items/paper-42",
    pdfUrl: "https://repository.example.edu/items/paper-42.pdf",
    hostDomain: "repository.example.edu",
    license: "CC-BY-4.0",
    evidence: "oa repository (via Unpaywall)",
    contentHandling: "LINK_ONLY",
    verificationStatus: "VERIFIED",
    verificationHttpStatus: 200,
    verificationContentType: "application/pdf",
    verificationFailureCode: null,
    providerUpdatedAt: "2026-08-16T09:15:00Z",
    retrievedAt: "2026-08-17T14:30:30Z",
    lastSeenAt: "2026-08-17T14:31:00Z",
    verifiedAt: "2026-08-17T14:31:00Z",
    ...overrides,
  };
}

export function paperAccessResponseFixture(
  overrides: Partial<PaperAccessResponse> = {},
): PaperAccessResponse {
  return {
    paperId: testIds.paper,
    status: "OPEN_PDF",
    cacheDisposition: "RESOLVED",
    checkedAt: "2026-08-17T14:31:00Z",
    freshUntil: "2026-08-18T14:31:00Z",
    bestLocationId: testIds.location,
    providerCoverage: [
      { provider: "UNPAYWALL", status: "SUCCESS", candidateCount: 1 },
    ],
    warnings: [],
    locations: [paperAccessLocationFixture()],
    ...overrides,
  };
}
