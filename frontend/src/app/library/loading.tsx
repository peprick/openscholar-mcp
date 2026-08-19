export default function LibraryLoading(): React.JSX.Element {
  return (
    <main className="shell page" id="main-content">
      <div aria-live="polite" className="loadingPanel" role="status">
        <span aria-hidden="true" className="loadingPulse" />
        <div>
          <strong>Opening your library</strong>
          <p>Loading collections and saved-paper metadata…</p>
        </div>
      </div>
    </main>
  );
}
