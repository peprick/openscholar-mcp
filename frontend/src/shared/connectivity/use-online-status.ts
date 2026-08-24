"use client";

import { useMemo, useSyncExternalStore } from "react";

type OnlineStatus = {
  online: boolean;
  revision: number;
};

let revision = 0;
const listeners = new Set<() => void>();

function notifyConnectivityChange(): void {
  revision += 1;
  listeners.forEach((listener) => listener());
}

function subscribe(listener: () => void): () => void {
  if (listeners.size === 0) {
    window.addEventListener("online", notifyConnectivityChange);
    window.addEventListener("offline", notifyConnectivityChange);
  }
  listeners.add(listener);

  return () => {
    listeners.delete(listener);
    if (listeners.size === 0) {
      window.removeEventListener("online", notifyConnectivityChange);
      window.removeEventListener("offline", notifyConnectivityChange);
    }
  };
}

function getBrowserSnapshot(): string {
  return `${navigator.onLine ? "online" : "offline"}:${revision}`;
}

function getServerSnapshot(): string {
  // Render the unobtrusive connected state on the server, then reconcile with
  // the browser after hydration. navigator.onLine is a UX hint, not a backend
  // or research-provider health check.
  return "online:0";
}

export function useOnlineStatus(): OnlineStatus {
  const snapshot = useSyncExternalStore(
    subscribe,
    getBrowserSnapshot,
    getServerSnapshot,
  );
  return useMemo(() => {
    const [state, value] = snapshot.split(":");
    return { online: state === "online", revision: Number(value) };
  }, [snapshot]);
}
