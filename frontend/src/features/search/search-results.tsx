import type { SearchResponse } from "@/shared/api/schemas";

import { PaperResultCard } from "./paper-result-card";
import { SearchPagination } from "./search-pagination";

function SearchNotice({
  search,
}: {
  search: SearchResponse;
}): React.JSX.Element | null {
  if (search.executionSource === "LOCAL_CATALOG") {
    if (search.requestedMode === "LOCAL") {
      return (
        <aside
          aria-label="Search notice"
          className="searchNotice"
          role="status"
        >
          <strong>Local results.</strong>
          <p>
            These papers were already saved or previously discovered in
            OpenScholar. Search online for newer work.
          </p>
        </aside>
      );
    }

    return (
      <aside aria-label="Search notice" className="warningPanel" role="status">
        <strong>Online research sources are unavailable.</strong>
        <p>
          Showing previously discovered papers so you can keep working.
        </p>
      </aside>
    );
  }

  if (search.executionSource === "STALE_CACHE") {
    return (
      <aside aria-label="Search notice" className="warningPanel" role="status">
        <strong>Showing earlier results.</strong>
        <p>Live research sources are unavailable right now.</p>
      </aside>
    );
  }

  if (search.warnings.length > 0) {
    return (
      <aside aria-label="Search notice" className="warningPanel">
        <strong>Some research sources could not be reached.</strong>
        <p>
          The results shown may be incomplete. You can still open and save them.
        </p>
      </aside>
    );
  }

  return null;
}

function resultCount(search: SearchResponse): string {
  const count = search.results.length;
  if (search.executionSource === "LOCAL_CATALOG") {
    const papers =
      count === 1
        ? "previously discovered paper"
        : "previously discovered papers";
    return search.nextCursor === null
      ? `${count} ${papers} available`
      : `${count} ${papers} shown · more local results available`;
  }

  const papers = count === 1 ? "paper" : "papers";
  return search.nextCursor === null
    ? `${count} ${papers} found`
    : `${count} ${papers} shown · more results available`;
}

export function SearchResults({
  search,
}: {
  search: SearchResponse;
}): React.JSX.Element {
  return (
    <>
      <section className="searchSummary" aria-labelledby="results-heading">
        <div>
          <span className="eyebrow">Search results</span>
          <h1 id="results-heading">{search.query}</h1>
          <p>{resultCount(search)}</p>
        </div>
      </section>

      <SearchNotice search={search} />

      {search.results.length > 0 ? (
        <section className="resultList" aria-label="Research results">
          {search.results.map((result) => (
            <PaperResultCard key={result.paperId} result={result} />
          ))}
        </section>
      ) : (
        <section className="emptyState">
          {search.executionSource === "LOCAL_CATALOG" ? (
            <>
              <h2>No locally known papers matched this search.</h2>
              <p>
                {search.requestedMode === "LOCAL"
                  ? "Try a broader topic, or search online for newer research."
                  : "Reconnect to search online research sources."}
              </p>
            </>
          ) : (
            <>
              <h2>No papers matched this search.</h2>
              <p>
                Try a broader topic, remove a filter, or search another language.
              </p>
            </>
          )}
        </section>
      )}

      {search.nextCursor !== null ? (
        <SearchPagination searchId={search.searchId} />
      ) : null}
    </>
  );
}
