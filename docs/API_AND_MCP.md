# REST and MCP Contracts

## Principles

- REST and MCP delegate to the same application use cases.
- Contracts use stable IDs/enums, not persistence entities.
- Inputs are bounded and validated.
- Results include provenance, freshness, and warnings.
- Useful partial provider results survive individual provider failures.
- REST versioning begins at `/api/v1`.
- Hosted user-owned reads/writes are authorized by the OIDC issuer/subject-derived owner, not by possession of a UUID.

The complete machine-readable REST contract is the checked-in [OpenAPI 3.1 specification](openapi.yaml). It covers every current `/api/v1` operation, hosted scopes and principal visibility, request bounds, success representations, and stable Problem Details responses. The backend CI suite validates that its documented method/path inventory continues to match the REST controllers; run the focused check from `backend/` with `./mvnw -Dtest=OpenApiContractTests test`.

## Authentication modes

Local development keeps REST on the fixed local owner and protects `/mcp` with `MCP_LOCAL_API_KEY`. When `OIDC_SECURITY_ENABLED=true`, Spring Boot becomes a stateless JWT resource server: it validates signature, expiry, issuer, and audience, then applies `openscholar.search`, `openscholar.library`, `openscholar.jobs`, `openscholar.privacy`, `openscholar.mcp`, or `openscholar.ops` by route. Search snapshots and library collections are resolved through the authenticated issuer+subject owner; unauthorized owned identifiers return not found rather than revealing another principal's object.

Hosted MCP publishes protected-resource metadata at `/.well-known/oauth-protected-resource/mcp`. Its `401`/`403` challenges identify that metadata document and the required `openscholar.mcp` scope. The issuer remains the authorization server; OpenScholar does not issue tokens.

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
  "forceRefresh": false,
  "mode": "AUTO"
}
```

`mode` is optional and defaults to `AUTO`:

- `AUTO` keeps the normal provider/cache pipeline and uses the owner-scoped local catalog only when provider-backed results cannot satisfy the request.
- `ONLINE` uses provider-backed fetch/cache behavior and never falls back to local-catalog retrieval. Use `forceRefresh=true` when a live provider call, rather than an exact fresh cache hit, is required.
- `LOCAL` searches previously discovered or saved metadata in PostgreSQL and never contacts a provider. `forceRefresh=true` is invalid in this mode.

Responses include search ID, query fingerprint, the requested mode, actual execution source, cache disposition, freshness, provider coverage/warnings, results, scores, ranking reasons, provenance, and pagination. `executionSource` is one of `PROVIDER_FETCH`, `EXACT_CACHE`, `STALE_CACHE`, or `LOCAL_CATALOG`; it is the authoritative indication of how the response was produced and can differ from `requestedMode=AUTO`.

The implemented backend returns `201 Created` for a newly fetched or locally generated immutable snapshot and `200 OK` for an exact cache hit or stale provider fallback. `GET /api/v1/searches/{searchId}` reads the current owner's stored snapshot without contacting a provider. `POST /api/v1/searches/{searchId}/next` derives the continuation from that immutable snapshot: it reuses the stored query, filters, and page size, replaces the current cursor with the stored opaque continuation, and disables forced refresh. A local snapshot continues locally even when its original request used `AUTO`, so a local cursor is never sent to a provider after connectivity returns. A newly created continuation returns `201`; replaying a fresh continuation returns `200 EXACT_HIT`; a missing or other-owner source snapshot returns `404 SEARCH_NOT_FOUND`; and a snapshot without another page returns `409 SEARCH_PAGE_EXHAUSTED`. Current cache dispositions are `EXACT_HIT`, `MISS_FETCHED`, `STALE_REFRESHED`, `FORCED_REFRESH`, `STALE_FALLBACK`, and `LOCAL_RESULT`.

Local-catalog eligibility is owner-scoped: hosted users can discover canonical papers they previously received in a search snapshot or saved in a collection, while the fixed local user searches its own catalog. A local response has empty `providerCoverage`; each result still carries deterministic stored provider provenance and its retrieval time so database retrieval is not mistaken for newly refreshed metadata. `LOCAL_RESULT` and `LOCAL_CATALOG` do not claim that external links are reachable or current. Open-access-only local filtering uses stored provider-reported evidence, not a new legal-access verification.

OpenAlex is enabled by default. DataCite, DOAJ, and CORE are operator opt-ins; CORE additionally requires the licence-confirmation guard. Enabled providers run concurrently. A successful provider is preserved when another fails, coverage/warnings describe both outcomes, exact identifiers merge duplicate works, and a multi-provider page is ranked by deterministic reciprocal-rank fusion with provider contributions retained. DataCite is thesis/dissertation metadata-only discovery and never returns a discovery PDF or open-access claim.

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

`GET /papers/{paperId}` is a database-only canonical-details read. It returns canonical bibliographic fields—including nullable publisher, institution, volume, issue, pages, article number, edition, ISBN/ISSN, and degree metadata—paper-specific credited author names in provider order, every identifier, citation-count freshness, metadata completeness/freshness, deterministic record-level provenance, and a compact stored-access summary. Provenance identifies associated provider records and the record selected for canonical authorship; it is not field-level attribution. Source URLs are restricted to absolute HTTP(S) records and returned without query strings or fragments. Raw provider metadata and provider-reported landing/PDF links are excluded, while `/versions` remains the contract for verified access locations. An unknown UUID returns `404 PAPER_NOT_FOUND`; a malformed UUID returns the safe `400 INVALID_REQUEST` problem.

`GET /papers/{paperId}/related` is an experimental live, database-only lookup over the current canonical catalog. It accepts `limit=1..25` (default 10), excludes the source paper, and always reports a typed `rankingMode` plus a nullable typed `fallbackReason`. The default is `LEXICAL` with `HYBRID_DISABLED`: up to 16 OR-connected source-title lexemes rank the weighted title/abstract/venue search vector with normalized `ts_rank_cd(..., 32)`, and each result reports its `POSTGRES_FULL_TEXT` feature value.

Operators may explicitly set `RELATED_PAPERS_HYBRID_ENABLED=true` after populating the pinned Qwen/Ollama profile. The hybrid path takes bounded lexical and HNSW pools, obtains exact cosine values for every lexical candidate, reranks their union with `0.50 * lexical + 0.50 * clamp((cosine + 1) / 2, 0, 1)`, and breaks final score ties by paper UUID text. Hybrid results report both `POSTGRES_FULL_TEXT` and `CLAMPED_COSINE` feature values. The configurable pool is restricted to `25..100` and defaults to 100; the pinned HNSW query applies its separately frozen internal oversampling bound.

If the pinned embedding profile or source vector is absent, or any bounded lexical candidate lacks that vector, the endpoint returns the unchanged lexical order and score with `EMBEDDING_PROFILE_MISSING`, `SOURCE_VECTOR_MISSING`, or `CANDIDATE_VECTOR_COVERAGE_INCOMPLETE`. It catches only those expected data-availability conditions; database and other operational failures remain errors. Neither mode invokes an inference provider, and the response deliberately has no search snapshot ID, cache disposition, provider coverage, or invented local-provider provenance. The current text-search configuration remains English.

`GET /citation` accepts `format=bibtex` (the default) or `format=csl-json`. It returns a raw UTF-8 citation document rather than the normal JSON response envelope, with `Content-Disposition: attachment` and a deterministic filename. BibTeX uses `application/x-bibtex`; CSL-JSON uses `application/vnd.citationstyles.csl+json` and a one-item top-level array, as required by the CSL data schema. The stable citation key is based only on the canonical paper UUID.

Citation export is read-only and never contacts a research or access provider. It emits the best currently stored metadata, preserves Unicode, represents each author as a literal name, prefers a normalized DOI over an arXiv URL, and omits unavailable or invalid fields rather than guessing them. BibTeX and CSL-JSON now carry the stored publisher/institution, volume, issue, pages or article number, edition, ISBN/ISSN, and degree metadata where their formats permit; author-name parts remain intentionally unparsed. `404 PAPER_NOT_FOUND` identifies an unknown paper, while an unsupported format returns `400 UNSUPPORTED_CITATION_FORMAT`.

`POST /access/verify` performs bounded synchronous resolution. With the default `forceRefresh=false`, it returns a fresh cached result when available. `forceRefresh=true` bypasses that fresh-cache check; refreshing an existing resolution reports `FORCED_REFRESH`, while a first resolution remains `RESOLVED`. Forced refreshes are protected per paper by `openscholar.access.force-refresh-cooldown` (five minutes by default). A repeated request inside that window returns `429 ACCESS_REFRESH_RATE_LIMITED`, a `retryAfterSeconds` problem property, and a matching `Retry-After` header. The other access cache dispositions are `CACHE_HIT`, `REFRESHED`, `STALE_FALLBACK`, `NO_SUPPORTED_IDENTIFIER`, and `NOT_YET_RESOLVED`.

Access resolution uses exact identifiers already attached to the canonical paper:

- Unpaywall receives one normalized DOI at `GET /v2/{doi}`. Its backend contact email is optional application configuration; without it, coverage reports `NOT_CONFIGURED` and other providers can still complete.
- arXiv receives one canonical ID through `id_list` with `max_results=1`. The returned entry and its access paths must match the requested identifier; version suffixes are honored when explicitly requested.

Results contain overall access status, freshness, provider coverage, warnings, a best-location ID, and verified version records with source, host/version classification, licence when reported, landing/PDF link, and verification timestamps. All current locations use `LINK_ONLY`: the API returns links but never PDF bytes.

The web reader can load a fresh, verified HTTPS PDF location classified as `OPEN_PDF`, `REPOSITORY_COPY`, or `PREPRINT` directly from the source into PDF.js in the user's browser. The latter two statuses still require a non-null verified PDF link; landing-page-only records remain external. This is not an API byte endpoint: Next.js and Spring Boot do not fetch or relay the document, and a source without compatible browser CORS headers falls back to its external link.

Access results remain fresh for 24 hours by default. The cache carries a fingerprint of the paper's DOI, arXiv ID, and abstract availability, so later catalog enrichment invalidates an incompatible negative result. Provider failures are isolated. If no provider can complete a refresh—or reported candidates cannot be safely re-verified—and a compatible older resolution exists, the API returns it unchanged as `STALE_FALLBACK` with machine-readable warnings rather than renewing stale links as fresh.

The implemented access-status vocabulary is `OPEN_PDF`, `OPEN_LANDING_PAGE`, `REPOSITORY_COPY`, `PREPRINT`, `ABSTRACT_ONLY`, `RESTRICTED`, `UNKNOWN`, and `UNAVAILABLE`. Verified arXiv candidates are classified as `PREPRINT`; verified Unpaywall locations reported with repository host evidence are `REPOSITORY_COPY`; other verified PDFs and landing pages retain `OPEN_PDF` and `OPEN_LANDING_PAGE`. Link verification accepts only provider candidates and validates every redirect before a location can become active.

Current access verification returns `404 PAPER_NOT_FOUND` for an unknown canonical paper, `429 ACCESS_REFRESH_RATE_LIMITED` for a forced-refresh cooldown violation, and `503 ACCESS_PROVIDERS_UNAVAILABLE` only when no provider can complete and there is no stored fallback. The 503 problem preserves aggregate retryability and an upstream `Retry-After` when available. The durable `PAPER_ACCESS` refresh job can run the same bounded resolution outside the interactive request when the default-off worker is enabled.

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

Every collection read/write is owner-scoped. Local mode resolves the fixed development owner; OIDC mode resolves or creates an internal user for the validated issuer+subject. Collection names contain 1–120 characters; descriptions are optional and bounded to 1,000 characters. `PUT` creates or replaces a paper membership, while `PATCH` requires an existing membership. A membership records `UNREAD`, `READING`, or `COMPLETED` and zero to ten canonical tags. Tags are trimmed, internal whitespace is collapsed, values are lowercased with locale-independent rules, and each tag is limited to 40 characters. Deleting a paper membership is idempotent; an unknown or other-owner collection returns `404 COLLECTION_NOT_FOUND`.

`GET /api/v1/library/papers` supports bounded `q`, `collectionId`, `readingStatus`, `tag`, `page`, and `size` parameters. Lexical matching covers collection name, paper title, abstract, venue, and credited author name. `%`, `_`, and `\` in `q` are treated literally rather than as SQL wildcards. Results are deterministic and retain one row per collection membership; a canonical paper saved in two collections therefore appears twice.

`POST /api/v1/citations/export` accepts one to 100 distinct canonical paper UUIDs plus `bibtex` or `csl-json`. It preserves caller order, fails the whole request if any paper is unknown, and returns a raw UTF-8 attachment with the same media types and stored-metadata policy as the single-paper endpoint. Duplicate IDs, empty/oversized lists, and malformed inputs are rejected rather than silently normalized.

Notes/highlights follow after the core library.

### Durable refresh jobs

```http
POST /api/v1/refresh-jobs
GET  /api/v1/refresh-jobs?page=0&size=20
GET  /api/v1/refresh-jobs/{jobId}
POST /api/v1/refresh-jobs/{jobId}/retry
```

The operational REST API exposes durable `SEARCH_METADATA` and `PAPER_ACCESS` refresh records. Enqueue validates the target, returns `202 Accepted`, and deduplicates an already `QUEUED`/`RUNNING` type+target. PostgreSQL stores `QUEUED`, `RUNNING`, `SUCCEEDED`, and `FAILED` states, attempt budget, lease timestamps, and bounded safe error details. The default-off worker claims with `FOR UPDATE SKIP LOCKED`, uses an expiring tokened lease, applies bounded exponential retry to classified transient failures, and rejects stale completions that lost their lease. Failed jobs can be manually retried through REST; optional default-off scheduling enqueues stale search/access targets and is invalid unless the worker is enabled.

These are operational REST refresh jobs, not MCP Tasks or per-user MCP job handles. The table has no `owner_id` and deduplicates by type+target. Under the `openscholar.jobs` scope, `SEARCH_METADATA` list/get/retry visibility follows the target snapshot's current owner, while `PAPER_ACCESS` jobs remain visible/retryable to every jobs-scoped principal because canonical papers and access evidence are shared catalog data. The consumer browser client does not request this scope by default.

### Privacy

```http
GET    /api/v1/privacy/export
DELETE /api/v1/privacy/account
```

OIDC mode requires `openscholar.privacy`. Export returns a no-store JSON attachment containing only the current principal's identity display data, search snapshots/filters, collections, and saved-paper memberships. Account deletion requires the exact `DELETE_MY_DATA` confirmation and deletes that principal's search snapshots, search-refresh jobs, collections/memberships/tags, and OIDC user row. Shared canonical paper/provider/access metadata and global access-refresh jobs are not personal-account records and are not deleted. A later valid token for the same issuer+subject provisions a new empty internal account. Local mode preserves the fixed bootstrap user while deleting its personal search/library data.

### Operations

```http
GET /actuator/health
GET /actuator/info
GET /actuator/prometheus
```

`health`, `info`, and the Prometheus registry are exposed by Actuator. Health details are never shown. Local mode remains loopback-bound; production moves all management endpoints to a separate private `9091` listener attached to the monitoring network, where Prometheus scrapes them. Caddy does not publish or route `/actuator/*`. Any broader diagnostics require a separately reviewed private or authenticated management boundary.

## Error model

REST uses RFC 9457 Problem Details with a stable error code, safe detail, validation violations, retryability, and optional retry-after. Stack traces and credentials never appear.

The current search slice implements stable validation, not-found, provider-unavailable, coordination-failure, and execution-deadline codes. A caller that cannot acquire its JVM-local coordination stripe within the configurable 12-second default rechecks the latest exact snapshot. A normal caller can reuse a newly fresh snapshot as `EXACT_HIT`; otherwise an available snapshot is returned as `STALE_FALLBACK` with `SEARCH_COORDINATION_TIMEOUT` in `warnings`. If no snapshot exists, REST returns retryable `503 SEARCH_COORDINATION_TIMEOUT` without `Retry-After`. An interrupted wait restores the thread interruption state and returns retryable `503 SEARCH_COORDINATION_INTERRUPTED`, also without `Retry-After`; interruption does not perform the timeout snapshot fallback.

The outer search execution deadline defaults to 18 seconds and applies to `search`, `next`, and `get` from validated application dispatch through `SearchView` construction. If it expires first, REST returns retryable `504 SEARCH_DEADLINE_EXCEEDED` without `Retry-After`; caller or server interruption returns retryable `503 SEARCH_EXECUTION_INTERRUPTED`. Deadline expiration interrupts the dedicated virtual-thread worker and is terminal: no new stale-snapshot fallback starts after the budget is exhausted. OpenAlex and coordination failures retain their existing codes and fallback behavior when they complete first. Persistence already in progress at the boundary may continue and commit, so a new immutable snapshot may later become visible, because JDBC interruption is not guaranteed.

## MCP transport

The implemented transport is stateless Streamable HTTP through `spring-ai-starter-mcp-server-webmvc` at `/mcp`. It is synchronous, advertises tools only, and does not expose legacy SSE, resources, prompts, completions, sampling, elicitation, or STDIO.

Spring AI 2.0 and MCP Java SDK 2.0 negotiate their supported revisions through a maximum tested revision of `2025-11-25`. The server does not claim newer Tasks or MCP Apps capabilities. Local mode requires the configured MCP bearer key. OIDC mode delegates bearer validation to the JWT resource server and requires `openscholar.mcp`; present `Origin` headers must still exactly match the configured allow-list.

The adapter registers five read-oriented tools. `search_research` in `AUTO` or `ONLINE` mode may contact the enabled discovery-provider set and update internal metadata/search caches; `LOCAL` is database-only but still stores an immutable owned snapshot. Because MCP annotations describe the whole tool rather than one invocation, `search_research` retains `readOnlyHint=false`, `idempotentHint=false`, and `openWorldHint=true` for every mode. Every discovery adapter has a configurable 10-second default whole-exchange deadline and 8 MiB streamed body limit. A separate configurable 12-second default bounds only acquisition of the JVM-local search-coordination stripe. The shared 18-second execution deadline bounds the application work for `search_research` as well as REST search operations. It excludes MCP input parsing/schema validation, tool DTO/framework serialization, and socket lifetime. The other four tools are database-only; none mutates user collections or reading state. MCP-specific search/library pages and citation batches are capped at 25 items.

## MVP MCP tools

### `search_research`

Finds provider-backed, cached, or owner-scoped local research metadata.

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
  "forceRefresh": false,
  "mode": "AUTO"
}
```

Output includes canonical paper IDs, nullable typed publication metadata (publisher/institution, volume/issue, pages or article number, edition, ISBN/ISSN, and degree), provider-reported access hints, ranking reasons, full contributing-provider provenance, requested mode, actual execution source, cache disposition, freshness, warnings, and the next cursor. The existing primary provider fields remain for compatibility; `provenance` is the complete contribution list. Provider-reported PDF links are not verified legal-access claims; use stored access output from `get_legal_full_text` after verification through REST/UI.

### `get_paper_details`

Accepts one canonical OpenScholar UUID and returns canonical metadata, identifiers, ordered authorship, record-level provenance, freshness, and the full stored access resolution, including coverage, warnings, and locations. It never contacts a provider. DOI, arXiv, and OpenAlex identifier resolution remains planned.

### `get_legal_full_text`

Accepts one canonical OpenScholar UUID and returns the stored access classification, provider coverage, warnings, and verified locations. It is database-only and may return `NOT_YET_RESOLVED`; legal verification remains an explicit REST/UI operation because the bounded synchronous provider pipeline can exceed the MCP interactive timeout. It never returns file bytes or attempts publisher authentication.

### `search_saved_library`

Searches the current owner's saved collection memberships using optional lexical text, collection UUID, reading status, normalized tag, page, and size. Results preserve one row per collection membership. Local mode uses the fixed owner; OIDC mode uses the authenticated issuer+subject owner.

### `export_citations`

Accepts one to 25 distinct canonical paper UUIDs plus `bibtex` or `csl-json`. It preserves caller order, fails atomically for an unknown paper, and returns format, filename, media type, count, and citation content as structured MCP output. It never contacts a provider.

## Deferred MCP tools

`build_reading_list`, provider-backed access verification, collection/note mutations, and MCP job handles are not advertised until their confirmation, ownership, and timeout behavior are implemented and tested. Durable REST refresh jobs already exist but are intentionally not represented as MCP Tasks.

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
- Local MCP limits use the server-observed remote address. OIDC MCP limits use a hash of validated issuer+subject; an independent aggregate hosted limit remains external work.
- arXiv has an implemented three-second outbound request gate. All discovery adapters have configurable whole-exchange deadlines/body limits; access providers use bounded timeouts and applicable upstream rate-limit information is propagated.
- Search coordination uses a configurable 12-second lock-acquisition limit. Timing out prevents that follower from invoking duplicate work and does not cancel the leader; snapshot fallback is used when available.
- Search application execution uses a configurable 18-second default deadline with interrupting virtual-thread cancellation and cooperative checkpoints. MCP exposes retryable `SEARCH_DEADLINE_EXCEEDED` and `SEARCH_EXECUTION_INTERRUPTED` safe prefixes without guessed retry-after values.
- Every protected MCP response carries a request ID that is also placed in logging context.
- The configured `spring.ai.mcp.server.request-timeout` is 20 seconds, but the stateless MCP Java SDK 2.0 path does not currently enforce it as whole-tool cancellation.
- Client-disconnect propagation, MCP `notifications/cancelled`, request parsing/validation deadlines, framework serialization deadlines, and socket-lifetime enforcement remain follow-ups on the pinned stateless SDK.
- Tool failures use safe text with stable prefixes such as `INVALID_REQUEST`, `PAPER_NOT_FOUND`, `SEARCH_COORDINATION_TIMEOUT`, `SEARCH_COORDINATION_INTERRUPTED`, `SEARCH_DEADLINE_EXCEEDED`, `SEARCH_EXECUTION_INTERRUPTED`, and provider-unavailable codes. Restricted access is a successful access status; dedicated structured tool-error contracts remain planned.

## Compatibility testing

- The pinned official runner's production-applicable `server-initialize` and `tools-list` scenarios pass with `--spec-version 2025-11-25`; its remaining scenarios require a synthetic fixture surface and are not run against the domain server.
- Track the canonical frozen `2025-11-25` requirements set as the official conformance runner evolves.
- Tool discovery, calls, invalid schemas, authentication, application deadline/interruption behavior, partial results, and shutdown are test targets. Client-disconnect and MCP `notifications/cancelled` propagation remain deferred with the transport limitation above.
- The supported protocol revision is recorded in `/actuator/info`; repeat it in release notes when a release artifact or tag is created.
