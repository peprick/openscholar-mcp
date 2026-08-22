import type { SearchResponse } from "@/shared/api/schemas";

import { PaperResultCard } from "./paper-result-card";
import { SearchPagination } from "./search-pagination";

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
          <p>
            {search.results.length} {search.results.length === 1 ? "paper" : "papers"}
            {search.nextCursor !== null ? " shown · more results available" : " found"}
          </p>
        </div>
      </section>

      {search.warnings.length > 0 ? (
        <aside className="warningPanel" aria-label="Search notice">
          <strong>Some research sources could not be reached.</strong>
          <p>The results shown may be incomplete. You can still open and save them.</p>
        </aside>
      ) : null}

      {search.results.length > 0 ? (
        <section className="resultList" aria-label="Research results">
          {search.results.map((result) => (
            <PaperResultCard key={result.paperId} result={result} />
          ))}
        </section>
      ) : (
        <section className="emptyState">
          <h2>No papers matched this search.</h2>
          <p>Try a broader topic, remove a filter, or search another language.</p>
        </section>
      )}

      {search.nextCursor !== null ? (
        <SearchPagination searchId={search.searchId} />
      ) : null}
    </>
  );
}
