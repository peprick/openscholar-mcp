import type {
  PaperDetailsResponse,
  RelatedPapersResponse,
} from "@/shared/api/schemas";
import {
  formatInteger,
  formatPublicationDate,
  humanizeEnum,
  identifierHref,
  providerDisplayName,
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
            <span className="eyebrow">Paper overview</span>
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
                    {author.orcid !== null ? (
                    <div className="authorIdentifiers">
                      {author.orcid !== null ? <code>ORCID {author.orcid}</code> : null}
                    </div>
                    ) : null}
                  </li>
                ))}
              </ol>
            ) : (
              <p className="inlineNotice">No credited authors are stored.</p>
            )}
          </section>

          <RelatedPapers related={related} unavailable={relatedUnavailable} />

          <section className="paperSection" aria-labelledby="provenance-heading">
            <span className="eyebrow">Where this came from</span>
            <h2 id="provenance-heading">Sources</h2>
            <p className="sectionDescription">
              OpenScholar combines trusted research databases into one paper record.
            </p>
            <div className="provenanceList">
              {paper.provenance.map((record) => (
                <article key={`${record.provider}-${record.providerRecordId}`}>
                  <div>
                    <strong>{providerDisplayName(record.provider)}</strong>
                    {record.authorshipSource ? (
                      <Badge tone="info">Author details</Badge>
                    ) : null}
                  </div>
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
          <div className="metadataCardHeader">
            <span className="eyebrow">At a glance</span>
            <h2>Paper details</h2>
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
              <dt>Venue</dt>
              <dd>{paper.venueName ?? "Not available"}</dd>
            </div>
            <div>
              <dt>Language</dt>
              <dd>{paper.language?.toUpperCase() ?? "Not available"}</dd>
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
