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
  function matchDescription(feature: string | undefined): string {
    if (feature === "CLAMPED_COSINE") return "Similar topic";
    if (feature === "POSTGRES_FULL_TEXT") return "Similar title or abstract";
    return "Similar paper";
  }

  return (
    <section className="paperSection" aria-labelledby="related-papers-heading">
      <span className="eyebrow">Keep exploring</span>
      <h2 id="related-papers-heading">Related papers</h2>
      <p className="sectionDescription">
        Suggestions based on similarities in title, abstract, and publication venue.
      </p>
      {unavailable ? (
        <p className="inlineNotice" role="status">
          Related papers are temporarily unavailable. This paper’s details are
          still available.
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
                  {matchDescription(result.rankingReasons[0]?.feature)}
                </p>
              </article>
            </li>
          ))}
        </ol>
      )}
    </section>
  );
}
