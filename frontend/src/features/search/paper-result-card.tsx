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

function matchReasonLabel(feature: string): string {
  if (feature === "TITLE_EXACT" || feature === "TITLE_PREFIX") {
    return "The title closely matches your topic";
  }
  if (
    feature === "TEXT_RELEVANCE" ||
    feature === "POSTGRES_FULL_TEXT" ||
    feature === "ABSTRACT_MATCH"
  ) {
    return "Similar words in the title or abstract";
  }
  if (feature === "AUTHOR_MATCH") return "An author matches your search";
  if (feature === "CITATION_SIGNAL") return "Frequently cited paper";
  if (feature === "PROVIDER_RECIPROCAL_RANK_FUSION") {
    return "Appears across research sources";
  }
  return "Strong topic match";
}

export function PaperResultCard({
  result,
}: {
  result: SearchResult;
}): React.JSX.Element {
  const matchReasons = Array.from(
    new Set(result.rankingReasons.map((reason) => matchReasonLabel(reason.feature))),
  );
  const identifiers = [
    result.identifiers.doi === null ? null : `DOI ${result.identifiers.doi}`,
    result.identifiers.arxiv === null
      ? null
      : `arXiv ${result.identifiers.arxiv}`,
  ].filter((value): value is string => value !== null);

  return (
    <article className="resultCard">
      <div className="resultContent">
        <div className="resultBadges">
          <Badge>{humanizeEnum(result.documentType)}</Badge>
          {result.reportedOpenAccess ? (
            <Badge tone="positive">Open access reported</Badge>
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
            No abstract available.
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
            View paper &amp; access <span aria-hidden="true">→</span>
          </Link>
        </div>
        <details className="rankingDetails">
          <summary>Why it matched</summary>
          <div>
            {matchReasons.length > 0 ? (
              <ul>
                {matchReasons.map((reason) => (
                  <li key={reason}>{reason}</li>
                ))}
              </ul>
            ) : null}
            <p>
              Metadata from {result.provenance.map((item) => item.provider).join(", ")}.
              Open-access labels come from those sources; OpenScholar checks links
              on the paper page.
            </p>
          </div>
        </details>
      </div>
    </article>
  );
}
