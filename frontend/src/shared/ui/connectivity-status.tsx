"use client";

import { useEffect, useRef } from "react";

import { useConnectivity } from "@/shared/connectivity/connectivity-context";

type ConnectivityStatusProps = {
  id?: string;
};

export function ConnectivityStatus({
  id,
}: ConnectivityStatusProps): React.JSX.Element {
  const {
    recoveredFromUnreachable,
    retryApplicationReachability,
    state,
  } = useConnectivity();
  const connected = state === "CONNECTED";
  const regionRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const region = regionRef.current;
    if (region === null) return;

    function updateStickyOffset(): void {
      const height = connected
        ? 0
        : Math.ceil(region?.getBoundingClientRect().height ?? 0);
      document.documentElement.style.setProperty(
        "--connectivity-height",
        `${height}px`,
      );
    }

    updateStickyOffset();
    const observer =
      typeof ResizeObserver === "undefined"
        ? null
        : new ResizeObserver(updateStickyOffset);
    observer?.observe(region);
    window.addEventListener("resize", updateStickyOffset);
    return () => {
      observer?.disconnect();
      window.removeEventListener("resize", updateStickyOffset);
      document.documentElement.style.removeProperty("--connectivity-height");
    };
  }, [connected, state]);

  return (
    <div
      className={`connectivityRegion${connected ? "" : " connectivityRegion--offline"}`}
      ref={regionRef}
    >
      <div className={connected ? undefined : "connectivityStatus shell"}>
        {!connected ? (
          <span aria-hidden="true" className="connectivityStatusDot" />
        ) : null}
        <span
          aria-atomic="true"
          aria-live="polite"
          className={connected ? "srOnly" : "connectivityStatusMessage"}
          id={id}
          role="status"
        >
          {connected ? (
            recoveredFromUnreachable ? (
              "OpenScholar can be reached again."
            ) : null
          ) : state === "DISCONNECTED" ? (
            <>
              <strong>OpenScholar can&apos;t be reached.</strong> Already-opened pages
              may still be readable.
            </>
          ) : state === "LOCAL_STACK_AVAILABLE" ? (
            <>
              <strong>Limited connectivity reported.</strong> OpenScholar is still
              available, but online research sources may be limited.
            </>
          ) : (
            <>
              <strong>Connectivity may be limited.</strong> Checking whether
              OpenScholar is still available…
            </>
          )}
        </span>
        {state === "DISCONNECTED" ? (
          <button
            className="connectivityRetry"
            onClick={retryApplicationReachability}
            type="button"
          >
            Check again
          </button>
        ) : null}
      </div>
    </div>
  );
}
