import { afterEach, describe, expect, it, vi } from "vitest";

import {
  clearDevelopmentServiceWorker,
  installServiceWorkerRegistration,
} from "@/pwa/service-worker-registration";

const originalServiceWorker = Object.getOwnPropertyDescriptor(
  navigator,
  "serviceWorker",
);

function exposeServiceWorker(
  register: ReturnType<typeof vi.fn>,
  getRegistrations: ReturnType<typeof vi.fn> = vi.fn().mockResolvedValue([]),
): void {
  Object.defineProperty(navigator, "serviceWorker", {
    configurable: true,
    value: { getRegistrations, register },
  });
}

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
  if (originalServiceWorker === undefined) {
    Reflect.deleteProperty(navigator, "serviceWorker");
  } else {
    Object.defineProperty(navigator, "serviceWorker", originalServiceWorker);
  }
});

describe("service-worker registration", () => {
  it("registers the root-scoped worker without consulting the HTTP cache", async () => {
    const register = vi.fn().mockResolvedValue(undefined);
    exposeServiceWorker(register);
    vi.spyOn(document, "readyState", "get").mockReturnValue("complete");

    const cleanup = installServiceWorkerRegistration("production");

    expect(register).toHaveBeenCalledWith("/sw.js?reader=2026-08-24-r4", {
      scope: "/",
      updateViaCache: "none",
    });
    await Promise.resolve();
    cleanup();
  });

  it("does not install a persistent worker during local development or tests", () => {
    const register = vi.fn().mockResolvedValue(undefined);
    exposeServiceWorker(register);

    installServiceWorkerRegistration("development");
    installServiceWorkerRegistration("test");

    expect(register).not.toHaveBeenCalled();
  });

  it("removes an OpenScholar production worker and only its caches in development", async () => {
    const register = vi.fn();
    const unregister = vi.fn().mockResolvedValue(true);
    const unrelatedUnregister = vi.fn().mockResolvedValue(true);
    const ownWorker = {
      active: {
        scriptURL: new URL(
          "/sw.js?reader=2026-08-24-r4",
          window.location.origin,
        ).toString(),
      },
      installing: null,
      unregister,
      waiting: null,
    };
    const unrelatedWorker = {
      active: {
        scriptURL: new URL("/other-worker.js", window.location.origin).toString(),
      },
      installing: null,
      unregister: unrelatedUnregister,
      waiting: null,
    };
    exposeServiceWorker(
      register,
      vi.fn().mockResolvedValue([ownWorker, unrelatedWorker]),
    );
    const deleteCache = vi.fn().mockResolvedValue(true);
    vi.stubGlobal("caches", {
      delete: deleteCache,
      keys: vi
        .fn()
        .mockResolvedValue(["openscholar-shell-old", "unrelated-cache"]),
    });

    await clearDevelopmentServiceWorker();

    expect(unregister).toHaveBeenCalledOnce();
    expect(unrelatedUnregister).not.toHaveBeenCalled();
    expect(deleteCache).toHaveBeenCalledOnce();
    expect(deleteCache).toHaveBeenCalledWith("openscholar-shell-old");
  });

  it("cancels deferred registration when the component unmounts", () => {
    const register = vi.fn().mockResolvedValue(undefined);
    exposeServiceWorker(register);
    vi.spyOn(document, "readyState", "get").mockReturnValue("loading");

    const cleanup = installServiceWorkerRegistration("production");
    cleanup();
    window.dispatchEvent(new Event("load"));

    expect(register).not.toHaveBeenCalled();
  });

  it("treats browser registration rejection as a non-fatal enhancement failure", async () => {
    const register = vi.fn().mockRejectedValue(new Error("blocked"));
    exposeServiceWorker(register);
    vi.spyOn(document, "readyState", "get").mockReturnValue("complete");

    installServiceWorkerRegistration("production");

    await expect(Promise.resolve()).resolves.toBeUndefined();
    expect(register).toHaveBeenCalledOnce();
  });
});
