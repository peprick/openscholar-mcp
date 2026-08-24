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

Successful eligible navigations remain network-first and refresh the generic
offline shell in the background. An eligible navigation whose network fetch
rejects may receive that shell, but its successful HTML or RSC response is never
stored.
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

This is not browser-only metadata search, offline library mutation, background
sync, or PDF reading. Explicit opt-in metadata packs, their encryption and
quota policy, ownership/logout behavior, deletion, synchronization, and tests
require a separate decision before any user record enters browser storage.
