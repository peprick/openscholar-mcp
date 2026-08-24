import { createServer } from "node:http";

const host = "127.0.0.1";
const port = Number(process.env.PLAYWRIGHT_FIXTURE_PORT ?? 4_100);
if (!Number.isInteger(port) || port < 1 || port > 65_535) {
  throw new Error("PLAYWRIGHT_FIXTURE_PORT must be an integer from 1 to 65535.");
}

const ids = Object.freeze({
  search: "550e8400-e29b-41d4-a716-446655440000",
  nextSearch: "6ce18ca9-4d49-45e7-aa2b-b5208dfa1c3c",
  verifiedPaper: "22c1800e-77f4-4aa9-98d7-5f79fa9a8a1c",
  restrictedPaper: "4a0f4958-e2a2-48a2-926d-43e8cb163810",
  relatedPaper: "dc237d32-2e9a-4723-ab55-c45949912f27",
  verifiedLocation: "ac3fb646-3b77-4d36-bb44-2c46c66a7202",
  restrictedLocation: "cd30143b-8e07-4a29-a979-ecf09c70bf6c",
  collection: "76fb2843-407a-4499-b3ac-59935440e928",
  createdCollection: "11111111-1111-4111-8111-111111111111",
});

const fixturePdfUrl = "https://papers.openscholar.test/offline-paper.pdf";
const retrievedAt = "2026-08-20T09:15:00Z";
const checkedAt = "2026-08-20T09:16:00Z";
const freshUntil = "2099-12-31T23:59:59Z";
const createdAt = "2026-08-20T10:00:00Z";

function buildPdf() {
  const pageOne = [
    "BT",
    "/F1 24 Tf",
    "72 720 Td",
    "(OpenScholar offline fixture) Tj",
    "/F1 13 Tf",
    "0 -38 Td",
    "(Page one: verified research is readable without provider traffic.) Tj",
    "ET",
  ].join("\n");
  const pageTwo = [
    "BT",
    "/F1 24 Tf",
    "72 720 Td",
    "(OpenScholar offline fixture) Tj",
    "/F1 13 Tf",
    "0 -38 Td",
    "(Page two: keyboard navigation reached this deterministic page.) Tj",
    "ET",
  ].join("\n");
  const objects = [
    "<< /Type /Catalog /Pages 2 0 R >>",
    "<< /Type /Pages /Kids [3 0 R 4 0 R] /Count 2 >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 6 0 R >>",
    "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 5 0 R >> >> /Contents 7 0 R >>",
    "<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica >>",
    `<< /Length ${Buffer.byteLength(pageOne)} >>\nstream\n${pageOne}\nendstream`,
    `<< /Length ${Buffer.byteLength(pageTwo)} >>\nstream\n${pageTwo}\nendstream`,
  ];

  const chunks = [Buffer.from("%PDF-1.4\n%\xe2\xe3\xcf\xd3\n", "latin1")];
  const offsets = [0];
  let length = chunks[0].length;
  objects.forEach((object, index) => {
    offsets.push(length);
    const chunk = Buffer.from(`${index + 1} 0 obj\n${object}\nendobj\n`, "ascii");
    chunks.push(chunk);
    length += chunk.length;
  });
  const xrefOffset = length;
  const xref = [
    `xref\n0 ${objects.length + 1}`,
    "0000000000 65535 f ",
    ...offsets.slice(1).map((offset) => `${String(offset).padStart(10, "0")} 00000 n `),
    `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>`,
    `startxref\n${xrefOffset}`,
    "%%EOF",
    "",
  ].join("\n");
  chunks.push(Buffer.from(xref, "ascii"));
  return Buffer.concat(chunks);
}

const pdf = buildPdf();

function initialState() {
  return {
    query: "graph neural networks for drug discovery",
    searchMode: "AUTO",
    collections: [
      {
        collectionId: ids.collection,
        name: "Thesis foundations",
        description: "Core papers for the literature review.",
        paperCount: 1,
        createdAt,
        updatedAt: "2026-08-20T10:30:00Z",
      },
    ],
    savedPapers: [
      {
        collectionId: ids.collection,
        collectionName: "Thesis foundations",
        paperId: ids.verifiedPaper,
        title: "Graph neural networks for molecular property prediction",
        authors: ["Ada Researcher"],
        publicationYear: 2025,
        documentType: "ARTICLE",
        readingStatus: "UNREAD",
        tags: ["methods"],
        savedAt: "2026-08-20T10:15:00Z",
        updatedAt: "2026-08-20T10:15:00Z",
      },
    ],
  };
}

let state = initialState();

function author(name, openAlexId) {
  return {
    name,
    orcid: name === "Ada Researcher" ? "0000-0002-1825-0097" : null,
    openAlexId,
  };
}

function searchResult({ rank, paperId, restricted = false }) {
  return {
    rank,
    paperId,
    title: restricted
      ? "A publisher-restricted molecular benchmark"
      : "Graph neural networks for molecular property prediction",
    abstractText: restricted
      ? "A benchmark whose metadata is open while its document remains restricted."
      : "A reproducible evaluation of graph representations for molecules.",
    authors: [
      author(restricted ? "Ravi Analyst" : "Ada Researcher", restricted ? "A5098765432" : "A5012345678"),
    ],
    publicationDate: restricted ? "2024-02-20" : "2025-06-12",
    publicationYear: restricted ? 2024 : 2025,
    documentType: restricted ? "CONFERENCE_PAPER" : "ARTICLE",
    language: "en",
    venue: restricted
      ? "Offline Benchmark Conference"
      : "Journal of Molecular Machine Learning",
    citationCount: restricted ? 18 : 37,
    identifiers: {
      doi: restricted
        ? "10.5555/openscholar.2024.17"
        : "10.5555/openscholar.2025.42",
      arxiv: restricted ? null : "2501.01234",
      openAlex: restricted ? "W4400765432" : "W4400123456",
    },
    reportedOpenAccess: !restricted,
    landingPageUrl: `https://metadata.openscholar.test/papers/${paperId}`,
    reportedPdfUrl: restricted ? null : fixturePdfUrl,
    score: restricted ? 0.72 : 0.94,
    rankingReasons: [
      { feature: "TEXT_RELEVANCE", value: restricted ? 0.71 : 0.91 },
      { feature: "CITATION_SIGNAL", value: restricted ? 0.31 : 0.63 },
    ],
    provenance: [
      {
        provider: "OPENALEX",
        providerRecordId: restricted ? "W4400765432" : "W4400123456",
        retrievedAt,
      },
    ],
  };
}

function searchResponse(searchId, nextPage = false) {
  const local = state.searchMode === "LOCAL";
  return {
    searchId,
    query: state.query,
    queryFingerprint:
      "90ff4c90bc8c9f06583d33a443f923f65d28ac04147b9ad8cf9a64221759c0de",
    requestedMode: state.searchMode,
    executionSource: local
      ? "LOCAL_CATALOG"
      : nextPage
        ? "PROVIDER_FETCH"
        : "EXACT_CACHE",
    cacheDisposition: local
      ? "LOCAL_RESULT"
      : nextPage
        ? "MISS_FETCHED"
        : "EXACT_HIT",
    searchedAt: "2026-08-20T09:20:00Z",
    freshUntil: "2026-08-20T10:20:00Z",
    nextCursor: nextPage ? null : "offline-next-page",
    providerCoverage: local
      ? []
      : [
          {
            provider: "OPENALEX",
            status: "SUCCESS",
            returnedCount: 1,
            totalMatches: 428,
          },
          {
            provider: "CROSSREF",
            status: "DEGRADED",
            returnedCount: 0,
            totalMatches: 0,
          },
        ],
    warnings: local ? [] : ["CROSSREF_SYNTHETIC_FAILURE"],
    results: [
      nextPage
        ? searchResult({
            rank: 2,
            paperId: ids.restrictedPaper,
            restricted: true,
          })
        : searchResult({ rank: 1, paperId: ids.verifiedPaper }),
    ],
  };
}

function accessSummary(restricted) {
  return {
    status: restricted ? "RESTRICTED" : "OPEN_PDF",
    cacheDisposition: "CACHE_HIT",
    checkedAt,
    freshUntil,
    bestLocationId: restricted ? null : ids.verifiedLocation,
    locationCount: 1,
    warnings: restricted
      ? ["The fixture publisher returned an access-controlled landing page."]
      : [],
  };
}

function paperDetails(paperId) {
  const restricted = paperId === ids.restrictedPaper;
  const recordId = restricted ? "W4400765432" : "W4400123456";
  return {
    paperId,
    title: restricted
      ? "A publisher-restricted molecular benchmark"
      : "Graph neural networks for molecular property prediction",
    abstractText: restricted
      ? "A benchmark whose metadata is open while its document remains restricted."
      : "A reproducible evaluation of graph representations for molecules.",
    authors: [
      {
        ...author(restricted ? "Ravi Analyst" : "Ada Researcher", restricted ? "A5098765432" : "A5012345678"),
        position: 0,
        corresponding: true,
      },
    ],
    publicationDate: restricted ? "2024-02-20" : "2025-06-12",
    publicationYear: restricted ? 2024 : 2025,
    documentType: restricted ? "CONFERENCE_PAPER" : "ARTICLE",
    language: "en",
    venueName: restricted
      ? "Offline Benchmark Conference"
      : "Journal of Molecular Machine Learning",
    citationCount: restricted ? 18 : 37,
    citationCountAsOf: retrievedAt,
    identifiers: [
      {
        type: "DOI",
        namespace: "doi",
        value: restricted
          ? "10.5555/openscholar.2024.17"
          : "10.5555/openscholar.2025.42",
      },
      { type: "OPENALEX", namespace: "openalex", value: recordId },
    ],
    metadataCompleteness: restricted ? 0.82 : 0.94,
    metadataUpdatedAt: retrievedAt,
    provenance: [
      {
        provider: "OPENALEX",
        providerRecordId: recordId,
        sourceUrl: `https://metadata.openscholar.test/records/${recordId}`,
        providerUpdatedAt: "2026-08-19T08:00:00Z",
        retrievedAt,
        reportedOpenAccess: !restricted,
        authorshipSource: true,
      },
    ],
    access: accessSummary(restricted),
  };
}

function accessResponse(paperId, disposition = "CACHE_HIT") {
  const restricted = paperId === ids.restrictedPaper;
  return {
    paperId,
    status: restricted ? "RESTRICTED" : "OPEN_PDF",
    cacheDisposition: disposition,
    checkedAt,
    freshUntil,
    bestLocationId: restricted ? null : ids.verifiedLocation,
    providerCoverage: [
      {
        provider: restricted ? "CROSSREF" : "UNPAYWALL",
        status: "SUCCESS",
        candidateCount: 1,
      },
    ],
    warnings: restricted
      ? ["The fixture publisher returned an access-controlled landing page."]
      : [],
    locations: restricted
      ? [
          {
            id: ids.restrictedLocation,
            source: "CROSSREF",
            best: false,
            accessStatus: "RESTRICTED",
            versionType: "PUBLISHED",
            hostType: "PUBLISHER",
            landingPageUrl:
              "https://publisher.openscholar.test/restricted-paper",
            pdfUrl: null,
            hostDomain: "publisher.openscholar.test",
            license: null,
            evidence: "Access-controlled fixture landing page",
            contentHandling: "LINK_ONLY",
            verificationStatus: "FAILED",
            verificationHttpStatus: 403,
            verificationContentType: "text/html",
            verificationFailureCode: "HTTP_STATUS_NOT_SUCCESS",
            providerUpdatedAt: "2026-08-19T08:00:00Z",
            retrievedAt,
            lastSeenAt: checkedAt,
            verifiedAt: null,
          },
        ]
      : [
          {
            id: ids.verifiedLocation,
            source: "UNPAYWALL",
            best: true,
            accessStatus: "OPEN_PDF",
            versionType: "ACCEPTED_MANUSCRIPT",
            hostType: "REPOSITORY",
            landingPageUrl:
              "https://papers.openscholar.test/offline-paper",
            pdfUrl: fixturePdfUrl,
            hostDomain: "papers.openscholar.test",
            license: "CC-BY-4.0",
            evidence: "Deterministic repository fixture",
            contentHandling: "LINK_ONLY",
            verificationStatus: "VERIFIED",
            verificationHttpStatus: 200,
            verificationContentType: "application/pdf",
            verificationFailureCode: null,
            providerUpdatedAt: "2026-08-19T08:00:00Z",
            retrievedAt,
            lastSeenAt: checkedAt,
            verifiedAt: checkedAt,
          },
        ],
  };
}

function relatedPapers(paperId) {
  return {
    sourcePaperId: paperId,
    rankingMode: "LEXICAL",
    fallbackReason: "HYBRID_DISABLED",
    results:
      paperId === ids.verifiedPaper
        ? [
            {
              rank: 1,
              paperId: ids.relatedPaper,
              title: "Message passing networks for molecular discovery",
              abstractText:
                "A local full-text match on graph representations and molecular learning.",
              authors: [author("Grace Scholar", "A5076543210")],
              publicationDate: "2024-03-12",
              publicationYear: 2024,
              documentType: "CONFERENCE_PAPER",
              language: "en",
              venue: "Molecular Learning Conference",
              publisher: "OpenScholar Press",
              institution: null,
              volume: "7",
              issue: "2",
              pages: "18-29",
              articleNumber: null,
              edition: null,
              isbn: [],
              issn: ["2049-3630"],
              degree: null,
              citationCount: 18,
              identifiers: {
                doi: "10.5555/openscholar.2024.18",
                arxiv: null,
                openAlex: "W4400765433",
              },
              score: 0.42,
              rankingReasons: [
                { feature: "POSTGRES_FULL_TEXT", value: 0.42 },
              ],
            },
          ]
        : [],
  };
}

function json(response, status, value, headers = {}) {
  const body = Buffer.from(JSON.stringify(value));
  response.writeHead(status, {
    "cache-control": "no-store",
    "content-length": String(body.length),
    "content-type": "application/json; charset=utf-8",
    "x-content-type-options": "nosniff",
    ...headers,
  });
  response.end(body);
}

function empty(response, status = 204) {
  response.writeHead(status, {
    "cache-control": "no-store",
    "content-length": "0",
  });
  response.end();
}

function text(response, status, value, contentType, filename) {
  const body = Buffer.from(value);
  response.writeHead(status, {
    "cache-control": "no-store",
    "content-disposition": `attachment; filename="${filename}"`,
    "content-length": String(body.length),
    "content-type": contentType,
    "x-content-type-options": "nosniff",
  });
  response.end(body);
}

async function requestBody(request) {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 1_000_000) {
      throw new Error("Fixture request body is too large.");
    }
    chunks.push(chunk);
  }
  if (chunks.length === 0) return {};
  return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

function problem(response, status, detail) {
  json(response, status, {
    type: "urn:openscholar:fixture:not-found",
    title: status === 404 ? "Not found" : "Fixture request failed",
    status,
    detail,
    code: status === 404 ? "NOT_FOUND" : "FIXTURE_REQUEST_FAILED",
    retryable: false,
  });
}

function page(items, url, fallbackSize) {
  const requestedPage = Number(url.searchParams.get("page") ?? 0);
  const requestedSize = Number(url.searchParams.get("size") ?? fallbackSize);
  const pageNumber = Number.isInteger(requestedPage) && requestedPage >= 0
    ? requestedPage
    : 0;
  const size = Number.isInteger(requestedSize) && requestedSize > 0
    ? requestedSize
    : fallbackSize;
  const start = pageNumber * size;
  return {
    items: items.slice(start, start + size),
    page: pageNumber,
    size,
    totalElements: items.length,
    totalPages: items.length === 0 ? 0 : Math.ceil(items.length / size),
  };
}

function servePdf(request, response) {
  const headers = {
    "accept-ranges": "bytes",
    "access-control-allow-headers": "Range",
    "access-control-allow-methods": "GET, HEAD, OPTIONS",
    "access-control-allow-origin": "*",
    "access-control-expose-headers": "Accept-Ranges, Content-Length, Content-Range",
    "cache-control": "no-store",
    "content-type": "application/pdf",
    "x-content-type-options": "nosniff",
  };
  if (request.method === "OPTIONS") {
    response.writeHead(204, { ...headers, "content-length": "0" });
    response.end();
    return;
  }

  const match = /^bytes=(\d+)-(\d*)$/.exec(request.headers.range ?? "");
  if (match !== null) {
    const start = Number(match[1]);
    const requestedEnd = match[2] === "" ? pdf.length - 1 : Number(match[2]);
    if (start >= pdf.length || requestedEnd < start) {
      response.writeHead(416, {
        ...headers,
        "content-length": "0",
        "content-range": `bytes */${pdf.length}`,
      });
      response.end();
      return;
    }
    const end = Math.min(requestedEnd, pdf.length - 1);
    const body = pdf.subarray(start, end + 1);
    response.writeHead(206, {
      ...headers,
      "content-length": String(body.length),
      "content-range": `bytes ${start}-${end}/${pdf.length}`,
    });
    response.end(request.method === "HEAD" ? undefined : body);
    return;
  }

  response.writeHead(200, { ...headers, "content-length": String(pdf.length) });
  response.end(request.method === "HEAD" ? undefined : pdf);
}

async function handle(request, response) {
  const url = new URL(request.url ?? "/", `http://${host}:${port}`);
  const path = url.pathname;

  if (path === "/__fixture/health" && request.method === "GET") {
    json(response, 200, { status: "ready" });
    return;
  }
  if (path === "/__fixture/reset" && request.method === "POST") {
    state = initialState();
    empty(response);
    return;
  }
  if (path === "/fixtures/paper.pdf") {
    servePdf(request, response);
    return;
  }
  if (path === "/api/v1/system/status" && request.method === "GET") {
    json(response, 200, {
      service: "openscholar-offline-fixture",
      status: "UP",
      timestamp: "2026-08-20T09:00:00Z",
    });
    return;
  }
  if (path === "/api/v1/searches" && request.method === "POST") {
    const body = await requestBody(request);
    if (typeof body.query === "string") state.query = body.query.trim();
    state.searchMode = ["AUTO", "ONLINE", "LOCAL"].includes(body.mode)
      ? body.mode
      : "AUTO";
    json(response, 201, searchResponse(ids.search), {
      location: `/api/v1/searches/${ids.search}`,
    });
    return;
  }
  if (path === `/api/v1/searches/${ids.search}` && request.method === "GET") {
    json(response, 200, searchResponse(ids.search));
    return;
  }
  if (
    path === `/api/v1/searches/${ids.search}/next` &&
    request.method === "POST"
  ) {
    json(response, 201, searchResponse(ids.nextSearch, true), {
      location: `/api/v1/searches/${ids.nextSearch}`,
    });
    return;
  }
  if (
    path === `/api/v1/searches/${ids.nextSearch}` &&
    request.method === "GET"
  ) {
    json(response, 200, searchResponse(ids.nextSearch, true));
    return;
  }

  const relatedMatch = /^\/api\/v1\/papers\/([^/]+)\/related$/.exec(path);
  if (relatedMatch !== null && request.method === "GET") {
    const paperId = decodeURIComponent(relatedMatch[1]);
    if (paperId !== ids.verifiedPaper && paperId !== ids.restrictedPaper) {
      problem(response, 404, "The requested fixture paper does not exist.");
      return;
    }
    json(response, 200, relatedPapers(paperId));
    return;
  }

  const versionsMatch = /^\/api\/v1\/papers\/([^/]+)\/versions$/.exec(path);
  if (versionsMatch !== null && request.method === "GET") {
    const paperId = decodeURIComponent(versionsMatch[1]);
    if (paperId !== ids.verifiedPaper && paperId !== ids.restrictedPaper) {
      problem(response, 404, "The requested fixture paper does not exist.");
      return;
    }
    json(response, 200, accessResponse(paperId));
    return;
  }

  const verifyMatch = /^\/api\/v1\/papers\/([^/]+)\/access\/verify$/.exec(path);
  if (verifyMatch !== null && request.method === "POST") {
    const paperId = decodeURIComponent(verifyMatch[1]);
    if (paperId !== ids.verifiedPaper && paperId !== ids.restrictedPaper) {
      problem(response, 404, "The requested fixture paper does not exist.");
      return;
    }
    const forced = url.searchParams.get("forceRefresh") === "true";
    json(response, 200, accessResponse(paperId, forced ? "FORCED_REFRESH" : "CACHE_HIT"));
    return;
  }

  const citationMatch = /^\/api\/v1\/papers\/([^/]+)\/citation$/.exec(path);
  if (citationMatch !== null && request.method === "GET") {
    const paperId = decodeURIComponent(citationMatch[1]);
    if (paperId !== ids.verifiedPaper && paperId !== ids.restrictedPaper) {
      problem(response, 404, "The requested fixture paper does not exist.");
      return;
    }
    if (url.searchParams.get("format") === "csl-json") {
      text(
        response,
        200,
        JSON.stringify([{ id: paperId, title: paperDetails(paperId).title }]),
        "application/vnd.citationstyles.csl+json; charset=utf-8",
        "openscholar-paper.json",
      );
    } else {
      text(
        response,
        200,
        `@article{openscholar_fixture,\n  title = {${paperDetails(paperId).title}}\n}\n`,
        "application/x-bibtex; charset=utf-8",
        "openscholar-paper.bib",
      );
    }
    return;
  }

  const paperMatch = /^\/api\/v1\/papers\/([^/]+)$/.exec(path);
  if (paperMatch !== null && request.method === "GET") {
    const paperId = decodeURIComponent(paperMatch[1]);
    if (paperId !== ids.verifiedPaper && paperId !== ids.restrictedPaper) {
      problem(response, 404, "The requested fixture paper does not exist.");
      return;
    }
    json(response, 200, paperDetails(paperId));
    return;
  }

  if (path === "/api/v1/collections" && request.method === "GET") {
    json(response, 200, page(state.collections, url, 20));
    return;
  }
  if (path === "/api/v1/collections" && request.method === "POST") {
    const body = await requestBody(request);
    const collection = {
      collectionId: ids.createdCollection,
      name: String(body.name ?? "Untitled fixture collection").trim(),
      description:
        typeof body.description === "string" && body.description.trim() !== ""
          ? body.description.trim()
          : null,
      paperCount: 0,
      createdAt,
      updatedAt: createdAt,
    };
    state.collections = [
      collection,
      ...state.collections.filter(
        (candidate) => candidate.collectionId !== collection.collectionId,
      ),
    ];
    json(response, 201, collection, {
      location: `/api/v1/collections/${collection.collectionId}`,
    });
    return;
  }

  const collectionPaperMatch =
    /^\/api\/v1\/collections\/([^/]+)\/papers\/([^/]+)$/.exec(path);
  if (collectionPaperMatch !== null) {
    const collectionId = decodeURIComponent(collectionPaperMatch[1]);
    const paperId = decodeURIComponent(collectionPaperMatch[2]);
    const collection = state.collections.find(
      (candidate) => candidate.collectionId === collectionId,
    );
    if (
      collection === undefined ||
      (paperId !== ids.verifiedPaper && paperId !== ids.restrictedPaper)
    ) {
      problem(response, 404, "The requested fixture membership does not exist.");
      return;
    }
    if (request.method === "DELETE") {
      const originalLength = state.savedPapers.length;
      state.savedPapers = state.savedPapers.filter(
        (paper) =>
          paper.collectionId !== collectionId || paper.paperId !== paperId,
      );
      if (state.savedPapers.length < originalLength) {
        collection.paperCount = Math.max(0, collection.paperCount - 1);
      }
      empty(response);
      return;
    }
    if (request.method === "PUT" || request.method === "PATCH") {
      const body = await requestBody(request);
      const prior = state.savedPapers.find(
        (paper) =>
          paper.collectionId === collectionId && paper.paperId === paperId,
      );
      const details = paperDetails(paperId);
      const savedPaper = {
        collectionId,
        collectionName: collection.name,
        paperId,
        title: details.title,
        authors: details.authors.map((item) => item.name),
        publicationYear: details.publicationYear,
        documentType: details.documentType,
        readingStatus: String(body.readingStatus ?? prior?.readingStatus ?? "UNREAD"),
        tags: Array.isArray(body.tags) ? body.tags.map(String) : (prior?.tags ?? []),
        savedAt: prior?.savedAt ?? createdAt,
        updatedAt: "2026-08-20T11:00:00Z",
      };
      state.savedPapers = [
        ...state.savedPapers.filter(
          (paper) =>
            paper.collectionId !== collectionId || paper.paperId !== paperId,
        ),
        savedPaper,
      ];
      if (prior === undefined) collection.paperCount += 1;
      json(response, 200, savedPaper);
      return;
    }
  }

  const collectionMatch = /^\/api\/v1\/collections\/([^/]+)$/.exec(path);
  if (collectionMatch !== null) {
    const collectionId = decodeURIComponent(collectionMatch[1]);
    const collection = state.collections.find(
      (candidate) => candidate.collectionId === collectionId,
    );
    if (collection === undefined) {
      problem(response, 404, "The requested fixture collection does not exist.");
      return;
    }
    if (request.method === "GET") {
      const papers = state.savedPapers.filter(
        (paper) => paper.collectionId === collectionId,
      );
      json(response, 200, { ...collection, papers: page(papers, url, 20) });
      return;
    }
    if (request.method === "PATCH") {
      const body = await requestBody(request);
      collection.name = String(body.name ?? collection.name).trim();
      collection.description =
        typeof body.description === "string" && body.description.trim() !== ""
          ? body.description.trim()
          : null;
      collection.updatedAt = "2026-08-20T11:30:00Z";
      state.savedPapers = state.savedPapers.map((paper) =>
        paper.collectionId === collectionId
          ? { ...paper, collectionName: collection.name }
          : paper,
      );
      json(response, 200, collection);
      return;
    }
    if (request.method === "DELETE") {
      state.collections = state.collections.filter(
        (candidate) => candidate.collectionId !== collectionId,
      );
      state.savedPapers = state.savedPapers.filter(
        (paper) => paper.collectionId !== collectionId,
      );
      empty(response);
      return;
    }
  }

  if (path === "/api/v1/library/papers" && request.method === "GET") {
    const q = url.searchParams.get("q")?.trim().toLowerCase();
    const collectionId = url.searchParams.get("collectionId");
    const readingStatus = url.searchParams.get("readingStatus");
    const tag = url.searchParams.get("tag")?.trim().toLowerCase();
    const filtered = state.savedPapers.filter((paper) => {
      const searchable = `${paper.title} ${paper.authors.join(" ")}`.toLowerCase();
      return (
        (q === undefined || q === "" || searchable.includes(q)) &&
        (collectionId === null || paper.collectionId === collectionId) &&
        (readingStatus === null || paper.readingStatus === readingStatus) &&
        (tag === undefined || tag === "" || paper.tags.includes(tag))
      );
    });
    json(response, 200, page(filtered, url, 20));
    return;
  }

  if (path === "/api/v1/citations/export" && request.method === "POST") {
    const body = await requestBody(request);
    const requestedIds = Array.isArray(body.paperIds) ? body.paperIds : [];
    const format = body.format === "csl-json" ? "csl-json" : "bibtex";
    if (format === "csl-json") {
      text(
        response,
        200,
        JSON.stringify(
          requestedIds.map((paperId) => ({
            id: paperId,
            title: paperDetails(paperId).title,
          })),
        ),
        "application/vnd.citationstyles.csl+json; charset=utf-8",
        "offline-library.json",
      );
    } else {
      text(
        response,
        200,
        requestedIds
          .map(
            (paperId) =>
              `@article{${paperId.slice(0, 8)},\n  title = {${paperDetails(paperId).title}}\n}`,
          )
          .join("\n\n"),
        "application/x-bibtex; charset=utf-8",
        "offline-library.bib",
      );
    }
    return;
  }

  problem(response, 404, `No offline fixture is defined for ${request.method} ${path}.`);
}

const server = createServer((request, response) => {
  void handle(request, response).catch((error) => {
    if (!response.headersSent) {
      problem(response, 500, "The deterministic fixture failed to handle the request.");
    } else {
      response.destroy(error);
    }
  });
});

server.listen(port, host, () => {
  process.stdout.write(`OpenScholar E2E fixture listening on http://${host}:${port}\n`);
});

function close() {
  server.close((error) => {
    process.exitCode = error === undefined ? 0 : 1;
  });
}

process.once("SIGINT", close);
process.once("SIGTERM", close);
