"use client";

import { useEffect } from "react";

export default function GlobalError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}): React.JSX.Element {
  useEffect(() => {
    console.error(error);
  }, [error]);

  return (
    <main className="shell page" id="main-content">
      <section className="emptyState" role="alert">
        <span className="eyebrow">Temporary problem</span>
        <h1>We could not load this research view.</h1>
        <p>
          OpenScholar is temporarily unavailable. Try again in a moment; your
          saved data has not been changed.
        </p>
        <button className="button button--primary" onClick={reset} type="button">
          Try again
        </button>
      </section>
    </main>
  );
}
