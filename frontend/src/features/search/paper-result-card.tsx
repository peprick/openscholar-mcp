import type { Route } from "next";
import Link from "next/link";

import type { SearchResult } from "@/shared/api/schemas";
import {
  authorSummary,
  formatInteger,
  formatPublicationDate,
  humanizeEnum,
} from "@/shared/formatting/display";
import { Badge } from "@/shared/ui/badge";

export function PaperResultCard({
  result,
}: {
  result: SearchResult;
}): React.JSX.Element {
  const identifiers = [
    result.identifiers.doi === null ? null : `DOI ${result.identifiers.doi}`,
    result.identifiers.arxiv === null
      ? null
      : `arXiv ${result.identifiers.arxiv}`,
  ].filter((value): value is string => value !== null);

  return (
    <article className="resultCard">
      <div className="rankMarker" aria-label={`Result rank ${result.rank}`}>
        {String(result.rank).padStart(2, "0")}
      </div>
      <div className="resultContent">
        <div className="resultBadges">
          <Badge>{humanizeEnum(result.documentType)}</Badge>
          {result.reportedOpenAccess ? (
            <Badge tone="positive">Provider reports open access</Badge>
          ) : null}
        </div>
        <h2>
          <Link href={`/papers/${result.paperId}` as Route}>{result.title}</Link>
        </h2>
        <p className="authorLine">{authorSummary(result.authors)}</p>
        <dl className="resultMetadata">
          <div>
            <dt>Published</dt>
            <dd>
              {formatPublicationDate(
                result.publicationDate,
                result.publicationYear,
              )}
            </dd>
          </div>
          <div>
            <dt>Venue</dt>
            <dd>{result.venue ?? "Not available"}</dd>
          </div>
          <div>
            <dt>Citations</dt>
            <dd>{formatInteger(result.citationCount)}</dd>
          </div>
        </dl>
        {result.abstractText !== null ? (
          <p className="resultAbstract">{result.abstractText}</p>
        ) : (
          <p className="resultAbstract resultAbstract--muted">
            No abstract was supplied by the provider.
          </p>
        )}
        <div className="resultFooter">
          {identifiers.length > 0 ? (
            <div
              className="identifierList"
              role="group"
              aria-label="Paper identifiers"
            >
              {identifiers.map((identifier) => (
                <code key={identifier}>{identifier}</code>
              ))}
            </div>
          ) : null}
          <Link
            className="textLink"
            href={`/papers/${result.paperId}` as Route}
          >
            Inspect paper and access <span aria-hidden="true">→</span>
          </Link>
        </div>
        <details className="rankingDetails">
          <summary>Why this result?</summary>
          <div>
            <p>
              Score: {result.score === null ? "not reported" : result.score.toFixed(3)}
            </p>
            {result.rankingReasons.length > 0 ? (
              <ul>
                {result.rankingReasons.map((reason) => (
                  <li key={reason.feature}>
                    {humanizeEnum(reason.feature)}
                    {reason.value === null ? "" : `: ${reason.value.toFixed(3)}`}
                  </li>
                ))}
              </ul>
            ) : null}
            <p>
              Record from {result.provenance.map((item) => item.provider).join(", ")}.
              Open-access labels on this card are provider-reported; verified links
              appear on the paper page.
            </p>
          </div>
        </details>
      </div>
    </article>
  );
}
