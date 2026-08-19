"use client";

import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";

import { responseErrorMessage } from "@/features/library/library-client";
import { formatTagInput, parseTagInput } from "@/features/library/tag-input";
import {
  collectionSummarySchema,
  readingStatuses,
  savedPaperMutationSchema,
  savedPaperSchema,
  type CollectionDetailsResponse,
  type ReadingStatus,
  type SavedPaper,
} from "@/shared/api/library-schemas";
import { humanizeEnum } from "@/shared/formatting/display";
import { Badge } from "@/shared/ui/badge";

function SavedPaperEditor({
  paper,
  onRemoved,
  onUpdated,
}: {
  paper: SavedPaper;
  onRemoved: (paperId: string) => void;
  onUpdated: (paper: SavedPaper) => void;
}): React.JSX.Element {
  const [status, setStatus] = useState<ReadingStatus>(paper.readingStatus);
  const [tagText, setTagText] = useState(() => formatTagInput(paper.tags));
  const [pending, setPending] = useState<"save" | "remove" | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const fieldSuffix = `${paper.collectionId}-${paper.paperId}`;
  const endpoint = `/api/collections/${encodeURIComponent(paper.collectionId)}/papers/${encodeURIComponent(paper.paperId)}`;

  async function save(): Promise<void> {
    const tags = parseTagInput(tagText);
    const request = tags.success
      ? savedPaperMutationSchema.safeParse({ readingStatus: status, tags: tags.data })
      : tags;
    if (!request.success) {
      setMessage(
        "Use at most 10 distinct comma-separated tags, each no longer than 40 characters.",
      );
      return;
    }
    setPending("save");
    setMessage(null);
    try {
      const response = await fetch(endpoint, {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(request.data),
      });
      if (!response.ok) {
        setMessage(
          await responseErrorMessage(response, "The saved paper could not be updated."),
        );
        return;
      }
      const parsed = savedPaperSchema.safeParse(await response.json());
      if (!parsed.success) {
        setMessage("The server returned an unexpected saved-paper response.");
        return;
      }
      setStatus(parsed.data.readingStatus);
      setTagText(formatTagInput(parsed.data.tags));
      onUpdated(parsed.data);
      setMessage("Reading status and tags saved.");
    } catch {
      setMessage("OpenScholar could not reach the library service.");
    } finally {
      setPending(null);
    }
  }

  async function remove(): Promise<void> {
    setPending("remove");
    setMessage(null);
    try {
      const response = await fetch(endpoint, { method: "DELETE" });
      if (!response.ok) {
        setMessage(
          await responseErrorMessage(response, "The paper could not be removed."),
        );
        return;
      }
      onRemoved(paper.paperId);
    } catch {
      setMessage("OpenScholar could not reach the library service.");
    } finally {
      setPending(null);
    }
  }

  return (
    <article className="collectionPaperCard">
      <div className="collectionPaperSummary">
        <div className="resultBadges">
          <Badge>{humanizeEnum(paper.documentType)}</Badge>
          <Badge tone="info">{humanizeEnum(paper.readingStatus)}</Badge>
        </div>
        <h2>
          <Link href={`/papers/${paper.paperId}` as Route}>{paper.title}</Link>
        </h2>
        <p>
          {paper.authors.length > 0
            ? paper.authors.join(", ")
            : "Authors unavailable"}
          {paper.publicationYear === null ? "" : ` · ${paper.publicationYear}`}
        </p>
      </div>
      <div className="collectionPaperControls">
        <div className="fieldGroup">
          <label htmlFor={`status-${fieldSuffix}`}>
            Reading status
            <span className="srOnly"> for {paper.title}</span>
          </label>
          <select
            id={`status-${fieldSuffix}`}
            onChange={(event) => setStatus(event.target.value as ReadingStatus)}
            value={status}
          >
            {readingStatuses.map((value) => (
              <option key={value} value={value}>
                {humanizeEnum(value)}
              </option>
            ))}
          </select>
        </div>
        <div className="fieldGroup collectionTagField">
          <label htmlFor={`tags-${fieldSuffix}`}>
            Tags<span className="srOnly"> for {paper.title}</span>
          </label>
          <input
            id={`tags-${fieldSuffix}`}
            onChange={(event) => setTagText(event.target.value)}
            placeholder="methods, key result"
            value={tagText}
          />
          <small>Up to 10 comma-separated tags.</small>
        </div>
        <div className="collectionPaperButtons">
          <button
            aria-label={`Save changes for ${paper.title}`}
            className="button button--secondary"
            disabled={pending !== null}
            onClick={() => void save()}
            type="button"
          >
            {pending === "save" ? "Saving…" : "Save changes"}
          </button>
          <button
            aria-label={`Remove ${paper.title} from ${paper.collectionName}`}
            className="button button--danger"
            disabled={pending !== null}
            onClick={() => void remove()}
            type="button"
          >
            {pending === "remove" ? "Removing…" : "Remove"}
          </button>
        </div>
        <p aria-live="polite" className="libraryMessage" role="status">
          {message}
        </p>
      </div>
    </article>
  );
}

export function CollectionManager({
  collection: initialCollection,
}: {
  collection: CollectionDetailsResponse;
}): React.JSX.Element {
  const router = useRouter();
  const [collection, setCollection] = useState(initialCollection);
  const [papers, setPapers] = useState(initialCollection.papers.items);
  const [name, setName] = useState(initialCollection.name);
  const [description, setDescription] = useState(
    initialCollection.description ?? "",
  );
  const [pending, setPending] = useState<"rename" | "delete" | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const endpoint = `/api/collections/${encodeURIComponent(collection.collectionId)}`;

  async function updateDetails(): Promise<void> {
    setPending("rename");
    setMessage(null);
    try {
      const response = await fetch(endpoint, {
        method: "PATCH",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          name,
          description: description.trim() === "" ? null : description,
        }),
      });
      if (!response.ok) {
        setMessage(
          await responseErrorMessage(response, "The collection could not be updated."),
        );
        return;
      }
      const parsed = collectionSummarySchema.safeParse(await response.json());
      if (!parsed.success) {
        setMessage("The server returned an unexpected collection response.");
        return;
      }
      setCollection((current) => ({ ...current, ...parsed.data }));
      setName(parsed.data.name);
      setDescription(parsed.data.description ?? "");
      setMessage("Collection details saved.");
    } catch {
      setMessage("OpenScholar could not reach the library service.");
    } finally {
      setPending(null);
    }
  }

  async function removeCollection(): Promise<void> {
    if (!window.confirm(`Delete “${collection.name}” and its saved-paper links?`)) {
      return;
    }
    setPending("delete");
    setMessage(null);
    try {
      const response = await fetch(endpoint, { method: "DELETE" });
      if (!response.ok) {
        setMessage(
          await responseErrorMessage(response, "The collection could not be deleted."),
        );
        return;
      }
      router.push("/library" as Route);
      router.refresh();
    } catch {
      setMessage("OpenScholar could not reach the library service.");
    } finally {
      setPending(null);
    }
  }

  function removePaper(paperId: string): void {
    if (papers.length === 1 && initialCollection.papers.page > 0) {
      router.push(
        `/library/collections/${collection.collectionId}?page=${initialCollection.papers.page - 1}` as Route,
      );
      return;
    }
    setPapers((current) => current.filter((paper) => paper.paperId !== paperId));
    setCollection((current) => ({
      ...current,
      paperCount: Math.max(0, current.paperCount - 1),
    }));
    router.refresh();
  }

  function updatePaper(updated: SavedPaper): void {
    setPapers((current) =>
      current.map((paper) =>
        paper.paperId === updated.paperId ? updated : paper,
      ),
    );
  }

  return (
    <>
      <header className="collectionHeader">
        <span className="eyebrow">Collection</span>
        <h1>{collection.name}</h1>
        <p>{collection.description ?? "A saved OpenScholar reading list."}</p>
        <Badge>{collection.paperCount} papers</Badge>
      </header>

      <section className="collectionSettings" aria-labelledby="settings-heading">
        <div>
          <span className="eyebrow">Collection settings</span>
          <h2 id="settings-heading">Name and description</h2>
        </div>
        <form action={updateDetails} className="collectionSettingsForm">
          <div className="fieldGroup">
            <label htmlFor="edit-collection-name">Name</label>
            <input
              id="edit-collection-name"
              maxLength={120}
              onChange={(event) => setName(event.target.value)}
              required
              value={name}
            />
          </div>
          <div className="fieldGroup">
            <label htmlFor="edit-collection-description">Description</label>
            <textarea
              id="edit-collection-description"
              maxLength={1_000}
              onChange={(event) => setDescription(event.target.value)}
              rows={3}
              value={description}
            />
          </div>
          <div className="collectionSettingsActions">
            <button
              className="button button--primary"
              disabled={pending !== null}
              type="submit"
            >
              {pending === "rename" ? "Saving…" : "Save details"}
            </button>
            <button
              className="button button--danger"
              disabled={pending !== null}
              onClick={() => void removeCollection()}
              type="button"
            >
              {pending === "delete" ? "Deleting…" : "Delete collection"}
            </button>
          </div>
          <p aria-live="polite" className="libraryMessage" role="status">
            {message}
          </p>
        </form>
      </section>

      <section className="collectionPapers" aria-labelledby="collection-papers-heading">
        <div className="librarySectionHeader">
          <div>
            <span className="eyebrow">Reading progress</span>
            <h2 id="collection-papers-heading">Saved papers</h2>
          </div>
        </div>
        {papers.length > 0 ? (
          <div className="collectionPaperList">
            {papers.map((paper) => (
              <SavedPaperEditor
                key={paper.paperId}
                onRemoved={removePaper}
                onUpdated={updatePaper}
                paper={paper}
              />
            ))}
          </div>
        ) : (
          <div className="libraryEmptyState">
            <h3>
              {collection.paperCount === 0
                ? "This collection is empty"
                : "No papers are shown on this page"}
            </h3>
            <p>
              {collection.paperCount === 0
                ? "Open a paper detail page and choose “Save to collection”."
                : "Use the collection pagination to continue browsing saved papers."}
            </p>
            <Link className="button button--secondary" href="/">
              Search research
            </Link>
          </div>
        )}
      </section>
    </>
  );
}
