"use client";

import Link from "next/link";
import { useState } from "react";

import { loadOfflinePackRuntime } from "@/pwa/offline-pack-loader";
import type {
  OfflinePackDeletionFence,
  OpenScholarOfflinePackRuntime,
} from "@/pwa/offline-pack-runtime";

const DELETE_CONFIRMATION = "DELETE_MY_DATA";
const DELETE_UNCONFIRMED_MESSAGE =
  "OpenScholar could not confirm whether server deletion completed. The encrypted offline copy on this device was already removed. Refresh this page to check your workspace before trying again.";
const OFFLINE_PURGE_FAILED_MESSAGE =
  "OpenScholar could not remove this browser’s encrypted offline copy, so server deletion did not start. Close other OpenScholar tabs and try again.";
const OFFLINE_COMPLETION_FAILED_MESSAGE =
  "Your OpenScholar data was deleted, but browser cleanup could not be completed. Refresh before saving another offline copy.";

type Feedback = {
  kind: "error" | "status";
  message: string;
};

function ActionFeedback({ feedback }: { feedback: Feedback | null }): React.JSX.Element | null {
  if (feedback === null) return null;
  return (
    <p
      aria-live={feedback.kind === "error" ? "assertive" : "polite"}
      className={`privacyFeedback privacyFeedback--${feedback.kind}`}
      role={feedback.kind === "error" ? "alert" : "status"}
    >
      {feedback.message}
    </p>
  );
}

export function PrivacyCenter(): React.JSX.Element {
  const [exportFeedback, setExportFeedback] = useState<Feedback | null>(null);
  const [confirmation, setConfirmation] = useState("");
  const [deletePending, setDeletePending] = useState(false);
  const [deleteComplete, setDeleteComplete] = useState(false);
  const [deleteFeedback, setDeleteFeedback] = useState<Feedback | null>(null);

  function announcePersonalDataDownload(): void {
    setExportFeedback({
      kind: "status",
      message:
        "Your OpenScholar data export download started. Your browser will show when it finishes or if it fails.",
    });
  }

  async function deletePersonalData(
    event: React.FormEvent<HTMLFormElement>,
  ): Promise<void> {
    event.preventDefault();
    if (confirmation !== DELETE_CONFIRMATION || deletePending) return;

    setDeletePending(true);
    setDeleteFeedback({
      kind: "status",
      message: "Removing this browser’s encrypted offline copy…",
    });
    let offlineDeletion: {
      fence: OfflinePackDeletionFence;
      runtime: OpenScholarOfflinePackRuntime;
    } | null = null;
    try {
      if (typeof indexedDB !== "undefined") {
        try {
          const runtime = await loadOfflinePackRuntime();
          const fence = await runtime.beginDeletion();
          offlineDeletion = { fence, runtime };
        } catch {
          setDeleteFeedback({
            kind: "error",
            message: OFFLINE_PURGE_FAILED_MESSAGE,
          });
          return;
        }
      }
      setDeleteFeedback({
        kind: "status",
        message: "Deleting your OpenScholar data…",
      });
      const response = await fetch("/api/privacy/account", {
        method: "DELETE",
        headers: { "content-type": "application/json" },
        body: JSON.stringify({ confirmation: DELETE_CONFIRMATION }),
      });
      if (!response.ok) {
        setDeleteFeedback({
          kind: "error",
          message: DELETE_UNCONFIRMED_MESSAGE,
        });
        return;
      }
      if (offlineDeletion !== null) {
        try {
          await offlineDeletion.runtime.completeDeletion(
            offlineDeletion.fence,
          );
          // The successful response can apply Clear-Site-Data before this
          // continuation runs. In that case the exact fence is already gone
          // and `false` is still a successful terminal state.
        } catch {
          // A confirmed server deletion must not clear or bypass an uncertain
          // local fence, which therefore remains fail-closed.
          setDeleteFeedback({
            kind: "error",
            message: OFFLINE_COMPLETION_FAILED_MESSAGE,
          });
          return;
        }
      }

      setConfirmation("");
      setDeleteComplete(true);
      setDeleteFeedback({
        kind: "status",
        message: "Your OpenScholar data was deleted.",
      });
    } catch {
      setDeleteFeedback({
        kind: "error",
        message: DELETE_UNCONFIRMED_MESSAGE,
      });
    } finally {
      setDeletePending(false);
    }
  }

  return (
    <div className="privacyActions">
      <section className="privacyCard" aria-labelledby="export-data-heading">
        <div className="privacyCardNumber" aria-hidden="true">
          01
        </div>
        <div className="privacyCardBody">
          <span className="eyebrow">Portable by design</span>
          <h2 id="export-data-heading">Download a copy</h2>
          <p>
            Get one JSON file containing your account display details, saved
            searches and filters, collections, and saved-paper memberships.
          </p>
          <ul className="privacyPlainList">
            <li>Research PDFs are not included because OpenScholar does not store them.</li>
            <li>The downloaded file may contain private research interests; keep it safe.</li>
          </ul>
          <a
            aria-describedby="export-download-help"
            className="button button--primary"
            href="/api/privacy/export"
            onClick={announcePersonalDataDownload}
            rel="noopener"
            target="_blank"
          >
            Download my data
          </a>
          <small id="export-download-help">
            Your browser handles this download directly. If OpenScholar cannot
            prepare it, the error opens separately so this page stays available.
          </small>
          <ActionFeedback feedback={exportFeedback} />
        </div>
      </section>

      <section
        className="privacyCard privacyCard--danger"
        aria-labelledby="delete-data-heading"
      >
        <div className="privacyCardNumber" aria-hidden="true">
          02
        </div>
        <div className="privacyCardBody">
          <span className="eyebrow">Permanent action</span>
          <h2 id="delete-data-heading">Delete my OpenScholar data</h2>
          <p>
            This removes your saved searches, collections, saved-paper links,
            reading statuses, and tags from this OpenScholar workspace. It also
            removes this browser’s encrypted offline collection before server
            deletion starts.
          </p>
          <div className="privacyBoundaryNote">
            <strong>What stays unchanged</strong>
            <p>
              Shared paper metadata and checked access records remain. If you use
              hosted sign-in, this also does not delete your account with that
              sign-in provider. OpenScholar never stored source PDFs.
            </p>
            <p>
              Remove offline copies separately on every other device or browser
              profile where you saved one.
            </p>
          </div>

          {deleteComplete ? (
            <div className="privacyComplete">
              <ActionFeedback feedback={deleteFeedback} />
              <p>You can start again with an empty research workspace.</p>
              <Link className="button button--secondary" href="/">
                Search research
              </Link>
            </div>
          ) : (
            <form className="privacyDeleteForm" onSubmit={(event) => void deletePersonalData(event)}>
              <div className="fieldGroup">
                <label htmlFor="delete-data-confirmation">
                  Type <code>{DELETE_CONFIRMATION}</code> to confirm
                </label>
                <input
                  aria-describedby="delete-data-help"
                  autoComplete="off"
                  id="delete-data-confirmation"
                  onChange={(event) => {
                    setConfirmation(event.target.value);
                    if (deleteFeedback?.kind === "error") {
                      setDeleteFeedback(null);
                    }
                  }}
                  spellCheck={false}
                  value={confirmation}
                />
                <small id="delete-data-help">
                  This phrase is case-sensitive. Deletion cannot be undone.
                </small>
              </div>
              <button
                className="button button--danger privacyDeleteButton"
                disabled={
                  confirmation !== DELETE_CONFIRMATION || deletePending
                }
                type="submit"
              >
                {deletePending ? "Deleting data…" : "Delete my OpenScholar data"}
              </button>
              <ActionFeedback feedback={deleteFeedback} />
            </form>
          )}
        </div>
      </section>
    </div>
  );
}
