# REST and MCP Contracts

## Principles

- REST and MCP delegate to the same application use cases.
- Contracts use stable IDs/enums, not persistence entities.
- Inputs are bounded and validated.
- Results include provenance, freshness, and warnings.
- Useful partial provider results survive individual provider failures.
- REST versioning begins at `/api/v1`.

## Planned REST endpoints

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
  "forceRefresh": false
}
```

Responses include search ID, query fingerprint, cache disposition, freshness, provider coverage/warnings, results, scores, ranking reasons, and pagination.

### Papers and access

```http
GET  /api/v1/papers/{paperId}
GET  /api/v1/papers/{paperId}/versions
GET  /api/v1/papers/{paperId}/related
GET  /api/v1/papers/{paperId}/citation?format=bibtex
POST /api/v1/papers/{paperId}/access/verify
```

Access verification is rate-limited and can return an async job when it cannot finish within the interactive deadline.

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
