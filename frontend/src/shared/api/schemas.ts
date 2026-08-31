import { z } from "zod";

export const documentTypes = [
  "ARTICLE",
  "PREPRINT",
  "CONFERENCE_PAPER",
  "THESIS",
  "DISSERTATION",
  "BOOK",
  "BOOK_CHAPTER",
  "REPORT",
  "DATASET",
  "OTHER",
] as const;

export const documentTypeSchema = z.enum(documentTypes);

export const searchModeSchema = z.enum(["AUTO", "ONLINE", "LOCAL"]);

export const searchExecutionSourceSchema = z.enum([
  "PROVIDER_FETCH",
  "EXACT_CACHE",
  "STALE_CACHE",
  "LOCAL_CATALOG",
]);

export const createSearchRequestSchema = z
  .object({
    query: z.string().trim().min(3).max(500),
    mode: searchModeSchema.default("AUTO"),
    filters: z
      .object({
        yearFrom: z.number().int().min(1000).max(9999).optional(),
        yearTo: z.number().int().min(1000).max(9999).optional(),
        documentTypes: z.array(documentTypeSchema).max(12).default([]),
        openAccessOnly: z.boolean().default(false),
        pdfAvailableOnly: z.boolean().default(false),
        minimumCitations: z.number().int().min(0).default(0),
        languages: z
          .array(z.string().trim().toLowerCase().regex(/^[a-z]{2,3}$/))
          .max(20)
          .default([]),
      })
      .strict()
      .superRefine((filters, context) => {
        if (
          filters.yearFrom !== undefined &&
          filters.yearTo !== undefined &&
          filters.yearFrom > filters.yearTo
        ) {
          context.addIssue({
            code: "custom",
            message: "Start year must not be after end year.",
            path: ["yearTo"],
          });
        }
      })
      .default({
        documentTypes: [],
        openAccessOnly: false,
        pdfAvailableOnly: false,
        minimumCitations: 0,
        languages: [],
      }),
    pageSize: z.number().int().min(1).max(50).default(20),
    cursor: z.string().max(4096).optional(),
    forceRefresh: z.boolean().default(false),
  })
  .strict()
  .superRefine((request, context) => {
    if (request.mode === "LOCAL" && request.forceRefresh) {
      context.addIssue({
        code: "custom",
        message: "Local searches cannot force an online refresh.",
        path: ["forceRefresh"],
      });
    }
  });

const httpUrlSchema = z
  .string()
  .url()
  .refine((value) => {
    const protocol = new URL(value).protocol;
    return protocol === "http:" || protocol === "https:";
  }, "Expected an HTTP(S) URL.");
const nullableUrlSchema = httpUrlSchema.nullable();
const nullableInstantSchema = z.string().datetime({ offset: true }).nullable();
const instantSchema = z.string().datetime({ offset: true });
const localDateSchema = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);

const authorSchema = z.object({
  name: z.string(),
  orcid: z.string().nullable(),
  openAlexId: z.string().nullable(),
});

export const searchResponseSchema = z.object({
  searchId: z.string().uuid(),
  query: z.string(),
  queryFingerprint: z.string(),
  requestedMode: searchModeSchema,
  executionSource: searchExecutionSourceSchema,
  cacheDisposition: z.enum([
    "EXACT_HIT",
    "MISS_FETCHED",
    "STALE_REFRESHED",
    "FORCED_REFRESH",
    "STALE_FALLBACK",
    "LOCAL_RESULT",
  ]),
  searchedAt: instantSchema,
  freshUntil: instantSchema,
  nextCursor: z.string().nullable(),
  providerCoverage: z.array(
    z.object({
      provider: z.string(),
      status: z.string(),
      returnedCount: z.number().int().nonnegative(),
      totalMatches: z.number().int().nonnegative(),
    }),
  ),
  warnings: z.array(z.string()),
  results: z.array(
    z.object({
      rank: z.number().int().positive(),
      paperId: z.string().uuid(),
      title: z.string(),
      abstractText: z.string().nullable(),
      authors: z.array(authorSchema),
      publicationDate: localDateSchema.nullable(),
      publicationYear: z.number().int().nullable(),
      documentType: documentTypeSchema,
      language: z.string().nullable(),
      venue: z.string().nullable(),
      citationCount: z.number().int().nonnegative().nullable(),
      identifiers: z.object({
        doi: z.string().nullable(),
        arxiv: z.string().nullable(),
        openAlex: z.string().nullable(),
      }),
      reportedOpenAccess: z.boolean(),
      landingPageUrl: nullableUrlSchema,
      reportedPdfUrl: nullableUrlSchema,
      score: z.number().nullable(),
      rankingReasons: z.array(
        z.object({
          feature: z.string(),
          value: z.number().nullable(),
        }),
      ),
      provenance: z.array(
        z.object({
          provider: z.string(),
          providerRecordId: z.string(),
          retrievedAt: instantSchema,
        }),
      ),
    }),
  ),
});

export const accessStatusSchema = z.enum([
  "OPEN_PDF",
  "OPEN_LANDING_PAGE",
  "REPOSITORY_COPY",
  "PREPRINT",
  "ABSTRACT_ONLY",
  "RESTRICTED",
  "UNKNOWN",
  "UNAVAILABLE",
]);

export const accessDispositionSchema = z.enum([
  "CACHE_HIT",
  "RESOLVED",
  "REFRESHED",
  "FORCED_REFRESH",
  "STALE_FALLBACK",
  "NO_SUPPORTED_IDENTIFIER",
  "NOT_YET_RESOLVED",
]);

const accessSummarySchema = z.object({
  status: accessStatusSchema,
  cacheDisposition: accessDispositionSchema,
  checkedAt: nullableInstantSchema,
  freshUntil: nullableInstantSchema,
  bestLocationId: z.string().uuid().nullable(),
  locationCount: z.number().int().nonnegative(),
  warnings: z.array(z.string()),
});

export const paperDetailsResponseSchema = z.object({
  paperId: z.string().uuid(),
  title: z.string(),
  abstractText: z.string().nullable(),
  authors: z.array(
    authorSchema.extend({
      position: z.number().int().nonnegative(),
      corresponding: z.boolean(),
    }),
  ),
  publicationDate: localDateSchema.nullable(),
  publicationYear: z.number().int().nullable(),
  documentType: documentTypeSchema,
  language: z.string().nullable(),
  venueName: z.string().nullable(),
  citationCount: z.number().int().nonnegative().nullable(),
  citationCountAsOf: nullableInstantSchema,
  identifiers: z.array(
    z.object({
      type: z.enum([
        "DOI",
        "ARXIV",
        "OPENALEX",
        "PMID",
        "PMCID",
        "CORE",
        "REPOSITORY",
      ]),
      namespace: z.string(),
      value: z.string(),
    }),
  ),
  metadataCompleteness: z.number().min(0).max(1),
  metadataUpdatedAt: instantSchema,
  provenance: z.array(
    z.object({
      provider: z.string(),
      providerRecordId: z.string(),
      sourceUrl: nullableUrlSchema,
      providerUpdatedAt: nullableInstantSchema,
      retrievedAt: instantSchema,
      reportedOpenAccess: z.boolean(),
      authorshipSource: z.boolean(),
    }),
  ),
  access: accessSummarySchema,
});

export const paperIdentifierLookupRequestSchema = z
  .object({
    identifier: z
      .string()
      .trim()
      .min(1, "Enter a DOI, arXiv ID, or OpenAlex work ID.")
      .max(512, "The paper identifier is too long.")
      .refine(
        (value) => !/[\u0000-\u001f\u007f]/u.test(value),
        "The paper identifier contains unsupported characters.",
      ),
  })
  .strict();

export const paperIdentifierResolutionSchema = z
  .object({
    paperId: z.string().uuid(),
    identifierType: z.enum(["DOI", "ARXIV", "OPENALEX"]),
    normalizedValue: z.string().min(1).max(512),
  })
  .strict();

const relatedPaperAuthorSchema = authorSchema.strict();
const relatedPaperRankingModeSchema = z.enum(["LEXICAL", "HYBRID"]);
const relatedPaperFallbackReasonSchema = z.enum([
  "HYBRID_DISABLED",
  "EMBEDDING_PROFILE_MISSING",
  "SOURCE_VECTOR_MISSING",
  "CANDIDATE_VECTOR_COVERAGE_INCOMPLETE",
]);
const relatedPaperRankingFeatureSchema = z.enum([
  "POSTGRES_FULL_TEXT",
  "CLAMPED_COSINE",
]);
const relatedPaperRankingReasonSchema = z
  .object({
    feature: relatedPaperRankingFeatureSchema,
    value: z.number().finite().min(0).max(1),
  })
  .strict();

export const relatedPapersResponseSchema = z
  .object({
    sourcePaperId: z.string().uuid(),
    rankingMode: relatedPaperRankingModeSchema,
    fallbackReason: relatedPaperFallbackReasonSchema.nullable(),
    results: z.array(
      z
        .object({
          rank: z.number().int().positive(),
          paperId: z.string().uuid(),
          title: z.string(),
          abstractText: z.string().nullable(),
          authors: z.array(relatedPaperAuthorSchema),
          publicationDate: localDateSchema.nullable(),
          publicationYear: z.number().int().nullable(),
          documentType: documentTypeSchema,
          language: z.string().nullable(),
          venue: z.string().nullable(),
          publisher: z.string().nullable(),
          institution: z.string().nullable(),
          volume: z.string().nullable(),
          issue: z.string().nullable(),
          pages: z.string().nullable(),
          articleNumber: z.string().nullable(),
          edition: z.string().nullable(),
          isbn: z.array(z.string()),
          issn: z.array(z.string()),
          degree: z.string().nullable(),
          citationCount: z.number().int().nonnegative().nullable(),
          identifiers: z
            .object({
              doi: z.string().nullable(),
              arxiv: z.string().nullable(),
              openAlex: z.string().nullable(),
            })
            .strict(),
          score: z.number().finite().nonnegative(),
          rankingReasons: z.array(relatedPaperRankingReasonSchema),
        })
        .strict(),
    ),
  })
  .strict();

export const paperAccessResponseSchema = z.object({
  paperId: z.string().uuid(),
  status: accessStatusSchema,
  cacheDisposition: accessDispositionSchema,
  checkedAt: nullableInstantSchema,
  freshUntil: nullableInstantSchema,
  bestLocationId: z.string().uuid().nullable(),
  providerCoverage: z.array(
    z.object({
      provider: z.string(),
      status: z.string(),
      candidateCount: z.number().int().nonnegative(),
    }),
  ),
  warnings: z.array(z.string()),
  locations: z.array(
    z.object({
      id: z.string().uuid(),
      source: z.string(),
      best: z.boolean(),
      accessStatus: accessStatusSchema,
      versionType: z.enum([
        "PUBLISHED",
        "ACCEPTED_MANUSCRIPT",
        "SUBMITTED_MANUSCRIPT",
        "PREPRINT",
        "UNKNOWN",
      ]),
      hostType: z.enum([
        "PUBLISHER",
        "REPOSITORY",
        "PREPRINT_SERVER",
        "UNKNOWN",
      ]),
      landingPageUrl: nullableUrlSchema,
      pdfUrl: nullableUrlSchema,
      hostDomain: z.string().nullable(),
      license: z.string().nullable(),
      evidence: z.string().nullable(),
      contentHandling: z.enum(["LINK_ONLY", "RETENTION_ALLOWED"]),
      verificationStatus: z.enum(["VERIFIED", "UNVERIFIED", "FAILED"]),
      verificationHttpStatus: z.number().int().nullable(),
      verificationContentType: z.string().nullable(),
      verificationFailureCode: z.string().nullable(),
      providerUpdatedAt: nullableInstantSchema,
      retrievedAt: instantSchema,
      lastSeenAt: instantSchema,
      verifiedAt: nullableInstantSchema,
    }),
  ),
});

export const apiProblemSchema = z
  .object({
    type: z.string().optional(),
    title: z.string(),
    status: z.number().int(),
    detail: z.string(),
    instance: z.string().optional(),
    code: z.string(),
    violations: z
      .array(
        z.object({
          field: z.string(),
          message: z.string(),
        }),
      )
      .optional(),
    retryable: z.boolean().optional(),
    retryAfterSeconds: z.number().int().positive().optional(),
  })
  .passthrough();

export const systemStatusResponseSchema = z.object({
  service: z.string(),
  status: z.string(),
  timestamp: instantSchema,
});

export type CreateSearchRequest = z.infer<typeof createSearchRequestSchema>;
export type SearchMode = z.infer<typeof searchModeSchema>;
export type SearchResponse = z.infer<typeof searchResponseSchema>;
export type SearchResult = SearchResponse["results"][number];
export type PaperDetailsResponse = z.infer<typeof paperDetailsResponseSchema>;
export type PaperIdentifierLookupRequest = z.infer<
  typeof paperIdentifierLookupRequestSchema
>;
export type PaperIdentifierResolution = z.infer<
  typeof paperIdentifierResolutionSchema
>;
export type RelatedPapersResponse = z.infer<typeof relatedPapersResponseSchema>;
export type RelatedPaperResult = RelatedPapersResponse["results"][number];
export type PaperAccessResponse = z.infer<typeof paperAccessResponseSchema>;
export type PaperAccessLocation = PaperAccessResponse["locations"][number];
export type ApiProblem = z.infer<typeof apiProblemSchema>;
export type SystemStatusResponse = z.infer<typeof systemStatusResponseSchema>;
