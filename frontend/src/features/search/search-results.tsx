import type { SearchResponse } from "@/shared/api/schemas";
import { formatInstant, formatInteger } from "@/shared/formatting/display";

import { CacheBadge } from "./cache-badge";
import { PaperResultCard } from "./paper-result-card";
import { SearchPagination } from "./search-pagination";

export function SearchResults({
  search,
}: {
  search: SearchResponse;
}): React.JSX.Element {
  const totalMatches = Math.max(
    0,
    ...search.providerCoverage.map((item) => item.totalMatches),
  );

  return (
    <>
      <section className="searchSummary" aria-labelledby="results-heading">
        <div>
          <span className="eyebrow">Saved research snapshot</span>
          <h1 id="results-heading">{search.query}</h1>
          <p>
            {search.results.length} ranked results in this snapshot
            {totalMatches > search.results.length
              ? ` · ${formatInteger(totalMatches)} provider matches`
              : ""}
          </p>
        </div>
        <div className="snapshotFacts">
          <CacheBadge disposition={search.cacheDisposition} />
          <span>Captured {formatInstant(search.searchedAt)}</span>
          <span>Fresh until {formatInstant(search.freshUntil)}</span>
        </div>
      </section>

      {search.warnings.length > 0 ? (
        <aside className="warningPanel" aria-label="Search warnings">
          <strong>Some provider data may be incomplete.</strong>
          <ul>
            {search.warnings.map((warning) => (
              <li key={warning}>{warning}</li>
            ))}
          </ul>
        </aside>
      ) : null}

      <section className="coverageBar" aria-label="Provider coverage">
        {search.providerCoverage.map((coverage) => (
          <div key={coverage.provider}>
            <span>{coverage.provider}</span>
            <strong>{coverage.status}</strong>
            <small>
              {coverage.returnedCount} returned · {formatInteger(coverage.totalMatches)}
              {" "}matches
            </small>
          </div>
        ))}
        {search.nextCursor !== null ? (
          <p>
            Additional provider results exist. Continue below to load the next
            immutable page.
          </p>
        ) : null}
      </section>

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
