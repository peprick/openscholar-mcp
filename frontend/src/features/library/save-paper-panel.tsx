"use client";

import type { Route } from "next";
import Link from "next/link";
import { useState } from "react";

import { responseErrorMessage } from "@/features/library/library-client";
import { parseTagInput } from "@/features/library/tag-input";
import {
  collectionListResponseSchema,
  readingStatuses,
  savedPaperMutationSchema,
  savedPaperSchema,
  type CollectionSummary,
  type ReadingStatus,
} from "@/shared/api/library-schemas";
import { humanizeEnum } from "@/shared/formatting/display";

export function SavePaperPanel({
  paperId,
}: {
  paperId: string;
}): React.JSX.Element {
  const [open, setOpen] = useState(false);
  const [collections, setCollections] = useState<CollectionSummary[]>([]);
  const [collectionsLoaded, setCollectionsLoaded] = useState(false);
  const [collectionPage, setCollectionPage] = useState(-1);
  const [collectionTotalPages, setCollectionTotalPages] = useState(0);
  const [failedCollectionPage, setFailedCollectionPage] = useState<number | null>(
    null,
  );
  const [loadError, setLoadError] = useState<string | null>(null);
  const [collectionId, setCollectionId] = useState("");
  const [readingStatus, setReadingStatus] =
    useState<ReadingStatus>("UNREAD");
  const [tagText, setTagText] = useState("");
  const [pending, setPending] = useState<"load" | "save" | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  async function loadCollections(page: number, append: boolean): Promise<void> {
    setPending("load");
    setLoadError(null);
    setFailedCollectionPage(null);
    try {
      const response = await fetch(`/api/collections?page=${page}&size=50`);
      if (!response.ok) {
        setFailedCollectionPage(page);
        setLoadError(
          await responseErrorMessage(response, "Collections could not be loaded."),
        );
        return;
      }
      const parsed = collectionListResponseSchema.safeParse(await response.json());
      if (!parsed.success) {
        setFailedCollectionPage(page);
        setLoadError("The server returned an unexpected collection list.");
        return;
      }
      setCollections((current) => {
        if (!append) return parsed.data.items;
        const byId = new Map(
          [...current, ...parsed.data.items].map((collection) => [
            collection.collectionId,
            collection,
          ]),
        );
        return [...byId.values()];
      });
      setCollectionId(
        (current) => current || parsed.data.items[0]?.collectionId || "",
      );
      setCollectionPage(parsed.data.page);
      setCollectionTotalPages(parsed.data.totalPages);
      setCollectionsLoaded(true);
    } catch {
      setFailedCollectionPage(page);
      setLoadError("OpenScholar could not reach the library service.");
    } finally {
      setPending(null);
    }
  }

  async function openPanel(): Promise<void> {
    if (open) {
      setOpen(false);
      return;
    }
    setOpen(true);
    if (collectionsLoaded) return;
    setMessage(null);
    await loadCollections(0, false);
  }

  async function save(): Promise<void> {
    const tags = parseTagInput(tagText);
    const request = tags.success
      ? savedPaperMutationSchema.safeParse({ readingStatus, tags: tags.data })
      : tags;
    if (!request.success) {
      setMessage(
        "Use at most 10 distinct comma-separated tags, each no longer than 40 characters.",
      );
      return;
    }
    if (collectionId === "") {
      setMessage("Choose a collection before saving this paper.");
      return;
    }
    setPending("save");
    setMessage(null);
    try {
      const response = await fetch(
        `/api/collections/${encodeURIComponent(collectionId)}/papers/${encodeURIComponent(paperId)}`,
        {
          method: "PUT",
          headers: { "content-type": "application/json" },
          body: JSON.stringify(request.data),
        },
      );
      if (!response.ok) {
        setMessage(
          await responseErrorMessage(response, "The paper could not be saved."),
        );
        return;
      }
      const parsed = savedPaperSchema.safeParse(await response.json());
      if (!parsed.success || parsed.data.paperId !== paperId) {
        setMessage("The server returned an unexpected saved-paper response.");
        return;
      }
      setReadingStatus(parsed.data.readingStatus);
      setTagText(parsed.data.tags.join(", "));
      setMessage(`Saved to “${parsed.data.collectionName}”.`);
    } catch {
      setMessage("OpenScholar could not reach the library service.");
    } finally {
      setPending(null);
    }
  }

  return (
    <section className="savePaperPanel" aria-labelledby="save-paper-heading">
      <div>
        <span className="eyebrow">Build your reading list</span>
        <h2 id="save-paper-heading">Save to your library</h2>
        <p>
          Keep the paper, your reading progress, and personal tags together.
          OpenScholar does not save the source PDF.
        </p>
      </div>
      <button
        aria-expanded={open}
        className="button button--primary"
        disabled={pending === "load"}
        onClick={() => void openPanel()}
        type="button"
      >
        {pending === "load" ? "Loading collections…" : open ? "Close" : "Save to collection"}
      </button>

      {open ? (
        <div className="savePaperForm">
          {pending === "load" && !collectionsLoaded ? (
            <p aria-live="polite" className="libraryMessage" role="status">
              Loading your collection list…
            </p>
          ) : null}
          {loadError !== null ? (
            <div className="errorSummary libraryLoadError" role="alert">
              <p>{loadError}</p>
              <button
                className="button button--secondary"
                disabled={pending !== null}
                onClick={() =>
                  void loadCollections(
                    failedCollectionPage ?? 0,
                    (failedCollectionPage ?? 0) > 0,
                  )
                }
                type="button"
              >
                Retry loading collections
              </button>
            </div>
          ) : null}
          {collectionsLoaded && collections.length === 0 && loadError === null ? (
            <div className="libraryEmptyState libraryEmptyState--compact">
              <p>Create your first collection before saving this paper.</p>
              <Link
                className="button button--secondary"
                href={"/library" as Route}
              >
                Open research library
              </Link>
            </div>
          ) : null}
          {collections.length > 0 ? (
            <>
              <div className="fieldGroup">
                <label htmlFor={`save-collection-${paperId}`}>Collection</label>
                <select
                  id={`save-collection-${paperId}`}
                  onChange={(event) => setCollectionId(event.target.value)}
                  value={collectionId}
                >
                  {collections.map((collection) => (
                    <option key={collection.collectionId} value={collection.collectionId}>
                      {collection.name}
                    </option>
                  ))}
                </select>
              </div>
              <div className="fieldGroup">
                <label htmlFor={`save-status-${paperId}`}>Reading status</label>
                <select
                  id={`save-status-${paperId}`}
                  onChange={(event) =>
                    setReadingStatus(event.target.value as ReadingStatus)
                  }
                  value={readingStatus}
                >
                  {readingStatuses.map((status) => (
                    <option key={status} value={status}>
                      {humanizeEnum(status)}
                    </option>
                  ))}
                </select>
              </div>
              <div className="fieldGroup saveTagsField">
                <label htmlFor={`save-tags-${paperId}`}>Tags (optional)</label>
                <input
                  id={`save-tags-${paperId}`}
                  onChange={(event) => setTagText(event.target.value)}
                  placeholder="methods, literature review"
                  value={tagText}
                />
                <small>Up to 10 comma-separated tags.</small>
              </div>
              <button
                className="button button--secondary"
                disabled={pending !== null}
                onClick={() => void save()}
                type="button"
              >
                {pending === "save" ? "Saving…" : "Save paper"}
              </button>
              {loadError === null && collectionPage + 1 < collectionTotalPages ? (
                <button
                  className="button button--ghost"
                  disabled={pending !== null}
                  onClick={() => void loadCollections(collectionPage + 1, true)}
                  type="button"
                >
                  {pending === "load" ? "Loading more…" : "Load more collections"}
                </button>
              ) : null}
            </>
          ) : null}
        </div>
      ) : null}
      <p aria-live="polite" className="libraryMessage" role="status">
        {message}
      </p>
    </section>
  );
}
