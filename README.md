# OpenScholar MCP

OpenScholar MCP is an open-access research discovery and reading workspace. A user describes a research topic, and the platform searches scholarly indexes and repositories, finds legal full-text versions, removes duplicates, ranks the results, saves reusable knowledge in PostgreSQL, and exposes the same capabilities to AI agents through the Model Context Protocol (MCP).

> Status: cached OpenAlex search and the first legal-access backend slice are implemented. Canonical papers can now be resolved by exact DOI through Unpaywall and by exact arXiv ID, with provider-isolated failures, safe outbound-link verification, 24-hour reuse, stale fallback, and link-only persistence. The API never returns PDF bytes, and the backend retains no PDF documents.

## Product goals

- Find papers, preprints, theses, and dissertations by topic.
- Prefer legal open-access versions and clearly label restricted material.
- Cache normalized results so repeated and related searches are faster.
- Provide an in-app research library, PDF reader, collections, notes, and citation exports.
- Support semantic search across saved research using PostgreSQL and pgvector.
- Expose safe, typed tools through an MCP server implemented in Spring Boot.
- Preserve provenance: every record and document link must show where it came from.

## Planned stack

- Java 21 LTS
- Spring Boot 4.1.x
- Spring AI 2.0.x and the official MCP Java SDK
- Spring MVC with stateless Streamable HTTP MCP
- Maven Wrapper
- PostgreSQL with pgvector
- Flyway migrations
- Next.js and TypeScript for the web client
- PDF.js for in-browser reading
- Docker Compose for local development
- Testcontainers, JUnit 5, WireMock, and Playwright for verification

## Proposed repository layout

```text
openscholar-mcp/
├── backend/                    # Spring Boot API, MCP server, ingestion and jobs
├── frontend/                   # Next.js web application
├── infra/                      # Docker Compose and local infrastructure
├── docs/                       # Product, architecture, security and delivery plans
├── .github/workflows/          # CI pipelines
├── compose.yaml
└── README.md
```

The backend will begin as a modular monolith. REST, MCP, provider integrations, persistence, and scheduled work share one deployable application but remain separated by package boundaries. This keeps the first release understandable while leaving room to extract workers later.

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

## Backend development

See the [backend README](backend/README.md) for current Java setup and verification commands.

## License

No open-source license has been selected yet. Until one is added, normal copyright rules apply.
