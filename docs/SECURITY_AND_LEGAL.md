# Security, Privacy, and Legal Boundaries

## Threat model

OpenScholar handles untrusted search text, provider responses, metadata, URLs, PDFs, and eventually document text. It also exposes agent-callable tools. Primary risks are SSRF, prompt injection, credential leakage, cross-user access, provider abuse, malicious PDFs, licence violations, overly powerful tools, and dependency/container supply-chain issues.

## Access control

### Local MVP

- Bind services to loopback by default.
- Use a generated local API key when remote-style MCP is enabled.
- Validate `Origin` for HTTP MCP connections.
- Keep write tools disabled unless explicitly configured.

### Hosted deployment

- OAuth 2.0/OIDC for users.
- Spring Security OAuth 2.0 resource server for REST and MCP.
- Validate token signature, issuer, expiry, audience, and scopes.
- Principal/tenant/resource-owner authorization in application services.
- Short-lived tokens and HTTPS.
- CSRF protection for cookie browser flows; bearer APIs stay separate.

Suggested scopes:

```text
research:read
library:read
library:write
documents:read
admin:operations
```

Inbound MCP tokens are never forwarded to OpenAlex, Unpaywall, CORE, or other providers; provider credentials are server-owned and separate.

## MCP safety

- Default to read-only tools.
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
- The web reader accepts only a user-selected, freshly verified `OPEN_PDF` HTTPS location matched by paper and location UUID. Its isolated PDF.js worker requests that source directly without application credentials; neither Next.js nor Spring Boot proxies or retains the document bytes.
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

Unpaywall's contact email is backend-owned configuration. It is not accepted from a REST caller and is used only on exact DOI requests to Unpaywall. If it is absent, Unpaywall is reported as not configured without disabling arXiv access checks.

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

The implemented `V4` schema enforces this default: every active access location is `LINK_ONLY`, and `retention_allowed` is constrained to `false`. A successful PDF probe verifies that a provider-reported URL currently behaves like a PDF endpoint; it does not grant redistribution, text-mining, or retention rights.

## Paywalled content

The system may retain allowed metadata and a canonical landing page. It must not bypass paywalls or institutional logins, share credentials, evade controls, retrieve pirated copies, or label unverified mirrors as legal open access. If no open version exists, show `RESTRICTED` or `ABSTRACT_ONLY`.

Unpaywall and arXiv are queried only by exact identifiers attached to the canonical paper. Provider responses must echo/match that DOI or arXiv ID before their locations are considered. Providers are isolated: one source's failure produces bounded warnings and cannot erase a verified location from another source. A 24-hour cache reduces repeated provider calls, and a total outage may return an explicitly labelled stale result.

Normal refresh requests reuse the cache. Forced refresh is an explicit bypass protected by a PostgreSQL-backed, per-paper cooldown (`openscholar.access.force-refresh-cooldown`, five minutes by default). Early repeats fail with `429 ACCESS_REFRESH_RATE_LIMITED` and a bounded `Retry-After` value instead of creating additional provider traffic.

The local server binds to loopback by default. Any remote/container exposure must deliberately change `SERVER_ADDRESS` and add authentication plus aggregate/principal rate limiting before public use; the per-paper cooldown is not a substitute for those hosted controls.

## Privacy and retention

- Search history and notes are private by default.
- Provide export/deletion before multi-user launch.
- Minimize provider response retention.
- Avoid sending private user data to providers.
- Define retention periods for audits and diagnostics.

## Security verification

- Dependency, secret, static-analysis, and container scans.
- Authorization tests for every user-owned resource.
- SSRF, redirect, decompression-bomb, and oversized-response tests.
- Invalid Origin, wrong audience/scope, expired-token, and cross-user tests.
- MCP conformance and negative-schema tests.
- SBOM generation for releases.

## Review before public hosting

- Provider terms and attribution.
- Privacy/export/deletion flows.
- Document retention matrix by source/licence.
- Authentication threat model and abuse policy.
- Incident response and credential rotation.

This is an engineering policy, not legal advice. Commercial deployment should receive qualified legal review.
