# REST and MCP Contracts

## Principles

- REST and MCP delegate to the same application use cases.
- Contracts use stable IDs/enums, not persistence entities.
- Inputs are bounded and validated.
- Results include provenance, freshness, and warnings.
- Useful partial provider results survive individual provider failures.
- REST versioning begins at `/api/v1`.

## REST endpoints

### Search

```http
POST /api/v1/searches
POST /api/v1/searches/{searchId}/next
GET  /api/v1/searches/{searchId}
```

Example:

```json
{
  "query": "multi-agent reinforcement learning in healthcare",
  "filters": {
    "yearFrom": 2023,
    "yearTo": 2026,
    "documentTypes": ["ARTICLE", "PREPRINT", "THESIS"],
    "openAccessOnly": true,
    "minimumCitations": 0,
    "languages": ["en"]
  },
  "pageSize": 20,
  "cursor": "opaque cursor returned by a previous response, or omit",
  "forceRefresh": false
}
```

Responses include search ID, query fingerprint, cache disposition, freshness, provider coverage/warnings, results, scores, ranking reasons, and pagination.

The implemented backend returns `201 Created` for a newly fetched immutable snapshot and `200 OK` for an exact cache hit or stale fallback. `GET /api/v1/searches/{searchId}` reads the stored snapshot without contacting OpenAlex. `POST /api/v1/searches/{searchId}/next` derives the continuation from that immutable snapshot: it reuses the stored query, filters, and page size, replaces the current cursor with the stored opaque `nextCursor`, and disables forced refresh. A newly fetched continuation returns `201`; replaying a fresh continuation returns `200 EXACT_HIT`; a missing source snapshot returns `404 SEARCH_NOT_FOUND`; and a snapshot without another page returns `409 SEARCH_PAGE_EXHAUSTED`. Current cache dispositions are `EXACT_HIT`, `MISS_FETCHED`, `STALE_REFRESHED`, `FORCED_REFRESH`, and `STALE_FALLBACK`.

Open-access flags and PDF URLs in this search response are explicitly provider-reported, not independently verified legal-access claims. Use the implemented `POST /api/v1/papers/{paperId}/access/verify` flow to resolve and independently verify stored legal-access locations.

### Papers and access

```http
GET  /api/v1/papers/{paperId}
GET  /api/v1/papers/{paperId}/versions
GET  /api/v1/papers/{paperId}/related
GET  /api/v1/papers/{paperId}/citation?format=bibtex
POST /api/v1/papers/{paperId}/access/verify?forceRefresh=false
```

All five paper/access routes above are implemented. `GET /versions` reads only the stored resolution. Before the first resolution it returns `NOT_YET_RESOLVED` with no locations and does not contact Unpaywall or arXiv.

`GET /papers/{paperId}` is a database-only canonical-details read. It returns canonical bibliographic fields, paper-specific credited author names in provider order, every identifier, citation-count freshness, metadata completeness/freshness, deterministic record-level provenance, and a compact stored-access summary. Provenance identifies associated provider records and the record selected for canonical authorship; it is not field-level attribution. Source URLs are restricted to absolute HTTP(S) records and returned without query strings or fragments. Raw provider metadata and provider-reported landing/PDF links are excluded, while `/versions` remains the contract for verified access locations. An unknown UUID returns `404 PAPER_NOT_FOUND`; a malformed UUID returns the safe `400 INVALID_REQUEST` problem.

`GET /papers/{paperId}/related` is an experimental live, database-only lookup over the current canonical catalog. It accepts `limit=1..25` (default 10), excludes the source paper, and returns canonical result metadata with a normalized PostgreSQL full-text score and `POSTGRES_FULL_TEXT` ranking reason. The baseline derives up to 16 OR-connected lexemes from the source title and ranks a weighted title/abstract/venue search vector. It makes no provider call and deliberately returns no search snapshot ID, cache disposition, provider coverage, or invented local-provider provenance. The current text-search configuration is English; multilingual configuration and hybrid vector retrieval remain evaluation-driven follow-ups.

`GET /citation` accepts `format=bibtex` (the default) or `format=csl-json`. It returns a raw UTF-8 citation document rather than the normal JSON response envelope, with `Content-Disposition: attachment` and a deterministic filename. BibTeX uses `application/x-bibtex`; CSL-JSON uses `application/vnd.citationstyles.csl+json` and a one-item top-level array, as required by the CSL data schema. The stable citation key is based only on the canonical paper UUID.

Citation export is read-only and never contacts a research or access provider. It emits the best currently stored metadata, preserves Unicode, represents each author as a literal name, prefers a normalized DOI over an arXiv URL, and omits unavailable or invalid fields rather than guessing them. The current canonical model does not yet distinguish author name parts, publisher/school/institution, volume, issue, pages, article number, edition, ISBN/ISSN, or degree level; those richer fields are a catalog-hardening follow-up. `404 PAPER_NOT_FOUND` identifies an unknown paper, while an unsupported format returns `400 UNSUPPORTED_CITATION_FORMAT`.

`POST /access/verify` performs bounded synchronous resolution. With the default `forceRefresh=false`, it returns a fresh cached result when available. `forceRefresh=true` bypasses that fresh-cache check; refreshing an existing resolution reports `FORCED_REFRESH`, while a first resolution remains `RESOLVED`. Forced refreshes are protected per paper by `openscholar.access.force-refresh-cooldown` (five minutes by default). A repeated request inside that window returns `429 ACCESS_REFRESH_RATE_LIMITED`, a `retryAfterSeconds` problem property, and a matching `Retry-After` header. The other access cache dispositions are `CACHE_HIT`, `REFRESHED`, `STALE_FALLBACK`, `NO_SUPPORTED_IDENTIFIER`, and `NOT_YET_RESOLVED`.

Access resolution uses exact identifiers already attached to the canonical paper:

- Unpaywall receives one normalized DOI at `GET /v2/{doi}`. Its backend contact email is optional application configuration; without it, coverage reports `NOT_CONFIGURED` and other providers can still complete.
- arXiv receives one canonical ID through `id_list` with `max_results=1`. The returned entry and its access paths must match the requested identifier; version suffixes are honored when explicitly requested.

Results contain overall access status, freshness, provider coverage, warnings, a best-location ID, and verified version records with source, host/version classification, licence when reported, landing/PDF link, and verification timestamps. All current locations use `LINK_ONLY`: the API returns links but never PDF bytes.

The web reader can load a fresh, verified `OPEN_PDF` HTTPS location directly from the source into PDF.js in the user's browser. This is not an API byte endpoint: Next.js and Spring Boot do not fetch or relay the document, and a source without compatible browser CORS headers falls back to its external link.

Access results remain fresh for 24 hours by default. The cache carries a fingerprint of the paper's DOI, arXiv ID, and abstract availability, so later catalog enrichment invalidates an incompatible negative result. Provider failures are isolated. If no provider can complete a refresh—or reported candidates cannot be safely re-verified—and a compatible older resolution exists, the API returns it unchanged as `STALE_FALLBACK` with machine-readable warnings rather than renewing stale links as fresh.

The implemented access-status vocabulary is `OPEN_PDF`, `OPEN_LANDING_PAGE`, `REPOSITORY_COPY`, `PREPRINT`, `ABSTRACT_ONLY`, `RESTRICTED`, `UNKNOWN`, and `UNAVAILABLE`. Link verification accepts only provider candidates and validates every redirect before a location can become active.

Current access verification returns `404 PAPER_NOT_FOUND` for an unknown canonical paper, `429 ACCESS_REFRESH_RATE_LIMITED` for a forced-refresh cooldown violation, and `503 ACCESS_PROVIDERS_UNAVAILABLE` only when no provider can complete and there is no stored fallback. The 503 problem preserves aggregate retryability and an upstream `Retry-After` when available. An asynchronous access job is deferred until interactive provider coverage needs it.

### Collections

```http
GET    /api/v1/collections
POST   /api/v1/collections
GET    /api/v1/collections/{collectionId}
PATCH  /api/v1/collections/{collectionId}
DELETE /api/v1/collections/{collectionId}
PUT    /api/v1/collections/{collectionId}/papers/{paperId}
DELETE /api/v1/collections/{collectionId}/papers/{paperId}
PATCH  /api/v1/collections/{collectionId}/papers/{paperId}
GET    /api/v1/library/papers
POST   /api/v1/citations/export
```

These endpoints are implemented for the fixed local development user, and every collection read/write is owner-scoped. Collection names contain 1–120 characters; descriptions are optional and bounded to 1,000 characters. `PUT` creates or replaces a paper membership, while `PATCH` requires an existing membership. A membership records `UNREAD`, `READING`, or `COMPLETED` and zero to ten canonical tags. Tags are trimmed, internal whitespace is collapsed, values are lowercased with locale-independent rules, and each tag is limited to 40 characters. Deleting a paper membership is idempotent; an unknown or unauthorized collection returns `404 COLLECTION_NOT_FOUND`.

`GET /api/v1/library/papers` supports bounded `q`, `collectionId`, `readingStatus`, `tag`, `page`, and `size` parameters. Lexical matching covers collection name, paper title, abstract, venue, and credited author name. `%`, `_`, and `\` in `q` are treated literally rather than as SQL wildcards. Results are deterministic and retain one row per collection membership; a canonical paper saved in two collections therefore appears twice.

`POST /api/v1/citations/export` accepts one to 100 distinct canonical paper UUIDs plus `bibtex` or `csl-json`. It preserves caller order, fails the whole request if any paper is unknown, and returns a raw UTF-8 attachment with the same media types and stored-metadata policy as the single-paper endpoint. Duplicate IDs, empty/oversized lists, and malformed inputs are rejected rather than silently normalized.

Notes/highlights follow after the core library.

### Operations

```http
GET /actuator/health
GET /actuator/info
```

Only `health` and `info` are exposed. Health details are never shown, and the Micrometer MCP metrics are recorded internally without exposing an Actuator metrics endpoint. Administrative authentication is required before broader diagnostics can be exposed in a hosted deployment.

## Error model

REST uses RFC 9457 Problem Details with a stable error code, safe detail, validation violations, retryability, and optional retry-after. Stack traces and credentials never appear.

The current search slice implements stable validation, not-found, provider-unavailable, coordination-failure, and execution-deadline codes. A caller that cannot acquire its JVM-local coordination stripe within the configurable 12-second default rechecks the latest exact snapshot. A normal caller can reuse a newly fresh snapshot as `EXACT_HIT`; otherwise an available snapshot is returned as `STALE_FALLBACK` with `SEARCH_COORDINATION_TIMEOUT` in `warnings`. If no snapshot exists, REST returns retryable `503 SEARCH_COORDINATION_TIMEOUT` without `Retry-After`. An interrupted wait restores the thread interruption state and returns retryable `503 SEARCH_COORDINATION_INTERRUPTED`, also without `Retry-After`; interruption does not perform the timeout snapshot fallback.

The outer search execution deadline defaults to 18 seconds and applies to `search`, `next`, and `get` from validated application dispatch through `SearchView` construction. If it expires first, REST returns retryable `504 SEARCH_DEADLINE_EXCEEDED` without `Retry-After`; caller or server interruption returns retryable `503 SEARCH_EXECUTION_INTERRUPTED`. Deadline expiration interrupts the dedicated virtual-thread worker and is terminal: no new stale-snapshot fallback starts after the budget is exhausted. OpenAlex and coordination failures retain their existing codes and fallback behavior when they complete first. Persistence already in progress at the boundary may continue and commit, so a new immutable snapshot may later become visible, because JDBC interruption is not guaranteed.

## MCP transport

The implemented transport is stateless Streamable HTTP through `spring-ai-starter-mcp-server-webmvc` at `/mcp`. It is synchronous, advertises tools only, and does not expose legacy SSE, resources, prompts, completions, sampling, elicitation, or STDIO.

Spring AI 2.0 and MCP Java SDK 2.0 negotiate their supported revisions through a maximum tested revision of `2025-11-25`. The server does not claim newer Tasks or MCP Apps capabilities. Every request requires the configured local bearer key; present `Origin` headers must exactly match the configured allow-list.

The initial adapter registers five read-oriented tools. Search may contact OpenAlex and update internal metadata/search caches. Its OpenAlex exchange has a configurable 10-second default deadline covering request transmission, response headers, and streamed response-body consumption. A separate configurable 12-second default bounds only acquisition of the JVM-local search-coordination stripe. The shared 18-second execution deadline bounds the application work for `search_research` as well as REST search operations. It excludes MCP input parsing/schema validation, tool DTO/framework serialization, and socket lifetime. The other four tools are database-only; none mutates user collections or reading state. MCP-specific search/library pages and citation batches are capped at 25 items.

## MVP MCP tools

### `search_research`

Finds cached and provider-backed research.

```json
{
  "topic": "string, 3..500 chars",
  "yearFrom": 2020,
  "yearTo": 2026,
  "documentTypes": ["ARTICLE", "PREPRINT", "THESIS"],
  "openAccessOnly": true,
  "minimumCitations": 0,
  "languages": ["en"],
  "limit": 20,
  "cursor": "opaque cursor from an earlier response, or omit",
  "forceRefresh": false
}
```

Output includes canonical paper IDs, bibliographic metadata, provider-reported access hints, ranking reasons, provider provenance, cache disposition, freshness, warnings, and the next cursor. Provider-reported PDF links are not verified legal-access claims; use stored access output from `get_legal_full_text` after verification through REST/UI.

### `get_paper_details`

Accepts one canonical OpenScholar UUID and returns canonical metadata, identifiers, ordered authorship, record-level provenance, freshness, and the full stored access resolution, including coverage, warnings, and locations. It never contacts a provider. DOI, arXiv, and OpenAlex identifier resolution remains planned.

### `get_legal_full_text`

Accepts one canonical OpenScholar UUID and returns the stored access classification, provider coverage, warnings, and verified locations. It is database-only and may return `NOT_YET_RESOLVED`; legal verification remains an explicit REST/UI operation because the bounded synchronous provider pipeline can exceed the MCP interactive timeout. It never returns file bytes or attempts publisher authentication.

### `search_saved_library`

Searches the fixed local owner's saved collection memberships using optional lexical text, collection UUID, reading status, normalized tag, page, and size. Results preserve one row per collection membership. The application-service lookup is owner-scoped; authenticated principal propagation replaces the fixed local owner in multi-user mode.

### `export_citations`

Accepts one to 25 distinct canonical paper UUIDs plus `bibtex` or `csl-json`. It preserves caller order, fails atomically for an unknown paper, and returns format, filename, media type, count, and citation content as structured MCP output. It never contacts a provider.

## Deferred MCP tools

`build_reading_list`, provider-backed access verification, collection/note mutations, and job handles are not advertised until their application services, confirmation model, ownership, and timeout behavior are implemented and tested.

### Long-running jobs

Long-running operations will use owned job handles when implemented:

```text
start_research_job
get_research_job
cancel_research_job
```

Owned job handles and their retention/authorization policy remain planned; no job tools are currently advertised.

## Deferred write tools

Collection and note mutation tools are deferred until authentication and host-confirmation behavior are verified. They will be separately advertised so clients can disable writes while retaining discovery.

## Potential MCP resources

- `openscholar://papers/{id}` for canonical metadata.
- `openscholar://collections/{id}` for an authorized reading list.
- `openscholar://searches/{id}` for a saved snapshot.

Resources expose metadata or user-authorized content, never arbitrary URLs or unrestricted filesystem paths.

## Tool safety

- JSON Schema validation and strict bounds.
- Tool descriptions state side effects/access constraints.
- External document text is never interpreted as tool instructions.
- Implemented local MCP limits per server-observed remote address; hosted mode adds aggregate and authenticated-principal limits.
- arXiv has an implemented three-second outbound request gate. OpenAlex uses a configurable 10-second whole-exchange deadline, and Unpaywall uses bounded timeouts; both propagate applicable upstream rate-limit information. Broader per-provider budgets remain planned.
- Search coordination uses a configurable 12-second lock-acquisition limit. Timing out prevents that follower from invoking duplicate work and does not cancel the leader; snapshot fallback is used when available.
- Search application execution uses a configurable 18-second default deadline with interrupting virtual-thread cancellation and cooperative checkpoints. MCP exposes retryable `SEARCH_DEADLINE_EXCEEDED` and `SEARCH_EXECUTION_INTERRUPTED` safe prefixes without guessed retry-after values.
- Every protected MCP response carries a request ID that is also placed in logging context.
- The configured `spring.ai.mcp.server.request-timeout` is 20 seconds, but the stateless MCP Java SDK 2.0 path does not currently enforce it as whole-tool cancellation.
- Client-disconnect propagation, MCP `notifications/cancelled`, request parsing/validation deadlines, framework serialization deadlines, and socket-lifetime enforcement remain follow-ups on the pinned stateless SDK.
- Tool failures use safe text with stable prefixes such as `INVALID_REQUEST`, `PAPER_NOT_FOUND`, `SEARCH_COORDINATION_TIMEOUT`, `SEARCH_COORDINATION_INTERRUPTED`, `SEARCH_DEADLINE_EXCEEDED`, `SEARCH_EXECUTION_INTERRUPTED`, and provider-unavailable codes. Restricted access is a successful access status; dedicated structured tool-error contracts remain planned.

## Compatibility testing

- The pinned official runner's production-applicable `server-initialize` and `tools-list` scenarios pass with `--spec-version 2025-11-25`; its remaining scenarios require a synthetic fixture surface and are not run against the domain server.
- Track the canonical frozen `2025-11-25` requirements set as the official conformance runner evolves.
- Tool discovery, calls, invalid schemas, cancellation, authentication, timeouts, partial results, and shutdown.
- Supported protocol revision recorded in `/actuator/info` and release notes.
