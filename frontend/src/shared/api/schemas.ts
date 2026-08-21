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

export const createSearchRequestSchema = z
  .object({
    query: z.string().trim().min(3).max(500),
    filters: z
      .object({
        yearFrom: z.number().int().min(1000).max(9999).optional(),
        yearTo: z.number().int().min(1000).max(9999).optional(),
        documentTypes: z.array(documentTypeSchema).max(12).default([]),
        openAccessOnly: z.boolean().default(false),
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
        minimumCitations: 0,
        languages: [],
      }),
    pageSize: z.number().int().min(1).max(50).default(20),
    cursor: z.string().max(4096).optional(),
    forceRefresh: z.boolean().default(false),
  })
  .strict();

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
  cacheDisposition: z.enum([
    "EXACT_HIT",
    "MISS_FETCHED",
    "STALE_REFRESHED",
    "FORCED_REFRESH",
    "STALE_FALLBACK",
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
export type SearchResponse = z.infer<typeof searchResponseSchema>;
export type SearchResult = SearchResponse["results"][number];
export type PaperDetailsResponse = z.infer<typeof paperDetailsResponseSchema>;
export type RelatedPapersResponse = z.infer<typeof relatedPapersResponseSchema>;
export type RelatedPaperResult = RelatedPapersResponse["results"][number];
export type PaperAccessResponse = z.infer<typeof paperAccessResponseSchema>;
export type PaperAccessLocation = PaperAccessResponse["locations"][number];
export type ApiProblem = z.infer<typeof apiProblemSchema>;
export type SystemStatusResponse = z.infer<typeof systemStatusResponseSchema>;
