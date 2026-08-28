# OpenScholar Backend

Java 21 and Spring Boot 4.1 backend for the OpenScholar research workspace and MCP server. It exposes REST and stateless Streamable HTTP MCP adapters over the same application services.

The backend is responsible for:

- scholarly metadata discovery, normalization, exact-identifier deduplication, ranking, and cached search snapshots;
- legal-access resolution through independently verified provider links;
- canonical paper metadata, related-paper retrieval, citations, collections, and reading state;
- durable metadata/access refresh jobs and owner-scoped privacy operations;
- PostgreSQL/pgvector persistence managed by Flyway; and
- local bearer-key or hosted OIDC authorization for the MCP and REST boundaries.

It stores metadata and verified links. It does not proxy, retain, or return research PDF bytes.

## Prerequisites

- JDK 21
- Docker Engine or Docker Desktop with Compose v2

Maven does not need to be installed globally; use the committed Maven Wrapper.

## Run locally

From this directory:

```bash
./mvnw spring-boot:run
```

Spring Boot starts the PostgreSQL/pgvector service in `compose.yaml`, applies Flyway migrations, and binds the application to `127.0.0.1:8080` by default.

To manage PostgreSQL separately:

```bash
docker compose up -d postgres
SPRING_DOCKER_COMPOSE_ENABLED=false ./mvnw spring-boot:run
```

Do not run this directory's Compose service at the same time as the repository-root stack unless their PostgreSQL ports differ.

Check the running service:

```bash
curl --fail http://127.0.0.1:8080/api/v1/system/status
curl --fail http://127.0.0.1:8080/actuator/health
```

## API surface

| Area | Routes |
|---|---|
| Status | `GET /api/v1/system/status` |
| Search snapshots | `POST /api/v1/searches`, `GET /api/v1/searches/{searchId}`, `POST /api/v1/searches/{searchId}/next` |
| Papers | `GET /api/v1/papers/{paperId}`, `GET /api/v1/papers/{paperId}/related` |
| Legal access | `GET /api/v1/papers/{paperId}/versions`, `POST /api/v1/papers/{paperId}/access/verify` |
| Citations | `GET /api/v1/papers/{paperId}/citation`, `POST /api/v1/citations/export` |
| Library | `/api/v1/collections/**`, `GET /api/v1/library/papers` |
| Refresh jobs | `/api/v1/refresh-jobs/**` |
| Privacy | `GET /api/v1/privacy/export`, `DELETE /api/v1/privacy/account` |
| MCP | `POST /mcp` |
| Operations | `/actuator/health`, `/actuator/info`, `/actuator/prometheus` |

The complete REST contract is the checked-in [OpenAPI 3.1 specification](../docs/openapi.yaml). See [REST and MCP contracts](../docs/API_AND_MCP.md) for request semantics, status codes, ownership, and authorization scopes.

Example search:

```bash
curl --request POST http://127.0.0.1:8080/api/v1/searches \
  --header 'Content-Type: application/json' \
  --data '{
    "query": "graph neural networks for drug discovery",
    "filters": {
      "yearFrom": 2021,
      "documentTypes": ["ARTICLE", "PREPRINT"],
      "openAccessOnly": true,
      "languages": ["en"]
    },
    "pageSize": 20
  }'
```

## Configuration

Safe local defaults live in `src/main/resources/application.yaml`. Use environment variables for deployment-specific values and credentials; never commit a populated `.env` file.

| Area | Main settings |
|---|---|
| Database/server | `SPRING_DATASOURCE_URL`, `POSTGRES_USER`, `POSTGRES_PASSWORD`, `SPRING_DOCKER_COMPOSE_ENABLED`, `SERVER_ADDRESS` |
| Search limits | `SEARCH_PROVIDER_CONCURRENCY`, `SEARCH_COORDINATION_WAIT_TIMEOUT`, `SEARCH_EXECUTION_TIMEOUT` |
| Privacy export | `PRIVACY_EXPORT_GLOBAL_PERMITS`, `PRIVACY_EXPORT_PER_PRINCIPAL_PERMITS`, `PRIVACY_EXPORT_RETRY_AFTER` |
| Legal access | `UNPAYWALL_EMAIL`, `UNPAYWALL_BASE_URL`, `ARXIV_BASE_URL` |
| Local MCP | `MCP_LOCAL_API_KEY`, `MCP_ALLOWED_ORIGINS`, `MCP_RATE_LIMIT_*`, `MCP_MAX_*_BYTES` |
| Hosted auth | `OIDC_SECURITY_ENABLED`, `OIDC_ISSUER_URI`, `OIDC_JWK_SET_URI`, `OIDC_AUDIENCE`, `OIDC_MCP_RESOURCE_URI` |
| Refresh jobs | `REFRESH_JOBS_WORKER_ENABLED`, `REFRESH_JOBS_SCHEDULED_ENABLED`, and the associated lease, retry, and batch settings |
| Related papers | `RELATED_PAPERS_HYBRID_ENABLED`, `RELATED_PAPERS_HYBRID_CANDIDATE_POOL_SIZE` |

The [backend environment example](.env.example) lists direct-development variables. The [root environment example](../.env.example) is authoritative for the full Docker Compose stack.

Privacy-export admission is fail-fast and local to each backend JVM/replica. Defaults allow four exports in total and one per authenticated principal, with a 10-second retry hint. Keep the global permit count below Hikari's `maximum-pool-size` with spare connections for normal traffic; multiple replicas multiply the effective cluster capacity.

### Research providers

| Provider | Purpose | Default | Configuration |
|---|---|---:|---|
| OpenAlex | General scholarly discovery | Enabled | `OPENALEX_API_KEY` is optional; transport limits are configurable |
| Europe PMC | Metadata-only `SRC:MED` journal articles held in PMC | Disabled | `EUROPE_PMC_ENABLED=true`; no credential |
| DataCite | Thesis/dissertation metadata | Disabled | `DATACITE_ENABLED=true`; optional contact email |
| DOAJ | Open-access article metadata and reported links | Disabled | `DOAJ_ENABLED=true`; optional contact email |
| CORE | Metadata-only work discovery | Disabled | Requires `CORE_ENABLED=true` and `CORE_LICENSE_CONFIRMED=true`; optional API key |
| Unpaywall | Exact-DOI legal-access evidence | Needs configuration | Requires a backend-owned `UNPAYWALL_EMAIL` |
| arXiv | Exact-arXiv-ID legal-access evidence | Enabled | No credential |

The five discovery adapters are isolated during concurrent fan-out, so an applicable provider failure can be reported alongside useful results from another provider. `SEARCH_PROVIDER_CONCURRENCY` defaults to five, matching the current adapter count, and may be tuned within its validated bounds for the deployment. Enabling CORE records an operator configuration decision; it does not replace the required terms, licence, and attribution review.

Europe PMC is default-off and keyless. It uses only the Articles REST `/search` route, structurally limits discovery to `SRC:MED` journal articles held in PMC, and maps DOI, PMID, PMCID, abstracts, and bounded bibliographic metadata. It ignores `fullTextUrlList`, never calls `fullTextXML`, supplementary-file, PDF, or bulk-download routes, and always leaves the discovery `pdfUrl` null. A Europe PMC open-access value is stored only as an unverified provider hint because rights vary by article; the exact-identifier legal-access pipeline remains separate. Provider policy and document-handling boundaries are documented in [Security and legal](../docs/SECURITY_AND_LEGAL.md).

### MCP

The `/mcp` endpoint is disabled at the security boundary until `MCP_LOCAL_API_KEY` is set in local mode. It exposes six bounded tools over stateless Streamable HTTP. Use the [MCP quickstart](../docs/MCP_QUICKSTART.md) for key generation, client configuration, tool discovery, and protocol smoke checks.

## Verify

With Docker running:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

The suite includes unit, MVC, module-boundary, migration, persistence, provider-contract, raw MCP, and PostgreSQL/pgvector Testcontainers coverage. Normal verification does not contact live research providers or Ollama.

The deterministic Europe PMC quality gate can be isolated with `./mvnw --batch-mode --no-transfer-progress -Dtest=EuropePmcProviderQualityEvaluationTests test`. It uses synthetic inputs and the real catalog/search-snapshot stores; it neither enables Europe PMC nor publishes metrics to readers. A fused-page diagnostic remains available from the repository root with `node scripts/capture-europe-pmc-quality.mjs --base-url http://127.0.0.1:8080`; a separate private Actuator origin may be supplied with `--management-url` and `OPENSCHOLAR_PROVIDER_QUALITY_METRICS_BEARER_TOKEN`. Production Actuator stays private and un-published—use an approved loopback/private tunnel or evaluation endpoint, never a public proxy route.

For isolated-provider and pre-reconciliation evidence, run the separately opt-in `EuropePmcComparativeLiveEvaluationTests` from a fresh or disposable clean committed checkout. It calls OpenAlex and Europe PMC once per frozen query, replays the identical raw metadata through rollback-only OpenAlex-only, Europe-PMC-only, and fused scenarios in a disposable Testcontainers database, and writes private digest-bound artifacts below ignored `backend/target/provider-quality/`. The runner verifies the claimed revision against `HEAD`, rejects a dirty worktree, pins the official HTTPS endpoints, and disables the OpenAlex API key. Use `clean test` to exclude stale ignored bytecode; first retain any approved prior capture elsewhere because Maven `clean` removes `backend/target/`:

```bash
RUN_PROVIDER_QUALITY_COMPARATIVE_CAPTURE=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeLiveEvaluationTests \
  clean test
```

The comparative runner never downloads documents or serializes PDF URLs, and ordinary verification skips it before creating a Spring context. It produces unlabelled engineering evidence, not a reader feature or permission to enable Europe PMC by default. See [Provider quality evaluation](../docs/PROVIDER_QUALITY.md) for artifact separation, retention, holdout, and decision boundaries.

Comparative payload documents use schema version `2`: ranked scenario results expose only bounded metadata-field presence bits, never canonical metadata values. The enclosing `manifest.json` remains schema version `1`. Before scoring, a read-only verifier requires exactly `manifest.json`, `summary.json`, `blinded-candidates.json`, `provenance-map.json`, and `reconciliation-trace.json`; it checks the bounded file set, sizes, SHA-256 digests, payload schemas, shared evidence identity, and review-ready status.

Generate a provenance-free packet and incomplete worksheet from an approved capture. Keep the capture outside `backend/target/`: every command below uses Maven `clean`, which deletes prior captures, review files, judgments, and reports there.

```bash
RUN_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE=/absolute/evidence-dir \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeReviewPacketGenerationTests \
  clean test
```

Give the independent reviewer only the generated `review-packet.json` and `review-worksheet.json`. Preserve the packet's exact immutable bytes for compilation and scoring. It contains an opaque session binding, safe query text, neutral packet-local candidate keys, and blinded bibliographic metadata; author entries expose only display names, and language values use a canonical ISO-639-3 form. It exposes no raw internal review key, campaign-bearing binding, provider-specific author-position semantics, or corresponding-author flag and never includes provenance, reconciliation, or scenario results. In the worksheet, complete every null candidate value without changing `candidateKey` order, use the same safe `goldPaperKey` for the same work, and assign consistent `0..3` grades and expected fields within each work. `expectedFields` is a lexicographically sorted unique array of `ABSTRACT`, `AUTHORS`, `CITATION_COUNT`, `DOCUMENT_TYPE`, `DOI`, `ISSN`, `LANGUAGE`, `ORCID`, `PMCID`, `PMID`, `PUBLICATION_YEAR`, `SOURCE_URL`, `TITLE`, or `VENUE`; `[]` explicitly records that none are expected. Must-separate `leftCandidateKey`/`rightCandidateKey` values and the pair array must be in canonical ascending order; `DISTINCT_WORKS` is one valid uppercase reason code. Set `mustSeparateReviewComplete=true` for every query even when there are no pairs, and enter `AUTHORED_WITHOUT_PROVENANCE_OR_SCENARIO_OUTPUT` only when it is true.

Preserve the exact packet, completed worksheet, and verified capture outside `backend/target/`, then compile the strict judgment packet:

```bash
RUN_PROVIDER_QUALITY_COMPARATIVE_REVIEW_COMPILE=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE=/absolute/evidence-dir \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET=/absolute/review-packet.json \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_WORKSHEET=/absolute/review-worksheet.json \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeReviewWorksheetCompilationTests \
  clean test
```

The compiler reruns the full evidence preflight, verifies the exact packet bytes, rejects incomplete, reordered, duplicate, or mismatched review content, translates candidate aliases back to hidden scorer keys, and writes a private ignored `provider-quality-independent-judgments-v2` `judgments.json` below `backend/target/provider-quality/`. The judgment file binds the packet SHA-256 and exact evidence/query-set/policy values. No real worksheet, label, or judgment packet is checked in. Packet generation and compilation use no Spring context, Docker, network, PDF, UI, or runtime endpoint and do not enable Europe PMC.

Pass the compiled `judgments.json` and the exact immutable reviewed packet to the separately opt-in offline scorer. Move or copy the judgments outside `backend/target/` before running the required `clean test`:

```bash
RUN_PROVIDER_QUALITY_COMPARATIVE_SCORING=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE=/absolute/evidence-dir \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET=/absolute/review-packet.json \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_JUDGMENTS=/absolute/judgments.json \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeOfflineScoringTests \
  clean test
```

Before scoring, the runner repeats the full evidence preflight, regenerates the expected review projection, verifies the supplied packet's exact bytes, and requires its SHA-256 to equal the compiled judgment binding. The judgment file also binds the exact evidence-manifest, query-set, and frozen-scoring-policy digests and attests that it was authored without provenance or scenario output. Eligible captures must retain the exact blinded-review instruction and evidence-scoped shuffled candidate order, deterministic review keys are recomputed, and hidden provenance values are checked against their bounded producer schema. The runner binds the claimed query-set ID, digest, and ordered keys to the checked-in frozen resource. It rejects a dirty checkout, a claimed revision different from `HEAD`, or scorer code at a revision different from the capture revision. It uses no Spring context, Docker, network, PDF, UI, or runtime endpoint. It computes cluster-aware Recall@20, nDCG@10, Precision@5, and MRR@20 together with per-query pairwise deduplication, must-separate violations, expected-field recovery, and Europe-PMC-unique relevant-query coverage. Queries with no relevant judged candidate and deduplication rates with a zero denominator remain in the report with explicit not-applicable rates and counts; they are not reported as perfect or silently dropped.

The v2 score summary carries the capture's canonical timestamp, manifest digest, and repository revision. After publishing a new private ignored report below `backend/target/provider-quality/` through create-new staging/move semantics, the runner reopens and exactly verifies its three-file layout, total bound, canonical manifest, file digests, strict JSON, recomputed report identity, and regenerated payload bytes before printing success. To verify a retained copy without writing another report, rerun the same command and add `OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_SCORE_REPORT=/absolute/external/report-directory`; the runner rejects lexical or resolved aliases into `backend/target/` because `clean` deletes that tree first. Replay assumes a locally operator-controlled directory with no concurrent file or ancestor replacement. The success record omits the environment-supplied path and contains only the mode, verified report ID, report-manifest digest, evidence ID, capture time, revision, query count, and total bytes—not metrics, queries, labels, or candidate data.

Exact report replay remains a report-only integrity check. The optional run-seal promotion below additionally retains the verified inputs and binds the exact completed worksheet bytes to the scored run. Neither mode enforces external authenticity, confidentiality, retention, or provider enablement.

### Local comparative run-seal promotion

The existing offline scoring runner can promote one fully verified comparative run into an operator-controlled filesystem root. Promotion mode requires these two variables together; supplying only one fails:

```bash
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_WORKSHEET=/absolute/completed-worksheet.json \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_ROOT=/absolute/operator-controlled-root \
RUN_PROVIDER_QUALITY_COMPARATIVE_SCORING=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE=/absolute/evidence-dir \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_REVIEW_PACKET=/absolute/review-packet.json \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_JUDGMENTS=/absolute/judgments.json \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeOfflineScoringTests \
  clean test
```

The run-seal root must already exist, be an absolute real directory, and resolve outside the repository. On a POSIX filesystem it must be owner-private and traversable (`0700`); for example, run `chmod 700 /absolute/operator-controlled-root` before promotion. The publisher also requires every created directory to remain `0700` and every created file to remain `0600`. It assumes that the root, its ancestors, and its files are controlled by the operator and cannot be replaced or modified concurrently.

The publisher stages private create-new files beneath the root and requires an atomic move for publication; a filesystem without atomic-move support fails closed. It reopens the final directory and verifies its exact bytes and layout. The scoring runner then performs the full semantic replay: evidence verification and preflight, review-packet regeneration and exact verification, completed-worksheet compilation, exact canonical-judgment comparison, rescoring, exact score-report verification, and final run-seal verification. An existing deterministic run ID is accepted idempotently only when its complete bundle verifies exactly; it is never overwritten.

The deterministic canonical v1 bundle has this fixed layout:

```text
<runId>/
  run-seal.json
  capture/<evidenceId>/
    manifest.json
    summary.json
    blinded-candidates.json
    provenance-map.json
    reconciliation-trace.json
  review/
    review-packet.json
    completed-worksheet.json
    judgments.json
  score/<reportId>/
    manifest.json
    query-scores.json
    score-summary.json
```

The 11 retained payload files are bounded to 153,157,632 bytes in total, `run-seal.json` is bounded to 65,536 bytes, and the complete bundle is bounded to 153,223,168 bytes. Existing component limits remain in force: 64 MiB for capture payloads plus a 64 KiB capture manifest, 72 MiB for the review packet, 1 MiB each for the completed worksheet and judgments, and 8 MiB for the complete score bundle.

The deterministic canonical `provider-quality-comparative-run-seal-v1` document binds the exact completed worksheet bytes, canonical judgments, capture, review packet, scoring policy/query set, and exact score report through a versioned identity and sorted relative-path/size/SHA-256 inventory. A successful promotion logs only its mode, run-seal ID, run-seal SHA-256, and report ID—never paths, metrics, queries, labels, or candidate data. The independent reviewer must never receive this bundle because it contains hidden capture provenance and score material; the reviewer still receives only `review-packet.json` and the worksheet.

This local SHA-256 seal provides integrity linkage, not authentication, a signature, a trusted timestamp, WORM storage, retention enforcement, access history, or confidentiality. Operators must provide the required encryption, access controls, immutable/versioned storage, signing, retention, and audit policy outside OpenScholar. Promotion itself adds no cloud integration, database, UI, MCP/REST surface, PDF handling, or longitudinal conclusion.

### Verify a retained comparative run seal

Use the standalone verifier to check one retained run-seal directory without publishing or changing provider-quality artifacts:

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_VERIFY=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_DIRECTORY=/absolute/external/provider-quality-comparative-run-seal-v1-... \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeRunSealVerificationTests \
  clean test
```

`OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_RUN_SEAL_DIRECTORY` must name one existing absolute real, non-symlink run directory that resolves outside the repository. The verifier requires the checked-out worktree to be clean, requires `OPENSCHOLAR_PROVIDER_QUALITY_REVISION` to equal committed `HEAD`, and requires the seal's capture revision to equal that exact revision. Run it only against a local operator-controlled directory whose files and ancestors cannot be replaced or mutated concurrently.

The verifier first enforces the exact canonical bundle, layout, permissions, limits, identities, sizes, and SHA-256 inventory. It then performs the complete semantic replay from the retained bytes: strict capture verification and preflight; frozen query-set and scoring-policy loading from the exact revision; review-packet regeneration and exact verification; completed-worksheet compilation; exact canonical-judgment comparison and loading; rescoring; exact score-report verification; recomputation of every seal binding; and final seal verification.

The verifier itself writes no provider-quality artifact or bundle bytes and never modifies the retained directory. Maven `clean test` may still create ordinary ignored build and test output below `backend/target/`. The path has no Spring context, Docker, network, PDF, UI, REST, or MCP dependency. On success it prints only this bounded record shape—no path, revision, metric, query, label, or candidate data:

```text
provider-quality-comparative-run-seal-v1 mode=verified run-seal-id=<id> run-seal-sha256=<sha256> report-id=<id>
```

This is local SHA-256 integrity and semantic-replay evidence under the stated filesystem assumption. It provides no authentication, signature, trusted timestamp, WORM or immutable-storage guarantee, confidentiality, retention or deletion enforcement, access history, or audit evidence. Keep those controls external, and never send the run-seal bundle to the independent reviewer; that reviewer receives only `review-packet.json` and the worksheet.

### Compare retained runs longitudinally

The separate local comparator accepts a strict private JSON selection of two through sixteen retained run-seal directories. Every run must use the same exact clean repository revision, report schema, frozen query-set identity and ordered keys, scoring-policy identity, query count, and scenarios; run-seal, evidence, report, and canonical capture-time identities must be distinct. The selection file must be an absolute real non-symlink file outside the repository, bounded to 16 KiB, and `0600` on POSIX filesystems. Every selected path must be an absolute real non-symlink directory outside the repository, with no resolved duplicates or ancestor overlap:

```json
{
  "schemaVersion": 1,
  "protocolId": "provider-quality-comparative-longitudinal-selection-v1",
  "runSealDirectories": [
    "/absolute/operator-controlled/run-seal-a",
    "/absolute/operator-controlled/run-seal-b"
  ]
}
```

Run the file-only workflow from the exact revision bound by all selected seals:

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_SELECTION=/absolute/private-selection.json \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeLongitudinalComparisonTests \
  clean test
```

The runner exactly verifies and semantically replays every seal before it writes a report. It sorts by the content-bound canonical capture time, derives a deterministic path-independent identity from the ordered seal IDs and SHA-256 values, and publishes exactly `manifest.json` plus `longitudinal-report.json` below ignored `backend/target/provider-quality/<comparisonId>/`. The private bundle is capped at 8 MiB and uses `0700`/`0600` permissions on POSIX filesystems. `clean` deletes it.

To promote the same verified bytes into governed external storage before a later clean, export this optional variable before running the command:

```bash
export OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_REPORT_ROOT=/absolute/private-retention-root
```

The root must already exist as an absolute real, final-component non-symlink directory outside—and not containing—the repository, be writable by the invoking operator, use `0700` on POSIX filesystems, and be disjoint from every selected run directory. This is a local filesystem-directory handoff, not an object-storage or cloud-retention integration. Promotion copies only the exact two report files through a create-new private staging directory and requires the filesystem to support a same-filesystem atomic rename to `<root>/<comparisonId>`; unsupported atomic publication fails closed. An existing destination is accepted only when exact verification against the replay-built comparison succeeds. For an absent destination, the publisher requests an atomic move without replacement, but Java does not guarantee no-replacement if another process creates the target concurrently. The non-overwrite behavior therefore depends on the required operator-controlled, non-concurrently-mutated filesystem assumption. The generated source is never modified, and the retained copy is reopened and exactly verified before success.

The report keeps exact run snapshots and adjacent aggregate and per-query changes with their counts and undefined values. It never averages captures, assigns improvement/regression or significance, applies a pass/fail gate, or changes a provider default. Its metrics remain operator-only and are never exposed through the UI, REST, MCP, database, Spring application, Docker, or normal logs. Without the optional root, success prints only:

```text
provider-quality-comparative-longitudinal-v1 mode=generated comparison-id=<id> manifest-sha256=<sha256> runs=<count>
```

Successful external promotion instead prints the equally bounded record:

```text
provider-quality-comparative-longitudinal-v1 mode=promoted comparison-id=<id> manifest-sha256=<sha256> runs=<count>
```

Beyond the displayed comparison ID and manifest digest, neither record contains a path, revision, capture time, individual run-seal/evidence/report identity, query, metric, label, candidate datum, or byte count. The comparator and promoter use no Spring context, Docker, network, provider call, PDF/document path, or runtime endpoint. The declared capture times are content-bound but are not trusted timestamps; the local atomic copy supplies no authentication, signing, immutable retention, confidentiality, deletion, or access/audit control. See [Provider quality evaluation](../docs/PROVIDER_QUALITY.md) for the full boundary.

### Verify a retained longitudinal report

After automatically promoting—or otherwise placing—an approved longitudinal report in governed external storage, verify it without generating or changing any provider-quality artifact:

```bash
cd backend
RUN_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_REPORT_VERIFY=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_SELECTION=/absolute/private-selection.json \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_LONGITUDINAL_REPORT_DIRECTORY=/absolute/external/provider-quality-comparative-longitudinal-v1-... \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeLongitudinalReportVerificationTests \
  clean test
```

Both inputs must exist outside the repository before the command starts because Maven `clean` removes the original ignored report. The selection keeps its strict private-file contract. The report input must be an existing absolute real, non-symlink directory outside the repository, retain the comparison-ID directory name, contain exactly `manifest.json` and `longitudinal-report.json`, and keep the report bundle's private permissions. Use only operator-controlled paths whose files and ancestors cannot be replaced or mutated concurrently.

The verifier checks the clean exact revision, then exactly verifies and fully semantically replays every selected run seal. It reconstructs the canonical comparison from those replayed results and only then verifies the retained report's exact layout, identity, canonical bytes, sizes, manifest, and digests against that reconstruction. The report never supplies its own expected cohort or identity.

The verifier itself writes no provider-quality artifact or bundle bytes and does not modify the selection, run seals, or report. Maven may still create ordinary ignored build/test output below `backend/target/`. Success prints no path, revision, capture time, run identity, query, metric, label, or candidate data:

```text
provider-quality-comparative-longitudinal-v1 mode=verified comparison-id=<id> manifest-sha256=<sha256> runs=<count>
```

This remains local deterministic integrity and semantic-replay evidence—not authentication, a signature, trusted time, immutable retention, confidentiality, deletion enforcement, access history, audit evidence, statistical interpretation, or a provider-enablement decision.

## Optional local embeddings

Embedding generation is an offline maintenance workflow and is disabled during normal startup and CI. The current profile is pinned to a separately installed loopback Ollama `0.31.1` process and the exact `qwen3-embedding:0.6b` artifact. OpenScholar never pulls the model automatically.

Before a run:

1. Configure `OLLAMA_NO_CLOUD=1` on the Ollama server process and verify its startup log.
2. Install the pinned model yourself with `ollama pull qwen3-embedding:0.6b`.
3. Inspect the local runtime and installed models, then copy the complete
   `models[].digest` value for `qwen3-embedding:0.6b` into
   `OLLAMA_EMBEDDING_MODEL_DIGEST`:

```bash
curl -fsS http://127.0.0.1:11434/api/version
curl -fsS http://127.0.0.1:11434/api/tags
```

Keep the service on a numeric loopback URL and reject a changed runtime or
artifact digest until it has been reviewed as a new immutable profile.

Run one bounded backfill page against an already running database:

```bash
SPRING_MAIN_WEB_APPLICATION_TYPE=none \
SPRING_DOCKER_COMPOSE_ENABLED=false \
OLLAMA_EMBEDDING_ENABLED=true \
OLLAMA_LOCAL_ONLY_CONFIRMED=true \
OLLAMA_QWEN3_EMBEDDING_DIGEST='replace-with-the-full-64-character-digest' \
EMBEDDING_BACKFILL_ENABLED=true \
EMBEDDING_BACKFILL_LIMIT=100 \
./mvnw spring-boot:run
```

Omit `SPRING_DOCKER_COMPOSE_ENABLED=false` when no database is already running and the backend Compose service should start it. The process reports a `nextCursor` for another page; resume with `EMBEDDING_BACKFILL_AFTER_EXCLUSIVE`. Do not enable the backfill in a web application.

`GET /api/v1/papers/{paperId}/related` never invokes Ollama. Its optional hybrid mode reads only precomputed vectors and remains off by default. The immutable profile design and measured evaluation are documented in [ADR 0005](../docs/decisions/0005-versioned-embedding-profiles.md), [Search quality](../docs/SEARCH_QUALITY.md), and the [HNSW evaluation protocol](../docs/HNSW_EVALUATION_PROTOCOL.md).

## Architecture and operations

The backend is a Spring Modulith modular monolith. Feature modules expose application-facing types while implementations remain below their `internal` packages. See:

- [Architecture](../docs/ARCHITECTURE.md)
- [Data model](../docs/DATA_MODEL.md)
- [Development guide](../docs/DEVELOPMENT.md)
- [Provider quality evaluation](../docs/PROVIDER_QUALITY.md)
- [Hosted deployment](../docs/DEPLOYMENT.md)
- [Operations runbook](../docs/OPERATIONS_RUNBOOK.md)
- [Threat model](../docs/THREAT_MODEL.md)

The Next.js application is documented in the [frontend README](../frontend/README.md).
