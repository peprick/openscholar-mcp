# Testing Strategy

## Unit tests

- Query normalization, mode/provider/local fingerprint separation, and fingerprint stability.
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
- Search-mode defaults/invariants, opaque local-cursor bounds, deterministic local ranking, and requested-mode/execution-source mapping.
- Durable refresh-job configuration, lease/claim state transitions, retry classification/backoff, stale-completion rejection, worker/scheduler guards, and safe error details.
- OIDC property/JWT validation, route-scope decisions, issuer+subject resolution, protected-resource metadata/challenges, and principal rate-limit identity.
- Frontend OIDC configuration, PKCE/state/nonce callback, ID-token/JWKS/token validation, encrypted session/refresh behavior, exact-Origin enforcement, and job response schemas.
- Privacy-export configuration bounds, fail-fast per-principal/global contention and permit release, count/byte limit arithmetic, exact two-pass byte accounting, unopened targets on admission/preflight rejection, non-closing servlet-stream ownership, and stable `PRIVACY_EXPORT_BUSY`/`PRIVACY_EXPORT_TOO_LARGE` translation.
- Frontend privacy export/deletion validation, native non-buffering download, exact bounded `Content-Length`, fixed no-store/no-transform/nosniff attachment forwarding, identity-encoding request and encoded-upstream rejection, upstream cancellation and mid-stream failure propagation, clearance of the dedicated 140-second timer after response headers, finite abort of a stalled non-success body, hosted-session expiry, accessible confirmation/status behavior, and wording that never claims a download completed or a destructive action was rolled back.
- Frontend backend-JSON media types, declared and streamed byte ceilings, forged/missing `Content-Length`, overflow cancellation, UTF-8/JSON/schema rejection, and safe Problem Details fallback.
- PDF reader source validation, byte/page-count/load/render/canvas/text ceilings, cleanup, generic fallback, and accessible page/zoom/text controls.
- PWA manifest/install fields, production/explicit-browser-test registration, service-worker update headers, auth-proxy exclusions, account-neutral fallback content, cache-only required reader-pair serving, coherent reader-revision enforcement, failed-install cleanup, network-first fixed install assets, the 96-entry runtime-static bound, exact-URL anonymous cache admission, redirect/private/no-store/cookie-vary rejection, safe app-owned cache/version cleanup, blocked IndexedDB upgrade/late-success cleanup, and connectivity probe/state behavior.

## Slice tests

- Spring MVC validation and Problem Details.
- JPA mappings and JSON contracts.
- Raw BibTeX/CSL-JSON response media types, attachment headers, sparse records, and stable citation errors.
- Collection CRUD, saved-paper mutations, owner-scoped not-found behavior, library filters/pagination, and citation-batch attachment contracts.
- Canonical paper details, record-level provenance, immutable credited names, date/year integrity, and stored-access summaries without provider calls.
- Project-owned MCP annotation/specification discovery, closed generated input schemas, safe callback validation,
  and successful structured-output conformance.
- REST/MCP AUTO, ONLINE, and LOCAL contract mapping, including rejection of forced LOCAL refresh and complete result provenance.
- Refresh-job enqueue/list/get/retry contracts and privacy export/confirmed-delete contracts, including exact attachment headers and pre-transaction `429`/pre-commit `422` responses.

## Integration tests

Testcontainers supplies real PostgreSQL/pgvector. Current coverage verifies Flyway from empty, V7-to-V8 library upgrade, V8-to-V9 full-text backfill, constraints/indexes, transactions, idempotent identifier upserts, collection/tag database invariants, literal wildcard handling, deterministic library pagination, owner-scoped access, generated full-text-vector refresh, the GIN index, stopword-only and punctuation-heavy queries, bounded related-paper ranking, venue-only matches, deterministic repeat reads, default-off lexical equivalence, pinned HNSW hybrid ordering, exact lexical-candidate vector coverage, explicit profile/source/coverage fallback modes, operational-error propagation, identical-search miss coalescing, distinct-stripe provider concurrency, explicit concurrent force-refresh behavior, coordination-timeout snapshot rechecks without duplicate provider work, execution-deadline provider interruption with no post-timeout snapshot, successful same-query retry, same-key leader-to-follower handoff, immutable embedding profiles, exact cosine storage, source invalidation, missing-vector cursor paging, and PostgreSQL advisory-lock exclusion.

The current suite also covers the `V13` refresh-job schema, active-target deduplication, `SKIP LOCKED` claims, lease expiry/reclaim and stale-token safety; the `V14` issuer+subject identity constraints; the `V15` search-owner migration/cache indexes; the `V16` mode/origin constraints and origin-aware cache index; current-version-only automatic stale search selection; cross-user search/library rejection; and owner-only privacy export/deletion. Privacy-export persistence tests additionally verify owner-only rows, stable array/tag ordering, cursor mapping, combined-row preflight, the repeatable-read two-pass snapshot, and recovery after an output failure releases admission. Metadata-only local-search coverage must prove zero provider calls in LOCAL, AUTO fallback order, ONLINE exclusion of local results, owner-visible candidate isolation, stable pagination, local continuation after reconnection, and persisted execution provenance. Broader high-contention/load reconciliation remains later work.

## Provider contract tests

Spring `MockRestServiceServer` fixtures use synthetic or permitted sample responses. Every adapter covers its applicable success, pagination, empty/incomplete results, rate limits, timeouts, malformed payloads, and tolerant schema-evolution cases. OpenAlex additionally verifies exact-limit acceptance and non-retryable rejection of oversized bodies with declared or unknown content lengths, plus duplicate/null/negative/out-of-range/excessive abstract-index positions and oversized reconstructed text while the JSON remains below the transport limit. DOAJ fixtures verify keyless v4 article-search requests, literal query escaping, unsupported-filter skips, source-reported access semantics, metadata/link-only mapping, public page/window bounds, and rejection of foreign continuation URLs. CORE fixtures verify the fail-closed licence gate, optional backend bearer key, supported v3 search shape, response aliases, and deliberate omission of full text/download URLs. DataCite fixtures verify thesis/dissertation-only query construction, controlled/legacy type handling, provider-owned cursor paging, OA-only short-circuiting, and no discovery OA/PDF claim. Latch-driven real-transport tests for each discovery adapter stall before headers and after a partial body, asserting stable retryable timeout translation and exactly one upstream request with no automatic retry.

Live-provider tests run manually or on a scheduled, strictly budgeted workflow—not normal pull requests.

The Ollama adapter is exercised through a mock HTTP server with synthetic inputs and vectors. Normal tests do not require an Ollama process, download the 639 MB model, call `/api/pull`, or send scholarly metadata to a hosted provider.

An opt-in Testcontainers evaluation runs the synthetic 18-paper fixture through the real pinned Ollama adapter, performs a complete backfill in an ephemeral PostgreSQL/pgvector database, and measures stable exact cosine neighbors. It is enabled only by `RUN_OLLAMA_VECTOR_EVALUATION=true`; the operator must separately enable the adapter, attest local-only Ollama configuration, and supply the exact full model digest. The gate records Recall@K, nDCG@K, Precision@1, and MRR and is skipped in ordinary CI. It does not enable the application backfill runner, touch a development database, pull a model, or test HNSW.

That manual run also reports an exploratory in-sample hybrid sensitivity sweep over the complete 17-candidate fixture pool. The lexical component is the returned `ts_rank_cd(..., 32)` score, the semantic component is `(cosine + 1) / 2`, and weights `0`, `0.25`, `0.50`, `0.75`, and `1` are all reported. Judgments are applied only after ranking. Endpoint equivalence, repeated component reads, bounded features, deterministic ties, and source/duplicate exclusion are structural gates; no mixed-weight quality floor or product default is selected without independently authored held-out query groups.

A separate opt-in holdout evaluation is enabled only by `RUN_OLLAMA_HOLDOUT_EVALUATION=true`. It proves that the development and holdout paper keys, query keys, source keys, and normalized content fingerprints are disjoint; loads the frozen policy; embeds only the independently authored 26-paper holdout; and evaluates seven query groups over all 25 non-seed candidates. It reads lexical and exact-vector components twice for stability, reports lexical/vector controls and only the frozen `w = 0.50` hybrid, and enforces the predeclared per-query and macro advancement gates. Ordinary CI loads no real model and skips this class.

The first real-model invocation stopped before complete metrics because a valid zero-match Japanese lexical control hit an overstrict structural assertion. Commit `288bf4f` fixed the assertion without consulting labels: empty lexical results remain a measurable control, while vector and frozen-hybrid candidate-pool completeness stays enforced. No policy, fixture paper, judgment, cutoff, or acceptance gate changed. The subsequent run passed all frozen gates: lexical macro `0.857 / 0.648 / 0.286 / 0.571`, exact-vector macro `1.000 / 0.893 / 1.000 / 1.000`, and hybrid macro `1.000 / 0.917 / 0.857 / 0.929` for Recall@K, nDCG@K, Precision@1, and MRR. Five query groups strictly improved nDCG and none regressed.

The separately frozen HNSW gate uses a deterministic 10,000-vector, 1024-dimensional corpus and 20 queries at cutoff 25. Its completed reference run achieved macro Recall@25 `1.0000`, exact p95 `47.491 ms`, approximate p95 `20.082 ms`, and `2.365x` speedup. Ordinary PostgreSQL tests also cover the pinned index/query contract and default-off hybrid orchestration without invoking a real embedding model.

## MCP tests

- Implemented: direct adapter tests for defaults, mode mapping/invariants, bounds, complete provider-provenance mapping, database-only behavior, stable errors, and safe unexpected failures.
- Implemented: safe retryable `SEARCH_COORDINATION_TIMEOUT` and `SEARCH_COORDINATION_INTERRUPTED` tool mappings without nested causes or invented retry-after values.
- Implemented: safe retryable `SEARCH_DEADLINE_EXCEEDED` and `SEARCH_EXECUTION_INTERRUPTED` REST/MCP mappings without nested causes or invented retry-after values.
- Implemented: raw REST and MCP deadline calls prove bounded responses, provider interruption and exit, no post-timeout snapshot, stable versioned tool-error metadata, and no nested-cause leakage.
- Implemented: authenticated raw JSON-RPC initialization, initialized notification, exactly six unique tools, closed mode/source input schemas, output schemas, conservative static annotations, full result provenance, a null-heavy structured search result, and missing/additional/wrong-type argument rejection before provider invocation.
- Implemented: successful raw structured calls for all four database-only tools, using a canonical paper created through the search tool where required.
- Implemented: API-key, duplicate-header, exact-Origin, response-header, and disabled-until-configured security-filter coverage.
- Implemented: hosted missing/invalid/expired/wrong-issuer/wrong-audience/insufficient-scope JWT cases, route-scope enforcement, issuer+subject ownership, protected-resource metadata/challenges, and principal-derived MCP rate-limit keys.
- Implemented: official MCP Inspector CLI discovery and an empty-library `search_saved_library` call against the Compose image.
- Implemented: pinned official conformance `server-initialize` and `tools-list` scenarios with `--spec-version 2025-11-25`; both pass without warnings through the loopback bearer-injection proxy.
- Implemented: locked official JavaScript SDK client smoke over real Streamable HTTP, covering initialization, ping, exact tool/template discovery, the intentionally empty concrete-resource list, and one disposable owner-scoped collection resource read with cleanup.
- Implemented: raw-wire malformed/schema-invalid input, missing-versus-other-owner descriptor equality, oversized request rejection, oversized result rejection without sensitive-content leakage, restricted-access results, and mixed provider success/failure coverage.
- Implemented: the project-owned callback validates successful `structuredContent` before the SDK wrapper and replaces schema drift with the generic versioned `MCP_TOOL_FAILED` result without exposing validator diagnostics.
- Current limitation: the configured 20-second MCP request timeout is not enforced by the stateless MCP Java SDK 2.0 path; the separate 18-second application deadline bounds search use-case execution instead.
- Implemented: repeated JSON-RPC identifiers are shown not to act as idempotency keys; ordinary search fingerprint caching still yields one provider call followed by an exact hit.
- Remaining SDK/transport limitation: client-disconnect and MCP `notifications/cancelled` propagation, framework parsing/serialization and socket-lifetime deadlines, and in-flight JDBC persistence cancellation guarantees.
- Hosted follow-up: live authorization-server/client interoperability, signing-key rotation, token refresh/revocation, and public-edge behavior. Audience/scope/principal ownership is covered synthetically in the application suite.
- Track the canonical frozen `2025-11-25` requirements set as the official runner advances beyond its current fixture-oriented release.

Implemented resource-template coverage includes project-owned unit, integration, and raw JSON-RPC tests that prove:

- initialization advertises a resource capability without `subscribe` or `listChanged`, and `resources/templates/list` returns exactly `openscholar://papers/{paperId}`, `openscholar://collections/{collectionId}`, and `openscholar://searches/{searchId}` with stable JSON metadata;
- `resources/read` returns one bounded `application/json` text resource for each template, with resource-owned deterministic JSON shapes, a 25-item collection page ceiling, and a serialized-size failure path that does not leak discarded content;
- only canonical UUIDs and exact template URIs reach an application use case; malformed or non-canonical IDs, alternate schemes, cross-template paths, extra segments, query strings, fragments, and encoded URI variants fail before dispatch, with raw-wire tests distinguishing fixed matched-template errors from the SDK's standard unmatched-URI error data;
- collection and search handlers map absent and other-owner domain results to indistinguishable safe protocol errors, while hosted OIDC integration coverage proves that the authenticated issuer+subject owner propagates through immediate MCP collection reads;
- paper, collection, and search reads are database-only, verified with zero unintended use-case/provider interactions and with no arbitrary URL dereference, filesystem read, PDF bytes, or source-document fetch;
- `resources/list` returns an empty concrete list, while resource subscriptions and change notifications are neither advertised nor implemented.

The official fixture-oriented conformance suite is not sufficient evidence for this narrower production contract because it expects global resource and subscription behavior that OpenScholar intentionally omits. The production-applicable official subset remains initialization plus tool discovery; project-owned raw-wire tests provide current evidence for resource-template discovery and reads.

If STDIO is added, logs use `stderr`; `stdout` remains protocol-only.

## End-to-end tests

The network-isolated Playwright suite covers search/cache/provider warnings/provenance, immutable continuation, verified versus restricted access, direct PDF.js rendering and keyboard/focus/text controls, individual citation download, paper save, collection creation, reading status/tags, selected citation export, and the personal-data export/confirmed-deletion journey. It blocks unexpected external requests and blocks service workers so request assertions remain visible. This is not a disconnected-browser test.

A dedicated PWA browser scenario permits the production worker, verifies that CacheStorage contains only allowlisted shell assets, confirms successful owned pages are absent, toggles Chromium offline, confirms application unreachability pauses both search actions, loads the account-neutral fallback, runs an accessibility scan, and verifies recovery after reconnection. The encrypted-pack scenario also gates a server collection deletion between request arrival and mutation, begins with an active encrypted pack, proves the real save UI makes no snapshot request while the durable deletion intent is present, rejects a legacy database-version writer, and verifies no active/deletion record after confirmed deletion. A separate delayed-save case proves final-commit rejection. Component and route tests separately cover the advisory `navigator.onLine` signal, the same-origin credentialless `no-store` probe, manual recheck after transient failure, probe-confirmed assistive-technology recovery announcements, continued use of a reachable self-hosted stack with a limited-online-sources warning, fail-closed probe responses, and bypass of hosted session refresh even with an expired cookie. Service-worker policy tests cover network-first install assets, cache-only serving of the required fallback/reader pair, bounded runtime-static cleanup, and deletion of only OpenScholar-owned worker state in non-production runs.

A separate controlled-profile Chromium harness serves synthetic previous, current, incoherent, and uniquely forward-versioned rollback releases from the checked-in worker, shell, and encrypted reader. It proves that an incoherent update becomes redundant and removes its candidate cache while the incumbent remains active; coherent upgrade and schema-compatible rollback workers remain waiting behind an open incumbent client, then activate only after that client closes and a reopened client binds to the new worker. Each activation removes only superseded `openscholar-shell-*` caches, preserves an unrelated cache sentinel and the encrypted IndexedDB envelope/control records byte-for-byte, and still unlocks the saved pack offline with its original passphrase. Public-cache decoys prove that only an anonymous, same-URL allowlisted response is stored: redirects, cookie- or `*`-varying responses, `private`/`no-store` responses, query variants, authorization/range requests, API/auth paths, and document bytes stay out. The rollback release reuses the current schema-compatible implementation under a unique forward revision; this is compatibility evidence, not proof that any historical production artifact can be rolled back safely.

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

The separate `paper-deduplication-development-v2.json` corpus contains 48 synthetic records, 24 gold clusters, 36 positive pairs, 18 case families, six critical must-separate pairs, and three frozen ingest orders. Strict Java loaders reject unknown fields, invalid values, duplicate or broken references, and policy drift; `paper-deduplication-policy-v2.json` binds the fixture digest and frozen exact-baseline metrics. The Testcontainers evaluator runs every order through the real catalog in rollback-only transactions and checks pairwise/B-cubed/exact-cluster/case-family metrics, safety boundaries, and partition stability. It is evaluation-only: v1 production matching remains unchanged and a separately authored disjoint holdout is mandatory before any metadata fallback can activate.

## Performance tests

- A checked-in loopback-safe harness measures one forced cold search, repeated exact-cache searches, repeated paper-detail reads, cache-hit ratio, and Prometheus provider error rate. Its 40-sample synthetic Compose reference run passed at 6.944 ms cached-search p95 and 7.305 ms paper-detail p95; see [Local performance evidence](PERFORMANCE_EVIDENCE.md).
- Deterministic provider tests already exercise concurrent fan-out, partial failure, and pre-header/partial-body stalls under configured deadlines. Raw-wire MCP tests enforce inbound-body and structured-result size limits without leaking oversized content.
- Cached search throughput and concurrent-client capacity remain deployment tests.
- Connection-pool saturation, large normalization/persistence batches, and full-text/vector query latency at realistic scale remain target-environment stress tests.
- MCP framework-serialization throughput and socket-lifetime behavior remain transport/deployment tests; application result-size enforcement is already covered.

Use synthetic metadata; commit no copyrighted corpus.

## CI gates

The list below is the target release gate. Unit/integration/frontend checks, controller-inventory-checked OpenAPI, container builds, lockfile-backed MCP conformance, dependency review, CodeQL, Trivy, secret scanning, SBOM generation, immutable-reference/image-policy/VEX validation, deployment/backup guard validation, an offline Playwright workflow, and a deterministic Compose-backed Playwright workflow have checked-in automation. A checked-in workflow is not evidence that a particular revision passed on GitHub; local results are reported separately.

1. Formatting, static, immutable-reference, frozen-tooling, production image-policy, current scoped-VEX, and deployment-configuration checks.
2. Backend unit/slice/integration, OpenAPI inventory, and ArchUnit boundary tests.
3. Frontend unit/contract/security-header tests and production build.
4. Backend, frontend, Caddy, and blackbox-exporter project-owned final-runtime builds and scans; a release additionally rescans their returned registry digests.
5. Network-isolated, true PWA-offline, and Compose-backed Playwright workflows with WCAG 2.2 axe checks.
6. Raw-wire MCP tests plus the production-applicable conformance subset.
7. Dependency, secret, CodeQL, Trivy, and source/runtime SBOM gates.
8. Guarded backup/restore behavior tests and monitoring configuration/rule validation. These fail-closed script tests use mocks; a release still requires a real backup/restore drill.

Conformance and performance suites may run on main/nightly when unsuitable for every pull request.

## Release verification

Run the checked-in aggregate verifier from a clean committed checkout:

```bash
scripts/verify-clean-clone.sh
```

The script fails closed on a dirty source tree so it cannot silently test an older commit. It creates a detached clone of committed `HEAD`, runs host tools with an allowlisted environment and temporary home/configuration, and then composes—not reimplements—the documentation, supply-chain, operations-policy, PostgreSQL/production guard, backend, frontend, standalone PWA/offline Playwright, isolated full-stack Compose Playwright, official MCP SDK, and supported conformance checks. A final Git status check rejects changes to the detached checkout.

Each run generates local credentials and a unique Compose project. Configurable loopback ports, child-specific proxy readiness, failure logs, and trap-backed cleanup constrain removal to that project's disposable containers, network, and PostgreSQL volume. Bootstrap may contact Maven, npm, Playwright, and Docker registries. Runtime search traffic is routed to the checked-in OpenAlex fixture, optional providers are disabled, and the REST/MCP smokes are database-only; this is controlled application behavior, not live-provider evidence.

The script accepts no skip flags. Chromium is installed when absent, but Linux still needs Playwright's operating-system libraries installed by the host or CI image. Port overrides are listed by `scripts/verify-clean-clone.sh --help` for safe parallel use. The source checkout and host configuration are isolated, but this is not a cold or reproducible build: the Docker daemon may reuse pulled images and build layers.

A successful run is bounded local evidence for that committed revision only. Run the verifier only on trusted commits: Maven/Testcontainers receives privileged Docker-socket access, so an untrusted pull request requires a disposable runner. The following remain separate release gates and are not claimed by the script:

- GitHub-native dependency review, CodeQL, Trivy, secret/misconfiguration scanning, and source/runtime SBOM workflows.
- Publication, registry-digest rescanning, signing, attestation, and deployment pinning of all project-owned images.
- A real backup followed by restoration and data verification in an isolated target-like environment; the local guard tests do not perform that drill.
- A real OIDC client/issuer lifecycle, alert delivery/on-call ownership, and target-environment load, accessibility/assistive-technology, penetration, and disaster-recovery exercises.
- Provider, privacy, licence, and jurisdiction-specific legal approval.
