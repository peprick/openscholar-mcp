import type {
  PaperDetailsResponse,
  RelatedPapersResponse,
} from "@/shared/api/schemas";
import {
  formatInstant,
  formatInteger,
  formatPercent,
  formatPublicationDate,
  humanizeEnum,
  identifierHref,
} from "@/shared/formatting/display";
import { Badge } from "@/shared/ui/badge";
import { ExternalLink } from "@/shared/ui/external-link";

import { RelatedPapers } from "./related-papers";

export function PaperDetails({
  paper,
  related,
  relatedUnavailable = false,
}: {
  paper: PaperDetailsResponse;
  related: RelatedPapersResponse;
  relatedUnavailable?: boolean;
}): React.JSX.Element {
  return (
    <article>
      <header className="paperHeader">
        <div className="resultBadges">
          <Badge>{humanizeEnum(paper.documentType)}</Badge>
          {paper.language !== null ? <Badge>{paper.language.toUpperCase()}</Badge> : null}
        </div>
        <h1>{paper.title}</h1>
        <p className="paperByline">
          {paper.authors.length > 0
            ? paper.authors.map((author) => author.name).join(", ")
            : "Authors unavailable"}
        </p>
        <div className="paperHeadlineFacts">
          <span>
            {formatPublicationDate(paper.publicationDate, paper.publicationYear)}
          </span>
          <span>{paper.venueName ?? "Venue unavailable"}</span>
          <span>{formatInteger(paper.citationCount)} citations</span>
        </div>
      </header>

      <div className="paperLayout">
        <div className="paperMain">
          <section className="paperSection" aria-labelledby="abstract-heading">
            <span className="eyebrow">Canonical metadata</span>
            <h2 id="abstract-heading">Abstract</h2>
            {paper.abstractText !== null ? (
              <p className="abstractText">{paper.abstractText}</p>
            ) : (
              <p className="inlineNotice">No abstract is stored for this paper.</p>
            )}
          </section>

          <section className="paperSection" aria-labelledby="authors-heading">
            <span className="eyebrow">Credited order</span>
            <h2 id="authors-heading">Authors</h2>
            {paper.authors.length > 0 ? (
              <ol className="authorList">
                {paper.authors.map((author) => (
                  <li key={`${author.position}-${author.name}`}>
                    <div>
                      <strong>{author.name}</strong>
                      {author.corresponding ? <Badge tone="info">Corresponding</Badge> : null}
                    </div>
                    <div className="authorIdentifiers">
                      {author.orcid !== null ? <code>ORCID {author.orcid}</code> : null}
                      {author.openAlexId !== null ? (
                        <code>OpenAlex {author.openAlexId}</code>
                      ) : null}
                    </div>
                  </li>
                ))}
              </ol>
            ) : (
              <p className="inlineNotice">No credited authors are stored.</p>
            )}
          </section>

          <RelatedPapers related={related} unavailable={relatedUnavailable} />

          <section className="paperSection" aria-labelledby="provenance-heading">
            <span className="eyebrow">Record-level evidence</span>
            <h2 id="provenance-heading">Provenance</h2>
            <p className="sectionDescription">
              These records contributed to the canonical paper. The authorship
              marker identifies the source used for the ordered credited names;
              it is not field-by-field attribution.
            </p>
            <div className="provenanceList">
              {paper.provenance.map((record) => (
                <article key={`${record.provider}-${record.providerRecordId}`}>
                  <div>
                    <strong>{record.provider}</strong>
                    {record.authorshipSource ? (
                      <Badge tone="info">Authorship source</Badge>
                    ) : null}
                  </div>
                  <code>{record.providerRecordId}</code>
                  <dl>
                    <div>
                      <dt>Retrieved</dt>
                      <dd>{formatInstant(record.retrievedAt)}</dd>
                    </div>
                    <div>
                      <dt>Provider updated</dt>
                      <dd>{formatInstant(record.providerUpdatedAt)}</dd>
                    </div>
                  </dl>
                  {record.sourceUrl !== null ? (
                    <ExternalLink className="textLink" href={record.sourceUrl}>
                      View source record
                    </ExternalLink>
                  ) : null}
                </article>
              ))}
            </div>
          </section>
        </div>

        <aside className="metadataCard" aria-label="Paper metadata">
          <div className="completeness">
            <div>
              <span>Metadata completeness</span>
              <strong>{formatPercent(paper.metadataCompleteness)}</strong>
            </div>
            <progress max="1" value={paper.metadataCompleteness}>
              {formatPercent(paper.metadataCompleteness)}
            </progress>
          </div>
          <dl className="metadataList">
            <div>
              <dt>Document type</dt>
              <dd>{humanizeEnum(paper.documentType)}</dd>
            </div>
            <div>
              <dt>Publication</dt>
              <dd>
                {formatPublicationDate(paper.publicationDate, paper.publicationYear)}
              </dd>
            </div>
            <div>
              <dt>Citation count as of</dt>
              <dd>{formatInstant(paper.citationCountAsOf)}</dd>
            </div>
            <div>
              <dt>Metadata updated</dt>
              <dd>{formatInstant(paper.metadataUpdatedAt)}</dd>
            </div>
          </dl>
          <div className="identifierPanel">
            <h2>Identifiers</h2>
            {paper.identifiers.length > 0 ? (
              <ul>
                {paper.identifiers.map((identifier) => {
                  const href = identifierHref(identifier.type, identifier.value);
                  return (
                    <li key={`${identifier.namespace}-${identifier.value}`}>
                      <span>{identifier.type}</span>
                      {href === null ? (
                        <code>{identifier.value}</code>
                      ) : (
                        <ExternalLink href={href}>{identifier.value}</ExternalLink>
                      )}
                    </li>
                  );
                })}
              </ul>
            ) : (
              <p>None stored</p>
            )}
          </div>
        </aside>
      </div>
    </article>
  );
}
