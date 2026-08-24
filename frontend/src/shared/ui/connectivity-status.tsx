"use client";

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

  return (
    <div
      className={`connectivityRegion${connected ? "" : " connectivityRegion--offline"}`}
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
