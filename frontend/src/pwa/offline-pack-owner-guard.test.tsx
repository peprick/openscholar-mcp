import { act, cleanup, render, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";

import { loadOfflinePackRuntime } from "@/pwa/offline-pack-loader";
import { OfflinePackOwnerGuard } from "@/pwa/offline-pack-owner-guard";

vi.mock("@/pwa/offline-pack-loader", () => ({
  loadOfflinePackRuntime: vi.fn(),
}));

function response(body: unknown, ok = true): Response {
  return { json: vi.fn().mockResolvedValue(body), ok } as unknown as Response;
}

function deferred<T>(): {
  promise: Promise<T>;
  resolve: (value: T) => void;
} {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((settle) => {
    resolve = settle;
  });
  return { promise, resolve };
}

const runtime = {
  lock: vi.fn(),
  purge: vi.fn().mockResolvedValue(true),
  purgeMismatched: vi.fn().mockResolvedValue(false),
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
  vi.mocked(loadOfflinePackRuntime).mockReset();
  runtime.lock.mockReset();
  runtime.purge.mockReset().mockResolvedValue(true);
  runtime.purgeMismatched.mockReset().mockResolvedValue(false);
});

describe("offline-pack owner guard", () => {
  it("removes a pack only when its opaque scope differs", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        response({
          mode: "oidc",
          authenticated: true,
          storageScope: "oidc-v1.current-owner",
        }),
      ),
    );
    vi.mocked(loadOfflinePackRuntime).mockResolvedValue(runtime as never);

    render(<OfflinePackOwnerGuard />);

    await waitFor(() =>
      expect(runtime.purgeMismatched).toHaveBeenCalledWith(
        "oidc-v1.current-owner",
      ),
    );
    expect(runtime.purge).not.toHaveBeenCalled();
  });

  it("locks and purges when hosted authentication has no owner scope", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(
        response({ mode: "oidc", authenticated: false, storageScope: null }),
      ),
    );
    vi.mocked(loadOfflinePackRuntime).mockResolvedValue(runtime as never);

    render(<OfflinePackOwnerGuard />);

    await waitFor(() => expect(runtime.purge).toHaveBeenCalledOnce());
    expect(runtime.lock).toHaveBeenCalledOnce();
  });

  it.each([
    ["offline", () => Promise.reject(new TypeError("offline"))],
    ["non-success", () => Promise.resolve(response({}, false))],
    ["invalid contract", () => Promise.resolve(response({ mode: "oidc" }))],
  ])("retains the encrypted pack after an %s owner check", async (_label, result) => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(result));

    render(<OfflinePackOwnerGuard />);

    await waitFor(() => expect(fetch).toHaveBeenCalledOnce());
    expect(loadOfflinePackRuntime).not.toHaveBeenCalled();
  });

  it("checks again when a page is restored from the back-forward cache", async () => {
    const fetchMock = vi.fn().mockResolvedValue(
      response({
        mode: "local",
        authenticated: false,
        storageScope: "local-v1",
      }),
    );
    vi.stubGlobal("fetch", fetchMock);
    vi.mocked(loadOfflinePackRuntime).mockResolvedValue(runtime as never);
    render(<OfflinePackOwnerGuard />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());

    await act(async () => {
      const event = new Event("pageshow") as PageTransitionEvent;
      Object.defineProperty(event, "persisted", { value: true });
      window.dispatchEvent(event);
    });

    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
  });

  it("ignores an older owner response that settles after a newer check", async () => {
    const first = deferred<Response>();
    const fetchMock = vi
      .fn()
      .mockImplementationOnce(() => first.promise)
      .mockResolvedValueOnce(
        response({
          mode: "oidc",
          authenticated: true,
          storageScope: "oidc-v1.current-owner",
        }),
      );
    vi.stubGlobal("fetch", fetchMock);
    vi.mocked(loadOfflinePackRuntime).mockResolvedValue(runtime as never);
    render(<OfflinePackOwnerGuard />);
    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());

    await act(async () => {
      const event = new Event("pageshow") as PageTransitionEvent;
      Object.defineProperty(event, "persisted", { value: true });
      window.dispatchEvent(event);
    });
    await waitFor(() =>
      expect(runtime.purgeMismatched).toHaveBeenCalledWith(
        "oidc-v1.current-owner",
      ),
    );

    first.resolve(
      response({
        mode: "oidc",
        authenticated: true,
        storageScope: "oidc-v1.stale-owner",
      }),
    );
    await act(async () => {
      await first.promise;
      await Promise.resolve();
    });
    expect(runtime.purgeMismatched).toHaveBeenCalledOnce();
  });

  it("serializes owner-driven privacy mutations", async () => {
    const firstMutation = deferred<boolean>();
    runtime.purgeMismatched
      .mockImplementationOnce(() => firstMutation.promise)
      .mockResolvedValueOnce(false);
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        response({
          mode: "oidc",
          authenticated: true,
          storageScope: "oidc-v1.first-owner",
        }),
      )
      .mockResolvedValueOnce(
        response({
          mode: "oidc",
          authenticated: true,
          storageScope: "oidc-v1.second-owner",
        }),
      );
    vi.stubGlobal("fetch", fetchMock);
    vi.mocked(loadOfflinePackRuntime).mockResolvedValue(runtime as never);
    render(<OfflinePackOwnerGuard />);
    await waitFor(() =>
      expect(runtime.purgeMismatched).toHaveBeenCalledWith(
        "oidc-v1.first-owner",
      ),
    );

    await act(async () => {
      const event = new Event("pageshow") as PageTransitionEvent;
      Object.defineProperty(event, "persisted", { value: true });
      window.dispatchEvent(event);
    });
    await waitFor(() => expect(fetchMock).toHaveBeenCalledTimes(2));
    expect(runtime.purgeMismatched).toHaveBeenCalledOnce();

    firstMutation.resolve(false);
    await waitFor(() =>
      expect(runtime.purgeMismatched).toHaveBeenNthCalledWith(
        2,
        "oidc-v1.second-owner",
      ),
    );
  });
});
