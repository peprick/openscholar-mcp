"use client";

import type { Route } from "next";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useState } from "react";

import {
  batchCitationExportRequestSchema,
  collectionSummarySchema,
  type CollectionListResponse,
  type CollectionSummary,
  type ReadingStatus,
  type SavedLibraryQuery,
  type SavedLibraryResponse,
} from "@/shared/api/library-schemas";
import { humanizeEnum } from "@/shared/formatting/display";
import { Badge } from "@/shared/ui/badge";
import {
  citationFilename,
  responseErrorMessage,
} from "@/features/library/library-client";

type LibraryDashboardProps = {
  collectionOptions: CollectionSummary[];
  collections: CollectionListResponse;
  papers: SavedLibraryResponse;
  query: SavedLibraryQuery;
};

const DOWNLOAD_CLEANUP_DELAY_MS = 1_500;

function pageHref(query: SavedLibraryQuery, page: number): Route {
  const params = new URLSearchParams();
  if (query.q !== undefined) params.set("q", query.q);
  if (query.collectionId !== undefined) {
    params.set("collectionId", query.collectionId);
  }
  if (query.readingStatus !== undefined) {
    params.set("readingStatus", query.readingStatus);
  }
  if (query.tag !== undefined) params.set("tag", query.tag);
  params.set("page", String(page));
  return `/library?${params}` as Route;
}

function collectionPageHref(
  query: SavedLibraryQuery,
  collectionsPage: number,
): Route {
  const params = new URLSearchParams();
  if (query.q !== undefined) params.set("q", query.q);
  if (query.collectionId !== undefined) {
    params.set("collectionId", query.collectionId);
  }
  if (query.readingStatus !== undefined) {
    params.set("readingStatus", query.readingStatus);
  }
  if (query.tag !== undefined) params.set("tag", query.tag);
  if (query.page > 0) params.set("page", String(query.page));
  params.set("collectionsPage", String(collectionsPage));
  return `/library?${params}` as Route;
}

export function LibraryDashboard({
  collectionOptions: initialCollectionOptions,
  collections: initialCollections,
  papers,
  query,
}: LibraryDashboardProps): React.JSX.Element {
  const router = useRouter();
  const [collectionPage, setCollectionPage] = useState(initialCollections);
  const collections = collectionPage.items;
  const [collectionOptions, setCollectionOptions] = useState(
    initialCollectionOptions,
  );
  const [creating, setCreating] = useState(false);
  const [createMessage, setCreateMessage] = useState<string | null>(null);
  const [selectedPaperIds, setSelectedPaperIds] = useState<Set<string>>(
    () => new Set(),
  );
  const [exporting, setExporting] = useState<"bibtex" | "csl-json" | null>(
    null,
  );
  const [exportMessage, setExportMessage] = useState<string | null>(null);

  async function createCollection(formData: FormData): Promise<void> {
    setCreating(true);
    setCreateMessage(null);
    const description = String(formData.get("description") ?? "").trim();
    try {
      const response = await fetch("/api/collections", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({
          name: String(formData.get("name") ?? ""),
          ...(description === "" ? {} : { description }),
        }),
      });
      if (!response.ok) {
        setCreateMessage(
          await responseErrorMessage(response, "The collection could not be created."),
        );
        return;
      }
      const parsed = collectionSummarySchema.safeParse(await response.json());
      if (!parsed.success) {
        setCreateMessage("The server returned an unexpected collection response.");
        return;
      }
      setCollectionOptions((current) => [
        parsed.data,
        ...current.filter(
          (collection) => collection.collectionId !== parsed.data.collectionId,
        ),
      ]);
      if (initialCollections.page === 0) {
        setCollectionPage((current) => {
          const totalElements = current.totalElements + 1;
          return {
            ...current,
            items: [parsed.data, ...current.items].slice(0, current.size),
            totalElements,
            totalPages: Math.ceil(totalElements / current.size),
          };
        });
      }
      setCreateMessage(`Created “${parsed.data.name}”.`);
      if (initialCollections.page > 0) {
        router.push(collectionPageHref(query, 0));
      }
    } catch {
      setCreateMessage("OpenScholar could not reach the library service.");
    } finally {
      setCreating(false);
    }
  }

  function togglePaper(paperId: string): void {
    setSelectedPaperIds((current) => {
      const next = new Set(current);
      if (next.has(paperId)) next.delete(paperId);
      else next.add(paperId);
      return next;
    });
  }

  async function exportSelected(format: "bibtex" | "csl-json"): Promise<void> {
    const request = batchCitationExportRequestSchema.safeParse({
      paperIds: [...selectedPaperIds],
      format,
    });
    if (!request.success) {
      setExportMessage("Select between 1 and 100 distinct papers to export.");
      return;
    }
    setExporting(format);
    setExportMessage(null);
    try {
      const response = await fetch("/api/citations/export", {
        method: "POST",
        headers: { "content-type": "application/json" },
        body: JSON.stringify(request.data),
      });
      if (!response.ok) {
        setExportMessage(
          await responseErrorMessage(response, "Citation export could not be created."),
        );
        return;
      }
      const objectUrl = URL.createObjectURL(await response.blob());
      const anchor = document.createElement("a");
      anchor.href = objectUrl;
      anchor.download = citationFilename(
        response.headers.get("content-disposition"),
        format,
      );
      anchor.hidden = true;
      document.body.append(anchor);
      try {
        anchor.click();
      } finally {
        window.setTimeout(() => {
          anchor.remove();
          URL.revokeObjectURL(objectUrl);
        }, DOWNLOAD_CLEANUP_DELAY_MS);
      }
      setExportMessage(`Exported ${selectedPaperIds.size} selected papers.`);
    } catch {
      setExportMessage("OpenScholar could not reach the citation service.");
    } finally {
      setExporting(null);
    }
  }

  return (
    <>
      <section className="libraryIntro" aria-labelledby="library-heading">
        <span className="eyebrow">Your saved research</span>
        <h1 id="library-heading">Research library</h1>
        <p>
          Organize canonical papers, track reading progress, and export a bounded
          citation set without downloading or retaining source PDFs.
        </p>
      </section>

      <div className="libraryGrid">
        <section className="libraryCollections" aria-labelledby="collections-heading">
          <div className="librarySectionHeader">
            <div>
              <span className="eyebrow">Reading lists</span>
              <h2 id="collections-heading">Collections</h2>
            </div>
            <Badge>
              {collections.length} of {collectionPage.totalElements} shown
            </Badge>
          </div>

          <form action={createCollection} className="collectionCreateForm">
            <div className="fieldGroup">
              <label htmlFor="collection-name">Collection name</label>
              <input
                id="collection-name"
                maxLength={120}
                name="name"
                placeholder="e.g. Thesis foundations"
                required
              />
            </div>
            <div className="fieldGroup collectionDescriptionField">
              <label htmlFor="collection-description">Description (optional)</label>
              <input
                id="collection-description"
                maxLength={1_000}
                name="description"
                placeholder="What belongs in this list?"
              />
            </div>
            <button className="button button--primary" disabled={creating} type="submit">
              {creating ? "Creating…" : "Create collection"}
            </button>
          </form>
          <p aria-live="polite" className="libraryMessage" role="status">
            {createMessage}
          </p>

          {collections.length > 0 ? (
            <div className="collectionCardGrid">
              {collections.map((collection) => (
                <article className="collectionCard" key={collection.collectionId}>
                  <div>
                    <Badge>{collection.paperCount} papers</Badge>
                    <h3>
                      <Link
                        href={
                          `/library/collections/${collection.collectionId}` as Route
                        }
                      >
                        {collection.name}
                      </Link>
                    </h3>
                    <p>{collection.description ?? "No description yet."}</p>
                  </div>
                  <Link
                    className="textLink"
                    href={`/library/collections/${collection.collectionId}` as Route}
                  >
                    Manage collection <span aria-hidden="true">→</span>
                  </Link>
                </article>
              ))}
            </div>
          ) : (
            <div className="libraryEmptyState">
              <h3>No collections yet</h3>
              <p>Create a reading list, then save a paper from its detail page.</p>
            </div>
          )}
          {collectionPage.totalPages > 1 ? (
            <nav aria-label="Collection pages" className="pagination">
              {collectionPage.page > 0 ? (
                <Link
                  href={collectionPageHref(query, collectionPage.page - 1)}
                >
                  Previous
                </Link>
              ) : (
                <span aria-disabled="true">Previous</span>
              )}
              <span>
                Page {collectionPage.page + 1} of {collectionPage.totalPages}
              </span>
              {collectionPage.page + 1 < collectionPage.totalPages ? (
                <Link
                  href={collectionPageHref(query, collectionPage.page + 1)}
                >
                  Next
                </Link>
              ) : (
                <span aria-disabled="true">Next</span>
              )}
            </nav>
          ) : null}
        </section>

        <section className="savedLibrary" aria-labelledby="saved-papers-heading">
          <div className="librarySectionHeader">
            <div>
              <span className="eyebrow">Across every collection</span>
              <h2 id="saved-papers-heading">Saved papers</h2>
            </div>
            <Badge>{papers.totalElements} memberships</Badge>
          </div>
          <form action="/library" className="libraryFilterForm" method="get">
            <div className="fieldGroup libraryQueryField">
              <label htmlFor="library-query">Title or author</label>
              <input
                defaultValue={query.q ?? ""}
                id="library-query"
                maxLength={200}
                name="q"
                placeholder="Search saved papers"
              />
            </div>
            <div className="fieldGroup">
              <label htmlFor="library-collection">Collection</label>
              <select
                defaultValue={query.collectionId ?? ""}
                id="library-collection"
                name="collectionId"
              >
                <option value="">All collections</option>
                {collectionOptions.map((collection) => (
                  <option key={collection.collectionId} value={collection.collectionId}>
                    {collection.name}
                  </option>
                ))}
              </select>
            </div>
            <div className="fieldGroup">
              <label htmlFor="library-status">Reading status</label>
              <select
                defaultValue={query.readingStatus ?? ""}
                id="library-status"
                name="readingStatus"
              >
                <option value="">Any status</option>
                {(["UNREAD", "READING", "COMPLETED"] as ReadingStatus[]).map(
                  (status) => (
                    <option key={status} value={status}>
                      {humanizeEnum(status)}
                    </option>
                  ),
                )}
              </select>
            </div>
            <div className="fieldGroup">
              <label htmlFor="library-tag">Tag</label>
              <input
                defaultValue={query.tag ?? ""}
                id="library-tag"
                maxLength={40}
                name="tag"
                placeholder="e.g. methods"
              />
            </div>
            <button className="button button--secondary" type="submit">
              Filter library
            </button>
            <Link className="button button--ghost" href={"/library" as Route}>
              Clear
            </Link>
          </form>

          {papers.items.length > 0 ? (
            <>
              <div className="batchExportBar">
                <p>
                  <strong>{selectedPaperIds.size}</strong> distinct papers selected
                </p>
                <div className="buttonGroup">
                  <button
                    className="button button--secondary"
                    disabled={exporting !== null || selectedPaperIds.size === 0}
                    onClick={() => void exportSelected("bibtex")}
                    type="button"
                  >
                    {exporting === "bibtex" ? "Exporting…" : "Export BibTeX"}
                  </button>
                  <button
                    className="button button--secondary"
                    disabled={exporting !== null || selectedPaperIds.size === 0}
                    onClick={() => void exportSelected("csl-json")}
                    type="button"
                  >
                    {exporting === "csl-json" ? "Exporting…" : "Export CSL-JSON"}
                  </button>
                </div>
              </div>
              <p aria-live="polite" className="libraryMessage" role="status">
                {exportMessage}
              </p>
              <div className="savedPaperList">
                {papers.items.map((paper) => (
                  <article
                    className="savedPaperCard"
                    key={`${paper.collectionId}:${paper.paperId}`}
                  >
                    <label className="paperSelectControl">
                      <input
                        checked={selectedPaperIds.has(paper.paperId)}
                        onChange={() => togglePaper(paper.paperId)}
                        type="checkbox"
                      />
                      <span className="srOnly">
                        Select {paper.title} from {paper.collectionName}
                      </span>
                    </label>
                    <div>
                      <div className="resultBadges">
                        <Badge tone="info">{humanizeEnum(paper.readingStatus)}</Badge>
                        <Badge>{paper.collectionName}</Badge>
                      </div>
                      <h3>
                        <Link href={`/papers/${paper.paperId}` as Route}>
                          {paper.title}
                        </Link>
                      </h3>
                      <p>
                        {paper.authors.length > 0
                          ? paper.authors.join(", ")
                          : "Authors unavailable"}
                        {paper.publicationYear === null
                          ? ""
                          : ` · ${paper.publicationYear}`}
                      </p>
                      {paper.tags.length > 0 ? (
                        <ul className="tagList" aria-label="Tags">
                          {paper.tags.map((tag) => (
                            <li key={tag}>{tag}</li>
                          ))}
                        </ul>
                      ) : null}
                    </div>
                  </article>
                ))}
              </div>
              {papers.totalPages > 1 ? (
                <nav aria-label="Saved library pages" className="pagination">
                  {papers.page > 0 ? (
                    <Link href={pageHref(query, papers.page - 1)}>Previous</Link>
                  ) : (
                    <span aria-disabled="true">Previous</span>
                  )}
                  <span>
                    Page {papers.page + 1} of {papers.totalPages}
                  </span>
                  {papers.page + 1 < papers.totalPages ? (
                    <Link href={pageHref(query, papers.page + 1)}>Next</Link>
                  ) : (
                    <span aria-disabled="true">Next</span>
                  )}
                </nav>
              ) : null}
            </>
          ) : (
            <div className="libraryEmptyState">
              <h3>No saved papers match</h3>
              <p>Adjust the filters or save a canonical paper to a collection.</p>
            </div>
          )}
        </section>
      </div>
    </>
  );
}
