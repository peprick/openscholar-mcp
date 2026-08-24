# ADR 0007: Use an account-neutral PWA shell

- Status: accepted
- Date: 2026-08-24

## Context

OpenScholar can search owner-visible metadata without scholarly providers, but
that `LOCAL` mode still needs the Next.js server, Spring Boot, and PostgreSQL.
Making the web application installable must not imply that a disconnected
browser contains the research catalog. Hosted pages are owner-specific and the
Next.js proxy refreshes an encrypted OIDC session; caching rendered pages or
React Server Component payloads could expose stale account data or bypass that
session boundary. PDF storage remains outside the product.

## Decision

Register a dependency-free, root-scoped service worker only in production (and
an explicit browser-test mode). It may cache only:

- a self-contained, account-neutral `/offline.html` fallback;
- the manifest and OpenScholar application icons, served network-first with a
  cached fallback; and
- queryless, same-origin, build-hashed `/_next/static/` assets requested by the
  browser, with at most 96 such runtime entries retained.

[ADR 0010](0010-store-one-encrypted-offline-metadata-pack.md) later extends this
positive allowlist with an account-neutral static reader runtime. The fallback
and runtime form one required, versioned cache pair: installation completes only
after both are fetched and written, and the active worker serves their exact
paths cache-only until a later worker version installs a complete replacement.
This prevents an independent runtime or shell refresh from mixing reader
versions. It does not permit an owned page, RSC response, API response, or user
record in CacheStorage.

Successful eligible navigations remain network-only. An eligible navigation
whose network fetch rejects may receive the cached generic shell, but its
successful HTML or RSC response is never stored and does not refresh the reader
pair.
Requests for APIs, authentication, MCP, OAuth metadata, exports, external
origins, document extensions, authorization headers, byte ranges, or dynamic
image/data routes are not intercepted. Responses marked `private`, `no-store`,
opaque, unsuccessful, or `Vary: *` are not stored.

The worker uses an application-owned versioned cache prefix, removes only older
OpenScholar shell caches after safe activation, and does not force
`skipWaiting`. `/sw.js` is served with `no-store`/revalidation and
`Service-Worker-Allowed: /`; public install assets bypass hosted session
processing. When persistent registration is disabled in development or ordinary
test runs, best-effort cleanup unregisters only the exact same-origin `/sw.js`
worker and deletes only caches with the OpenScholar prefix. The explicit PWA
browser-test mode keeps the worker enabled.

A global, visually quiet connectivity region treats `navigator.onLine` only as
an advisory browser hint. When it reports offline, the UI probes the same-origin
`/api/connectivity` route with `no-store` and browser credentials omitted; the
public route bypasses hosted session refresh and reports success only when
Next.js can reach an `UP` Spring Boot backend. During the initial advisory check,
and when a local or self-hosted stack remains reachable, AUTO and LOCAL search
stay enabled and the UI warns that online research sources may be limited. A
failed or non-successful probe disables those server-backed search actions. A
visible **Check again** action starts a fresh bounded probe after transient
recovery, but actions remain disabled until it succeeds. After confirmed
unreachability, a returning browser signal follows the same rule and needs a
successful application probe before actions resume or the live region announces
that OpenScholar can be reached again. The copy describes observed OpenScholar
reachability rather than claiming to know the Internet connection state. The
probe does not classify individual provider health, queue mutations, or claim
that PostgreSQL is available inside the browser.

## Consequences

The application is installable and has a reliable, accessible offline landing
shell without putting private research state or documents in CacheStorage. The
database remains authoritative, logout/account switching cannot reveal a cached
owned page, an offline browser signal does not unnecessarily disable a reachable
self-hosted stack, and a worker rollback does not require deleting user data.

This decision alone is not browser-only metadata search, offline library
mutation, background sync, or PDF reading. ADR 0010 separately permits one
explicit, encrypted, metadata-only collection pack in IndexedDB with manual full
refresh, read-only access, owner-mismatch/deletion handling, and quota limits.
CacheStorage remains account-neutral, PostgreSQL remains authoritative, and PDF
storage remains excluded.
