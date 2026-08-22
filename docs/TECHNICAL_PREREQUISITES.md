# Technical Prerequisites

## Required local software

| Tool | Target | Purpose |
|---|---|---|
| Git | Current supported release | Version control |
| Java | JDK 21 LTS | Backend compilation/runtime baseline |
| Docker Desktop/Engine | Current release with Compose v2 | PostgreSQL, pgvector, reproducible services |
| jq | Current stable | Production Compose model validation and scoped vulnerability-exception checks |
| Node.js | 24 LTS recommended; `>=22.19` required | Next.js, PDF.js, MCP Inspector 2, and conformance tooling |
| pnpm | 11.19.x | Frontend package management and reproducible lockfile |
| GitHub CLI | Current stable | Repository/release workflow |
| Ollama | Optional, exactly 0.31.1 for the implemented profile | Explicit local embedding backfill only; not required for normal development or CI |

Maven does not need to be globally installed; the backend uses the committed `./mvnw` wrapper with Maven 3.9.x.

This development machine currently has Java 26 and Docker 29.6.1. Java 26 is compatible with the current Spring Boot release, while CI and containers target Java 21 LTS for a stable contributor baseline.

## Backend knowledge

### Essential

- Java records, generics, exceptions, collections, streams, and concurrency fundamentals.
- Spring dependency injection, configuration, profiles, validation, and error handling.
- REST/HTTP semantics: methods, status codes, caching, idempotency, authentication, and rate limits.
- Relational modelling, indexes, transactions, constraints, and SQL joins.
- Maven lifecycle, dependency scopes, and the wrapper.
- JUnit 5, Mockito, integration tests, and Testcontainers.

### Project-specific topics

- Spring Boot 4.1 and Spring Framework 7 conventions.
- Spring AI `@McpTool`, structured schemas, stateless Streamable HTTP configuration, and protocol negotiation. The current server does not advertise resources or prompts.
- PostgreSQL full-text search and pgvector similarity queries.
- Spring AI's low-level Ollama API, immutable model-artifact pinning, and offline backfill safety.
- Provider deadlines/body limits, concurrent fan-out, partial failures, reciprocal-rank fusion, and durable leased-job retry. Resilience4j circuit breakers/bulkheads remain a possible later addition, not a current dependency.
- Flyway migration discipline.
- Spring Security OAuth 2.0 JWT resource-server validation, route scopes, issuer+subject ownership, protected-resource metadata, and local MCP-key authentication.
- Micrometer instrumentation, the planned OpenTelemetry tracing boundary, and ArchUnit boundaries.

## Frontend knowledge

- TypeScript and asynchronous programming.
- React components, hooks, forms, and rendering boundaries.
- Next.js routing and API integration.
- Authorization Code + PKCE BFF flows, state/nonce, ID-token/JWKS validation, encrypted HttpOnly sessions, token refresh, and same-origin mutation checks.
- Accessible HTML and keyboard interaction.
- PDF.js and browser cross-origin restrictions.
- Playwright end-to-end testing.

## Infrastructure knowledge

- Dockerfiles, multi-stage builds, health checks, volumes, and networks.
- Docker Compose profiles and environment substitution.
- Secret handling through ignored files or a secret manager.
- GitHub Actions jobs, caches, service containers, and protected secrets.
- Basic DNS, TLS, reverse proxies, and container deployment.
- Caddy routing/automatic TLS, blackbox Prometheus/Alertmanager operation, file-secret ownership, and guarded PostgreSQL backup/restore with checksums and optional `age` encryption.

## Academic-data concepts

- DOI normalization and scholarly identifiers.
- Work versus version: publisher version, accepted manuscript, repository copy, and preprint.
- “Free to read” does not automatically permit redistribution or model training.
- BibTeX and CSL-JSON.
- Citation counts are provider-dependent, time-varying metadata.

## Accounts and API credentials

### Local development

- A generated `MCP_LOCAL_API_KEY` for the local MCP route.
- OpenAlex works without a project-owned credential in the default configuration; an optional backend API key may be configured when approved.
- A contact email for Unpaywall is optional; leaving it blank disables Unpaywall while arXiv access checks remain available.
- DataCite and DOAJ discovery are keyless, disabled-by-default providers with optional contact identity.
- CORE remains disabled until its terms/applicable licence are approved and `CORE_LICENSE_CONFIRMED=true`; its backend bearer key is optional where the approved service permits it.
- No LLM/embedding key for the first lexical-search milestone.

### Optional local embedding evaluation

- A separately installed Ollama `0.31.1` process bound to a numeric loopback address, with `OLLAMA_NO_CLOUD=1` configured on that server process.
- The explicit `qwen3-embedding:0.6b` model tag and its reviewed full SHA-256 digest.
- No hosted embedding credential is required for the implemented local profile.

### Hosted deployment prerequisites

- An external OIDC authorization server with a confidential browser client, MCP-client grants, JWT access tokens for the configured API audience, approved `openscholar.*` scopes, explicit authorization/token/JWKS/logout endpoints, and tested rotation/revocation.
- A 32-byte-base64 browser session-sealing secret and confidential OIDC client secret, preferably delivered by a managed secret system.
- DNS/TLS/ingress; a reviewed registry and CI identity capable of building, scanning, publishing, registry-digest-rescanning, signing/attesting, and pinning the backend, frontend, Caddy, and blackbox-exporter images; database/PITR/backup decisions; and a working alert route. The checked-in image definitions and deployment files are gated templates, not published or provisioned services.

### Optional later

- Hosted embedding provider credentials for a separately versioned comparison profile.
- S3-compatible object storage.
- Error monitoring.

All credentials belong in ignored environment files or deployment secrets. `.env.example` contains variable names, safe local defaults, and documentation only.

## Official baselines at planning time

- Spring Boot 4.1 requires Java 17+ and supports Java through 26.
- Spring AI 2.0 supports Spring Boot 4.0/4.1.
- Use `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`; older Spring transport coordinates under `io.modelcontextprotocol.sdk` are obsolete for this baseline.
- Spring AI 2.0 manages official MCP Java SDK 2.0 transitively.
- Java SDK 2.0 supports MCP revision `2025-11-25`; newer protocol-only features remain deferred until official Java support.

Application dependencies are versioned through the Maven and pnpm manifests/lockfiles, and the official MCP conformance CLI has a separate frozen, integrity-bearing tooling lockfile. The Maven distribution has a checked SHA-256, third-party Actions use full commit SHAs, and Dockerfile/Compose/validation images use tag-plus-digest references enforced by `scripts/validate-supply-chain.sh`; update each human-readable version and immutable reference deliberately together.
