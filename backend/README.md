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

After an independent reviewer supplies a strict judgment packet, run the separately opt-in offline scorer from this directory. Move or copy the approved capture outside `backend/target/` first: the required Maven `clean` deletes all prior captures and reports below that directory.

```bash
RUN_PROVIDER_QUALITY_COMPARATIVE_SCORING=true \
OPENSCHOLAR_PROVIDER_QUALITY_REVISION="$(git rev-parse HEAD)" \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_EVIDENCE=/absolute/evidence-dir \
OPENSCHOLAR_PROVIDER_QUALITY_COMPARATIVE_JUDGMENTS=/absolute/judgments.json \
./mvnw --batch-mode --no-transfer-progress \
  -Dtest=EuropePmcComparativeOfflineScoringTests \
  clean test
```

The packet must bind the exact evidence-manifest, query-set, and frozen-scoring-policy digests and attest that it was authored without provenance or scenario output. Eligible captures must retain the exact blinded-review instruction and evidence-scoped shuffled candidate order, deterministic review keys are recomputed, and hidden provenance values are checked against their bounded producer schema before scoring. The runner also binds the claimed query-set ID, digest, and ordered keys to the checked-in frozen resource. No real judgment packet is checked into this repository. The runner rejects a dirty checkout, a claimed revision different from `HEAD`, or scorer code at a revision different from the capture revision. It uses no Spring context, Docker, network, PDF, UI, or runtime endpoint. It computes cluster-aware Recall@20, nDCG@10, Precision@5, and MRR@20 together with per-query pairwise deduplication, must-separate violations, expected-field recovery, and Europe-PMC-unique relevant-query coverage. Queries with no relevant judged candidate and deduplication rates with a zero denominator remain in the report with explicit not-applicable rates and counts; they are not reported as perfect or silently dropped. Its digest-bound report is a private ignored artifact below `backend/target/provider-quality/`; it is not an enablement gate or a provider-default decision.

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
