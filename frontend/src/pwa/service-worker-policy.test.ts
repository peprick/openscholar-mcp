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

function response(
  cacheControl = "public, max-age=31536000",
  ok = true,
  options: {
    body?: string;
    contentType?: string;
    redirected?: boolean;
    url?: string;
  } = {},
) {
  const value = {
    headers: new Headers({
      "cache-control": cacheControl,
      "content-type": options.contentType ?? "application/octet-stream",
    }),
    ok,
    redirected: options.redirected ?? false,
    type: "basic",
    url: options.url ?? "https://openscholar.test/asset",
    clone: vi.fn(),
    text: vi.fn().mockResolvedValue(options.body ?? ""),
  };
  value.clone.mockReturnValue(value);
  return value;
}

function readerAssetResponse(
  input: { url: string },
  revision = "2026-08-24-r4",
) {
  const url = new URL(input.url);
  const shell = url.pathname === "/offline.html";
  return response("public, max-age=31536000", true, {
    body: shell
      ? `<html data-offline-reader-revision="${revision}"></html>`
      : `const READER_REVISION = "${revision}";`,
    contentType: shell ? "text/html; charset=utf-8" : "text/javascript",
    url: url.toString(),
  });
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
    location: {
      href: "https://openscholar.test/sw.js?reader=2026-08-24-r4",
      origin: "https://openscholar.test",
    },
    registration: { active: null as { scriptURL: string } | null },
    skipWaiting: vi.fn().mockResolvedValue(undefined),
  };
  class WorkerRequest {
    readonly cache: string;
    readonly credentials: string;
    readonly headers: Headers;
    readonly method: string;
    readonly mode: string;
    readonly url: string;

    constructor(
      input: URL | string | WorkerEvent["request"],
      init: { cache?: string; credentials?: string } = {},
    ) {
      const source =
        typeof input === "string" || input instanceof URL ? undefined : input;
      this.url = source === undefined ? input.toString() : source.url;
      this.headers = new Headers(source?.headers);
      this.method = source?.method ?? "GET";
      this.mode = source?.mode ?? "cors";
      this.cache = init.cache ?? "default";
      this.credentials = init.credentials ?? "same-origin";
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
    const assetUrl =
      "https://openscholar.test/_next/static/chunks/app-a1b2c3.js";
    const networkResponse = response("public, max-age=31536000", true, {
      url: assetUrl,
    });
    const fetchMock = vi.fn().mockResolvedValue(networkResponse);
    const runtime = loadWorker(fetchMock);
    const asset = request(assetUrl);

    const result = await dispatchFetch(runtime.handlers.get("fetch")!, asset);

    expect(result).toMatchObject({ handled: true, response: networkResponse });
    expect(runtime.cache.put).toHaveBeenCalledWith(asset, networkResponse);
    expect(fetchMock.mock.calls[0]?.[0]).toMatchObject({
      credentials: "omit",
      url: assetUrl,
    });

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
    const manifestUrl = "https://openscholar.test/manifest.webmanifest";
    const cachedResponse = response("public, max-age=31536000");
    const freshResponse = response("public, max-age=60", true, {
      url: manifestUrl,
    });
    const fetchMock = vi.fn().mockResolvedValue(freshResponse);
    const runtime = loadWorker(fetchMock);
    runtime.cache.match.mockResolvedValue(cachedResponse);
    const manifestRequest = request(manifestUrl);

    const result = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      manifestRequest,
    );

    expect(result).toEqual({ handled: true, response: freshResponse });
    expect(fetchMock.mock.calls[0]?.[0]).toMatchObject({
      credentials: "omit",
      url: manifestUrl,
    });
    expect(runtime.cache.put).toHaveBeenCalledWith(
      manifestRequest,
      freshResponse,
    );
  });

  it.each([
    ["server error", "public, max-age=60", false],
    ["private response", "private, max-age=60", true],
    ["no-store response", "no-store", true],
  ])(
    "keeps a cached install asset after a resolved %s",
    async (_label, cacheControl, ok) => {
      const manifestUrl = "https://openscholar.test/manifest.webmanifest";
      const cachedResponse = response();
      const unsafeResponse = response(cacheControl, ok, { url: manifestUrl });
      const runtime = loadWorker(vi.fn().mockResolvedValue(unsafeResponse));
      runtime.cache.match.mockResolvedValue(cachedResponse);
      const manifestRequest = request(manifestUrl);

      const result = await dispatchFetch(
        runtime.handlers.get("fetch")!,
        manifestRequest,
      );

      expect(result).toEqual({ handled: true, response: cachedResponse });
      expect(runtime.cache.put).not.toHaveBeenCalled();
    },
  );

  it.each([
    ["redirected", { redirected: true, vary: "" }],
    ["cookie-varying", { redirected: false, vary: "Cookie" }],
  ])(
    "keeps the cached install asset after a %s public response",
    async (_label, policy) => {
      const manifestUrl = "https://openscholar.test/manifest.webmanifest";
      const cachedResponse = response();
      const unsafeResponse = response("public, max-age=60", true, {
        redirected: policy.redirected,
        url: policy.redirected
          ? "https://openscholar.test/account/manifest.webmanifest"
          : manifestUrl,
      });
      if (policy.vary !== "") unsafeResponse.headers.set("vary", policy.vary);
      const runtime = loadWorker(vi.fn().mockResolvedValue(unsafeResponse));
      runtime.cache.match.mockResolvedValue(cachedResponse);

      const result = await dispatchFetch(
        runtime.handlers.get("fetch")!,
        request(manifestUrl),
      );

      expect(result).toEqual({ handled: true, response: cachedResponse });
      expect(runtime.cache.put).not.toHaveBeenCalled();
    },
  );

  it("does not treat query variants as a required cached-reader asset", async () => {
    const networkResponse = response();
    const runtime = loadWorker(vi.fn().mockResolvedValue(networkResponse));

    const script = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request("https://openscholar.test/offline-pack.js?v=owner"),
    );
    expect(script.handled).toBe(false);

    const shell = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request("https://openscholar.test/offline.html?owner=one", "navigate"),
    );
    expect(shell).toEqual({ handled: true, response: networkResponse });
    expect(runtime.fetchMock).toHaveBeenCalledOnce();
    expect(runtime.cache.match).not.toHaveBeenCalled();
  });

  it("maps only its exact revision-qualified runtime URL to the coherent cache", async () => {
    const cachedResponse = response();
    const runtime = loadWorker(vi.fn());
    runtime.cache.match.mockResolvedValue(cachedResponse);

    const current = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request(
        "https://openscholar.test/offline-pack.js?reader=2026-08-24-r4",
      ),
    );
    const future = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request(
        "https://openscholar.test/offline-pack.js?reader=2026-08-24-r5",
      ),
    );

    expect(current).toEqual({ handled: true, response: cachedResponse });
    expect(runtime.cache.match).toHaveBeenCalledWith("/offline-pack.js");
    expect(future.handled).toBe(false);
    expect(runtime.fetchMock).not.toHaveBeenCalled();
  });

  it.each([
    ["runtime", "https://openscholar.test/offline-pack.js", "cors"],
    ["shell", "https://openscholar.test/offline.html", "navigate"],
  ])(
    "serves the required offline-reader %s only from the coherent precache",
    async (_label, url, mode) => {
      const cachedResponse = response();
      const runtime = loadWorker(vi.fn().mockResolvedValue(response()));
      runtime.cache.match.mockResolvedValue(cachedResponse);
      const readerRequest = request(url, mode);

      const result = await dispatchFetch(
        runtime.handlers.get("fetch")!,
        readerRequest,
      );

      expect(result).toEqual({ handled: true, response: cachedResponse });
      expect(runtime.cache.match).toHaveBeenCalledWith(readerRequest);
      expect(runtime.fetchMock).not.toHaveBeenCalled();
      expect(runtime.cache.put).not.toHaveBeenCalled();
    },
  );

  it("bounds obsolete build-hashed static assets", async () => {
    const newAssetUrl =
      "https://openscholar.test/_next/static/chunks/new.js";
    const runtime = loadWorker(
      vi.fn().mockResolvedValue(
        response("public, max-age=31536000", true, { url: newAssetUrl }),
      ),
    );
    const oldAssets = Array.from({ length: 98 }, (_, index) =>
      request(
        `https://openscholar.test/_next/static/chunks/old-${index}.js`,
      ),
    );
    runtime.cache.keys.mockResolvedValue(oldAssets);

    await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request(newAssetUrl),
    );

    expect(runtime.cache.delete).toHaveBeenCalledTimes(2);
    expect(runtime.cache.delete).toHaveBeenNthCalledWith(1, oldAssets[0]);
    expect(runtime.cache.delete).toHaveBeenNthCalledWith(2, oldAssets[1]);
  });

  it("honours response no-store and private directives", async () => {
    const assetUrl = "https://openscholar.test/_next/static/chunks/app.js";
    const fetchMock = vi.fn().mockResolvedValue(
      response("private, no-store", true, { url: assetUrl }),
    );
    const runtime = loadWorker(fetchMock);

    await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request(assetUrl),
    );

    expect(runtime.cache.put).not.toHaveBeenCalled();
  });

  it("does not store responses whose representation varies on every request", async () => {
    const assetUrl = "https://openscholar.test/_next/static/chunks/app.js";
    const unsafeResponse = response("public, max-age=31536000", true, {
      url: assetUrl,
    });
    unsafeResponse.headers.set("vary", "Accept-Encoding, *");
    const fetchMock = vi.fn().mockResolvedValue(unsafeResponse);
    const runtime = loadWorker(fetchMock);

    await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request(assetUrl),
    );

    expect(runtime.cache.put).not.toHaveBeenCalled();
  });

  it.each([
    ["a redirect", { redirected: true, sameUrl: false, vary: "" }],
    ["a final-URL mismatch", { redirected: false, sameUrl: false, vary: "" }],
    [
      "a cookie-varying response",
      { redirected: false, sameUrl: true, vary: "Cookie" },
    ],
  ])("does not store %s under an allowlisted static key", async (_label, policy) => {
    const assetUrl =
      "https://openscholar.test/_next/static/chunks/public-shell.js";
    const unsafeResponse = response("public, max-age=31536000", true, {
      redirected: policy.redirected,
      url: policy.sameUrl
        ? assetUrl
        : "https://openscholar.test/account/personalized.js",
    });
    if (policy.vary !== "") unsafeResponse.headers.set("vary", policy.vary);
    const runtime = loadWorker(vi.fn().mockResolvedValue(unsafeResponse));

    const result = await dispatchFetch(
      runtime.handlers.get("fetch")!,
      request(assetUrl),
    );

    expect(result).toEqual({ handled: true, response: unsafeResponse });
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
    expect(runtime.cache.put).not.toHaveBeenCalled();
    expect(runtime.fetchMock).toHaveBeenCalledOnce();
  });

  it("pre-caches only the owner-neutral offline shell and install assets", async () => {
    const runtime = loadWorker(
      vi.fn(async (workerRequest: { url: string }) => {
        const pathname = new URL(workerRequest.url).pathname;
        return pathname === "/offline.html" || pathname === "/offline-pack.js"
          ? readerAssetResponse(workerRequest)
          : response("public, max-age=31536000", true, {
              url: workerRequest.url,
            });
      }),
    );
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
      "/offline-pack.js",
      "/manifest.webmanifest",
      "/icon.svg",
      "/icon-192.png",
      "/icon-512.png",
      "/apple-touch-icon.png",
    ]);
    expect(runtime.cache.put).not.toHaveBeenCalledWith("/", expect.anything());
    expect(runtime.worker.skipWaiting).not.toHaveBeenCalled();
  });

  it.each(["/offline.html", "/offline-pack.js"])(
    "rejects installation when the required cold-reader asset is unavailable: %s",
    async (failedPath) => {
      const fetchMock = vi.fn(async (workerRequest: { url: string }) =>
        new URL(workerRequest.url).pathname === failedPath
          ? response("no-store", true, { url: workerRequest.url })
          : ["/offline.html", "/offline-pack.js"].includes(
                new URL(workerRequest.url).pathname,
              )
            ? readerAssetResponse(workerRequest)
            : response("public, max-age=31536000", true, {
                url: workerRequest.url,
              }),
      );
      const runtime = loadWorker(fetchMock);
      let installWork: Promise<unknown> | undefined;

      runtime.handlers.get("install")!({
        request: request("https://openscholar.test/sw.js"),
        respondWith: vi.fn(),
        waitUntil: (work) => {
          installWork = work;
        },
      });

      expect(installWork).toBeDefined();
      await expect(installWork!).rejects.toThrow(
        "The encrypted offline reader could not be cached.",
      );
      expect(runtime.cacheStorage.delete).toHaveBeenCalledWith(
        "openscholar-shell-2026-08-24-r4",
      );
      expect(runtime.cache.put).not.toHaveBeenCalled();
      expect(runtime.worker.skipWaiting).not.toHaveBeenCalled();
    },
  );

  it.each([
    ["old coherent pair", "2026-08-24-r0", "2026-08-24-r0"],
    ["mixed runtime", "2026-08-24-r4", "2026-08-24-r0"],
  ])(
    "rejects installation of an %s",
    async (_label, shellRevision, runtimeRevision) => {
      const fetchMock = vi.fn(async (workerRequest: { url: string }) => {
        const pathname = new URL(workerRequest.url).pathname;
        if (pathname === "/offline.html" || pathname === "/offline-pack.js") {
          return readerAssetResponse(
            workerRequest,
            pathname === "/offline.html" ? shellRevision : runtimeRevision,
          );
        }
        return response("public, max-age=31536000", true, {
          url: workerRequest.url,
        });
      });
      const runtime = loadWorker(fetchMock);
      let installWork: Promise<unknown> | undefined;

      runtime.handlers.get("install")!({
        request: request("https://openscholar.test/sw.js"),
        respondWith: vi.fn(),
        waitUntil: (work) => {
          installWork = work;
        },
      });

      await expect(installWork!).rejects.toThrow(
        "The encrypted offline reader could not be cached.",
      );
      expect(runtime.cacheStorage.delete).toHaveBeenCalledWith(
        "openscholar-shell-2026-08-24-r4",
      );
      expect(runtime.cache.put).not.toHaveBeenCalled();
    },
  );

  it("rejects installation when either required reader write aborts", async () => {
    const runtime = loadWorker(
      vi.fn(async (workerRequest: { url: string }) => {
        const pathname = new URL(workerRequest.url).pathname;
        return pathname === "/offline.html" || pathname === "/offline-pack.js"
          ? readerAssetResponse(workerRequest)
          : response("public, max-age=31536000", true, {
              url: workerRequest.url,
            });
      }),
    );
    runtime.cache.put.mockImplementation(async (key: string) => {
      if (key === "/offline-pack.js") {
        throw new DOMException("quota", "QuotaExceededError");
      }
    });
    let installWork: Promise<unknown> | undefined;

    runtime.handlers.get("install")!({
      request: request("https://openscholar.test/sw.js"),
      respondWith: vi.fn(),
      waitUntil: (work) => {
        installWork = work;
      },
    });

    await expect(installWork!).rejects.toMatchObject({
      name: "QuotaExceededError",
    });
    expect(runtime.cacheStorage.delete).toHaveBeenCalledWith(
      "openscholar-shell-2026-08-24-r4",
    );
    expect(runtime.worker.skipWaiting).not.toHaveBeenCalled();
  });

  it("rejects a worker whose script URL claims a different revision", async () => {
    const runtime = loadWorker(vi.fn());
    runtime.worker.location.href =
      "https://openscholar.test/sw.js?reader=2026-08-24-r0";
    let installWork: Promise<unknown> | undefined;

    runtime.handlers.get("install")!({
      request: request("https://openscholar.test/sw.js"),
      respondWith: vi.fn(),
      waitUntil: (work) => {
        installWork = work;
      },
    });

    await expect(installWork!).rejects.toThrow(
      "The worker URL does not match the offline reader revision.",
    );
    expect(runtime.cacheStorage.keys).not.toHaveBeenCalled();
    expect(runtime.fetchMock).not.toHaveBeenCalled();
    expect(runtime.cacheStorage.open).not.toHaveBeenCalled();
  });

  it("rejects a worker update that reuses the incumbent revision", async () => {
    const runtime = loadWorker(vi.fn());
    runtime.worker.registration.active = {
      scriptURL: "https://openscholar.test/sw.js?reader=2026-08-24-r4",
    };
    let installWork: Promise<unknown> | undefined;

    runtime.handlers.get("install")!({
      request: request("https://openscholar.test/sw.js"),
      respondWith: vi.fn(),
      waitUntil: (work) => {
        installWork = work;
      },
    });

    await expect(installWork!).rejects.toThrow(
      "The offline reader revision must change before updating the worker.",
    );
    expect(runtime.fetchMock).not.toHaveBeenCalled();
    expect(runtime.cacheStorage.open).not.toHaveBeenCalled();
    expect(runtime.cacheStorage.delete).not.toHaveBeenCalled();
    expect(runtime.cache.put).not.toHaveBeenCalled();
  });

  it("removes a stranded candidate cache owned by an older revision", async () => {
    const runtime = loadWorker(
      vi.fn(async (workerRequest: { url: string }) => {
        const pathname = new URL(workerRequest.url).pathname;
        return pathname === "/offline.html" || pathname === "/offline-pack.js"
          ? readerAssetResponse(workerRequest)
          : response("public, max-age=31536000", true, {
              url: workerRequest.url,
            });
      }),
    );
    runtime.worker.registration.active = {
      scriptURL: "https://openscholar.test/sw.js?reader=2026-08-24-r3",
    };
    runtime.cacheStorage.keys.mockResolvedValue([
      "openscholar-shell-2026-08-24-r3",
      "openscholar-shell-2026-08-24-r4",
    ]);
    let installWork: Promise<unknown> | undefined;

    runtime.handlers.get("install")!({
      request: request("https://openscholar.test/sw.js"),
      respondWith: vi.fn(),
      waitUntil: (work) => {
        installWork = work;
      },
    });
    await installWork;

    expect(runtime.cacheStorage.delete).toHaveBeenCalledOnce();
    expect(runtime.cacheStorage.delete).toHaveBeenCalledWith(
      "openscholar-shell-2026-08-24-r4",
    );
    expect(runtime.cacheStorage.open).toHaveBeenCalledWith(
      "openscholar-shell-2026-08-24-r4",
    );
    expect(runtime.cache.put).toHaveBeenCalledWith(
      "/offline-pack.js",
      expect.anything(),
    );
  });

  it("deletes only superseded OpenScholar shell caches during activation", async () => {
    const runtime = loadWorker();
    runtime.cacheStorage.keys.mockResolvedValue([
      "openscholar-shell-old",
      "openscholar-shell-2026-08-24-r4",
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
