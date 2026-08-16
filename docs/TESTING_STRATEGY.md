# Testing Strategy

## Unit tests

- Query normalization and fingerprint stability.
- DOI/arXiv normalization.
- Provider mapping.
- Deduplication/conflict resolution.
- Ranking features and explanations.
- Cache freshness/coverage.
- Access classification.
- Citation serialization.
- Authorization decisions.

## Slice tests

- Spring MVC validation and Problem Details.
- JPA mappings and JSON contracts.
- MCP annotation discovery and tool validation.

## Integration tests

Testcontainers supplies real PostgreSQL/pgvector. Verify Flyway from empty, constraints/indexes, transactions, idempotent upserts, full-text/vector queries, concurrent deduplication, job leasing, and owner-scoped access.

## Provider contract tests

WireMock fixtures use synthetic or permitted sample responses. Every adapter covers success, pagination, empty/incomplete results, duplicate versions, rate limits, timeouts, malformed payloads, unsafe redirects, and tolerant schema evolution.

Live-provider tests run manually or on a scheduled, strictly budgeted workflow—not normal pull requests.

## MCP tests

- Tool listing and valid calls.
- Invalid/oversized input.
- Access-restricted results.
- Provider partial failure.
- Deadlines, cancellation, and duplicate requests.
- Authentication, Origin, audience, scope, and ownership.
- Response-size limits.
- Official conformance suite pinned to MCP `2025-11-25` until the Java stack advances.

If STDIO is added, logs use `stderr`; `stdout` remains protocol-only.

## End-to-end tests

Playwright covers search/filter/provenance, repeat-query caching, legal PDF or external fallback, collections, reading status, citation export, keyboard navigation, provider warnings, and restricted-paper handling.

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
