# Testing Strategy

## Unit tests

- Query normalization and fingerprint stability.
- DOI/arXiv normalization.
- Provider mapping.
- Deduplication/conflict resolution.
- Ranking features and explanations.
- Cache freshness/coverage.
- Search-coordinator default/custom/invalid budgets, deterministic acquisition timeout, supplier non-entry, lock reuse, and interruption-state restoration.
- Search execution default/custom/invalid budgets, successful and failed completion, repeated deadline expiry with no active worker left behind, interrupting worker cancellation, and caller interruption-state restoration.
- Access classification.
- Citation type mapping, identifier normalization, deterministic keys, Unicode, literal authors, and hostile BibTeX escaping.
- Ordered citation batches, strict size/distinct-ID bounds, and all-or-nothing missing-paper behavior.
- Locale-independent collection/tag normalization and literal-safe saved-library queries.
- Embedding contract invariants, exact Ollama runtime/profile/request/digest checks, no-proxy/redirect transport, response-size enforcement, failure-scope translation, and disabled-by-default configuration.
- Backfill command/result invariants, generator selection, bounded verification/generation/stale handling, fail-fast systemic errors, deletion/failure accounting, overflow protection, non-web startup guard, nonzero incomplete-run behavior, and duplicate-generator rejection.
- Multi-provider fan-out, provider-set fingerprints, cursor composition, exact-ID merging, reciprocal-rank fusion, and partial success/failure reporting.
- Durable refresh-job configuration, lease/claim state transitions, retry classification/backoff, stale-completion rejection, worker/scheduler guards, and safe error details.
- OIDC property/JWT validation, route-scope decisions, issuer+subject resolution, protected-resource metadata/challenges, and principal rate-limit identity.
- Frontend OIDC configuration, PKCE/state/nonce callback, ID-token/JWKS/token validation, encrypted session/refresh behavior, exact-Origin enforcement, and job response schemas.

## Slice tests

- Spring MVC validation and Problem Details.
- JPA mappings and JSON contracts.
- Raw BibTeX/CSL-JSON response media types, attachment headers, sparse records, and stable citation errors.
- Collection CRUD, saved-paper mutations, owner-scoped not-found behavior, library filters/pagination, and citation-batch attachment contracts.
- Canonical paper details, record-level provenance, immutable credited names, date/year integrity, and stored-access summaries without provider calls.
- MCP annotation discovery and tool validation.
- Refresh-job enqueue/list/get/retry contracts and privacy export/confirmed-delete contracts.

## Integration tests

Testcontainers supplies real PostgreSQL/pgvector. Current coverage verifies Flyway from empty, V7-to-V8 library upgrade, V8-to-V9 full-text backfill, constraints/indexes, transactions, idempotent identifier upserts, collection/tag database invariants, literal wildcard handling, deterministic library pagination, owner-scoped access, generated full-text-vector refresh, the GIN index, stopword-only and punctuation-heavy queries, bounded related-paper ranking, venue-only matches, deterministic repeat reads, default-off lexical equivalence, pinned HNSW hybrid ordering, exact lexical-candidate vector coverage, explicit profile/source/coverage fallback modes, operational-error propagation, identical-search miss coalescing, distinct-stripe provider concurrency, explicit concurrent force-refresh behavior, coordination-timeout snapshot rechecks without duplicate provider work, execution-deadline provider interruption with no post-timeout snapshot, successful same-query retry, same-key leader-to-follower handoff, immutable embedding profiles, exact cosine storage, source invalidation, missing-vector cursor paging, and PostgreSQL advisory-lock exclusion.

The current suite also covers the `V13` refresh-job schema, active-target deduplication, `SKIP LOCKED` claims, lease expiry/reclaim and stale-token safety; the `V14` issuer+subject identity constraints; the `V15` search-owner migration/cache indexes; cross-user search/library rejection; and owner-only privacy export/deletion. Broader high-contention/load reconciliation remains later work.

## Provider contract tests

Spring `MockRestServiceServer` fixtures use synthetic or permitted sample responses. Every adapter covers its applicable success, pagination, empty/incomplete results, rate limits, timeouts, malformed payloads, and tolerant schema-evolution cases. OpenAlex additionally verifies exact-limit acceptance and non-retryable rejection of oversized bodies with declared or unknown content lengths. DOAJ fixtures verify keyless v4 article-search requests, literal query escaping, unsupported-filter skips, source-reported access semantics, metadata/link-only mapping, public page/window bounds, and rejection of foreign continuation URLs. CORE fixtures verify the fail-closed licence gate, optional backend bearer key, supported v3 search shape, response aliases, and deliberate omission of full text/download URLs. DataCite fixtures verify thesis/dissertation-only query construction, controlled/legacy type handling, provider-owned cursor paging, OA-only short-circuiting, and no discovery OA/PDF claim. Latch-driven real-transport tests for each discovery adapter stall before headers and after a partial body, asserting stable retryable timeout translation and exactly one upstream request with no automatic retry.

Live-provider tests run manually or on a scheduled, strictly budgeted workflow—not normal pull requests.

The Ollama adapter is exercised through a mock HTTP server with synthetic inputs and vectors. Normal tests do not require an Ollama process, download the 639 MB model, call `/api/pull`, or send scholarly metadata to a hosted provider.

An opt-in Testcontainers evaluation runs the synthetic 18-paper fixture through the real pinned Ollama adapter, performs a complete backfill in an ephemeral PostgreSQL/pgvector database, and measures stable exact cosine neighbors. It is enabled only by `RUN_OLLAMA_VECTOR_EVALUATION=true`; the operator must separately enable the adapter, attest local-only Ollama configuration, and supply the exact full model digest. The gate records Recall@K, nDCG@K, Precision@1, and MRR and is skipped in ordinary CI. It does not enable the application backfill runner, touch a development database, pull a model, or test HNSW.

That manual run also reports an exploratory in-sample hybrid sensitivity sweep over the complete 17-candidate fixture pool. The lexical component is the returned `ts_rank_cd(..., 32)` score, the semantic component is `(cosine + 1) / 2`, and weights `0`, `0.25`, `0.50`, `0.75`, and `1` are all reported. Judgments are applied only after ranking. Endpoint equivalence, repeated component reads, bounded features, deterministic ties, and source/duplicate exclusion are structural gates; no mixed-weight quality floor or product default is selected without independently authored held-out query groups.

A separate opt-in holdout evaluation is enabled only by `RUN_OLLAMA_HOLDOUT_EVALUATION=true`. It proves that the development and holdout paper keys, query keys, source keys, and normalized content fingerprints are disjoint; loads the frozen policy; embeds only the independently authored 26-paper holdout; and evaluates seven query groups over all 25 non-seed candidates. It reads lexical and exact-vector components twice for stability, reports lexical/vector controls and only the frozen `w = 0.50` hybrid, and enforces the predeclared per-query and macro advancement gates. Ordinary CI loads no real model and skips this class.

The first real-model invocation stopped before complete metrics because a valid zero-match Japanese lexical control hit an overstrict structural assertion. Commit `288bf4f` fixed the assertion without consulting labels: empty lexical results remain a measurable control, while vector and frozen-hybrid candidate-pool completeness stays enforced. No policy, fixture paper, judgment, cutoff, or acceptance gate changed. The subsequent run passed all frozen gates: lexical macro `0.857 / 0.648 / 0.286 / 0.571`, exact-vector macro `1.000 / 0.893 / 1.000 / 1.000`, and hybrid macro `1.000 / 0.917 / 0.857 / 0.929` for Recall@K, nDCG@K, Precision@1, and MRR. Five query groups strictly improved nDCG and none regressed.

The separately frozen HNSW gate uses a deterministic 10,000-vector, 1024-dimensional corpus and 20 queries at cutoff 25. Its completed reference run achieved macro Recall@25 `1.0000`, exact p95 `47.491 ms`, approximate p95 `20.082 ms`, and `2.365x` speedup. Ordinary PostgreSQL tests also cover the pinned index/query contract and default-off hybrid orchestration without invoking a real embedding model.

## MCP tests

- Implemented: direct adapter tests for defaults, bounds, result mapping, database-only behavior, stable errors, and safe unexpected failures.
- Implemented: safe retryable `SEARCH_COORDINATION_TIMEOUT` and `SEARCH_COORDINATION_INTERRUPTED` tool mappings without nested causes or invented retry-after values.
- Implemented: safe retryable `SEARCH_DEADLINE_EXCEEDED` and `SEARCH_EXECUTION_INTERRUPTED` REST/MCP mappings without nested causes or invented retry-after values.
- Implemented: raw REST and MCP deadline calls prove bounded responses, provider interruption and exit, no post-timeout snapshot, stable JSON-RPC error wrapping, and no nested-cause leakage.
- Implemented: authenticated raw JSON-RPC initialization, initialized notification, exact tool discovery, input/output schemas, annotations, a null-heavy structured search result, and invalid-schema rejection before provider invocation.
- Implemented: successful raw structured calls for all four database-only tools, using a canonical paper created through the search tool where required.
- Implemented: API-key, duplicate-header, exact-Origin, response-header, and disabled-until-configured security-filter coverage.
- Implemented: hosted missing/invalid/expired/wrong-issuer/wrong-audience/insufficient-scope JWT cases, route-scope enforcement, issuer+subject ownership, protected-resource metadata/challenges, and principal-derived MCP rate-limit keys.
- Implemented: official MCP Inspector CLI discovery and an empty-library `search_saved_library` call against the Compose image.
- Implemented: pinned official conformance `server-initialize` and `tools-list` scenarios with `--spec-version 2025-11-25`; both pass without warnings through the loopback bearer-injection proxy.
- Implemented: raw-wire malformed/schema-invalid input, oversized request rejection, oversized result rejection without sensitive-content leakage, restricted-access results, and mixed provider success/failure coverage.
- Current limitation: the configured 20-second MCP request timeout is not enforced by the stateless MCP Java SDK 2.0 path; the separate 18-second application deadline bounds search use-case execution instead.
- Implemented: repeated JSON-RPC identifiers are shown not to act as idempotency keys; ordinary search fingerprint caching still yields one provider call followed by an exact hit.
- Remaining SDK/transport limitation: client-disconnect and MCP `notifications/cancelled` propagation, framework parsing/serialization and socket-lifetime deadlines, and in-flight JDBC persistence cancellation guarantees.
- Hosted follow-up: live authorization-server/client interoperability, signing-key rotation, token refresh/revocation, and public-edge behavior. Audience/scope/principal ownership is covered synthetically in the application suite.
- Track the canonical frozen `2025-11-25` requirements set as the official runner advances beyond its current fixture-oriented release.

If STDIO is added, logs use `stderr`; `stdout` remains protocol-only.

## End-to-end tests

The offline Playwright suite covers search/cache/provider warnings/provenance, immutable continuation, verified versus restricted access, direct PDF.js rendering and keyboard/focus/text controls, individual citation download, paper save, collection creation, reading status/tags, and selected citation export. Every scenario blocks unexpected external requests and runs serious/critical WCAG 2.2 axe checks.

The separate Compose Playwright lane drives the production Next.js build against Spring Boot, PostgreSQL, and a deterministic OpenAlex fixture. It asserts the cold `201/MISS_FETCHED` and repeated `200/EXACT_HIT` boundary, three provider records becoming two canonical DOI-deduplicated results, collection persistence/filtering, BibTeX download, and WCAG scans across search, paper, library, and collection pages. Vitest/React Testing Library retains narrower component/contract coverage. A live IdP login/refresh/logout browser run remains an external interoperability gate because it requires an actual registered client and issuer.

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

The versioned `related-metadata-baseline-v1.json` development corpus contains only synthetic metadata and graded `0..3` relevance judgments. Its related-paper evaluations record lexical and exact-vector Recall@K, nDCG@K, Precision@1, and mean-reciprocal-rank floors plus a non-gating hybrid sensitivity sweep; it retains recent uncited work, a DOI-less thesis, Spanish metadata, incomplete metadata, and deliberately difficult cross-domain negatives. The independently authored `related-metadata-holdout-v1.json` adds seven whole query groups across 26 disjoint synthetic papers, including German, English, French, and Japanese metadata, and is paired with the machine-readable frozen `related-hybrid-policy-v1.json`. Exact score decimals and exact total ordering are not ordinary test contracts; the opt-in holdout evaluator records rankings and checks stable repeated reads under the pinned runtime.

## Performance tests

- A checked-in loopback-safe harness measures one forced cold search, repeated exact-cache searches, repeated paper-detail reads, cache-hit ratio, and Prometheus provider error rate. Its 40-sample synthetic Compose reference run passed at 6.944 ms cached-search p95 and 7.305 ms paper-detail p95; see [Local performance evidence](PERFORMANCE_EVIDENCE.md).
- Cached search throughput and concurrent-client capacity remain deployment tests.
- Provider fan-out with simulated delays.
- Connection-pool saturation.
- Large normalization/persistence batches.
- Full-text/vector query latency at realistic scale.
- MCP serialization and response limits.

Use synthetic metadata; commit no copyrighted corpus.

## CI gates

The list below is the target release gate. Unit/integration/frontend checks, container builds, MCP conformance, dependency review, CodeQL, Trivy, secret scanning, SBOM generation, an offline Playwright workflow, and a deterministic Compose-backed Playwright workflow have checked-in workflows. A checked-in workflow is not evidence that an unpushed revision passed on GitHub; local results are reported separately.

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
