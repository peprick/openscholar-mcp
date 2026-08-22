export default function Loading(): React.JSX.Element {
  return (
    <main className="shell page" id="main-content">
      <div aria-live="polite" className="loadingPanel" role="status">
        <span className="loadingPulse" aria-hidden="true" />
        <div>
          <strong>Loading research</strong>
          <p>Loading your research view…</p>
        </div>
      </div>
    </main>
  );
}
