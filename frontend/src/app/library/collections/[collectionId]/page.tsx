import type { Metadata, Route } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { z } from "zod";

import { CollectionManager } from "@/features/library/collection-manager";
import { BackendApiError, getCollection } from "@/shared/api/server";

export const metadata: Metadata = {
  title: "Manage collection",
};

type CollectionPageProps = {
  params: Promise<{ collectionId: string }>;
  searchParams: Promise<{ page?: string | string[] }>;
};

export default async function CollectionPage({
  params,
  searchParams,
}: CollectionPageProps): Promise<React.JSX.Element> {
  const { collectionId } = await params;
  if (!z.string().uuid().safeParse(collectionId).success) notFound();
  const requestedPage = (await searchParams).page;
  const pageValue = Array.isArray(requestedPage) ? requestedPage[0] : requestedPage;
  const parsedPage = z.coerce.number().int().nonnegative().safeParse(pageValue ?? 0);
  const page = parsedPage.success ? parsedPage.data : 0;

  let collection;
  try {
    collection = await getCollection(collectionId, page, 20);
  } catch (error) {
    if (error instanceof BackendApiError && error.status === 404) notFound();
    throw error;
  }

  return (
    <main className="shell page collectionPage" id="main-content">
      <div className="pageToolbar">
        <Link className="backLink" href={"/library" as Route}>
          <span aria-hidden="true">←</span> Research library
        </Link>
      </div>
      {!parsedPage.success ? (
        <div className="warningPanel" role="alert">
          The requested page number was invalid, so the first page is shown.
        </div>
      ) : null}
      <CollectionManager
        collection={collection}
        key={`${collection.collectionId}:${collection.papers.page}:${collection.papers.totalElements}:${collection.papers.items.map((paper) => paper.updatedAt).join(",")}`}
      />
      {collection.papers.totalPages > 1 ? (
        <nav aria-label="Collection paper pages" className="pagination">
          {collection.papers.page > 0 ? (
            <Link href={`?page=${collection.papers.page - 1}`}>Previous</Link>
          ) : (
            <span aria-disabled="true">Previous</span>
          )}
          <span>
            Page {collection.papers.page + 1} of {collection.papers.totalPages}
          </span>
          {collection.papers.page + 1 < collection.papers.totalPages ? (
            <Link href={`?page=${collection.papers.page + 1}`}>Next</Link>
          ) : (
            <span aria-disabled="true">Next</span>
          )}
        </nav>
      ) : null}
    </main>
  );
}
