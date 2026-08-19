import type { Metadata } from "next";
import { z } from "zod";

import { LibraryDashboard } from "@/features/library/library-dashboard";
import { savedLibraryQuerySchema } from "@/shared/api/library-schemas";
import {
  getAllCollectionOptions,
  getCollections,
  searchSavedLibrary,
} from "@/shared/api/server";

export const metadata: Metadata = {
  title: "Research library",
};

type LibraryPageProps = {
  searchParams: Promise<Record<string, string | string[] | undefined>>;
};

function first(value: string | string[] | undefined): string | undefined {
  return Array.isArray(value) ? value[0] : value;
}

export default async function LibraryPage({
  searchParams,
}: LibraryPageProps): Promise<React.JSX.Element> {
  const parameters = await searchParams;
  const requestedQuery = savedLibraryQuerySchema.safeParse({
    q: first(parameters.q),
    collectionId: first(parameters.collectionId),
    readingStatus: first(parameters.readingStatus),
    tag: first(parameters.tag),
    page:
      first(parameters.page) === undefined
        ? undefined
        : Number(first(parameters.page)),
    size: 20,
  });
  const requestedCollectionsPage = z.coerce
    .number()
    .int()
    .nonnegative()
    .safeParse(first(parameters.collectionsPage) ?? 0);
  const query = requestedQuery.success
    ? requestedQuery.data
    : savedLibraryQuerySchema.parse({ page: 0, size: 20 });
  const collectionsPage = requestedCollectionsPage.success
    ? requestedCollectionsPage.data
    : 0;
  const [collections, collectionOptions, papers] = await Promise.all([
    getCollections(collectionsPage, 12),
    getAllCollectionOptions(),
    searchSavedLibrary(query),
  ]);

  return (
    <main className="shell page libraryPage" id="main-content">
      {!requestedQuery.success ? (
        <div className="warningPanel" role="alert">
          Invalid library filters were cleared. Search terms are limited to 200
          characters and page numbers must be non-negative.
        </div>
      ) : null}
      {!requestedCollectionsPage.success ? (
        <div className="warningPanel" role="alert">
          The requested collections page was invalid, so the first page is shown.
        </div>
      ) : null}
      <LibraryDashboard
        collectionOptions={collectionOptions}
        collections={collections}
        key={`${collections.page}:${collections.totalElements}:${collections.items.map((collection) => collection.updatedAt).join(",")}:${papers.page}:${query.q ?? ""}:${query.collectionId ?? ""}:${query.readingStatus ?? ""}:${query.tag ?? ""}`}
        papers={papers}
        query={query}
      />
    </main>
  );
}
