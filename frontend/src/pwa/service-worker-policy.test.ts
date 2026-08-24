import { readFileSync } from "node:fs";
import { resolve } from "node:path";

import { describe, expect, it, vi } from "vitest";

type WorkerEvent = {
  request: {
    headers: Headers;
    method: string;
    mode: string;
    url: string;
  };
  respondWith: (response: Promise<unknown>) => void;
  waitUntil: (work: Promise<unknown>) => void;
};

type EventHandler = (event: WorkerEvent) => void;

function response(cacheControl = "public, max-age=31536000") {
  const value = {
    headers: new Headers({ "cache-control": cacheControl }),
    ok: true,
    type: "basic",
    clone: vi.fn(),
  };
  value.clone.mockReturnValue(value);
  return value;
}

function loadWorker(fetchMock = vi.fn()) {
  const handlers = new Map<string, EventHandler>();
  const cache = {
    delete: vi.fn().mockResolvedValue(true),
    keys: vi.fn().mockResolvedValue([]),
    match: vi.fn().mockResolvedValue(undefined),
    put: vi.fn().mockResolvedValue(undefined),
  };
  const cacheStorage = {
    delete: vi.fn().mockResolvedValue(true),
    keys: vi.fn().mockResolvedValue([]),
    match: vi.fn().mockResolvedValue(undefined),
    open: vi.fn().mockResolvedValue(cache),
  };
  const worker = {
    addEventListener: vi.fn((name: string, handler: EventHandler) => {
      handlers.set(name, handler);
    }),
    clients: { claim: vi.fn().mockResolvedValue(undefined) },
    location: { origin: "https://openscholar.test" },
    skipWaiting: vi.fn().mockResolvedValue(undefined),
  };
  class WorkerRequest {
    readonly headers = new Headers();
    readonly method = "GET";
    readonly mode = "cors";
    readonly url: string;

    constructor(input: URL | string) {
      this.url = input.toString();
    }
  }
  const workerResponse = { error: vi.fn(() => ({ ok: false })) };
  const source = readFileSync(resolve(process.cwd(), "public/sw.js"), "utf8");
  const evaluate = new Function(
    "self",
    "caches",
    "fetch",
    "Request",
    "Response",
    "URL",
    source,
  );
  evaluate(
    worker,
    cacheStorage,
    fetchMock,
    WorkerRequest,
    workerResponse,
    URL,
  );
  return { cache, cacheStorage, fetchMock, handlers, worker };
}

function request(url: string, mode = "cors"): WorkerEvent["request"] {
  return {
    headers: new Headers(),
    method: "GET",
    mode,
    url,
  };
}

async function dispatchFetch(
  handler: EventHandler,
  workerRequest: WorkerEvent["request"],
): Promise<{ handled: boolean; response?: unknown }> {
  let responsePromise: Promise<unknown> | undefined;
  const backgroundWork: Promise<unknown>[] = [];
  handler({
    request: workerRequest,
    respondWith: (responseValue) => {
      responsePromise = responseValue;
    },
    waitUntil: (work) => {
      backgroundWork.push(work);
    },
  });
  if (responsePromise === undefined) return { handled: false };
  const responseValue = await responsePromise;
  await Promise.all(backgroundWork);
  return { handled: true, response: responseValue };
}

describe("OpenScholar service-worker cache policy", () => {
  it.each([
    "https://openscholar.test/api/searches",
    "https://openscholar.test/api/auth/status",
    "https://openscholar.test/mcp/tools",
    "https://openscholar.test/oauth/callback",
    "https://openscholar.test/exports/library.bib",
    "https://openscholar.test/files/research-paper.pdf",
    "https://journals.example/research-paper.pdf",
  ])("never intercepts private, protocol, or research-document traffic: %s", async (url) => {
    const runtime = loadWorker();
    const result = await dispatchFetch(runtime.handlers.get("fetch")!, request(url));

    expect(result.handled).toBe(false);
    expect(runtime.fetchMock).not.toHaveBeenCalled();
    expect(runtime.cache.put).not.toHaveBeenCalled();
  });

  it("caches only same-origin immutable Next.js assets", async () => {
    const networkResponse = response();
    const fetchMock = vi.fn().mockResolvedValue(networkResponse);
    const runtime = loadWorker(fetchMock);
    const asset = request(
      "https://openscholar.test/_next/static/chunks/app-a1b2c3.js",
    );

    const result = await dispatchFetch(runtime.handlers.get("fetch")!, asset);

    expect(result).toMatchObject({ handled: true, response: networkResponse });
    expect(runtime.cache.put).toHaveBeenCalledWith(asset, networkResponse);

    const dynamicImage = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request("https://openscholar.test/_next/image?url=https%3A%2F%2Fpapers.example%2Fcover.png"),
    );
    expect(dynamicImage.handled).toBe(false);

    const queryVariant = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request("https://openscholar.test/icon.svg?owner=researcher"),
    );
    expect(queryVariant.handled).toBe(false);
  });

  it("refreshes same-URL install assets before using a cached copy", async () => {
    const cachedResponse = response("public, max-age=31536000");
    const freshResponse = response("public, max-age=60");
    const fetchMock = vi.fn().mockResolvedValue(freshResponse);
    const runtime = loadWorker(fetchMock);
    runtime.cache.match.mockResolvedValue(cachedResponse);
    const manifestRequest = request(
      "https://openscholar.test/manifest.webmanifest",
    );

    const result = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      manifestRequest,
    );

    expect(result).toEqual({ handled: true, response: freshResponse });
    expect(fetchMock).toHaveBeenCalledWith(manifestRequest);
    expect(runtime.cache.put).toHaveBeenCalledWith(
      manifestRequest,
      freshResponse,
    );
  });

  it("bounds obsolete build-hashed static assets", async () => {
    const runtime = loadWorker(vi.fn().mockResolvedValue(response()));
    const oldAssets = Array.from({ length: 98 }, (_, index) =>
      request(
        `https://openscholar.test/_next/static/chunks/old-${index}.js`,
      ),
    );
    runtime.cache.keys.mockResolvedValue(oldAssets);

    await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request("https://openscholar.test/_next/static/chunks/new.js"),
    );

    expect(runtime.cache.delete).toHaveBeenCalledTimes(2);
    expect(runtime.cache.delete).toHaveBeenNthCalledWith(1, oldAssets[0]);
    expect(runtime.cache.delete).toHaveBeenNthCalledWith(2, oldAssets[1]);
  });

  it("honours response no-store and private directives", async () => {
    const fetchMock = vi.fn().mockResolvedValue(response("private, no-store"));
    const runtime = loadWorker(fetchMock);

    await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request("https://openscholar.test/_next/static/chunks/app.js"),
    );

    expect(runtime.cache.put).not.toHaveBeenCalled();
  });

  it("does not store responses whose representation varies on every request", async () => {
    const unsafeResponse = response();
    unsafeResponse.headers.set("vary", "Accept-Encoding, *");
    const fetchMock = vi.fn().mockResolvedValue(unsafeResponse);
    const runtime = loadWorker(fetchMock);

    await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request("https://openscholar.test/_next/static/chunks/app.js"),
    );

    expect(runtime.cache.put).not.toHaveBeenCalled();
  });

  it("uses the offline shell for failed navigations without storing research pages", async () => {
    const offline = response();
    const runtime = loadWorker(vi.fn().mockRejectedValue(new TypeError("offline")));
    runtime.cache.match.mockResolvedValue(offline);

    const result = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request("https://openscholar.test/library", "navigate"),
    );

    expect(result).toEqual({ handled: true, response: offline });
    expect(runtime.cache.match).toHaveBeenCalledWith("/offline.html");
    expect(runtime.cache.put).not.toHaveBeenCalled();
  });

  it("keeps every successful navigation network-only", async () => {
    const networkResponse = response();
    const runtime = loadWorker(vi.fn().mockResolvedValue(networkResponse));

    const result = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request("https://openscholar.test/", "navigate"),
    );

    expect(result).toEqual({ handled: true, response: networkResponse });
    expect(runtime.cache.match).not.toHaveBeenCalled();
    expect(runtime.cache.put).toHaveBeenCalledOnce();
    expect(runtime.cache.put).toHaveBeenCalledWith(
      "/offline.html",
      networkResponse,
    );
    expect(runtime.fetchMock).toHaveBeenCalledTimes(2);
  });

  it("pre-caches only the owner-neutral offline shell and install assets", async () => {
    const runtime = loadWorker(vi.fn().mockResolvedValue(response()));
    let installWork: Promise<unknown> | undefined;

    runtime.handlers.get("install")!({
      request: request("https://openscholar.test/sw.js"),
      respondWith: vi.fn(),
      waitUntil: (work) => {
        installWork = work;
      },
    });
    await installWork;

    expect(runtime.cache.put.mock.calls.map(([key]) => key)).toEqual([
      "/offline.html",
      "/manifest.webmanifest",
      "/icon.svg",
      "/icon-192.png",
      "/icon-512.png",
      "/apple-touch-icon.png",
    ]);
    expect(runtime.cache.put).not.toHaveBeenCalledWith("/", expect.anything());
    expect(runtime.worker.skipWaiting).not.toHaveBeenCalled();
  });

  it("deletes only superseded OpenScholar shell caches during activation", async () => {
    const runtime = loadWorker();
    runtime.cacheStorage.keys.mockResolvedValue([
      "openscholar-shell-old",
      "openscholar-shell-2026-08-24-v3",
      "unrelated-cache",
    ]);
    let activationWork: Promise<unknown> | undefined;

    runtime.handlers.get("activate")!({
      request: request("https://openscholar.test/sw.js"),
      respondWith: vi.fn(),
      waitUntil: (work) => {
        activationWork = work;
      },
    });
    await activationWork;

    expect(runtime.cacheStorage.delete).toHaveBeenCalledOnce();
    expect(runtime.cacheStorage.delete).toHaveBeenCalledWith(
      "openscholar-shell-old",
    );
    expect(runtime.worker.clients.claim).toHaveBeenCalledOnce();
  });
});
