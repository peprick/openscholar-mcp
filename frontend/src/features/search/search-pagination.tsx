"use client";

import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { searchResponseSchema } from "@/shared/api/schemas";

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
        setMessage("More results could not be loaded right now. Please try again.");
        return;
      }

      const nextPage = searchResponseSchema.safeParse(body);
      if (!nextPage.success || nextPage.data.searchId === searchId) {
        setMessage("OpenScholar received an unexpected response. Please try again.");
        return;
      }
      router.push(`/searches/${nextPage.data.searchId}` as Route);
    } catch {
      setMessage("More results could not be loaded right now. Please try again.");
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
