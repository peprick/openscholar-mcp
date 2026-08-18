import Link from "next/link";

export default function NotFound(): React.JSX.Element {
  return (
    <main className="shell page" id="main-content">
      <section className="emptyState">
        <span className="eyebrow">Not found</span>
        <h1>This research record is not available.</h1>
        <p>
          The link may be incomplete, or the saved search or paper no longer
          exists in this database.
        </p>
        <Link className="button button--primary" href="/">
          Start a new search
        </Link>
      </section>
    </main>
  );
}
