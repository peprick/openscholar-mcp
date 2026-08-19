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
- Java 21 virtual threads

The Next.js search, details, verified-version, PDF.js reader, citation, and research-library UI is available in `../frontend`. Richer typed publication metadata, MCP conformance automation, and additional scholarly providers remain to be built.

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

## Legal-access behavior

Access candidates are accepted only from configured providers. Each redirect hop must remain on an absolute HTTPS URL with no credentials, fragment, or non-default port, and DNS must resolve exclusively to public addresses. PDF candidates receive only a bounded range probe and must report `application/pdf` or begin with `%PDF-`; landing pages use `HEAD` with a bounded `GET` fallback when `HEAD` is unsupported.

Successful locations are returned with `contentHandling: LINK_ONLY`; `retention_allowed = false` is additionally enforced as a database invariant and is not an API field. Responses expose verified landing/PDF links, provenance, licence metadata when reported, verification time/status, provider coverage, warnings, and cache disposition—not document bytes.

## Package layout

Top-level packages are Spring Modulith application modules. Feature internals live below `internal` packages and are not exported to other modules.
