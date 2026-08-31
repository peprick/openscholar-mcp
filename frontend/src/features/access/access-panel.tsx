"use client";

import type { Route } from "next";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import {
  isReadablePdfAccessStatus,
  selectPreferredReaderSource,
  selectReaderSource,
} from "@/features/reader/reader-source";
import {
  apiProblemSchema,
  paperAccessResponseSchema,
  type PaperAccessLocation,
  type PaperAccessResponse,
} from "@/shared/api/schemas";
import { humanizeEnum } from "@/shared/formatting/display";
import { Badge } from "@/shared/ui/badge";
import { ExternalLink } from "@/shared/ui/external-link";

function statusTone(
  status: PaperAccessResponse["status"],
): "neutral" | "positive" | "warning" | "info" {
  if (
    status === "OPEN_PDF" ||
    status === "OPEN_LANDING_PAGE" ||
    status === "REPOSITORY_COPY" ||
    status === "PREPRINT"
  ) {
    return "positive";
  }
  if (status === "RESTRICTED" || status === "UNAVAILABLE") {
    return "warning";
  }
  return "neutral";
}

function accessStatusLabel(status: PaperAccessResponse["status"]): string {
  switch (status) {
    case "OPEN_PDF":
      return "Free PDF";
    case "OPEN_LANDING_PAGE":
      return "Free full-text page";
    case "REPOSITORY_COPY":
      return "Repository copy";
    case "PREPRINT":
      return "Preprint available";
    case "ABSTRACT_ONLY":
      return "Abstract only";
    case "RESTRICTED":
      return "May require access";
    case "UNKNOWN":
      return "Not checked";
    case "UNAVAILABLE":
      return "No free version";
  }
}

function verificationLabel(
  status: PaperAccessLocation["verificationStatus"],
): string {
  if (status === "VERIFIED") return "Link checked";
  if (status === "FAILED") return "Link unavailable";
  return "Not checked";
}

function verifiedLocationHref(location: PaperAccessLocation): string | null {
  if (location.verificationStatus !== "VERIFIED") {
    return null;
  }
  return location.pdfUrl ?? location.landingPageUrl;
}

function AccessLocation({
  canonicalBest,
  location,
  readerHref,
}: {
  canonicalBest: boolean;
  location: PaperAccessLocation;
  readerHref: Route | null;
}): React.JSX.Element {
  const href = verifiedLocationHref(location);
  return (
    <article className={`accessLocation${canonicalBest ? " accessLocation--best" : ""}`}>
      <div className="accessLocationHeader">
        <div>
          <span className="eyebrow">
            {canonicalBest ? "Recommended source" : "Another source"}
          </span>
          <h3>{location.hostDomain ?? humanizeEnum(location.hostType)}</h3>
        </div>
        <Badge tone={location.verificationStatus === "VERIFIED" ? "positive" : "warning"}>
          {verificationLabel(location.verificationStatus)}
        </Badge>
      </div>
      <dl className="accessFacts">
        <div>
          <dt>Version</dt>
          <dd>{humanizeEnum(location.versionType)}</dd>
        </div>
        <div>
          <dt>Found through</dt>
          <dd>{location.source}</dd>
        </div>
        <div>
          <dt>License</dt>
          <dd>{location.license ?? "Not reported"}</dd>
        </div>
      </dl>
      {href !== null ? (
        <div className="buttonGroup accessLocationActions">
          {readerHref !== null ? (
            <Link className="button button--primary" href={readerHref}>
              Read this PDF
            </Link>
          ) : null}
          <ExternalLink
            className={
              readerHref === null
                ? "button button--primary"
                : "button button--ghost"
            }
            href={href}
          >
            {location.pdfUrl !== null
              ? "Open original PDF"
              : "Open full-text page"}
          </ExternalLink>
        </div>
      ) : (
        <p className="inlineNotice">
          This source does not currently have a link OpenScholar can safely open.
        </p>
      )}
      {location.verificationStatus === "VERIFIED" &&
      isReadablePdfAccessStatus(location.accessStatus) &&
      location.pdfUrl !== null &&
      readerHref === null ? (
        <p className="inlineNotice">
          Check access again to open this PDF in the reader.
        </p>
      ) : null}
      <p className="linkOnlyNote">
        Opens from the original source. OpenScholar does not store this document.
      </p>
    </article>
  );
}

export function AccessPanel({
  initialAccess,
  initialNow,
  paperId,
}: {
  initialAccess: PaperAccessResponse;
  initialNow: string;
  paperId: string;
}): React.JSX.Element {
  const [access, setAccess] = useState(initialAccess);
  const [readerSelectionTime, setReaderSelectionTime] = useState(
    () => new Date(initialNow),
  );
  const logicalClockRef = useRef({
    baselineTick: null as number | null,
    baselineTime: Date.parse(initialNow),
  });
  const [checking, setChecking] = useState(false);
  const [message, setMessage] = useState<string | null>(null);
  const hasResolved = access.cacheDisposition !== "NOT_YET_RESOLVED";

  function logicalNow(): number {
    const clock = logicalClockRef.current;
    if (clock.baselineTick === null) {
      clock.baselineTick = window.performance.now();
    }
    return (
      clock.baselineTime +
      Math.max(0, window.performance.now() - clock.baselineTick)
    );
  }

  async function verify(): Promise<void> {
    setChecking(true);
    setMessage(null);
    try {
      const response = await fetch(
        `/api/papers/${encodeURIComponent(paperId)}/access`,
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ forceRefresh: hasResolved }),
        },
      );
      const body: unknown = await response.json();
      if (!response.ok) {
        const problem = apiProblemSchema.safeParse(body);
        if (problem.success) {
          const retry = problem.data.retryAfterSeconds;
          setMessage(
            retry === undefined
              ? "Access could not be checked right now. Please try again."
              : `Access could not be checked right now. Try again in ${retry} seconds.`,
          );
        } else {
          setMessage("Access could not be checked right now. Please try again.");
        }
        return;
      }

      const parsed = paperAccessResponseSchema.safeParse(body);
      if (!parsed.success || parsed.data.paperId !== paperId) {
        setMessage("OpenScholar received an unexpected response. Please try again.");
        return;
      }
      setReaderSelectionTime(new Date(logicalNow()));
      setAccess(parsed.data);
      setMessage(
        parsed.data.locations.length > 0
          ? "Free full-text options are ready."
          : null,
      );
    } catch {
      setMessage("Access could not be checked right now. Please try again.");
    } finally {
      setChecking(false);
    }
  }

  useEffect(() => {
    const freshUntil = Date.parse(access.freshUntil ?? "");
    const authoritativeNow = Math.max(
      readerSelectionTime.getTime(),
      logicalNow(),
    );
    if (
      access.cacheDisposition === "STALE_FALLBACK" ||
      !Number.isFinite(freshUntil) ||
      freshUntil <= authoritativeNow
    ) {
      return;
    }
    const maximumTimerDelay = 2_147_483_647;
    const remaining = freshUntil - authoritativeNow + 1;
    const delay = Math.min(
      maximumTimerDelay,
      Math.max(0, remaining),
    );
    const nextLogicalTime =
      delay >= remaining ? freshUntil + 1 : authoritativeNow + delay;
    const timeout = window.setTimeout(
      () => setReaderSelectionTime(new Date(nextLogicalTime)),
      delay,
    );
    return () => window.clearTimeout(timeout);
  }, [access.cacheDisposition, access.freshUntil, readerSelectionTime]);

  const preferredReader = selectPreferredReaderSource(
    access,
    paperId,
    readerSelectionTime,
  );
  const preferredReaderHref =
    preferredReader === null
      ? null
      : (`/papers/${paperId}/read/${preferredReader.locationId}` as Route);

  return (
    <section className="accessPanel" aria-labelledby="access-heading">
      <div className="accessPanelHeader">
        <div>
          <span className="eyebrow">Free full-text access</span>
          <h2 id="access-heading">Full-text options</h2>
          <p>
            OpenScholar checks free full-text links from scholarly sources before
            showing them here.
          </p>
        </div>
        <Badge tone={statusTone(access.status)}>{accessStatusLabel(access.status)}</Badge>
      </div>

      <div className="accessActions">
        {preferredReaderHref !== null ? (
          <Link className="button button--primary" href={preferredReaderHref}>
            Read PDF
          </Link>
        ) : null}
        <button
          className={`button ${
            preferredReaderHref === null ? "button--primary" : "button--ghost"
          }`}
          disabled={checking}
          onClick={() => void verify()}
          type="button"
        >
          {checking
            ? "Checking…"
            : hasResolved
              ? "Check again"
              : "Check for free full text"}
        </button>
        <p aria-live="polite" className="accessMessage" role="status">
          {message}
        </p>
      </div>

      {access.locations.length > 0 ? (
        <div className="accessLocationList">
          {access.locations.map((location) => (
            <AccessLocation
              canonicalBest={location.id === access.bestLocationId}
              key={location.id}
              location={location}
              readerHref={
                selectReaderSource(
                  access,
                  paperId,
                  location.id,
                  readerSelectionTime,
                ) === null
                  ? null
                  : (`/papers/${paperId}/read/${location.id}` as Route)
              }
            />
          ))}
        </div>
      ) : (
        <div className="noAccessLocation">
          <strong>
            {!hasResolved
              ? "Full text has not been checked yet."
              : "No free full text found yet."}
          </strong>
          <p>
            {access.cacheDisposition === "NO_SUPPORTED_IDENTIFIER"
              ? "This paper does not have a DOI or arXiv ID that OpenScholar can check."
              : !hasResolved
                ? "Check trusted research sources for a free copy you can read."
                : "OpenScholar only shows links it can check. You can try again later."}
          </p>
        </div>
      )}
    </section>
  );
}
