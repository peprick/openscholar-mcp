"use client";

import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from "react";

import { useOnlineStatus } from "@/shared/connectivity/use-online-status";

export type ConnectivityState =
  | "CONNECTED"
  | "CHECKING_LOCAL_STACK"
  | "LOCAL_STACK_AVAILABLE"
  | "DISCONNECTED";

type ConnectivityValue = {
  canReachApplication: boolean;
  recoveredFromUnreachable: boolean;
  retryApplicationReachability: () => void;
  state: ConnectivityState;
};

const ConnectivityContext = createContext<ConnectivityValue>({
  canReachApplication: true,
  recoveredFromUnreachable: false,
  retryApplicationReachability: () => undefined,
  state: "CONNECTED",
});

export function ConnectivityProvider({
  children,
}: Readonly<{ children: React.ReactNode }>): React.JSX.Element {
  const { online: browserOnline, revision } = useOnlineStatus();
  const [probeAttempt, setProbeAttempt] = useState(0);
  const [offlineProbe, setOfflineProbe] = useState<{
    available: boolean;
    attempt: number;
    recovered: boolean;
    revision: number;
  } | null>(null);
  const confirmedUnavailable = useRef(false);
  const retryApplicationReachability = useCallback(() => {
    setProbeAttempt((attempt) => attempt + 1);
  }, []);

  useEffect(() => {
    if (browserOnline && !confirmedUnavailable.current) return;
    let active = true;
    const recoveryProbe = browserOnline && confirmedUnavailable.current;
    const controller = new AbortController();
    const timeout = window.setTimeout(() => controller.abort(), 3_000);

    void fetch("/api/connectivity", {
      cache: "no-store",
      credentials: "omit",
      headers: { accept: "application/json" },
      signal: controller.signal,
    })
      .then((response) => {
        if (!active) return;
        confirmedUnavailable.current = !response.ok;
        setOfflineProbe({
          available: response.ok,
          attempt: probeAttempt,
          recovered: response.ok && recoveryProbe,
          revision,
        });
      })
      .catch(() => {
        if (!active) return;
        confirmedUnavailable.current = true;
        setOfflineProbe({
          available: false,
          attempt: probeAttempt,
          recovered: false,
          revision,
        });
      })
      .finally(() => window.clearTimeout(timeout));

    return () => {
      active = false;
      controller.abort();
      window.clearTimeout(timeout);
    };
  }, [browserOnline, probeAttempt, revision]);

  const probeMatchesCurrentAttempt =
    offlineProbe?.revision === revision &&
    offlineProbe.attempt === probeAttempt;
  const applicationWasUnavailable = offlineProbe?.available === false;
  let state: ConnectivityState;
  if (browserOnline && !applicationWasUnavailable) state = "CONNECTED";
  else if (!probeMatchesCurrentAttempt) {
    state = "CHECKING_LOCAL_STACK";
  }
  else if (offlineProbe.available) {
    state = browserOnline ? "CONNECTED" : "LOCAL_STACK_AVAILABLE";
  }
  else state = "DISCONNECTED";

  const recoveredFromUnreachable =
    state === "CONNECTED" &&
    probeMatchesCurrentAttempt &&
    offlineProbe.recovered;
  const canReachApplication =
    state !== "DISCONNECTED" &&
    !(state === "CHECKING_LOCAL_STACK" && applicationWasUnavailable);

  return (
    <ConnectivityContext.Provider
      value={{
        canReachApplication,
        recoveredFromUnreachable,
        retryApplicationReachability,
        state,
      }}
    >
      {children}
    </ConnectivityContext.Provider>
  );
}

export function useConnectivity(): ConnectivityValue {
  return useContext(ConnectivityContext);
}
