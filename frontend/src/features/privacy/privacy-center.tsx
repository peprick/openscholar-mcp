"use client";

import Link from "next/link";
import { useState } from "react";

import { loadOfflinePackRuntime } from "@/pwa/offline-pack-loader";
import { apiProblemSchema } from "@/shared/api/schemas";

const DELETE_CONFIRMATION = "DELETE_MY_DATA";
const EXPORT_FILENAME = "openscholar-personal-data.json";
const DELETE_UNCONFIRMED_MESSAGE =
  "OpenScholar could not confirm whether server deletion completed. The encrypted offline copy on this device was already removed. Refresh this page to check your workspace before trying again.";
const OFFLINE_PURGE_FAILED_MESSAGE =
  "OpenScholar could not remove this browser’s encrypted offline copy, so server deletion did not start. Close other OpenScholar tabs and try again.";

type Feedback = {
  kind: "error" | "status";
  message: string;
};

async function responseErrorMessage(
  response: Response,
  fallback: string,
): Promise<string> {
  try {
    const parsed = apiProblemSchema.safeParse(await response.json());
    return parsed.success ? parsed.data.detail : fallback;
  } catch {
    return fallback;
  }
}

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
  const [exportPending, setExportPending] = useState(false);
  const [exportFeedback, setExportFeedback] = useState<Feedback | null>(null);
  const [confirmation, setConfirmation] = useState("");
  const [deletePending, setDeletePending] = useState(false);
  const [deleteComplete, setDeleteComplete] = useState(false);
  const [deleteFeedback, setDeleteFeedback] = useState<Feedback | null>(null);

  async function downloadPersonalData(): Promise<void> {
    setExportPending(true);
    setExportFeedback({
      kind: "status",
      message: "Preparing your private JSON export…",
    });
    try {
      const response = await fetch("/api/privacy/export", {
        cache: "no-store",
        headers: { accept: "application/json" },
      });
      if (!response.ok) {
        setExportFeedback({
          kind: "error",
          message: await responseErrorMessage(
            response,
            "OpenScholar could not prepare your data export. Please try again.",
          ),
        });
        return;
      }

      const downloadUrl = URL.createObjectURL(await response.blob());
      const link = document.createElement("a");
      link.href = downloadUrl;
      link.download = EXPORT_FILENAME;
      link.hidden = true;
      document.body.append(link);
      link.click();
      link.remove();
      window.setTimeout(() => URL.revokeObjectURL(downloadUrl), 0);
      setExportFeedback({
        kind: "status",
        message: "Your OpenScholar data export was downloaded.",
      });
    } catch {
      setExportFeedback({
        kind: "error",
        message:
          "OpenScholar could not prepare your data export. Please try again.",
      });
    } finally {
      setExportPending(false);
    }
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
    try {
      if (typeof indexedDB !== "undefined") {
        try {
          const runtime = await loadOfflinePackRuntime();
          runtime.lock();
          await runtime.purge();
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
          <button
            className="button button--primary"
            disabled={exportPending}
            onClick={() => void downloadPersonalData()}
            type="button"
          >
            {exportPending ? "Preparing download…" : "Download my data"}
          </button>
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
