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
| Search limits | `SEARCH_COORDINATION_WAIT_TIMEOUT`, `SEARCH_EXECUTION_TIMEOUT` |
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
| DataCite | Thesis/dissertation metadata | Disabled | `DATACITE_ENABLED=true`; optional contact email |
| DOAJ | Open-access article metadata and reported links | Disabled | `DOAJ_ENABLED=true`; optional contact email |
| CORE | Metadata-only work discovery | Disabled | Requires `CORE_ENABLED=true` and `CORE_LICENSE_CONFIRMED=true`; optional API key |
| Unpaywall | Exact-DOI legal-access evidence | Needs configuration | Requires a backend-owned `UNPAYWALL_EMAIL` |
| arXiv | Exact-arXiv-ID legal-access evidence | Enabled | No credential |

Optional providers are isolated during concurrent fan-out, so an applicable provider failure can be reported alongside useful results from another provider. Enabling CORE records an operator configuration decision; it does not replace the required terms, licence, and attribution review. Provider policy and document-handling boundaries are documented in [Security and legal](../docs/SECURITY_AND_LEGAL.md).

### MCP

The `/mcp` endpoint is disabled at the security boundary until `MCP_LOCAL_API_KEY` is set in local mode. It exposes five bounded tools over stateless Streamable HTTP. Use the [MCP quickstart](../docs/MCP_QUICKSTART.md) for key generation, client configuration, tool discovery, and protocol smoke checks.

## Verify

With Docker running:

```bash
./mvnw --batch-mode --no-transfer-progress verify
```

The suite includes unit, MVC, module-boundary, migration, persistence, provider-contract, raw MCP, and PostgreSQL/pgvector Testcontainers coverage. Normal verification does not contact live research providers or Ollama.

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
- [Hosted deployment](../docs/DEPLOYMENT.md)
- [Operations runbook](../docs/OPERATIONS_RUNBOOK.md)
- [Threat model](../docs/THREAT_MODEL.md)

The Next.js application is documented in the [frontend README](../frontend/README.md).
