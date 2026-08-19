import type { Route } from "next";
import Link from "next/link";

import type { RelatedPapersResponse } from "@/shared/api/schemas";
import {
  authorSummary,
  formatInteger,
  formatPublicationDate,
  humanizeEnum,
} from "@/shared/formatting/display";
import { Badge } from "@/shared/ui/badge";

export function RelatedPapers({
  related,
  unavailable = false,
}: {
  related: RelatedPapersResponse;
  unavailable?: boolean;
}): React.JSX.Element {
  return (
    <section className="paperSection" aria-labelledby="related-papers-heading">
      <span className="eyebrow">Experimental local relevance</span>
      <h2 id="related-papers-heading">Related papers</h2>
      <p className="sectionDescription">
        Ranked from title, abstract, and venue matches among papers already
        stored in OpenScholar.
      </p>
      {unavailable ? (
        <p className="inlineNotice" role="status">
          Related papers are temporarily unavailable. The canonical paper
          details remain available.
        </p>
      ) : related.results.length === 0 ? (
        <p className="inlineNotice">
          No related papers are available in the local catalog yet.
        </p>
      ) : (
        <ol className="relatedPaperList">
          {related.results.map((result) => (
            <li key={result.paperId}>
              <article>
                <div className="relatedPaperHeading">
                  <span className="relatedPaperRank" aria-label={`Rank ${result.rank}`}>
                    {String(result.rank).padStart(2, "0")}
                  </span>
                  <div>
                    <Badge>{humanizeEnum(result.documentType)}</Badge>
                    <h3>
                      <Link href={`/papers/${result.paperId}` as Route}>
                        {result.title}
                      </Link>
                    </h3>
                  </div>
                </div>
                <p className="authorLine">{authorSummary(result.authors)}</p>
                <p className="relatedPaperFacts">
                  <span>
                    {formatPublicationDate(
                      result.publicationDate,
                      result.publicationYear,
                    )}
                  </span>
                  <span>{result.venue ?? "Venue unavailable"}</span>
                  <span>{formatInteger(result.citationCount)} citations</span>
                </p>
                <p className="relatedPaperReason">
                  {result.rankingReasons.length > 0
                    ? humanizeEnum(result.rankingReasons[0]!.feature)
                    : "Local relevance"}
                  {` · score ${result.score.toFixed(3)}`}
                </p>
              </article>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
