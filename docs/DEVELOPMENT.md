# Development guide

## Full-stack workflow

From the repository root:

```bash
cp .env.example .env
docker compose up --build
```

This starts PostgreSQL/pgvector, Spring Boot on `http://localhost:8080`, and Next.js on `http://localhost:3000`. Published ports bind to `127.0.0.1`. The frontend reaches Spring Boot through the server-only `OPENSCHOLAR_API_BASE_URL`; the browser uses same-origin Next.js handlers and receives no provider credentials or private backend configuration.

Do not start `backend/compose.yaml` at the same time as the root stack because both publish the PostgreSQL port by default.

## Backend workflow

From the `backend` directory, start the application with Docker running:

```bash
./mvnw spring-boot:run
```

Spring Boot manages the PostgreSQL/pgvector Compose service automatically. To manage it separately:

```bash
docker compose up -d postgres
SPRING_DOCKER_COMPOSE_ENABLED=false ./mvnw spring-boot:run
```

Run `./mvnw verify` for the unit, web, architecture, and PostgreSQL Testcontainers tests.

The optional embedding workflow uses a separately installed, local-only Ollama `0.31.1` process and an explicit one-page non-web maintenance runner. It is disabled during normal startup and CI, is not part of the root Compose stack, and never pulls a model automatically. See [the backend embedding instructions](../backend/README.md#optional-local-embeddings).

## Frontend workflow

Start the backend, then from `frontend`:

```bash
cp .env.example .env.local
pnpm install
pnpm dev
```

Run `pnpm check` for ESLint, strict TypeScript, Vitest, and a production Next.js build. Next.js reads the backend origin only on the server. Do not replace it with a `NEXT_PUBLIC_` variable or call Spring Boot directly from browser components.

The frontend itself supports Node.js 22.13 or newer, while the full project development baseline is Node.js 22.19 or newer because MCP Inspector 2 requires it. Node.js 24 LTS is recommended. The
`predev` and `prebuild` hooks copy the pinned PDF.js worker and support assets
from `node_modules` into `public/pdfjs/<version>`. That generated directory is
ignored locally and rebuilt inside the frontend container; do not edit or
commit it.

## Environment variables

```text
POSTGRES_DB
POSTGRES_USER
POSTGRES_PASSWORD
POSTGRES_PORT
BACKEND_PORT
FRONTEND_PORT
SPRING_DATASOURCE_URL
SPRING_DOCKER_COMPOSE_ENABLED
SERVER_ADDRESS
OIDC_SECURITY_ENABLED
OIDC_ISSUER_URI
OIDC_JWK_SET_URI
OIDC_AUDIENCE
OIDC_MCP_RESOURCE_URI
OPENALEX_API_KEY
OPENALEX_BASE_URL
OPENALEX_REQUEST_TIMEOUT
OPENALEX_MAX_RESPONSE_BYTES
DOAJ_ENABLED
DOAJ_BASE_URL
DOAJ_CONTACT_EMAIL
DOAJ_CONNECT_TIMEOUT
DOAJ_REQUEST_TIMEOUT
DOAJ_MAX_RESPONSE_BYTES
CORE_ENABLED
CORE_LICENSE_CONFIRMED
CORE_API_KEY
CORE_BASE_URL
CORE_CONNECT_TIMEOUT
CORE_REQUEST_TIMEOUT
CORE_MAX_RESPONSE_BYTES
DATACITE_ENABLED
DATACITE_BASE_URL
DATACITE_CONTACT_EMAIL
DATACITE_CONNECT_TIMEOUT
DATACITE_REQUEST_TIMEOUT
DATACITE_MAX_RESPONSE_BYTES
SEARCH_COORDINATION_WAIT_TIMEOUT
SEARCH_EXECUTION_TIMEOUT
PRIVACY_EXPORT_GLOBAL_PERMITS
PRIVACY_EXPORT_PER_PRINCIPAL_PERMITS
PRIVACY_EXPORT_RETRY_AFTER
RELATED_PAPERS_HYBRID_ENABLED
RELATED_PAPERS_HYBRID_CANDIDATE_POOL_SIZE
UNPAYWALL_EMAIL
UNPAYWALL_BASE_URL
ARXIV_BASE_URL
MCP_LOCAL_API_KEY
MCP_ALLOWED_ORIGINS
MCP_RATE_LIMIT_ENABLED
MCP_RATE_LIMIT_REQUESTS
MCP_RATE_LIMIT_WINDOW
MCP_RATE_LIMIT_MAX_CLIENTS
MCP_MAX_REQUEST_BYTES
MCP_MAX_TOOL_RESULT_BYTES
REFRESH_JOBS_WORKER_ENABLED
REFRESH_JOBS_SCHEDULED_ENABLED
REFRESH_JOBS_POLL_DELAY
REFRESH_JOBS_SCAN_DELAY
REFRESH_JOBS_LEASE_DURATION
REFRESH_JOBS_RETRY_BACKOFF
REFRESH_JOBS_MAX_ATTEMPTS
REFRESH_JOBS_PER_POLL
REFRESH_JOBS_STALE_TARGETS_PER_SCAN
OLLAMA_EMBEDDING_ENABLED
OLLAMA_BASE_URL
OLLAMA_QWEN3_EMBEDDING_DIGEST
OLLAMA_LOCAL_ONLY_CONFIRMED
OLLAMA_CONNECT_TIMEOUT
OLLAMA_READ_TIMEOUT
OLLAMA_KEEP_ALIVE
EMBEDDING_BACKFILL_ENABLED
EMBEDDING_BACKFILL_PROFILE_KEY
EMBEDDING_BACKFILL_AFTER_EXCLUSIVE
EMBEDDING_BACKFILL_LIMIT
EMBEDDING_BACKFILL_MAX_ATTEMPTS
OPENSCHOLAR_API_BASE_URL
OPENSCHOLAR_AUTH_MODE
OPENSCHOLAR_AUTH_SESSION_SECRET
OPENSCHOLAR_AUTH_SESSION_MAX_AGE_SECONDS
OPENSCHOLAR_OIDC_ISSUER
OPENSCHOLAR_OIDC_AUTHORIZATION_ENDPOINT
OPENSCHOLAR_OIDC_TOKEN_ENDPOINT
OPENSCHOLAR_OIDC_JWKS_URI
OPENSCHOLAR_OIDC_END_SESSION_ENDPOINT
OPENSCHOLAR_OIDC_CLIENT_ID
OPENSCHOLAR_OIDC_CLIENT_AUTH_METHOD
OPENSCHOLAR_OIDC_CLIENT_SECRET
OPENSCHOLAR_OIDC_REDIRECT_URI
OPENSCHOLAR_OIDC_POST_LOGOUT_REDIRECT_URI
OPENSCHOLAR_OIDC_SCOPES
OPENSCHOLAR_OIDC_ID_TOKEN_ALGS
```

`SEARCH_COORDINATION_WAIT_TIMEOUT` defaults to `12s` and bounds only acquisition of the JVM-local striped search-coordination lock. It does not bound cache reads, provider work after acquisition, persistence, response serialization, or the complete REST/MCP request, and a caller that stops waiting does not cancel the leader already holding the stripe.

`SEARCH_EXECUTION_TIMEOUT` defaults to `18s`, accepts values of at least one millisecond, and bounds each `SearchResearchUseCase` `search`, `next`, or `get` execution shared by REST and MCP. A dedicated virtual-thread worker and cooperative checkpoints cover application dispatch through `SearchView` construction. Parsing/schema validation, transport DTO/framework serialization, socket lifetime, client disconnects, and MCP cancellation notifications are outside this setting.

Privacy-export admission defaults to four global permits and one permit per authenticated principal for each backend JVM/replica, with a `10s` retry hint. Global permits must be between 1 and 16, per-principal permits between 1 and 4 and no greater than the global value, and the retry hint between one second and five minutes. Keep the instance-global value below Hikari's `maximum-pool-size` with spare connections for normal traffic; backend replicas multiply the effective cluster capacity.

The embedding variables apply to direct backend development only; the root container stack intentionally leaves local inference disabled. Local auth mode is the default. To exercise hosted mode, backend OIDC and frontend BFF settings must describe the same issuer/audience/client and use explicit loopback or HTTPS endpoints; never expose these values through `NEXT_PUBLIC_*`. Optional discovery providers and durable workers/scheduling remain default-off. Object storage does not exist, and monitoring-specific variables live under `deploy/`. `.env.example` contains placeholders; `.env` is ignored.

## Backend conventions

- Java 21 and Maven Wrapper.
- Spring Boot 4.1 with the Spring AI 2.0 BOM and official MCP Java SDK 2.0.
- Constructor injection and immutable boundary records.
- Bean Validation on REST inputs; generated MCP JSON Schema plus adapter/application validation on MCP inputs.
- Injected `Clock` for freshness logic.
- Typed domain errors mapped at adapters.
- Transactions at application-service boundaries.
- No provider DTOs outside provider modules.
- No persistence entities returned through REST/MCP.
- Flyway for schema; Hibernate DDL disabled outside tests.

## Frontend conventions

- Strict TypeScript.
- Runtime-validated API responses aligned with the checked-in OpenAPI contract; generated types may be adopted where they reduce rather than duplicate validation.
- Accessible components and tested keyboard behavior.
- Dedicated server-data query/cache layer.
- No credentials/private backend configuration in browser bundles.
- Explicit PDF failure and external-link fallback.
- Only fresh, verified HTTPS PDF locations may enter the in-app reader; PDF.js fetches them directly without credentials, proxying, or retention.
- Keep runtime response schemas and the controller-checked OpenAPI document aligned.
- Only independently verified `/versions` links may be rendered as legal-access actions.

## Branch and commit workflow

- `main` remains releasable.
- Feature branches use short names such as `feat/openalex-search`.
- Pull requests include tests/docs for contract and policy changes.
- Material boundary decisions receive an ADR.

## Database workflow

- Add a numbered Flyway migration.
- Run it against clean Testcontainers PostgreSQL.
- Test upgrades for risky changes.
- Never edit an applied migration.
- Seed only synthetic/demo metadata with provenance.

## Provider workflow

1. Document official terms, authentication, attribution, and rate limits.
2. Add capabilities/configuration.
3. Create minimal permitted fixtures with sensitive fields removed.
4. Implement mapping and failure translation.
5. Add contract/resilience tests.
6. Add metrics and request budgets.
7. Enable behind configuration until verified.

## Feature definition of done

- Acceptance scenario works through its public adapter.
- Unit/integration tests cover policy branches.
- REST/MCP contracts and docs are updated.
- Migration implications are documented.
- Metrics/logs reveal success/failure.
- Security, privacy, access, and provider terms are reviewed.
- Compose and CI remain reproducible.
