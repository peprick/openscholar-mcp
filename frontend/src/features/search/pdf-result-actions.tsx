"use client";

import type { Route } from "next";
import { useRouter } from "next/navigation";
import { useEffect, useRef, useState } from "react";

import { selectPreferredReaderSource } from "@/features/reader/reader-source";
import {
  apiProblemSchema,
  paperAccessResponseSchema,
} from "@/shared/api/schemas";

type PdfIntent = "download" | "view";

export function PdfResultActions({
  paperId,
  title,
}: {
  paperId: string;
  title: string;
}): React.JSX.Element {
  const router = useRouter();
  const requestRef = useRef<AbortController | null>(null);
  const [pendingIntent, setPendingIntent] = useState<PdfIntent | null>(null);
  const [message, setMessage] = useState<string | null>(null);

  useEffect(
    () => () => {
      requestRef.current?.abort();
    },
    [],
  );

  async function openVerifiedPdf(intent: PdfIntent): Promise<void> {
    requestRef.current?.abort();
    const controller = new AbortController();
    requestRef.current = controller;
    setPendingIntent(intent);
    setMessage(null);
    try {
      const response = await fetch(
        `/api/papers/${encodeURIComponent(paperId)}/access`,
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ forceRefresh: false }),
          signal: controller.signal,
        },
      );
      const body: unknown = await response.json();
      if (controller.signal.aborted) {
        return;
      }
      if (!response.ok) {
        const problem = apiProblemSchema.safeParse(body);
        setMessage(
          problem.success
            ? problem.data.detail
            : "The PDF link could not be checked right now.",
        );
        return;
      }

      const parsed = paperAccessResponseSchema.safeParse(body);
      if (!parsed.success || parsed.data.paperId !== paperId) {
        setMessage("OpenScholar received an unexpected access response.");
        return;
      }

      const source = selectPreferredReaderSource(
        parsed.data,
        paperId,
        new Date(parsed.data.checkedAt ?? Date.now()),
      );
      if (source === null) {
        setMessage("No verified PDF is available yet. Review the access options.");
        router.push(`/papers/${paperId}#access-heading` as Route);
        return;
      }

      const suffix = intent === "download" ? "?download=1" : "";
      router.push(
        `/papers/${paperId}/read/${source.locationId}${suffix}` as Route,
      );
    } catch {
      if (!controller.signal.aborted) {
        setMessage("The PDF link could not be checked right now.");
      }
    } finally {
      if (requestRef.current === controller) {
        requestRef.current = null;
        if (!controller.signal.aborted) {
          setPendingIntent(null);
        }
      }
    }
  }

  return (
    <div
      aria-busy={pendingIntent !== null}
      className="pdfResultActionGroup"
    >
      <button
        aria-label={
          pendingIntent === "view"
            ? `Checking PDF before viewing: ${title}`
            : `View PDF: ${title}`
        }
        className="button button--primary"
        disabled={pendingIntent !== null}
        onClick={() => void openVerifiedPdf("view")}
        type="button"
      >
        {pendingIntent === "view" ? "Checking PDF…" : "View PDF"}
      </button>
      <button
        aria-label={
          pendingIntent === "download"
            ? `Checking PDF before downloading: ${title}`
            : `Download PDF: ${title}`
        }
        className="button button--secondary"
        disabled={pendingIntent !== null}
        onClick={() => void openVerifiedPdf("download")}
        type="button"
      >
        {pendingIntent === "download" ? "Checking PDF…" : "Download PDF"}
      </button>
      {pendingIntent !== null || message !== null ? (
        <p
          aria-live="polite"
          className={`pdfActionMessage${
            message !== null ? " pdfActionMessage--error" : ""
          }`}
          role="status"
        >
          {pendingIntent !== null
            ? "Checking for a verified PDF…"
            : message}
        </p>
      ) : null}
    </div>
  );
}
