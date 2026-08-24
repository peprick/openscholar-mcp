"use client";

import { useEffect } from "react";

const SERVICE_WORKER_URL = "/sw.js";
const CACHE_PREFIX = "openscholar-shell-";

function belongsToOpenScholar(
  registration: globalThis.ServiceWorkerRegistration,
): boolean {
  return [registration.active, registration.waiting, registration.installing]
    .filter((worker): worker is ServiceWorker => worker !== null)
    .some((worker) => {
      const url = new URL(worker.scriptURL);
      return url.origin === window.location.origin && url.pathname === SERVICE_WORKER_URL;
    });
}

export async function clearDevelopmentServiceWorker(): Promise<void> {
  if (
    typeof window === "undefined" ||
    !("serviceWorker" in navigator) ||
    typeof navigator.serviceWorker.getRegistrations !== "function"
  ) {
    return;
  }

  const registrations = await navigator.serviceWorker.getRegistrations();
  await Promise.all(
    registrations
      .filter(belongsToOpenScholar)
      .map((registration) => registration.unregister()),
  );
  if (!("caches" in globalThis)) return;
  const names = await caches.keys();
  await Promise.all(
    names
      .filter((name) => name.startsWith(CACHE_PREFIX))
      .map((name) => caches.delete(name)),
  );
}

export function installServiceWorkerRegistration(
  environment: string | undefined = process.env.NODE_ENV,
): () => void {
  if (environment !== "production") {
    void clearDevelopmentServiceWorker().catch(() => {
      // Development cleanup is best-effort in restricted browser profiles.
    });
    return () => undefined;
  }
  if (
    typeof window === "undefined" ||
    !("serviceWorker" in navigator)
  ) {
    return () => undefined;
  }

  let cancelled = false;
  const register = (): void => {
    if (cancelled) return;
    void navigator.serviceWorker
      .register(SERVICE_WORKER_URL, {
        scope: "/",
        updateViaCache: "none",
      })
      .catch(() => {
        // Offline startup and privacy tooling can reject registration. The web
        // experience remains usable, so registration failure is non-fatal.
      });
  };

  if (document.readyState === "complete") {
    register();
  } else {
    window.addEventListener("load", register, { once: true });
  }

  return () => {
    cancelled = true;
    window.removeEventListener("load", register);
  };
}

type ServiceWorkerRegistrationProps = {
  enabled?: boolean;
};

export function ServiceWorkerRegistration({
  enabled = process.env.NODE_ENV === "production",
}: ServiceWorkerRegistrationProps): null {
  useEffect(
    () =>
      installServiceWorkerRegistration(enabled ? "production" : "development"),
    [enabled],
  );
  return null;
}
