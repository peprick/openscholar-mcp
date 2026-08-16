# OpenScholar Backend

Java 21 and Spring Boot 4.1 backend for OpenScholar MCP.

## Current foundation

- Spring MVC and validation
- PostgreSQL persistence through Spring Data JPA
- Flyway-managed schema
- Spring Boot Actuator
- Spring Modulith boundary verification
- Testcontainers integration tests
- Docker Compose PostgreSQL/pgvector service
- Java 21 virtual threads

MCP and scholarly provider adapters are intentionally added in later milestones after the core catalog and search application services exist.

## Run locally

Start Docker Desktop/Engine, then run:

```bash
./mvnw spring-boot:run
```

Spring Boot detects `compose.yaml`, starts PostgreSQL when required, runs Flyway, and exposes:

- Application status: `http://localhost:8080/api/v1/system/status`
- Actuator health: `http://localhost:8080/actuator/health`
- Actuator info: `http://localhost:8080/actuator/info`

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

Copy `.env.example` to `.env` only if overriding development defaults. Never commit `.env` or credentials.

## Package layout

Top-level packages are Spring Modulith application modules. Feature internals live below `internal` packages and are not exported to other modules.
