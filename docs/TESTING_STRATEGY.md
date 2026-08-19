# Testing Strategy

## Unit tests

- Query normalization and fingerprint stability.
- DOI/arXiv normalization.
- Provider mapping.
- Deduplication/conflict resolution.
- Ranking features and explanations.
- Cache freshness/coverage.
- Access classification.
- Citation type mapping, identifier normalization, deterministic keys, Unicode, literal authors, and hostile BibTeX escaping.
- Ordered citation batches, strict size/distinct-ID bounds, and all-or-nothing missing-paper behavior.
- Locale-independent collection/tag normalization and literal-safe saved-library queries.
- Authorization decisions.

## Slice tests

- Spring MVC validation and Problem Details.
- JPA mappings and JSON contracts.
- Raw BibTeX/CSL-JSON response media types, attachment headers, sparse records, and stable citation errors.
- Collection CRUD, saved-paper mutations, owner-scoped not-found behavior, library filters/pagination, and citation-batch attachment contracts.
- Canonical paper details, record-level provenance, immutable credited names, date/year integrity, and stored-access summaries without provider calls.
- MCP annotation discovery and tool validation.

## Integration tests

Testcontainers supplies real PostgreSQL/pgvector. Current coverage verifies Flyway from empty, V7-to-V8 library upgrade, V8-to-V9 full-text backfill, constraints/indexes, transactions, idempotent identifier upserts, collection/tag database invariants, literal wildcard handling, deterministic library pagination, owner-scoped access, generated full-text-vector refresh, the GIN index, stopword-only and punctuation-heavy queries, bounded related-paper ranking, venue-only matches, and deterministic repeat reads. Broader concurrent reconciliation and job-leasing tests follow when those features are implemented.

## Provider contract tests

Spring `MockRestServiceServer` fixtures use synthetic or permitted sample responses. Every adapter covers its applicable success, pagination, empty/incomplete results, duplicate versions, rate limits, timeouts, malformed payloads, unsafe redirects, and tolerant schema-evolution cases.

Live-provider tests run manually or on a scheduled, strictly budgeted workflow—not normal pull requests.

## MCP tests

- Implemented: direct adapter tests for defaults, bounds, result mapping, database-only behavior, stable errors, and safe unexpected failures.
- Implemented: authenticated raw JSON-RPC initialization, initialized notification, exact tool discovery, input/output schemas, annotations, a null-heavy structured search result, and invalid-schema rejection before provider invocation.
- Implemented: successful raw structured calls for all four database-only tools, using a canonical paper created through the search tool where required.
- Implemented: API-key, duplicate-header, exact-Origin, response-header, and disabled-until-configured security-filter coverage.
- Implemented: official MCP Inspector CLI discovery and an empty-library `search_saved_library` call against the Compose image.
- Implemented: pinned official conformance `server-initialize` and `tools-list` scenarios with `--spec-version 2025-11-25`; both pass without warnings through the loopback bearer-injection proxy.
- Remaining: additional invalid/oversized input and response-size cases.
- Remaining: access-restricted results and provider partial failure at the MCP wire boundary.
- Remaining: deadlines, cancellation, and duplicate requests.
- Hosted follow-up: token audience/scope/principal ownership; local API-key and Origin behavior are already covered.
- Track the canonical frozen `2025-11-25` requirements set as the official runner advances beyond its current fixture-oriented release.

If STDIO is added, logs use `stderr`; `stdout` remains protocol-only.

## End-to-end tests

Planned Playwright coverage includes search/filter/provenance, repeat-query caching, legal PDF or external fallback, collections, reading status, citation export, keyboard navigation, provider warnings, and restricted-paper handling.

The current frontend slice has Vitest/React Testing Library coverage for runtime API validation, bounded search submission, RFC 9457 errors, verified-versus-unverified access links, reader-source policy selection, PDF.js loading/render lifecycle, controls, cleanup, generic external fallback, normalized library tags, collection mutations, and bounded citation-batch downloads. Manual browser smoke verification covers desktop/mobile layout, the live search → paper → arXiv access path, the persistent collection flow, and a controlled CORS-allowed render versus CORS-blocked fallback. Automating those reader and library cases with Playwright remains a CI follow-up.

## Evaluation fixtures

Version small metadata-only cases for:

- exact DOI duplicates;
- preprint/published pairs;
- theses without DOIs;
- common-title false positives;
- recent uncited relevant work;
- highly cited irrelevant work;
- multilingual/incomplete records;
- restricted canonical work with an open repository copy.

Expected canonical clusters and top-result ranges are preferred over brittle total ordering.

The versioned `related-metadata-baseline-v1.json` corpus contains only synthetic metadata and graded `0..3` relevance judgments. Its related-paper evaluation records Recall@K, nDCG@K, Precision@1, and mean-reciprocal-rank floors; it retains recent uncited work, a DOI-less thesis, Spanish metadata, incomplete metadata, and deliberately difficult cross-domain negatives. Exact `ts_rank_cd` decimals and exact total ordering are not test contracts.

## Performance tests

- Cached search latency/throughput.
- Provider fan-out with simulated delays.
- Connection-pool saturation.
- Large normalization/persistence batches.
- Full-text/vector query latency at realistic scale.
- MCP serialization and response limits.

Use synthetic metadata; commit no copyrighted corpus.

## CI gates

1. Formatting and static checks.
2. Backend unit/slice/integration tests.
3. Frontend tests.
4. ArchUnit boundary checks.
5. Container build.
6. Playwright smoke tests against Compose.
7. Dependency/secret scanning.

Conformance and performance suites may run on main/nightly when unsuitable for every pull request.

## Release verification

- Build from clean clone.
- Migrate a fresh database.
- Start Compose and pass health checks.
- Run fixed UI, REST, and MCP queries.
- Test restricted access and provider outage.
- Confirm no secrets/unlicensed PDFs in repo or images.
- Generate and scan SBOM/images.
