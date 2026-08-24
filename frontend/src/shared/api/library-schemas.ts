import { z } from "zod";

import { documentTypeSchema } from "@/shared/api/schemas";

const instantSchema = z.string().datetime({ offset: true });
const uuidSchema = z.string().uuid();
const localDateSchema = z.string().regex(/^\d{4}-\d{2}-\d{2}$/);

export const readingStatuses = ["UNREAD", "READING", "COMPLETED"] as const;
export const readingStatusSchema = z.enum(readingStatuses);

export const collectionNameSchema = z.string().trim().min(1).max(120);
export const collectionDescriptionSchema = z.string().trim().max(1_000).nullable();

const normalizedTagSchema = z
  .string()
  .transform((value) => value.trim().replace(/\s+/g, " ").toLowerCase())
  .pipe(z.string().min(1).max(40));

export const tagsSchema = z
  .array(normalizedTagSchema)
  .max(10)
  .refine((tags) => new Set(tags).size === tags.length, {
    message: "Tags must be distinct after normalization.",
  });

export const createCollectionRequestSchema = z
  .object({
    name: collectionNameSchema,
    description: collectionDescriptionSchema.optional(),
  })
  .strict();

export const updateCollectionRequestSchema = z
  .object({
    name: collectionNameSchema,
    description: collectionDescriptionSchema,
  })
  .strict();

export const savedPaperMutationSchema = z
  .object({
    readingStatus: readingStatusSchema,
    tags: tagsSchema,
  })
  .strict();

const pageSchema = z.number().int().nonnegative();
const sizeSchema = z.number().int().min(1).max(100);
const blankStringAsUndefined = (value: unknown): unknown =>
  typeof value === "string" && value.trim() === "" ? undefined : value;

export const collectionListQuerySchema = z
  .object({
    page: pageSchema.default(0),
    size: sizeSchema.default(20),
  })
  .strict();

export const savedLibraryQuerySchema = z
  .object({
    q: z.preprocess(
      blankStringAsUndefined,
      z.string().trim().max(200).optional(),
    ),
    collectionId: z.preprocess(blankStringAsUndefined, uuidSchema.optional()),
    readingStatus: z.preprocess(
      blankStringAsUndefined,
      readingStatusSchema.optional(),
    ),
    tag: z.preprocess(
      blankStringAsUndefined,
      normalizedTagSchema.optional(),
    ),
    page: pageSchema.default(0),
    size: sizeSchema.default(20),
  })
  .strict();

export const batchCitationExportRequestSchema = z
  .object({
    paperIds: z
      .array(uuidSchema)
      .min(1)
      .max(100)
      .refine((paperIds) => new Set(paperIds).size === paperIds.length, {
        message: "Paper IDs must be distinct.",
      }),
    format: z.enum(["bibtex", "csl-json"]),
  })
  .strict();

export const collectionSummarySchema = z.object({
  collectionId: uuidSchema,
  name: z.string(),
  description: z.string().nullable(),
  paperCount: z.number().int().nonnegative(),
  createdAt: instantSchema,
  updatedAt: instantSchema,
});

export const savedPaperSchema = z.object({
  collectionId: uuidSchema,
  collectionName: z.string(),
  paperId: uuidSchema,
  title: z.string(),
  authors: z.array(z.string()),
  publicationYear: z.number().int().nullable(),
  documentType: documentTypeSchema,
  readingStatus: readingStatusSchema,
  tags: z.array(z.string()),
  savedAt: instantSchema,
  updatedAt: instantSchema,
});

function pagedSchema<T extends z.ZodType>(itemSchema: T) {
  return z.object({
    items: z.array(itemSchema),
    page: pageSchema,
    size: sizeSchema,
    totalElements: z.number().int().nonnegative(),
    totalPages: z.number().int().nonnegative(),
  });
}

export const collectionListResponseSchema = pagedSchema(collectionSummarySchema);
export const savedLibraryResponseSchema = pagedSchema(savedPaperSchema);

export const collectionDetailsResponseSchema = collectionSummarySchema.extend({
  papers: savedLibraryResponseSchema,
});

const offlinePaperIdentifierSchema = z
  .object({
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
  })
  .strict();

const offlinePaperSchema = z
  .object({
    paperId: uuidSchema,
    title: z.string().min(1),
    authors: z.array(z.string()),
    publicationDate: localDateSchema.nullable(),
    publicationYear: z.number().int().nullable(),
    documentType: documentTypeSchema,
    language: z.string().nullable(),
    venueName: z.string().nullable(),
    identifiers: z.array(offlinePaperIdentifierSchema),
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
    readingStatus: readingStatusSchema,
    tags: z
      .array(z.string().min(1).max(40))
      .max(10)
      .refine((tags) => new Set(tags).size === tags.length, {
        message: "Offline paper tags must be distinct.",
      }),
  })
  .strict();

/**
 * The intentionally bounded, metadata-only contract used to create an
 * encrypted browser offline pack. The backend also enforces an exact 1 MiB
 * serialized UTF-8 limit before returning this response.
 */
export const offlineCollectionPackSchema = z
  .object({
    schemaVersion: z.literal(1),
    generatedAt: instantSchema,
    collection: z
      .object({
        collectionId: uuidSchema,
        name: z.string().min(1).max(120),
        description: z.string().max(1_000).nullable(),
      })
      .strict(),
    papers: z.array(offlinePaperSchema).max(500),
  })
  .strict();

export type ReadingStatus = z.infer<typeof readingStatusSchema>;
export type CreateCollectionRequest = z.infer<
  typeof createCollectionRequestSchema
>;
export type UpdateCollectionRequest = z.infer<
  typeof updateCollectionRequestSchema
>;
export type SavedPaperMutation = z.infer<typeof savedPaperMutationSchema>;
export type SavedLibraryQuery = z.infer<typeof savedLibraryQuerySchema>;
export type BatchCitationExportRequest = z.infer<
  typeof batchCitationExportRequestSchema
>;
export type CollectionSummary = z.infer<typeof collectionSummarySchema>;
export type CollectionListResponse = z.infer<
  typeof collectionListResponseSchema
>;
export type SavedPaper = z.infer<typeof savedPaperSchema>;
export type SavedLibraryResponse = z.infer<typeof savedLibraryResponseSchema>;
export type CollectionDetailsResponse = z.infer<
  typeof collectionDetailsResponseSchema
>;
export type OfflineCollectionPack = z.infer<
  typeof offlineCollectionPackSchema
>;
