# OpenScholar Backend

Java 21 and Spring Boot 4.1 backend for OpenScholar MCP.

## Current capabilities

- Spring MVC and validation
- PostgreSQL persistence through Spring Data JPA
- Flyway-managed schema
- OpenAlex topic search with bounded filters and opaque cursor paging
- Canonical DOI/OpenAlex deduplication, authors, and provider provenance
- Read-only canonical paper details with metadata completeness, record-level provenance, and stored-access summary
- Paper-specific credited author names and publication date/year integrity enforced by Flyway
- Immutable 24-hour search snapshots with exact-cache hits and stale fallback
- Exact DOI legal-access lookup through Unpaywall when a backend contact email is configured
- Exact arXiv-ID access lookup with canonical entry matching and a three-second request gate
- Provider-isolated access resolution with a 24-hour cache, forced refresh, and stale fallback
- SSRF-resistant PDF/landing-link verification with bounded requests and manually checked redirects
- Link-only paper-version persistence; the backend never proxies or stores complete PDF documents
- Deterministic single-paper BibTeX and CSL-JSON citation downloads from canonical metadata
- Owner-scoped local collections with persisted reading status and normalized tags
- Literal-safe saved-library search across papers, authors, venues, and collection names
- Ordered, bounded multi-paper BibTeX and CSL-JSON citation downloads
- Five typed, non-destructive, read-oriented MCP tools over stateless Streamable HTTP
- Local MCP bearer-key authentication, exact Origin validation, per-address rate limiting, request IDs, and Micrometer request metrics
- RFC 9457 validation, not-found, and provider-failure responses
- Spring Boot Actuator
- Spring Modulith boundary verification
- Testcontainers integration tests
- Docker Compose PostgreSQL/pgvector service
- Provider-neutral, checksum-guarded paper embedding profiles and vector storage
- Disabled-by-default, artifact-and-runtime-pinned local Ollama embedding adapter
- Explicit, resumable offline embedding backfill with per-profile PostgreSQL locking
- Java 21 virtual threads

The Next.js search, details, verified-version, PDF.js reader, citation, and research-library UI is available in `../frontend`. Semantic generation is an offline maintenance capability; the related-paper API remains database-only and still uses its measured lexical implementation until vector and hybrid quality pass the evaluation gates.

## Run locally

Start Docker Desktop/Engine, then run:

```bash
./mvnw spring-boot:run
```

Spring Boot detects `compose.yaml`, starts PostgreSQL when required, runs Flyway, and exposes:

- Application status: `http://localhost:8080/api/v1/system/status`
- Create/reuse a search: `POST http://localhost:8080/api/v1/searches`
- Retrieve a saved snapshot: `GET http://localhost:8080/api/v1/searches/{searchId}`
- Read canonical paper details: `GET http://localhost:8080/api/v1/papers/{paperId}`
- Read stored access versions: `GET http://localhost:8080/api/v1/papers/{paperId}/versions`
- Resolve or refresh legal access: `POST http://localhost:8080/api/v1/papers/{paperId}/access/verify`
- Download a citation: `GET http://localhost:8080/api/v1/papers/{paperId}/citation?format=bibtex`
- List/create collections: `GET|POST http://localhost:8080/api/v1/collections`
- Manage one collection: `GET|PATCH|DELETE http://localhost:8080/api/v1/collections/{collectionId}`
- Search saved papers: `GET http://localhost:8080/api/v1/library/papers`
- Export a citation batch: `POST http://localhost:8080/api/v1/citations/export`
- MCP endpoint: `POST http://localhost:8080/mcp`
- Actuator health: `http://localhost:8080/actuator/health`
- Actuator info: `http://localhost:8080/actuator/info`

The server binds to `127.0.0.1` by default. Set `SERVER_ADDRESS=0.0.0.0` only in a container or deployment that also supplies the appropriate network and authentication controls.

Example search:

```bash
curl --request POST http://localhost:8080/api/v1/searches \
  --header 'Content-Type: application/json' \
  --data '{
    "query": "graph neural networks for drug discovery",
    "filters": {
      "yearFrom": 2021,
      "yearTo": 2026,
      "documentTypes": ["ARTICLE", "PREPRINT"],
      "openAccessOnly": true,
      "languages": ["en"]
    },
    "pageSize": 20
  }'
```

To manage PostgreSQL separately:

```bash
docker compose up -d postgres
SPRING_DOCKER_COMPOSE_ENABLED=false ./mvnw spring-boot:run
```

Use a canonical paper ID returned by search to resolve legal access:

```bash
PAPER_ID='replace-with-a-paper-id-from-search'

curl --request POST \
  "http://localhost:8080/api/v1/papers/${PAPER_ID}/access/verify?forceRefresh=false"

curl \
  "http://localhost:8080/api/v1/papers/${PAPER_ID}/versions"

curl \
  "http://localhost:8080/api/v1/papers/${PAPER_ID}"
```

`forceRefresh=false` reuses a fresh access result. Set it to `true` to bypass the fresh-cache check and contact applicable providers; refreshing an existing resolution reports `FORCED_REFRESH`, while an initial resolution reports `RESOLVED`. Repeated forced refreshes for the same paper are limited by `openscholar.access.force-refresh-cooldown`, which defaults to five minutes; an early retry returns `429 ACCESS_REFRESH_RATE_LIMITED` with `Retry-After`. `GET /versions` is read-only and never contacts a provider.

`GET /papers/{paperId}` is also database-only. It returns canonical metadata, ordered credited authors, every stored identifier, record-level provider provenance, metadata freshness/completeness, and a compact summary of the currently stored access result. Provider payload fragments and unverified provider PDF links are deliberately excluded; use `/versions` for verified legal locations.

Export that paper's current canonical metadata without a provider call:

```bash
curl --remote-header-name --remote-name \
  "http://localhost:8080/api/v1/papers/${PAPER_ID}/citation?format=bibtex"

curl --remote-header-name --remote-name \
  "http://localhost:8080/api/v1/papers/${PAPER_ID}/citation?format=csl-json"
```

BibTeX is the default format. Both responses are raw importable documents with deterministic UUID-based citation keys and attachment filenames. Exports omit unavailable fields; author names remain literal display names because the current catalog does not safely distinguish given, family, particle, suffix, and organization names.

The local library seeds one fixed development user. Every collection lookup and mutation is owner-scoped so the storage boundary can later be replaced by an authenticated principal without changing the public use case. A saved paper stores only its canonical paper reference, collection membership, reading status, and up to ten normalized tags; it does not copy or retain the PDF.

## Verify

```bash
./mvnw verify
```

Integration tests use a real PostgreSQL/pgvector Testcontainer; Docker must be running.

## Configuration

OpenAlex permits limited keyless trials, but a free API key provides a larger daily allowance. Supply it only through the server environment:

```bash
OPENALEX_API_KEY=your-key ./mvnw spring-boot:run
```

Unpaywall's exact DOI endpoint requires a contact email. The application can start without one, but Unpaywall then reports `NOT_CONFIGURED`; arXiv resolution remains available for papers with an arXiv ID. Configure a backend-owned address, never an end-user address:

```bash
UNPAYWALL_EMAIL=backend-contact@example.org ./mvnw spring-boot:run
```

The arXiv adapter needs no credential. It uses an exact `id_list` lookup with one result and enforces at least three seconds between requests. Provider base URLs can be overridden for local tests through the variables documented in the [root environment example](../.env.example).

The root `.env.example` documents the full-stack variables; `backend/.env.example` documents direct backend development variables. Never commit `.env` or credentials. OpenAlex authorization and the Unpaywall contact email remain server-side and are never exposed through REST responses.

The MCP endpoint is disabled at the security boundary until `MCP_LOCAL_API_KEY` is set. See the [MCP quickstart](../docs/MCP_QUICKSTART.md) for discovery, tool calls, Origin policy, and client configuration.

## Generate local paper embeddings

Embedding generation is optional and disabled by default. Ordinary application startup, REST/MCP reads, and CI do not contact Ollama or download a model. This profile requires the separately installed [Ollama server version `0.31.1`](https://github.com/ollama/ollama/releases/tag/v0.31.1); a runtime upgrade is a new vector space and requires a code/profile change. Configure `OLLAMA_NO_CLOUD=1` on the **Ollama server process**, restart that process, and confirm its startup log contains `Ollama cloud disabled: true` before installing the fixed model tag:

```bash
ollama pull qwen3-embedding:0.6b
```

OpenScholar never calls Ollama's pull endpoint. Verify the runtime, then inspect the local tags response and copy the full lowercase 64-character digest for the exact `qwen3-embedding:0.6b` entry:

```bash
OLLAMA_BASE_URL='http://127.0.0.1:11434'
curl --noproxy '*' --fail-with-body --silent --show-error "${OLLAMA_BASE_URL}/api/version"
curl --noproxy '*' --fail-with-body --silent --show-error "${OLLAMA_BASE_URL}/api/tags"
```

Use the same numeric-loopback `OLLAMA_BASE_URL` for preflight and backfill. The version response's JSON `version` field must equal `0.31.1`. Setting `OLLAMA_NO_CLOUD=1` only on the Maven command does not reconfigure an already-running Ollama server. `OLLAMA_LOCAL_ONLY_CONFIRMED=true` below is an operator attestation; set it only after checking the server log. See the [Ollama local-only configuration](https://docs.ollama.com/faq#how-do-i-disable-ollamas-cloud-features).

Run one bounded maintenance page from the `backend` directory. If the root stack or another PostgreSQL instance is already running, prevent Spring from starting `backend/compose.yaml` as well:

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

If no PostgreSQL service is already running, omit `SPRING_DOCKER_COMPOSE_ENABLED=false` and Spring will start the backend Compose database. Do not run the root and backend Compose stacks together because both publish PostgreSQL on the same port. Maven launched from `backend` does not load the root `.env`; if the running database uses non-default ports or credentials, also pass an explicit `SPRING_DATASOURCE_URL`, `POSTGRES_USER`, and `POSTGRES_PASSWORD`. The datasource pool must permit at least two connections; the default Hikari pool does.

The adapter accepts only a numeric loopback HTTP root URL, bypasses system proxies, refuses redirects, and caps every response at 2 MiB. It verifies Ollama `0.31.1`, the exact `qwen3-embedding:0.6b` tag and full digest, embedding capability, context/output dimensions, and both the runtime and digest again after inference; requests use `truncate=false`. Verification retries are bounded, and systemic runtime/model drift aborts the page instead of being recorded as an isolated paper failure. After verification, it registers a digest/runtime-derived profile key of the form `paper-semantic-v1-<full-digest>-ollama-0-31-1`; its immutable model revision is `sha256:<full-digest>;ollama:0.31.1`.

Leave `EMBEDDING_BACKFILL_PROFILE_KEY` empty when Ollama is the sole enabled generator; the runner selects it and logs the exact derived key. An exact key is required only if multiple generators are enabled. Each cursor belongs to that exact profile identity. Each invocation scans at most 500 canonical papers and logs a `nextCursor` when another page exists. Resume with `EMBEDDING_BACKFILL_AFTER_EXCLUSIVE=<nextCursor>`. A PostgreSQL session advisory lock prevents two runs for the same profile; lock identities for different profiles remain independent. Run only one maintenance invocation per backend process so its leased lock connection cannot compete with another local job for the pool. Retryable provider failures and source changes share a bounded `EMBEDDING_BACKFILL_MAX_ATTEMPTS` budget (`1..3`). The runner logs all isolated failures and then exits nonzero; lock contention and systemic provider/profile failures also exit nonzero. A cursor advances past per-paper failures, deletions, and newly invalidated work below it, so complete the paged run and then start a fresh sweep with an empty cursor to catch any still-missing vectors.

Maintenance mode requires `spring.main.web-application-type=none`; startup fails if backfill is enabled in a web application. This command performs generation only. `GET /api/v1/papers/{paperId}/related` never invokes Ollama and continues to return lexical results while semantic ranking remains under evaluation.

### Evaluate exact vector and hybrid quality

After completing the same local-only checks and installing the exact model, run the gated relevance evaluation against an ephemeral Testcontainers database:

```bash
RUN_OLLAMA_VECTOR_EVALUATION=true \
SPRING_DOCKER_COMPOSE_ENABLED=false \
OLLAMA_EMBEDDING_ENABLED=true \
OLLAMA_LOCAL_ONLY_CONFIRMED=true \
OLLAMA_QWEN3_EMBEDDING_DIGEST='replace-with-the-full-64-character-digest' \
./mvnw -Dtest=MetadataFullTextSearchEvaluationTests test
```

Do not set `EMBEDDING_BACKFILL_ENABLED` for this test. The evaluation creates 18 synthetic papers, invokes the backfill use case programmatically, measures exact vector-only retrieval twice for stability, reports a five-weight exploratory lexical/vector sensitivity sweep, and then discards the Testcontainer. The sweep is evidence only; it neither selects a production hybrid weight nor changes the live lexical endpoint. Without `RUN_OLLAMA_VECTOR_EVALUATION=true`, the real-model method is skipped and the normal lexical evaluation still runs. The pinned baselines, formulas, caveats, and current quality floors are recorded in the [search-quality document](../docs/SEARCH_QUALITY.md).

After the development run, the hybrid transform, `w = 0.50` weight, candidate rule, tie-break, and advancement gates were frozen before the independently authored holdout was scored. Run that separate 26-paper, seven-query evaluation with the exact pinned digest:

```bash
RUN_OLLAMA_HOLDOUT_EVALUATION=true \
SPRING_DOCKER_COMPOSE_ENABLED=false \
OLLAMA_EMBEDDING_ENABLED=true \
OLLAMA_LOCAL_ONLY_CONFIRMED=true \
OLLAMA_QWEN3_EMBEDDING_DIGEST='ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d' \
./mvnw -Dtest=MetadataHybridHoldoutEvaluationTests test
```

This run also requires the local `qwen3-embedding:0.6b` tag to resolve to that full digest under Ollama `0.31.1`; it never pulls a model or touches a development database. The completed run passed every frozen gate. Macro lexical results were Recall 0.857, nDCG 0.648, Precision@1 0.286, and MRR 0.571; exact vector produced 1.000, 0.893, 1.000, and 1.000; the frozen hybrid produced 1.000, 0.917, 0.857, and 0.929. Five query groups strictly improved nDCG and none regressed. Passing this holdout does not enable hybrid ranking: the live related-paper endpoint remains lexical pending HNSW and production-readiness gates.

## Legal-access behavior

Access candidates are accepted only from configured providers. Each redirect hop must remain on an absolute HTTPS URL with no credentials, fragment, or non-default port, and DNS must resolve exclusively to public addresses. PDF candidates receive only a bounded range probe and must report `application/pdf` or begin with `%PDF-`; landing pages use `HEAD` with a bounded `GET` fallback when `HEAD` is unsupported.

Successful locations are returned with `contentHandling: LINK_ONLY`; `retention_allowed = false` is additionally enforced as a database invariant and is not an API field. Responses expose verified landing/PDF links, provenance, licence metadata when reported, verification time/status, provider coverage, warnings, and cache disposition—not document bytes.

## Package layout

Top-level packages are Spring Modulith application modules. Feature internals live below `internal` packages and are not exported to other modules.
