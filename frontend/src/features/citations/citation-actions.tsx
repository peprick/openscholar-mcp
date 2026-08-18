export function CitationActions({
  paperId,
}: {
  paperId: string;
}): React.JSX.Element {
  const basePath = `/api/papers/${encodeURIComponent(paperId)}/citation`;
  return (
    <section className="citationActions" aria-labelledby="citation-heading">
      <div>
        <span className="eyebrow">Use this research</span>
        <h2 id="citation-heading">Export citation</h2>
        <p>Generated locally from the canonical metadata shown on this page.</p>
      </div>
      <div className="buttonGroup">
        <a className="button button--secondary" href={`${basePath}?format=bibtex`}>
          Download BibTeX
        </a>
        <a className="button button--secondary" href={`${basePath}?format=csl-json`}>
          Download CSL-JSON
        </a>
      </div>
    </section>
  );
}
