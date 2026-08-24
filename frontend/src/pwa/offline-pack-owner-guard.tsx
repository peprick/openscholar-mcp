"use client";

import { useEffect } from "react";
import { z } from "zod";

import { loadOfflinePackRuntime } from "@/pwa/offline-pack-loader";

const OWNER_CHECK_TIMEOUT_MS = 5_000;
const authStatusSchema = z
  .object({
    mode: z.enum(["local", "oidc"]),
    authenticated: z.boolean(),
    storageScope: z.string().min(1).max(128).nullable(),
  })
  .strict();

type OfflineOwnerStatus = z.infer<typeof authStatusSchema>;

async function fetchOfflineOwnerStatus(
  signal: AbortSignal,
): Promise<OfflineOwnerStatus | null> {
  let response: Response;
  try {
    response = await fetch("/api/auth/status", {
      cache: "no-store",
      credentials: "same-origin",
      headers: { accept: "application/json" },
      signal,
    });
  } catch {
    // A disconnected browser must retain the encrypted pack for offline use.
    return null;
  }
  if (!response.ok) return null;

  try {
    const parsed = authStatusSchema.safeParse(await response.json());
    return parsed.success ? parsed.data : null;
  } catch {
    return null;
  }
}

async function applyOfflineOwnerStatus(
  status: OfflineOwnerStatus,
  isCurrent: () => boolean,
): Promise<void> {
  if (!isCurrent()) return;
  try {
    const runtime = await loadOfflinePackRuntime();
    if (!isCurrent()) return;
    if (status.storageScope !== null) {
      await runtime.purgeMismatched(status.storageScope);
    } else if (status.mode === "oidc") {
      runtime.lock();
      await runtime.purge();
    }
  } catch {
    // Unsupported or restricted browser storage must not break the web app.
  }
}

export function OfflinePackOwnerGuard(): null {
  useEffect(() => {
    let disposed = false;
    let generation = 0;
    let activeController: AbortController | null = null;
    let sideEffects = Promise.resolve();

    const reconcile = (): void => {
      if (disposed) return;
      const requestGeneration = ++generation;
      activeController?.abort();
      const controller = new AbortController();
      activeController = controller;
      const timeout = window.setTimeout(
        () => controller.abort(),
        OWNER_CHECK_TIMEOUT_MS,
      );
      const isCurrent = (): boolean =>
        !disposed &&
        requestGeneration === generation &&
        !controller.signal.aborted;
      void fetchOfflineOwnerStatus(controller.signal)
        .then((status) => {
          if (status === null || !isCurrent()) return;
          // Privacy mutations are serialized, and the generation is checked
          // again after loading the runtime, so an out-of-order owner response
          // cannot run after a newer reconciliation has taken ownership.
          sideEffects = sideEffects
            .catch(() => undefined)
            .then(() => applyOfflineOwnerStatus(status, isCurrent));
          return sideEffects;
        })
        .finally(() => {
          window.clearTimeout(timeout);
          if (activeController === controller) activeController = null;
        });
    };

    reconcile();
    const onPageShow = (event: PageTransitionEvent): void => {
      if (event.persisted) reconcile();
    };
    window.addEventListener("pageshow", onPageShow);
    return () => {
      disposed = true;
      generation += 1;
      activeController?.abort();
      window.removeEventListener("pageshow", onPageShow);
    };
  }, []);

  return null;
}
