import { describe, expect, it } from "vitest";

import {
  batchCitationExportRequestSchema,
  savedLibraryQuerySchema,
  savedPaperMutationSchema,
  tagsSchema,
} from "@/shared/api/library-schemas";
import { testIds } from "@/test/fixtures";

describe("library API schemas", () => {
  it("normalizes bounded tags before sending a saved-paper mutation", () => {
    expect(
      savedPaperMutationSchema.parse({
        readingStatus: "READING",
        tags: ["  Machine   Learning ", "KEY RESULT"],
      }),
    ).toEqual({
      readingStatus: "READING",
      tags: ["machine learning", "key result"],
    });
  });

  it("rejects duplicate normalized tags and more than ten tags", () => {
    expect(tagsSchema.safeParse(["Methods", " methods "]).success).toBe(false);
    expect(
      tagsSchema.safeParse(Array.from({ length: 11 }, (_, index) => `tag-${index}`))
        .success,
    ).toBe(false);
  });

  it("treats blank optional library filters as absent", () => {
    expect(
      savedLibraryQuerySchema.parse({
        q: "  ",
        collectionId: "",
        readingStatus: "",
        tag: "",
        page: 0,
        size: 20,
      }),
    ).toEqual({ page: 0, size: 20 });
  });

  it("bounds batch exports and requires distinct canonical IDs", () => {
    expect(
      batchCitationExportRequestSchema.safeParse({
        paperIds: [testIds.paper, testIds.paper],
        format: "bibtex",
      }).success,
    ).toBe(false);
    expect(
      batchCitationExportRequestSchema.safeParse({
        paperIds: [testIds.paper],
        format: "csl-json",
      }).success,
    ).toBe(true);
  });
});
