# Threat Model

## Scope and security posture

This model covers the Next.js UI, Spring Boot REST/MCP backend, PostgreSQL, external scholarly providers/document hosts, reverse proxy, CI/CD, operators, and optional monitoring in the hosted portfolio design. PDF bytes remain browser-to-source and are not retained or proxied by OpenScholar.

Local mode remains a loopback, fixed-owner development profile with a separate MCP key. Hosted mode is implemented and tested synthetically as a stateless OIDC resource server plus a Next.js authorization-code/PKCE BFF: JWT issuer/audience/scope checks and issuer+subject ownership protect searches and libraries. This is not evidence of interoperability with a real identity provider or public-production readiness. Public exposure remains blocked on the external launch gates in `DEPLOYMENT.md`.

## Assets

- User identity, private search topics, saved papers, collections, reading status, and tags.
- Account-neutral PWA shell assets in browser CacheStorage; no user record or document byte is intentionally cached there.
- Canonical metadata, provenance, access-resolution evidence, cached provider responses, and job state.
- Database/MCP/provider/OIDC credentials, signing keys, ACME account state, and backup decryption identities.
- Application/container source, dependencies, images, SBOMs, release attestations, and CI credentials.
- Availability, provider quotas, legal access policy, audit evidence, and operator trust.

OpenScholar does not currently retain PDF files. A legal external link or successful PDF probe is not a grant to store, redistribute, or mine the work.

## Trust boundaries and data flow

1. An untrusted browser or MCP client crosses the TLS edge into Caddy.
2. Hosted browser login crosses to an external OIDC authorization server. Next.js validates the callback and ID token, then stores tokens in an encrypted HttpOnly cookie; the browser never receives provider/database credentials or a JavaScript-readable access token.
3. Browser application and `/api/*` BFF routes go to Next.js. Next.js uses a server-only backend origin and attaches the user's access token. Direct `/api/v1/*` and `/mcp` callers reach Spring Boot through Caddy and must present their own scoped bearer token.
4. Spring Boot validates the token, derives the current internal user from issuer+subject, reads/writes PostgreSQL, and makes bounded outbound calls to configured scholarly providers or provider-supplied access locations. Inbound tokens are not forwarded.
5. A user-selected verified PDF URL is fetched directly by the isolated browser reader from the external document host.
6. CI obtains source and dependencies, builds artifacts, generates an SBOM, and publishes scan findings. This boundary can affect every runtime asset.
7. Operators and backup/monitoring systems cross a privileged administrative boundary not exposed through Caddy.

## Threats, controls, and remaining work

| Threat | Implemented or templated controls | Residual risk / required decision |
|---|---|---|
| Credential theft or forwarding | Backend-only provider credentials, file-backed database/session/OIDC-client secrets, sealed HttpOnly browser session, log-redaction policy, inbound tokens never forwarded, read-only filesystems | Choose secret manager/workload identity, prove signing/client/session-key rotation and revocation, audit CI/host access |
| Identity-provider or BFF compromise | JWT signature/expiry/issuer/audience checks; route scopes; authorization code + PKCE/state/nonce; bounded JWKS/token responses; ID-token signature/claim validation; exact-Origin checks on unsafe BFF calls | Register and test a real provider, restrict redirect/logout URIs and grants, define administrator policy, monitor provider/session compromise, retest on key rotation |
| Service-worker cache leaks account or document data | Strict same-origin static-asset allowlist; network-first fixed install assets; 96-entry runtime-static cap; successful navigations remain network-only while refreshing the neutral fallback; API/auth/MCP/export/document/range/authorization exclusions; private/no-store/Vary-star rejection; exact-worker/app-prefixed cleanup only | Explicit offline metadata packs require a separate storage, encryption, ownership/logout, deletion, quota, and synchronization design |
| Cross-user data access / IDOR | Search snapshots and libraries use issuer+subject-derived ownership; local-catalog candidates require a paper to be visible through that owner's prior snapshot or saved collection; other-owner identifiers return not found; search-refresh visibility follows snapshot ownership; privacy export/delete are current-principal operations; negative authorization tests exist | Shared `PAPER_ACCESS` jobs are visible/retryable to every `openscholar.jobs` principal; shared canonical/access records remain by design; review every new owned resource and local ranking join |
| MCP tool abuse | Small typed tool surface, local bearer or hosted audience/scope-checked JWT, protected-resource metadata, exact-Origin checks, bounded request/results, per-address or hashed-principal rate limits, no arbitrary SQL/shell/URL tools | In-memory limits are per instance with no aggregate budget; add edge/cluster abuse controls, trusted-proxy review, and disconnect/notification cancellation support |
| SSRF / DNS rebinding | Provider-derived URL candidates only, HTTPS/default-port policy, DNS/address rejection, redirect revalidation, bounded no-credential probes | Maintain redirect/DNS regression tests and outbound network policy; review every new provider/fetch feature |
| Malicious provider payload or decompression bomb | Bounded response streams/timeouts, strict parsers/mappers, isolated partial failure, no unrestricted retry | Fuzz high-risk parsers, monitor provider schema drift, add egress allow-list where feasible |
| Prompt injection in research content | Papers/abstracts/PDFs are treated as data and current tools do not execute their instructions | Any later LLM summarizer needs source delimiting, citation validation, tool isolation, and mutation confirmation |
| Malicious PDF/browser exploit | Fresh verified link selection, isolated local PDF.js worker, byte/page/canvas/text limits, no backend proxy or retention, enforced and edge-mirrored CSP with drift tests | Browser/PDF.js patch cadence, static CSP retains Next.js-compatible `unsafe-inline` and WebAssembly-only `wasm-unsafe-eval`, CORS/source failure handling, future nonce redesign and upload scanning if storage is added |
| Paywall/copyright bypass | Supported APIs, link-only records, exact identifier matching, no auth/cookie forwarding, no PDF retention | Provider/licence review, attribution, takedown process, source-specific retention matrix, qualified legal advice |
| Query/privacy leakage to providers | Backend chooses bounded metadata queries; explicit LOCAL mode sends no provider request; actual execution source is returned independently from AUTO caller intent; provider payload retention is minimized; owner-scoped no-store export and confirmed account deletion are implemented | AUTO and ONLINE may send the topic to enabled providers; approve privacy notice, purpose/retention periods, jurisdiction/vendor review, backup deletion treatment, and shared-catalog policy; avoid sending private notes/full text |
| SQL injection / corrupt persistence | Typed repositories/parameters, validation, Flyway constraints, immutable snapshots | Authorization is separate from injection safety; maintain database least privilege and restore/invariant tests |
| Denial of service / quota exhaustion | Payload/body/time budgets, bounded concurrent fan-out/results, partial failure, cache, cooldown, per-principal MCP limits, internal database network | Aggregate/cluster limits, concurrency/load tests, upstream edge controls, quota alerts and abuse policy |
| Supply-chain compromise | Application/tooling lockfiles, checksum-verified Maven Wrapper, reviewed immutable action/image pins with drift and resolved-image-policy validation, baked frontend entrypoint, project-owned scratch-runtime Caddy/blackbox builds, non-root multi-stage images, time-bounded component-scoped VEX, Dependabot, dependency review, CodeQL, Trivy SARIF and CycloneDX SBOM workflow | CI-build/publish/rescan/sign/attest all four project-owned runtime images and verify them at deployment; protect environments; independently review/renew exceptions and patch SLA; treat the checksum-pinned but unsigned blackbox upstream input as a provenance limitation |
| Container escape / lateral movement | Read-only root filesystems, dropped capabilities where compatible, `no-new-privileges`, non-root app/proxy/monitoring, segmented internal networks | Multi-homed-service reachability, host/runtime patching, seccomp/AppArmor policy, rootless-runtime feasibility, external penetration review |
| Database loss/ransomware | Persistent database volume, safe custom-format backup/restore scripts, checksum and optional age encryption | Managed PITR/HA decision, off-host immutable copies, KMS/key escrow, RPO/RTO and recurring restore drills |
| Monitoring failure / false confidence | Private backend Prometheus scrape, application-meter alerts, internal/public blackbox probes, missing-series/exporter/config alerts, no public monitoring route | Configure/test real receiver; independent uptime check, retention, and alert ownership |
| TLS/DNS/edge compromise | Caddy automatic HTTPS, HSTS/security headers, certificate-expiry probe, no direct service ports | Domain registrar/MFA/DNSSEC decision, ACME account backup, edge access policy, avoid HSTS until domain impact is reviewed |
| Unsafe migration/rollback | Flyway-only schema, backup requirement, immutable image guidance, restore runbook | Per-release compatibility matrix, migration rehearsal at production scale, explicit rollback/RTO owner |
| Insider/operator misuse | Narrow documented commands, no automatic backup deletion, restore confirmation/refusal on running clients | Separate duties, MFA/JIT access, audited sessions, break-glass process, retention and incident governance |

## Abuse cases to test before hosting

- Missing, expired, wrong-issuer, wrong-audience, and insufficient-scope tokens for REST and MCP.
- User A requesting/exporting/deleting User B resources by every identifier and pagination path.
- User A using LOCAL/AUTO fallback, filters, or cursors to infer a canonical paper visible only to User B.
- User A resolving User B's DOI, arXiv, or OpenAlex reference through REST or MCP and comparing not-found behavior with a globally absent identifier.
- Forged forwarding headers, Origin variants, oversized/chunked MCP payloads, expensive-query bursts, and rate-limit identity churn.
- Provider URLs resolving to private/link-local addresses initially or after redirect/DNS change.
- Huge/malformed JSON, Atom, compressed responses, redirect chains, slow bodies, malicious metadata, and partial provider outages.
- PDF load/render bombs, embedded actions, CORS failure, stale access locations, and source substitution.
- Backup corruption, wrong key, wrong host/project selection, active connections, partial restore, lost monitoring, and expired TLS.
- Compromised dependency/action/image simulation, leaked test credential, revoked provider key, and vulnerable release rollback.

## Security invariants

- No application endpoint fetches an arbitrary user-supplied URL.
- No inbound bearer token, cookie, or provider credential is forwarded to a document host.
- No PDF/document byte is retained unless a later source-specific policy and schema explicitly permit it.
- No user-owned object is authorized solely because its UUID is difficult to guess.
- No destructive administration operation is exposed as an MCP tool.
- No public management, Prometheus, Alertmanager, PostgreSQL, backend, or frontend port bypasses the edge.
- No release is considered recoverable until its backup has been restored and checked independently.

Account deletion removes the current principal's searches, search-refresh jobs, collections, memberships/tags, and hosted internal user row. It does not delete shared canonical/provider/access records or global access-refresh jobs, and it does not revoke the account at the identity provider. A later valid token for the same issuer+subject provisions a new empty internal user.

## Review triggers

Revisit this model when changing the identity provider/authentication design, adding a provider, caching user metadata in the browser, changing the service-worker allowlist, adding document retention/upload, LLM summarization, a write-capable MCP tool, collaboration/tenancy, object storage, a new egress path, a worker service, public metrics, or a new deployment environment. Record the reviewer, date, changed boundaries, tests, accepted residual risks, and expiry of each temporary exception.
