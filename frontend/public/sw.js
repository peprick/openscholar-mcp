"use strict";

const CACHE_PREFIX = "openscholar-shell-";
const OFFLINE_READER_REVISION = "2026-08-24-r2";
// Bump this value whenever the offline shell or cache policy changes.
const CACHE_NAME = `${CACHE_PREFIX}${OFFLINE_READER_REVISION}`;
const MAX_STATIC_ENTRIES = 96;
const OFFLINE_URL = "/offline.html";
const OFFLINE_PACK_RUNTIME_URL = "/offline-pack.js";
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

function responseCanBeStored(response) {
  if (!response.ok || response.type === "opaque") return false;
  const cacheControl = response.headers.get("cache-control") ?? "";
  const vary = response.headers.get("vary") ?? "";
  return (
    !/(?:^|,)\s*(?:no-store|private)(?:=|\s|,|$)/iu.test(cacheControl) &&
    !vary.split(",").some((value) => value.trim() === "*")
  );
}

async function store(cache, request, response) {
  if (!responseCanBeStored(response)) return;
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
  if (!responseCanBeStored(response)) return null;
  if (
    REQUIRED_PRECACHE_URLS.includes(pathname) &&
    !(await hasExpectedReaderRevision(pathname, response))
  ) {
    return null;
  }
  return response;
}

async function installShell() {
  const cache = await caches.open(CACHE_NAME);
  const requiredResponses = await Promise.all(
    REQUIRED_PRECACHE_URLS.map(fetchForPrecache),
  );
  if (requiredResponses.some((response) => response === null)) {
    throw new Error("The encrypted offline reader could not be cached.");
  }
  // Keep the previous worker active if this required write fails. That avoids
  // replacing a working offline shell with an incomplete upgrade.
  await Promise.all(
    REQUIRED_PRECACHE_URLS.map((pathname, index) =>
      cache.put(pathname, requiredResponses[index]),
    ),
  );
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

  const response = await fetch(request);
  await store(cache, request, response.clone());
  await trimStaticAssets(cache);
  return response;
}

async function networkFirstInstallAsset(request) {
  const cache = await caches.open(CACHE_NAME);
  try {
    const response = await fetch(request);
    if (responseCanBeStored(response)) {
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
  } else if (isInstallAsset(url)) {
    event.respondWith(networkFirstInstallAsset(request));
  } else if (isStaticAsset(url)) {
    event.respondWith(cacheFirst(request));
  }
});
