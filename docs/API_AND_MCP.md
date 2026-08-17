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
GET  /api/v1/searches/{searchId}
POST /api/v1/searches/{searchId}/refresh
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

The implemented backend returns `201 Created` for a newly fetched immutable snapshot and `200 OK` for an exact cache hit or stale fallback. `GET /api/v1/searches/{searchId}` reads the stored snapshot without contacting OpenAlex. Current cache dispositions are `EXACT_HIT`, `MISS_FETCHED`, `STALE_REFRESHED`, `FORCED_REFRESH`, and `STALE_FALLBACK`.

Open-access flags and PDF URLs in this search response are explicitly provider-reported, not independently verified legal-access claims. Verification arrives with the access-resolution milestone.

### Papers and access

```http
GET  /api/v1/papers/{paperId}
GET  /api/v1/papers/{paperId}/versions
GET  /api/v1/papers/{paperId}/related
GET  /api/v1/papers/{paperId}/citation?format=bibtex
POST /api/v1/papers/{paperId}/access/verify?forceRefresh=false
```

`GET /versions` and `POST /access/verify` are implemented; paper detail, related-paper, and citation endpoints remain planned. `GET /versions` reads only the stored resolution. Before the first resolution it returns `NOT_YET_RESOLVED` with no locations and does not contact Unpaywall or arXiv.

`POST /access/verify` performs bounded synchronous resolution. With the default `forceRefresh=false`, it returns a fresh cached result when available. `forceRefresh=true` bypasses that fresh-cache check; refreshing an existing resolution reports `FORCED_REFRESH`, while a first resolution remains `RESOLVED`. Forced refreshes are protected per paper by `openscholar.access.force-refresh-cooldown` (five minutes by default). A repeated request inside that window returns `429 ACCESS_REFRESH_RATE_LIMITED`, a `retryAfterSeconds` problem property, and a matching `Retry-After` header. The other access cache dispositions are `CACHE_HIT`, `REFRESHED`, `STALE_FALLBACK`, `NO_SUPPORTED_IDENTIFIER`, and `NOT_YET_RESOLVED`.

Access resolution uses exact identifiers already attached to the canonical paper:

- Unpaywall receives one normalized DOI at `GET /v2/{doi}`. Its backend contact email is optional application configuration; without it, coverage reports `NOT_CONFIGURED` and other providers can still complete.
- arXiv receives one canonical ID through `id_list` with `max_results=1`. The returned entry and its access paths must match the requested identifier; version suffixes are honored when explicitly requested.

Results contain overall access status, freshness, provider coverage, warnings, a best-location ID, and verified version records with source, host/version classification, licence when reported, landing/PDF link, and verification timestamps. All current locations use `LINK_ONLY`: the API returns links but never PDF bytes.

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
```

Notes/highlights follow after the core library.

### Operations

```http
GET /actuator/health
GET /actuator/info
```

Metrics and detailed health components require administrative authentication.

## Error model

REST uses RFC 9457 Problem Details with a stable error code, safe detail, correlation ID, validation violations, retryability, and optional retry-after. Stack traces and credentials never appear.

The current search slice implements stable validation, not-found, and provider-unavailable codes. Correlation IDs are added with the observability milestone.

## MCP transport

The primary transport is stateless Streamable HTTP through `spring-ai-starter-mcp-server-webmvc`, expected at `/mcp`. Legacy SSE is not a design target. STDIO is an optional local profile after HTTP conformance passes.

The initial server advertises MCP revision `2025-11-25`, which is the revision supported by Spring AI 2.0/MCP Java SDK 2.0. Newer Tasks and MCP Apps capabilities stay deferred until official Java/Spring support is available.

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
  "limit": 20,
  "forceRefresh": false
}
```

Output includes canonical paper IDs, bibliographic metadata, best access status, ranking reasons, provider provenance, and warnings.

### `get_paper_details`

Accepts exactly one internal paper ID, DOI, arXiv ID, or OpenAlex ID. Returns canonical metadata, identifiers, versions, provenance, and freshness.

### `get_legal_full_text`

Returns known legal locations and classifications. It never returns restricted file bytes or attempts publisher authentication.

### `build_reading_list`

Accepts topic, user goal, experience level, year/type filters, and maximum items. The initial deterministic selection balances relevance, recency, citation impact, access, and diversity and explains each choice.

### `search_saved_library`

Searches cached/saved papers with lexical retrieval initially and hybrid retrieval later. It is principal-scoped in multi-user mode.

### `export_citations`

Accepts a bounded list of paper IDs and returns BibTeX or CSL-JSON.

### Long-running jobs

Stateless tools that exceed interactive deadlines use:

```text
start_research_job
get_research_job
cancel_research_job
```

Job ownership is authorization-enforced, and result retention is bounded.

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
- Per-principal and per-provider rate limits.
- Correlation/audit IDs for every call.
- Deadlines and cancellation propagate where supported.
- Structured errors distinguish invalid input, not found, restricted, provider unavailable, and deadline exceeded.

## Compatibility testing

- Official MCP conformance suite pinned to the supported revision.
- Tool discovery, calls, invalid schemas, cancellation, authentication, timeouts, partial results, and shutdown.
- Supported protocol revision recorded in `/actuator/info` and release notes.
