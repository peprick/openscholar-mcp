import type { OpenScholarOfflinePackRuntime } from "@/pwa/offline-pack-runtime";

const RUNTIME_SOURCE = "/offline-pack.js";
const RUNTIME_MARKER = "script[data-openscholar-offline-pack]";
export const EXPECTED_OFFLINE_READER_REVISION = "2026-08-24-r3";

let pendingRuntime: Promise<OpenScholarOfflinePackRuntime> | null = null;

function installedRuntime(): OpenScholarOfflinePackRuntime | null {
  const runtime = globalThis.OpenScholarOfflinePack;
  return runtime?.constants.readerRevision === EXPECTED_OFFLINE_READER_REVISION
    ? runtime
    : null;
}

export function loadOfflinePackRuntime(): Promise<OpenScholarOfflinePackRuntime> {
  const installed = installedRuntime();
  if (installed !== null) return Promise.resolve(installed);
  if (pendingRuntime !== null) return pendingRuntime;
  if (typeof document === "undefined") {
    return Promise.reject(
      new Error("The offline-pack runtime can only be loaded in a browser."),
    );
  }

  pendingRuntime = new Promise((resolve, reject) => {
    const existing = document.querySelector<HTMLScriptElement>(RUNTIME_MARKER);
    const script = existing ?? document.createElement("script");

    function loaded(): void {
      const runtime = installedRuntime();
      if (runtime === null) {
        pendingRuntime = null;
        if (script.parentNode !== null) script.remove();
        reject(new Error("The encrypted offline runtime did not initialize."));
        return;
      }
      script.dataset.openscholarOfflinePack = "ready";
      resolve(runtime);
    }

    function failed(): void {
      pendingRuntime = null;
      if (script.parentNode !== null) script.remove();
      reject(new Error("The encrypted offline runtime could not be loaded."));
    }

    script.addEventListener("load", loaded, { once: true });
    script.addEventListener("error", failed, { once: true });
    if (existing === null) {
      script.async = true;
      script.dataset.openscholarOfflinePack = "loading";
      script.src = RUNTIME_SOURCE;
      document.head.append(script);
    }
  });

  return pendingRuntime;
}
