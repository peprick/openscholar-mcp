import { describe, expect, it } from "vitest";

import {
  batchCitationExportRequestSchema,
  offlineCollectionPackSchema,
  savedLibraryQuerySchema,
  savedPaperMutationSchema,
  tagsSchema,
} from "@/shared/api/library-schemas";
import { offlineCollectionPackFixture, testIds } from "@/test/fixtures";

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

  it("accepts the exact bounded metadata-only offline-pack contract", () => {
    expect(offlineCollectionPackSchema.parse(offlineCollectionPackFixture())).toEqual(
      offlineCollectionPackFixture(),
    );
  });

  it("accepts the OpenAPI collection, title, and tag boundaries", () => {
    const payload = offlineCollectionPackFixture();
    payload.collection.name = "n".repeat(120);
    payload.collection.description = "d".repeat(1_000);
    payload.papers[0]!.title = "t";
    payload.papers[0]!.tags = Array.from({ length: 10 }, (_, index) =>
      String(index).padEnd(40, "x"),
    );

    expect(offlineCollectionPackSchema.safeParse(payload).success).toBe(true);
  });

  it.each([
    ["a blank collection name", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.collection.name = "";
    }],
    ["a 121-character collection name", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.collection.name = "n".repeat(121);
    }],
    ["a 1001-character description", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.collection.description = "d".repeat(1_001);
    }],
    ["a blank paper title", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.papers[0]!.title = "";
    }],
    ["eleven tags", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.papers[0]!.tags = Array.from(
        { length: 11 },
        (_, index) => `tag-${index}`,
      );
    }],
    ["a blank tag", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.papers[0]!.tags = [""];
    }],
    ["a 41-character tag", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.papers[0]!.tags = ["t".repeat(41)];
    }],
    ["duplicate tags", (payload: ReturnType<typeof offlineCollectionPackFixture>) => {
      payload.papers[0]!.tags = ["methods", "methods"];
    }],
  ])("rejects an offline pack with %s", (_label, mutate) => {
    const payload = offlineCollectionPackFixture();
    mutate(payload);

    expect(offlineCollectionPackSchema.safeParse(payload).success).toBe(false);
  });

  it("rejects uncontracted private fields and more than 500 offline papers", () => {
    expect(
      offlineCollectionPackSchema.safeParse({
        ...offlineCollectionPackFixture(),
        papers: [
          {
            ...offlineCollectionPackFixture().papers[0]!,
            abstractText: "This must never enter the offline pack.",
          },
        ],
      }).success,
    ).toBe(false);

    const paper = offlineCollectionPackFixture().papers[0]!;
    expect(
      offlineCollectionPackSchema.safeParse({
        ...offlineCollectionPackFixture(),
        papers: Array.from({ length: 501 }, () => paper),
      }).success,
    ).toBe(false);
  });
});
