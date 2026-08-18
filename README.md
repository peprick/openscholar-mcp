# OpenScholar MCP

OpenScholar MCP is an open-access research discovery and reading workspace. A user describes a research topic, and the platform searches scholarly indexes and repositories, finds legal full-text versions, removes duplicates, ranks the results, and saves reusable knowledge in PostgreSQL. The same use cases are designed to be exposed to AI agents through the Model Context Protocol (MCP) in a later milestone.

> Status: the first end-to-end web flow is implemented. The Next.js client searches OpenAlex-backed snapshots, renders canonical paper details and provenance, explicitly verifies legal versions through Unpaywall/arXiv, and downloads BibTeX or CSL-JSON citations. PostgreSQL caches reusable metadata and access results. The API never returns PDF bytes, and the application retains no PDF documents.

## Product goals

- Find papers, preprints, theses, and dissertations by topic.
- Prefer legal open-access versions and clearly label restricted material.
- Cache normalized results so repeated and related searches are faster.
- Provide an in-app research library, PDF reader, collections, notes, and citation exports.
- Support semantic search across saved research using PostgreSQL and pgvector.
- Expose safe, typed tools through an MCP server implemented in Spring Boot.
- Preserve provenance: every record and document link must show where it came from.

## Current stack

- Java 21 LTS
- Spring Boot 4.1.x
- Spring MVC REST API
- Maven Wrapper
- PostgreSQL with pgvector
- Flyway migrations
- Next.js 16.2 and strict TypeScript for the web client
- Docker Compose for local development
- Testcontainers, JUnit, Vitest, and Testing Library for verification

Planned additions include Spring AI 2.0 with the official MCP Java SDK, stateless Streamable HTTP MCP, PDF.js, and Playwright.

## Repository layout

```text
openscholar-mcp/
├── backend/                    # Spring Boot API, providers, persistence, future MCP adapter
├── frontend/                   # Next.js web application
├── docs/                       # Product, architecture, security and delivery plans
├── .github/workflows/          # CI pipelines
├── compose.yaml
└── README.md
```

The backend is a modular monolith. REST, future MCP adapters, provider integrations, persistence, and scheduled work share one deployable application while remaining separated by package boundaries.

## Planned research sources

The implemented backend providers are OpenAlex for discovery, Unpaywall for exact DOI access evidence, and arXiv for exact identifier access evidence. CORE, PubMed Central, DOAJ, OATD, Shodhganga, and compatible institutional repositories follow after the core pipeline is stable.

The platform will use supported APIs and legal repository links. It will not bypass paywalls, authentication, CAPTCHAs, robots restrictions, or publisher controls.

## Documentation

- [Project plan](docs/PROJECT_PLAN.md)
- [Product requirements](docs/PRODUCT_REQUIREMENTS.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Technical prerequisites](docs/TECHNICAL_PREREQUISITES.md)
- [Data model](docs/DATA_MODEL.md)
- [REST and MCP contracts](docs/API_AND_MCP.md)
- [Security, privacy, and legal boundaries](docs/SECURITY_AND_LEGAL.md)
- [Testing strategy](docs/TESTING_STRATEGY.md)
- [Delivery roadmap](docs/ROADMAP.md)
- [Local development plan](docs/DEVELOPMENT.md)
- [Official references](docs/REFERENCES.md)
- [Architecture decisions](docs/decisions/)

## Initial success criteria

The MVP is complete when a user can search an academic topic, receive normalized and deduplicated results from OpenAlex, resolve legal full-text availability through Unpaywall or arXiv, revisit cached results, save papers to a collection, open supported PDFs in the web reader, and invoke the core search operations through an MCP client.

## Run the full stack

With Docker running:

```bash
cp .env.example .env
docker compose up --build
```

Open `http://localhost:3000`. The root stack binds PostgreSQL, the backend, and the frontend only to `127.0.0.1`. Provider credentials are passed only to the backend. Stop it with `docker compose down`; add `--volumes` only when you intentionally want to delete the local PostgreSQL data.

For faster component development, run Spring Boot and Next.js separately:

```bash
cd backend
./mvnw spring-boot:run
```

```bash
cd frontend
cp .env.example .env.local
pnpm install
pnpm dev
```

## Verification

Run `./mvnw verify` from `backend` and `pnpm check` from `frontend`. See the [backend README](backend/README.md), [frontend README](frontend/README.md), and [development guide](docs/DEVELOPMENT.md) for details.

## Remaining MVP work

Collections/library persistence, a supported in-app PDF.js reader, richer typed citation metadata, automated Playwright smoke coverage, and the Spring MCP adapter remain on the roadmap.

## License

No open-source license has been selected yet. Until one is added, normal copyright rules apply.
