"use client";

import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useState } from "react";

import {
  apiProblemSchema,
  searchResponseSchema,
} from "@/shared/api/schemas";

export function SearchPagination({
  searchId,
}: {
  searchId: string;
}): React.JSX.Element {
  const router = useRouter();
  const [pending, setPending] = useState(false);
  const [message, setMessage] = useState<string | null>(null);

  async function loadNextPage(): Promise<void> {
    setPending(true);
    setMessage(null);
    try {
      const response = await fetch(
        `/api/searches/${encodeURIComponent(searchId)}/next`,
        { method: "POST" },
      );
      const body: unknown = await response.json();
      if (!response.ok) {
        const problem = apiProblemSchema.safeParse(body);
        setMessage(
          problem.success
            ? problem.data.detail
            : "The next page of results could not be loaded.",
        );
        return;
      }

      const nextPage = searchResponseSchema.safeParse(body);
      if (!nextPage.success || nextPage.data.searchId === searchId) {
        setMessage("The backend returned an unexpected search response.");
        return;
      }
      router.push(`/searches/${nextPage.data.searchId}` as Route);
    } catch {
      setMessage(
        "OpenScholar could not reach the search service. Please retry.",
      );
    } finally {
      setPending(false);
    }
  }

  return (
    <nav aria-label="Search result pages" className="pagination searchPagination">
      <button
        className="button button--secondary"
        disabled={pending}
        onClick={loadNextPage}
        type="button"
      >
        {pending ? "Loading next results…" : "Next results"}
      </button>
      <span aria-live="polite" className="searchPaginationStatus">
        {message}
      </span>
    </nav>
  );
}
