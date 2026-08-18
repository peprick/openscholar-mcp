import type { Metadata } from "next";
import Link from "next/link";
import { notFound } from "next/navigation";
import { z } from "zod";

import { SearchResults } from "@/features/search/search-results";
import { BackendApiError, getSearch } from "@/shared/api/server";

export const metadata: Metadata = {
  title: "Search results",
};

type SearchPageProps = {
  params: Promise<{ searchId: string }>;
};

export default async function SearchPage({
  params,
}: SearchPageProps): Promise<React.JSX.Element> {
  const { searchId } = await params;
  if (!z.string().uuid().safeParse(searchId).success) {
    notFound();
  }

  let search;
  try {
    search = await getSearch(searchId);
  } catch (error) {
    if (error instanceof BackendApiError && error.status === 404) {
      notFound();
    }
    throw error;
  }

  return (
    <main className="shell page" id="main-content">
      <div className="pageToolbar">
        <Link className="backLink" href="/">
          <span aria-hidden="true">←</span> New search
        </Link>
        <code className="recordId">Search {search.searchId.slice(0, 8)}</code>
      </div>
      <SearchResults search={search} />
    </main>
  );
}
