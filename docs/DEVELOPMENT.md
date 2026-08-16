# Development Plan

## Current backend workflow

From the `backend` directory, start the application with Docker running:

```bash
./mvnw spring-boot:run
```

Spring Boot manages the PostgreSQL/pgvector Compose service automatically. To manage it separately:

```bash
docker compose up -d postgres
SPRING_DOCKER_COMPOSE_ENABLED=false ./mvnw spring-boot:run
```

Run `./mvnw verify` for the unit, web, architecture, and PostgreSQL Testcontainers tests. The frontend and full application container profile remain future milestones.

## Planned environment variables

```text
POSTGRES_DB
POSTGRES_USER
POSTGRES_PASSWORD
SPRING_DATASOURCE_URL
OPENALEX_API_KEY
UNPAYWALL_EMAIL
CORE_API_KEY
MCP_LOCAL_API_KEY
NEXT_PUBLIC_API_BASE_URL
```

Optional embedding, OAuth, storage, and monitoring variables arrive only with those features. `.env.example` contains placeholders; `.env` is ignored.

## Backend conventions

- Java 21 and Maven Wrapper.
- Spring Boot 4.1, Spring AI 2.0 BOM, official MCP Java SDK transitively managed.
- Constructor injection and immutable boundary records.
- Bean Validation on REST/MCP inputs.
- Injected `Clock` for freshness logic.
- Typed domain errors mapped at adapters.
- Transactions at application-service boundaries.
- No provider DTOs outside provider modules.
- No persistence entities returned through REST/MCP.
- Flyway for schema; Hibernate DDL disabled outside tests.
- Automated formatting/import order.

## Frontend conventions

- Strict TypeScript.
- Generated/validated OpenAPI types where practical.
- Accessible components and tested keyboard behavior.
- Dedicated server-data query/cache layer.
- No credentials/private backend configuration in browser bundles.
- Explicit PDF failure and external-link fallback.

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

## Local readiness at creation

- Git and Docker 29.6.1 are available.
- Java 26 is installed; builds will target Java 21.
- GitHub CLI is authenticated for repository publishing and CI inspection.
- No global Maven is required; the backend commits Maven Wrapper 3.9.16.
