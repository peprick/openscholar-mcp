"use strict";

const CACHE_PREFIX = "openscholar-shell-";
const OFFLINE_READER_REVISION = "2026-08-24-r4";
// Bump this value whenever the offline shell or cache policy changes.
const CACHE_NAME = `${CACHE_PREFIX}${OFFLINE_READER_REVISION}`;
const MAX_STATIC_ENTRIES = 96;
const OFFLINE_URL = "/offline.html";
const OFFLINE_PACK_RUNTIME_URL = "/offline-pack.js";
const VERSIONED_OFFLINE_PACK_RUNTIME_URL = `${OFFLINE_PACK_RUNTIME_URL}?reader=${encodeURIComponent(OFFLINE_READER_REVISION)}`;
const REQUIRED_PRECACHE_URLS = [OFFLINE_URL, OFFLINE_PACK_RUNTIME_URL];
const PRECACHE_URLS = [
  ...REQUIRED_PRECACHE_URLS,
  "/manifest.webmanifest",
  "/icon.svg",
  "/icon-192.png",
  "/icon-512.png",
  "/apple-touch-icon.png",
];
const STATIC_PATH_PREFIX = "/_next/static/";
const STATIC_URLS = new Set([
  "/apple-touch-icon.png",
  "/icon.svg",
  "/icon-192.png",
  "/icon-512.png",
  "/manifest.webmanifest",
  OFFLINE_PACK_RUNTIME_URL,
]);
const PRIVATE_PATH_PREFIXES = [
  "/api/",
  "/auth/",
  "/export/",
  "/exports/",
  "/login",
  "/logout",
  "/mcp/",
  "/oauth/",
  "/.well-known/",
];

function hasPrivatePath(pathname) {
  return PRIVATE_PATH_PREFIXES.some(
    (prefix) => pathname === prefix.slice(0, -1) || pathname.startsWith(prefix),
  );
}

function isResearchDocument(pathname) {
  return /\.(?:bib|docx?|epub|odt|pdf|ris|rtf)(?:$|\/)/iu.test(pathname);
}

function isStaticAsset(url) {
  return (
    url.search === "" &&
    (url.pathname.startsWith(STATIC_PATH_PREFIX) ||
      STATIC_URLS.has(url.pathname))
  );
}

function isInstallAsset(url) {
  return url.search === "" && STATIC_URLS.has(url.pathname);
}

function isRequiredReaderAsset(url) {
  return url.search === "" && REQUIRED_PRECACHE_URLS.includes(url.pathname);
}

function isVersionedReaderRuntime(url) {
  return (
    url.origin === self.location.origin &&
    `${url.pathname}${url.search}` === VERSIONED_OFFLINE_PACK_RUNTIME_URL
  );
}

function readerRevisionFromWorkerUrl(value) {
  try {
    const url = new URL(value);
    if (url.origin !== self.location.origin || url.pathname !== "/sw.js") {
      return null;
    }
    const values = url.searchParams.getAll("reader");
    if (values.length !== 1 || url.searchParams.size !== 1) return null;
    if (url.search !== `?reader=${encodeURIComponent(values[0])}`) return null;
    return values[0];
  } catch {
    return null;
  }
}

function activeReaderRevision() {
  const active = self.registration.active;
  return active === null ? null : readerRevisionFromWorkerUrl(active.scriptURL);
}

function responseCanBeStored(response, request) {
  if (!response.ok || response.type === "opaque" || response.redirected) {
    return false;
  }
  let responseUrl;
  let requestUrl;
  try {
    responseUrl = new URL(response.url);
    requestUrl = new URL(
      typeof request === "string" ? request : request.url,
      self.location.origin,
    );
  } catch {
    return false;
  }
  if (responseUrl.href !== requestUrl.href) return false;
  const cacheControl = response.headers.get("cache-control") ?? "";
  const vary = response.headers.get("vary") ?? "";
  return (
    !/(?:^|,)\s*(?:no-store|private)(?:=|\s|,|$)/iu.test(cacheControl) &&
    !vary
      .split(",")
      .some((value) => ["*", "cookie"].includes(value.trim().toLowerCase()))
  );
}

async function store(cache, request, response) {
  if (!responseCanBeStored(response, request)) return;
  try {
    await cache.put(request, response);
  } catch {
    // Quota limits and restrictive browser modes must not break navigation.
  }
}

async function trimStaticAssets(cache) {
  const requests = await cache.keys();
  const staticRequests = requests.filter((request) => {
    const url = new URL(request.url);
    return (
      url.origin === self.location.origin &&
      url.search === "" &&
      url.pathname.startsWith(STATIC_PATH_PREFIX)
    );
  });
  const excess = staticRequests.length - MAX_STATIC_ENTRIES;
  if (excess <= 0) return;
  await Promise.all(
    staticRequests.slice(0, excess).map((request) => cache.delete(request)),
  );
}

async function hasExpectedReaderRevision(pathname, response) {
  let finalUrl;
  try {
    finalUrl = new URL(response.url);
  } catch {
    return false;
  }
  if (
    finalUrl.origin !== self.location.origin ||
    finalUrl.pathname !== pathname ||
    finalUrl.search !== ""
  ) {
    return false;
  }
  const contentType = (response.headers.get("content-type") ?? "")
    .split(";", 1)[0]
    .trim()
    .toLowerCase();
  const expectedType =
    pathname === OFFLINE_URL
      ? contentType === "text/html"
      : [
          "application/ecmascript",
          "application/javascript",
          "text/ecmascript",
          "text/javascript",
        ].includes(contentType);
  if (!expectedType) return false;
  try {
    const source = await response.clone().text();
    return pathname === OFFLINE_URL
      ? source.includes(
          `data-offline-reader-revision="${OFFLINE_READER_REVISION}"`,
        )
      : source.includes(
          `const READER_REVISION = "${OFFLINE_READER_REVISION}"`,
        );
  } catch {
    return false;
  }
}

async function fetchForPrecache(pathname) {
  const request = new Request(new URL(pathname, self.location.origin), {
    cache: "reload",
    credentials: "omit",
  });
  const response = await fetch(request);
  if (!responseCanBeStored(response, request)) return null;
  if (
    REQUIRED_PRECACHE_URLS.includes(pathname) &&
    !(await hasExpectedReaderRevision(pathname, response))
  ) {
    return null;
  }
  return response;
}

async function installShell() {
  if (
    readerRevisionFromWorkerUrl(self.location.href) !==
    OFFLINE_READER_REVISION
  ) {
    throw new Error("The worker URL does not match the offline reader revision.");
  }
  const existingNames = await caches.keys();
  const cacheExists = existingNames.includes(CACHE_NAME);
  const incumbentRevision = activeReaderRevision();
  if (
    self.registration.active !== null &&
    (incumbentRevision === OFFLINE_READER_REVISION ||
      (cacheExists && incumbentRevision === null))
  ) {
    throw new Error(
      "The offline reader revision must change before updating the worker.",
    );
  }
  if (cacheExists) await caches.delete(CACHE_NAME);

  try {
    const requiredResponses = await Promise.all(
      REQUIRED_PRECACHE_URLS.map(fetchForPrecache),
    );
    if (requiredResponses.some((response) => response === null)) {
      throw new Error("The encrypted offline reader could not be cached.");
    }

    const cache = await caches.open(CACHE_NAME);
    // Complete each required write before starting the next one. If either
    // fails, cleanup below removes the complete candidate cache.
    for (let index = 0; index < REQUIRED_PRECACHE_URLS.length; index += 1) {
      await cache.put(
        REQUIRED_PRECACHE_URLS[index],
        requiredResponses[index],
      );
    }
    await Promise.allSettled(
      PRECACHE_URLS.filter(
        (pathname) => !REQUIRED_PRECACHE_URLS.includes(pathname),
      ).map(async (pathname) => {
        const response = await fetchForPrecache(pathname);
        if (response !== null) {
          await store(cache, pathname, response);
        }
      }),
    );
  } catch (error) {
    await caches.delete(CACHE_NAME);
    throw error;
  }
}

async function removeOldCaches() {
  const names = await caches.keys();
  await Promise.all(
    names
      .filter((name) => name.startsWith(CACHE_PREFIX) && name !== CACHE_NAME)
      .map((name) => caches.delete(name)),
  );
  await self.clients.claim();
}

async function cacheFirst(request) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(request);
  if (cached !== undefined) return cached;

  const publicRequest = new Request(request, { credentials: "omit" });
  const response = await fetch(publicRequest);
  await store(cache, request, response.clone());
  await trimStaticAssets(cache);
  return response;
}

async function networkFirstInstallAsset(request) {
  const cache = await caches.open(CACHE_NAME);
  try {
    const publicRequest = new Request(request, { credentials: "omit" });
    const response = await fetch(publicRequest);
    if (responseCanBeStored(response, request)) {
      await store(cache, request, response.clone());
      return response;
    }
    const cached = await cache.match(request);
    return cached ?? response;
  } catch {
    const cached = await cache.match(request);
    return cached ?? Response.error();
  }
}

async function requiredReaderResponse(request) {
  const cache = await caches.open(CACHE_NAME);
  const cached = await cache.match(request);
  return cached ?? Response.error();
}

async function navigationResponse(request) {
  try {
    return await fetch(request);
  } catch {
    const cache = await caches.open(CACHE_NAME);
    const cached = await cache.match(OFFLINE_URL);
    if (cached !== undefined) return cached;
    return Response.error();
  }
}

self.addEventListener("install", (event) => {
  event.waitUntil(installShell());
});

self.addEventListener("activate", (event) => {
  event.waitUntil(removeOldCaches());
});

self.addEventListener("fetch", (event) => {
  const { request } = event;
  if (
    request.method !== "GET" ||
    request.headers.has("authorization") ||
    request.headers.has("range")
  ) {
    return;
  }

  const url = new URL(request.url);
  if (
    url.origin !== self.location.origin ||
    hasPrivatePath(url.pathname) ||
    isResearchDocument(url.pathname)
  ) {
    return;
  }

  if (request.mode === "navigate") {
    event.respondWith(
      isRequiredReaderAsset(url)
        ? requiredReaderResponse(request)
        : navigationResponse(request),
    );
    return;
  }

  if (isRequiredReaderAsset(url)) {
    event.respondWith(requiredReaderResponse(request));
  } else if (isVersionedReaderRuntime(url)) {
    event.respondWith(requiredReaderResponse(OFFLINE_PACK_RUNTIME_URL));
  } else if (isInstallAsset(url)) {
    event.respondWith(networkFirstInstallAsset(request));
  } else if (isStaticAsset(url)) {
    event.respondWith(cacheFirst(request));
  }
});
