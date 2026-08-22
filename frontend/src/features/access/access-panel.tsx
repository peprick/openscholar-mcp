"use client";

import type { Route } from "next";
import Link from "next/link";
import { useEffect, useRef, useState } from "react";

import {
  isReadablePdfAccessStatus,
  selectReaderSource,
} from "@/features/reader/reader-source";
import {
  apiProblemSchema,
  paperAccessResponseSchema,
  type PaperAccessLocation,
  type PaperAccessResponse,
} from "@/shared/api/schemas";
import {
  formatInstant,
  humanizeEnum,
} from "@/shared/formatting/display";
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
            {canonicalBest ? "Best verified location" : "Alternative location"}
          </span>
          <h3>{location.hostDomain ?? humanizeEnum(location.hostType)}</h3>
        </div>
        <Badge tone={location.verificationStatus === "VERIFIED" ? "positive" : "warning"}>
          {humanizeEnum(location.verificationStatus)}
        </Badge>
      </div>
      <dl className="accessFacts">
        <div>
          <dt>Version</dt>
          <dd>{humanizeEnum(location.versionType)}</dd>
        </div>
        <div>
          <dt>Source</dt>
          <dd>{location.source}</dd>
        </div>
        <div>
          <dt>License</dt>
          <dd>{location.license ?? "Not reported"}</dd>
        </div>
        <div>
          <dt>Verified</dt>
          <dd>{formatInstant(location.verifiedAt)}</dd>
        </div>
      </dl>
      {href !== null ? (
        <div className="buttonGroup accessLocationActions">
          {readerHref !== null ? (
            <Link className="button button--primary" href={readerHref}>
              Read in OpenScholar
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
              ? "Open verified PDF externally"
              : "Open verified repository page"}
          </ExternalLink>
        </div>
      ) : (
        <p className="inlineNotice">
          No independently verified external link is available for this record.
        </p>
      )}
      {location.verificationStatus === "VERIFIED" &&
      isReadablePdfAccessStatus(location.accessStatus) &&
      location.pdfUrl !== null &&
      readerHref === null ? (
        <p className="inlineNotice">
          In-app reading requires a fresh, verified HTTPS PDF source.
        </p>
      ) : null}
      <p className="linkOnlyNote">
        Direct source access · OpenScholar does not proxy or retain this document.
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
  const [pendingAction, setPendingAction] = useState<"check" | "refresh" | null>(
    null,
  );
  const [message, setMessage] = useState<string | null>(null);

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

  async function verify(forceRefresh: boolean): Promise<void> {
    setPendingAction(forceRefresh ? "refresh" : "check");
    setMessage(null);
    try {
      const response = await fetch(
        `/api/papers/${encodeURIComponent(paperId)}/access`,
        {
          method: "POST",
          headers: { "content-type": "application/json" },
          body: JSON.stringify({ forceRefresh }),
        },
      );
      const body: unknown = await response.json();
      if (!response.ok) {
        const problem = apiProblemSchema.safeParse(body);
        if (problem.success) {
          const retry = problem.data.retryAfterSeconds;
          setMessage(
            retry === undefined
              ? problem.data.detail
              : `${problem.data.detail} Try again in ${retry} seconds.`,
          );
        } else {
          setMessage("Access providers could not complete this request.");
        }
        return;
      }

      const parsed = paperAccessResponseSchema.safeParse(body);
      if (!parsed.success || parsed.data.paperId !== paperId) {
        setMessage("The backend returned an unexpected access response.");
        return;
      }
      setReaderSelectionTime(new Date(logicalNow()));
      setAccess(parsed.data);
      setMessage(
        parsed.data.locations.length > 0
          ? "Legal-access information is ready."
          : "The check completed, but no verified open location was found.",
      );
    } catch {
      setMessage("OpenScholar could not reach the access service. Please retry.");
    } finally {
      setPendingAction(null);
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

  const hasResolved = access.cacheDisposition !== "NOT_YET_RESOLVED";

  return (
    <section className="accessPanel" aria-labelledby="access-heading">
      <div className="accessPanelHeader">
        <div>
          <span className="eyebrow">Independent access check</span>
          <h2 id="access-heading">Legal versions</h2>
          <p>
            Links here come only from backend verification, never from an
            unverified search-result PDF field.
          </p>
        </div>
        <Badge tone={statusTone(access.status)}>{humanizeEnum(access.status)}</Badge>
      </div>

      <div className="accessStateRow">
        <div>
          <span>Cache state</span>
          <strong>{humanizeEnum(access.cacheDisposition)}</strong>
        </div>
        <div>
          <span>Checked</span>
          <strong>{formatInstant(access.checkedAt)}</strong>
        </div>
        <div>
          <span>Locations</span>
          <strong>{access.locations.length}</strong>
        </div>
      </div>

      {access.warnings.length > 0 ? (
        <div className="warningPanel warningPanel--compact">
          <strong>Access warnings</strong>
          <ul>
            {access.warnings.map((warning) => (
              <li key={warning}>{warning}</li>
            ))}
          </ul>
        </div>
      ) : null}

      <div className="accessActions">
        <button
          className="button button--primary"
          disabled={pendingAction !== null}
          onClick={() => void verify(false)}
          type="button"
        >
          {pendingAction === "check"
            ? "Checking…"
            : hasResolved
              ? "Check cached access"
              : "Check legal access"}
        </button>
        {hasResolved ? (
          <button
            className="button button--ghost"
            disabled={pendingAction !== null}
            onClick={() => void verify(true)}
            type="button"
          >
            {pendingAction === "refresh" ? "Refreshing…" : "Refresh providers"}
          </button>
        ) : null}
        <p aria-live="polite" className="accessMessage" role="status">
          {message}
        </p>
      </div>

      {access.providerCoverage.length > 0 ? (
        <details className="providerDetails">
          <summary>Access provider coverage</summary>
          <ul>
            {access.providerCoverage.map((provider) => (
              <li key={provider.provider}>
                <strong>{provider.provider}</strong>
                <span>{humanizeEnum(provider.status)}</span>
                <span>{provider.candidateCount} candidates</span>
              </li>
            ))}
          </ul>
        </details>
      ) : null}

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
          <strong>No verified location stored yet.</strong>
          <p>
            Run an access check to query the providers supported by this paper’s
            DOI or arXiv identifier.
          </p>
        </div>
      )}
    </section>
  );
}
