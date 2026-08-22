# Product Requirements

## Problem statement

Research discovery is fragmented across indexes, journals, preprint archives, and university repositories. The same work may appear in several forms, access status is often unclear, and repeated searches rebuild the same result set. Users need one place to discover relevant work, identify legal full-text versions, read and organize it, and reuse that knowledge through both a web application and AI agents.

## Product principles

1. **Legal access first:** never imply that indexed means downloadable.
2. **Evidence over fluency:** show provenance, identifiers, and source links.
3. **Local reuse:** persisted results should improve future searches.
4. **Explainable ranking:** expose why a result was included.
5. **Partial resilience:** preserve useful metadata when another enabled provider fails or is inapplicable.
6. **Safe agent access:** tools are narrow, typed, auditable, and read-first.
7. **User control:** saving, opening external documents, summarizing, and deleting are explicit actions.

## MVP functional requirements

### Search

- Accept a free-text topic between 3 and 500 characters.
- Support publication-year range, document type, open-access-only, language, minimum-citation, and maximum-result filters. A source/provider filter remains planned.
- Return local results immediately when cache coverage and freshness meet policy.
- Query enabled providers when local coverage is insufficient.
- Return partial results with provider warnings rather than failing the entire request.
- Preserve the exact user query and a normalized fingerprint that also reflects filters and the enabled-provider set.

### Paper records

- Display title, abstract when available, authors, year, venue, type, citation count, identifiers, and source provenance. Canonical topic fields remain planned.
- Reconcile multiple provider records into one canonical paper.
- Preserve all known versions and provider-specific identifiers.
- Mark metadata as missing rather than fabricating it.

### Full-text availability

- Classify locations as `OPEN_PDF`, `OPEN_LANDING_PAGE`, `REPOSITORY_COPY`, `PREPRINT`, `ABSTRACT_ONLY`, `RESTRICTED`, `UNKNOWN`, or `UNAVAILABLE`. Repository-copy and preprint classifications require verified links plus corresponding provider host/source evidence; either may carry a verified PDF or landing-page-only link.
- Prefer a verified open version without hiding the canonical publisher page.
- Show source, host type, licence if known, and last verification time.
- Never attempt to download a restricted version.

### Library

- Create, rename, and delete collections.
- Add and remove papers from collections.
- Store reading status: unread, reading, or completed.
- Export selected papers as BibTeX and CSL-JSON.
- Persist personal notes in a later MVP increment if schedule permits.

### Reader

- Render PDFs when cross-origin access and licence rules permit.
- Fall back to opening the source landing page when embedding is not possible.
- Show document provenance and access classification in the reader.
- Do not proxy arbitrary user-provided URLs.

### MCP

- Expose search, paper details, stored full-text availability, saved-library lookup, and citation-export tools.
- Return stable internal paper IDs plus DOI/arXiv identifiers when present.
- Return JSON-compatible structured results.
- Keep collection mutations outside the current tool set; any future write tools require a separate capability, authentication, and host confirmation.

## Non-functional requirements

### Performance targets

- Cached search p95: under 500 ms on the local stack. The checked-in harness has a passing 40-sample reference run at 6.944 ms; see [Local performance evidence](PERFORMANCE_EVIDENCE.md).
- First uncached partial results: under 4 seconds when at least one provider is healthy.
- Each discovery adapter's outbound whole-exchange deadline: 10 seconds by default, covering request transmission, response headers, and response-body consumption.
- Same-instance search-coordination acquisition limit: 12 seconds by default; this is not an end-to-end request target.
- Search application-execution deadline: 18 seconds by default across REST and MCP search use cases.
- Full multi-provider fan-out target: 10 seconds by default; transport parsing/serialization and socket-lifetime deadlines remain planned.
- Cached paper detail p95: under 300 ms. The same reference run measured 7.305 ms.

### Reliability

- One provider failure must not discard successful results from other providers.
- Every outbound request has a timeout. Discovery whole-exchange deadlines do not include local coordination, database or persistence work, or transport serialization. Search coordination separately bounds only JVM-local lock acquisition to 12 seconds by default; it neither starts duplicate work after timeout nor cancels the leader. A timed-out caller reuses an exact owner-scoped snapshot when available and otherwise returns retryable `SEARCH_COORDINATION_TIMEOUT`; interruption is reported separately as retryable `SEARCH_COORDINATION_INTERRUPTED`. The shared 18-second execution deadline covers validated `search`, `next`, and `get` application work and returns retryable `SEARCH_DEADLINE_EXCEEDED` when it fires first or `SEARCH_EXECUTION_INTERRUPTED` for caller/server interruption. Deadline expiration is terminal, performs no new stale fallback, and may not stop JDBC persistence already in progress; it may later commit. These public errors omit `Retry-After`. arXiv pacing, access-refresh cooldowns, cache reuse, partial discovery results, and upstream `429` metadata are implemented; client-disconnect/MCP-notification cancellation, transport-level deadlines, general interactive retries, and provider concurrency budgets remain planned. Durable refresh jobs have a separate bounded retry/backoff policy.
- Durable `SEARCH_METADATA` and `PAPER_ACCESS` jobs are retryable operational records, not MCP job handles. Search-job visibility follows the target snapshot owner; shared-catalog access jobs are visible to all `openscholar.jobs` principals.
- Search persistence is idempotent.

### Accessibility and privacy

- Target WCAG 2.2 AA and keyboard-operable controls.
- Local mode runs without analytics and uses one fixed bootstrap owner.
- Hosted OIDC mode isolates libraries and search snapshots by the internal user derived from validated issuer+subject. Notes remain planned.
- No-store personal-data export and confirmed account deletion are implemented. Shared canonical/provider/access records remain, and a later valid token reprovisions an empty internal user.
- Providers receive only query data required for the search.

## Acceptance scenarios

### Cached exact search

Given a fresh stored snapshot for “multi-agent reinforcement learning in healthcare,” when the user repeats the same normalized query and filters, the system returns the exact snapshot without contacting an external provider. Semantic reuse for related wording remains planned.

### Paywalled canonical version with open repository copy

Given a DOI whose publisher page is restricted and whose repository copy is legal and open, the paper page shows both locations and recommends the open copy.

### No open full text

Given a discoverable but restricted paper, the system displays metadata and the canonical landing page, marks it `RESTRICTED`, and performs no PDF download.

### Provider outage

Given a paper with both DOI and arXiv identifiers, when Unpaywall times out while the exact arXiv access lookup succeeds, access resolution returns the verified arXiv result with a machine-readable provider warning.

### MCP search

Given a valid `search_research` tool call, the server returns structured results with identifiers, provider-reported access hints, ranking reasons, and source provenance.

## Deferred or external decisions

- Real hosted OIDC provider/client registration, grants, interoperability, and key rotation. The application-side resource server and BFF are implemented.
- Whether to evaluate a hosted embedding provider; the current default-off local Qwen/Ollama profile is already pinned and implemented.
- Commercial versus non-commercial deployment.
- Retained-PDF storage policy by licence family.
- Open-source licence for this repository.
