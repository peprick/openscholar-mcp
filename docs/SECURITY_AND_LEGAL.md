# Security, Privacy, and Legal Boundaries

## Threat model

OpenScholar handles untrusted search text, provider responses, metadata, URLs, PDFs, and eventually document text. It also exposes agent-callable tools. Primary risks are SSRF, prompt injection, credential leakage, cross-user access, provider abuse, malicious PDFs, licence violations, overly powerful tools, and dependency/container supply-chain issues.

## Access control

### Local MVP

- Bind services to loopback by default.
- REST resolves the fixed bootstrap owner and is unauthenticated; this mode is for loopback development only.
- Use a generated local API key for `/mcp`.
- Validate `Origin` for HTTP MCP connections.
- Keep write-oriented MCP tools absent.

### Hosted deployment

- Hosted mode is implemented as a stateless Spring Security OAuth 2.0 resource server for REST and MCP. It validates JWKS signatures, a required expiry, issuer, audience, and route scopes.
- The Next.js BFF implements confidential-client authorization code with PKCE S256, state, and nonce. It validates ID-token signatures and claims, stores access/refresh/ID tokens in an AES-256-GCM-sealed `__Host-` HttpOnly/Secure/SameSite=Lax cookie, and forwards the access token to Spring Boot only from the server.
- Unsafe browser `/api/*` BFF requests require an exact configured same-origin `Origin`. The backend remains a bearer API and does not use the browser cookie directly.
- Library collections and search snapshots are authorized in application services through an internal user keyed by the validated issuer+subject. A different principal receives not found for an owned identifier.
- MCP publishes protected-resource metadata and requires `openscholar.mcp`. OpenScholar is not an authorization server and does not issue tokens.
- Short-lived tokens, HTTPS, identity-provider registration/interoperability, key rotation, and an approved grant policy remain deployment responsibilities.

Implemented route scopes:

```text
openscholar.search
openscholar.library
openscholar.jobs
openscholar.privacy
openscholar.mcp
openscholar.ops
```

`openscholar.jobs` protects operational refresh records, not MCP job handles. Search-job enqueue and list/get/retry visibility follow the target snapshot's owner. `PAPER_ACCESS` jobs remain visible and retryable to every jobs-scoped principal because the canonical paper/access catalog is shared. Limit that scope accordingly if shared operational status should not be exposed to ordinary users.

Inbound user or MCP tokens are never forwarded to OpenAlex, DataCite, DOAJ, CORE, Unpaywall, arXiv, or document hosts; provider credentials are server-owned and separate.

## MCP safety

- Default to non-destructive, read-oriented tools; mark cache-affecting search honestly as non-read-only.
- Describe side effects in tool metadata.
- Require confirmation for collection/note mutations.
- Never expose arbitrary SQL, URL fetch, shell, or unrestricted file tools.
- Validate parameters independently of model-generated JSON.
- Apply authorization in services, not just MCP adapters.
- Treat annotations as hints, not access-control enforcement.
- Limit response sizes and redact sensitive fields.

## URL and document security

- Accept verification candidates only from configured provider responses; there is no arbitrary-URL REST or MCP fetch operation.
- Permit absolute HTTPS URLs by default, with HTTP available only through explicit local/test configuration. Reject credentials, fragments, and non-default ports.
- Resolve each request through the validating connection-layer DNS resolver and reject any hostname with a loopback, any-local, link-local, site-local/private, multicast, carrier-grade NAT, documentation/benchmark, or IPv6 unique-local answer.
- Disable automatic redirects and revalidate the URL and DNS answers for every manually followed redirect. Redirect count, connection time, response time, and probe bytes are bounded.
- Do not forward inbound authorization, cookies, or provider credentials to candidate hosts; automatic authentication, cookies, retries, decompression, and connection reuse are disabled for link probes.
- Probe PDF candidates with a bounded range `GET` and require either `application/pdf` or a `%PDF-` prefix. Probe landing pages with `HEAD`, using a bounded range `GET` only when `HEAD` is unsupported.
- Close a probe immediately after the bounded prefix. Backend access verification never downloads, buffers, proxies, or persists a complete PDF.
- Bound Unpaywall JSON and arXiv Atom responses while reading them from HTTP, before deserialization or XML parsing.
- The web reader accepts only a user-selected, freshly verified HTTPS location matched by paper and location UUID, with a non-null PDF and an access status of `OPEN_PDF`, `REPOSITORY_COPY`, or `PREPRINT`. Repository/preprint status alone is insufficient when only a landing page was verified. Its isolated PDF.js worker requests that source directly without application credentials; neither Next.js nor Spring Boot proxies or retains the document bytes.
- Next.js emits an enforced Content Security Policy on every frontend response, and the production Caddy edge mirrors the exact production value. The static production policy denies frames, plugins, base-URL changes, inline event handlers, and non-self forms; limits application code, styles, fonts, images, and PDF.js workers to local assets; and allows HTTPS connections because the reader fetches independently verified PDFs directly from their source. Next.js currently requires `unsafe-inline` for its bootstrap scripts and compatible styling, while the pinned PDF.js worker requires the narrower WebAssembly-only `wasm-unsafe-eval` permission for its local decoders. General `unsafe-eval` and WebSocket connections are development-only; adopting script nonces would require a coordinated Next.js rendering redesign.
- A source must permit browser cross-origin reading. Load or render failure exposes a generic error and the original verified external link; it never triggers an unrestricted server-side URL fetch.
- Enforce document-byte, load-time, render-time, canvas, image, and extracted-page-text ceilings. Render only PDF pages to a bounded canvas; the custom reader exposes no embedded scripts, attachments, form actions, or PDF-originated external actions.
- Scan retained files before use if permitted document retention is implemented later.

Retained files do not exist in the current implementation. The browser reader is a link-only view and does not change the `retention_allowed=false` policy.

## Prompt-injection boundary

Papers, abstracts, repository pages, and PDF metadata are data. They cannot modify tool policy, request secrets, authorize actions, or redefine a user task. Any later summarization pipeline must delimit sources, cite pages/passages, restrict available tools, validate generated citations, and require confirmation for mutations.

## Secrets

- Use ignored `.env` files locally and secret managers when hosted.
- Never commit tokens or embed private configuration in frontend code.
- Redact authorization headers, keys, provider-identification emails, and signed URLs from logs.
- Rotate credentials after suspected exposure.

The production template uses three file-backed secrets: the PostgreSQL password, a 32-byte-base64 session-sealing key, and the confidential OIDC client secret. Hosted mode does not use the local MCP key. Secret files are a single-host template boundary, not a substitute for a hosted secret manager, rotation, and revocation process.

Unpaywall's contact email is backend-owned configuration. It is not accepted from a REST caller and is used only on exact DOI requests to Unpaywall. If it is absent, Unpaywall is reported as not configured without disabling arXiv access checks. A configured CORE API key is likewise backend-owned, sent only as Bearer authentication to the configured CORE API base, and never included in a query parameter or forwarded to a document host.

## Open-access and copyright policy

OpenScholar distinguishes:

1. Metadata that provider terms allow to be indexed.
2. A document that is free to read.
3. A document that may be downloaded for personal use.
4. A document that may be retained, redistributed, mined, or used commercially.

These are not equivalent permissions.

Default behavior:

- Store metadata/source links according to provider terms.
- Link to legal full text rather than copying it.
- Store a PDF only under an explicit licence/policy or authorized user upload.
- Preserve attribution, licence, source URL, retrieval time, and checksum.
- Do not expose retained documents to others unless permitted.
- Delete retained bytes and embeddings when permission is withdrawn or an upload is deleted.

DOAJ article metadata is exposed by DOAJ under CC0, but that waiver does not transfer copyright or reuse rights in the underlying article. The DOAJ adapter therefore stores normalized metadata and source-reported links only. Its `reportedOpenAccess` value records DOAJ's index claim; it is not a licence grant, successful link verification, or permission to retain, mine, or redistribute the document.

DataCite discovery is disabled by default and limited to thesis/dissertation metadata. It uses canonical DOI landing links, does not emit a discovery PDF URL, sets no open-access claim, and skips open-access-only queries. A later exact-identifier access verification is the only route that may add a verified legal-access location. DataCite metadata availability is not permission to copy or retain the deposited thesis.

CORE API use is disabled by default and requires a separate operator confirmation that the current terms and an applicable licence have been reviewed. That switch is an operational guard, not permission by itself. CORE's terms currently require a licence for API use and direct projects related to existing CORE API, search, or discovery services to contact CORE; attribution requirements also apply. The API or dataset licence does not transfer rights in the underlying works.

The CORE adapter uses only the supported API v3 search route. It does not scrape CORE, call document-download or full-text endpoints, retain returned `fullText` or download URLs, or populate discovery PDF URLs. CORE-reported full-text availability remains an unverified provider claim and is not permission to download, retain, mine, or redistribute the work. Off-API or systematic harvesting is outside this project's policy.

The implemented `V4` schema enforces this default: every active access location is `LINK_ONLY`, and `retention_allowed` is constrained to `false`. A successful PDF probe verifies that a provider-reported URL currently behaves like a PDF endpoint; it does not grant redistribution, text-mining, or retention rights.

## Paywalled content

The system may retain allowed metadata and a canonical landing page. It must not bypass paywalls or institutional logins, share credentials, evade controls, retrieve pirated copies, or label unverified mirrors as legal open access. If no open version exists, show `RESTRICTED` or `ABSTRACT_ONLY`.

Unpaywall and arXiv are queried only by exact identifiers attached to the canonical paper. Provider responses must echo/match that DOI or arXiv ID before their locations are considered. Providers are isolated: one source's failure produces bounded warnings and cannot erase a verified location from another source. A 24-hour cache reduces repeated provider calls, and a total outage may return an explicitly labelled stale result.

Normal refresh requests reuse the cache. Forced refresh is an explicit bypass protected by a PostgreSQL-backed, per-paper cooldown (`openscholar.access.force-refresh-cooldown`, five minutes by default). Early repeats fail with `429 ACCESS_REFRESH_RATE_LIMITED` and a bounded `Retry-After` value instead of creating additional provider traffic.

The local server binds to loopback by default. Production Compose enables OIDC and principal-based in-memory MCP rate limiting, but that limiter is per instance and has no aggregate/cluster-wide abuse budget. Public exposure still requires an approved ingress/trusted-proxy design, aggregate abuse controls, and target-environment verification; the per-paper cooldown is not a substitute.

## Privacy and retention

- Library data and search snapshots are owner-scoped in local and OIDC modes; the latter derives ownership from validated issuer+subject rather than a client-supplied UUID.
- `GET /api/v1/privacy/export` returns a no-store JSON attachment with the current user's display data, search snapshots/filters/warnings, collections, and saved memberships/tags. It intentionally omits issuer/subject and operational job history.
- `DELETE /api/v1/privacy/account` requires exact `DELETE_MY_DATA`. It deletes the current user's search-refresh jobs, search snapshots, collections/memberships/tags, and hosted user row. Shared canonical paper/provider/access data and global access-refresh jobs remain because they are not personal ownership records. Local mode preserves the fixed bootstrap row.
- A later valid hosted token for the same issuer+subject provisions a new, empty internal account; deletion is not an identity-provider account revocation.
- Minimize provider response retention.
- Avoid sending private user data to providers.
- Define and enforce retention periods for searches, operational jobs, audits, diagnostics, and backups before launch. Export/deletion mechanics do not by themselves prove regulatory compliance.

## Security verification

- Dependency, secret, static-analysis, and container scans.
- Authorization tests for every user-owned resource.
- SSRF, redirect, decompression-bomb, and oversized-response tests.
- Invalid Origin, wrong audience/scope, expired-token, and cross-user tests.
- MCP conformance and negative-schema tests.
- SBOM generation for releases.

## Review before public hosting

- Provider terms and attribution.
- Privacy notice, retention schedule, export/deletion behavior, shared-catalog treatment, and qualified privacy review.
- Document retention matrix by source/licence.
- Real identity-provider registration/interoperability, token/client rotation, administrator grants, authentication threat model, and abuse policy.
- Incident response and credential rotation.
- A working alert receiver plus load, accessibility, penetration, backup/restore, and disaster-recovery evidence.

This is an engineering policy, not legal advice. Commercial deployment should receive qualified legal review.
