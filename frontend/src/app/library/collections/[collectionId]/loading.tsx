export default function CollectionLoading(): React.JSX.Element {
  return (
    <main className="shell page" id="main-content">
      <div aria-live="polite" className="loadingPanel" role="status">
        <span aria-hidden="true" className="loadingPulse" />
        <div>
          <strong>Opening this collection</strong>
          <p>Loading saved papers and reading progress…</p>
        </div>
      </div>
    </main>
  );
}
