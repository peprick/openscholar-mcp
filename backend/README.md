# OpenScholar Backend

Java 21 and Spring Boot 4.1 backend for OpenScholar MCP.

## Current capabilities

- Spring MVC and validation
- PostgreSQL persistence through Spring Data JPA
- Flyway-managed schema
- OpenAlex topic search with bounded filters and opaque cursor paging
- Canonical DOI/OpenAlex deduplication, authors, and provider provenance
- Immutable 24-hour search snapshots with exact-cache hits and stale fallback
- RFC 9457 validation, not-found, and provider-failure responses
- Spring Boot Actuator
- Spring Modulith boundary verification
- Testcontainers integration tests
- Docker Compose PostgreSQL/pgvector service
- Java 21 virtual threads

MCP, Unpaywall/arXiv access resolution, and additional scholarly providers follow after this search slice.

## Run locally

Start Docker Desktop/Engine, then run:

```bash
./mvnw spring-boot:run
```

Spring Boot detects `compose.yaml`, starts PostgreSQL when required, runs Flyway, and exposes:

- Application status: `http://localhost:8080/api/v1/system/status`
- Create/reuse a search: `POST http://localhost:8080/api/v1/searches`
- Retrieve a saved snapshot: `GET http://localhost:8080/api/v1/searches/{searchId}`
- Actuator health: `http://localhost:8080/actuator/health`
- Actuator info: `http://localhost:8080/actuator/info`

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

`.env.example` documents supported environment variables. Never commit `.env` or credentials. The API key is sent to OpenAlex through an authorization header and is never exposed through REST responses.

## Package layout

Top-level packages are Spring Modulith application modules. Feature internals live below `internal` packages and are not exported to other modules.
