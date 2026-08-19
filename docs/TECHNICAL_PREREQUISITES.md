# Technical Prerequisites

## Required local software

| Tool | Target | Purpose |
|---|---|---|
| Git | Current supported release | Version control |
| Java | JDK 21 LTS | Backend compilation/runtime baseline |
| Docker Desktop/Engine | Current release with Compose v2 | PostgreSQL, pgvector, reproducible services |
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

### Learn during implementation

- Spring Boot 4.1 and Spring Framework 7 conventions.
- Spring AI `@McpTool`, `@McpResource`, and `@McpPrompt` annotations.
- Stateless Streamable HTTP MCP configuration and protocol negotiation.
- PostgreSQL full-text search and pgvector similarity queries.
- Spring AI's low-level Ollama API, immutable model-artifact pinning, and offline backfill safety.
- Resilience4j circuit breakers, retries, and bulkheads.
- Flyway migration discipline.
- OAuth 2.0 resource-server security and local API-key authentication.
- Micrometer/OpenTelemetry instrumentation and ArchUnit boundaries.

## Frontend knowledge

- TypeScript and asynchronous programming.
- React components, hooks, forms, and rendering boundaries.
- Next.js routing and API integration.
- Accessible HTML and keyboard interaction.
- PDF.js and browser cross-origin restrictions.
- Playwright end-to-end testing.

## Infrastructure knowledge

- Dockerfiles, multi-stage builds, health checks, volumes, and networks.
- Docker Compose profiles and environment substitution.
- Secret handling through ignored files or a secret manager.
- GitHub Actions jobs, caches, service containers, and protected secrets.
- Basic DNS, TLS, reverse proxies, and container deployment.

## Academic-data concepts

- DOI normalization and scholarly identifiers.
- Work versus version: publisher version, accepted manuscript, repository copy, and preprint.
- “Free to read” does not automatically permit redistribution or model training.
- BibTeX and CSL-JSON.
- Citation counts are provider-dependent, time-varying metadata.

## Accounts and API credentials

### MVP

- OpenAlex API key.
- Contact email for Unpaywall API requests.
- CORE key only when that provider is enabled.
- No LLM/embedding key for the first lexical-search milestone.

### Optional local embedding evaluation

- A separately installed Ollama `0.31.1` process bound to a numeric loopback address, with `OLLAMA_NO_CLOUD=1` configured on that server process.
- The explicit `qwen3-embedding:0.6b` model tag and its reviewed full SHA-256 digest.
- No hosted embedding credential is required for the implemented local profile.

### Optional later

- Hosted embedding provider credentials for a separately versioned comparison profile.
- S3-compatible object storage.
- OAuth identity provider.
- Error monitoring.

All credentials belong in ignored environment files or deployment secrets. `.env.example` contains variable names, safe local defaults, and documentation only.

## Official baselines at planning time

- Spring Boot 4.1 requires Java 17+ and supports Java through 26.
- Spring AI 2.0 supports Spring Boot 4.0/4.1.
- Use `org.springframework.ai:spring-ai-starter-mcp-server-webmvc`; older Spring transport coordinates under `io.modelcontextprotocol.sdk` are obsolete for this baseline.
- Spring AI 2.0 manages official MCP Java SDK 2.0 transitively.
- Java SDK 2.0 supports MCP revision `2025-11-25`; newer protocol-only features remain deferred until official Java support.

Versions will be pinned and updated deliberately, never left as floating ranges.
