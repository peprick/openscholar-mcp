# OpenScholar MCP

OpenScholar MCP is an open-access research discovery and reading workspace. A user describes a research topic, and the platform searches scholarly indexes and repositories, finds legal full-text versions, removes duplicates, ranks the results, and saves reusable knowledge in PostgreSQL. Its core read use cases are also exposed to AI agents through the Model Context Protocol (MCP).

> Status: the end-to-end web flow, owner-scoped persistent library/search snapshots, read-oriented MCP adapter, privacy export/deletion, and durable metadata/access refresh jobs are implemented. OpenAlex is the default discovery source; DataCite, DOAJ, and licence-gated CORE adapters are explicit opt-ins and participate in isolated concurrent fan-out, exact-identifier merging, reciprocal-rank fusion, coverage, and warning reporting. Unpaywall/arXiv provide exact-identifier legal-access evidence, while PDF.js reads only fresh verified CORS-compatible links directly in the browser. Local mode remains loopback/fixed-user with a separate MCP key. Hosted mode has a tested Spring Security OIDC resource server, issuer/audience/scope validation, MCP protected-resource discovery, per-principal ownership, and a Next.js authorization-code/PKCE BFF with encrypted HttpOnly sessions. A hardened single-host Compose/Caddy template, project-owned Caddy and blackbox-exporter scratch-runtime builds, private application and blackbox monitoring, and guarded PostgreSQL backup/restore scripts are included. Production deployment remains image-gated until all four project-owned runtime images are published and pinned as reviewed registry digests; no cloud deployment or public-production readiness is claimed. The API never returns PDF bytes, and the application retains no PDF documents.

## Product goals

- Find papers, preprints, theses, and dissertations by topic.
- Prefer legal open-access versions and clearly label restricted material.
- Cache normalized results so exact repeated searches avoid unnecessary provider calls.
- Provide an in-app research library, PDF reader, collections, citation exports, and later personal notes.
- Provide explainable PostgreSQL full-text related-paper discovery and an explicitly enabled, evaluated pgvector hybrid mode.
- Expose safe, typed tools through an MCP server implemented in Spring Boot.
- Preserve provenance: every record and document link must show where it came from.

## Current stack

- Java 21 LTS
- Spring Boot 4.1.x
- Spring MVC REST API
- Spring AI 2.0 and the official MCP Java SDK 2.0
- Stateless Streamable HTTP MCP at `/mcp`
- Maven Wrapper
- PostgreSQL with pgvector
- Optional loopback Ollama for explicit offline embedding generation
- Flyway migrations
- Next.js 16.3.1, React 19.2.8, and strict TypeScript for the web client
- PDF.js 6.2 for direct, supported-source browser reading
- Docker Compose for local development
- Testcontainers, JUnit, Vitest, and Testing Library for verification
- Spring Security OAuth 2.0 resource server plus an optional Next.js OIDC BFF
- Optional single-host Caddy/Prometheus/Alertmanager/blackbox-exporter deployment artifacts and guarded PostgreSQL backup/restore scripts

Local evidence now includes automated offline and Compose-backed Playwright workflows, WCAG 2.2 axe scans, frozen relevance/deduplication gates, a loopback performance harness, and portfolio screenshots. Broader representative/load evaluation, additional approved repository sources, and target-production evidence remain future work.

## Repository layout

```text
openscholar-mcp/
├── backend/                    # Spring Boot REST/MCP adapters, providers, and persistence
├── frontend/                   # Next.js web application
├── deploy/                     # Loopback-first hosted/observability templates
├── docs/                       # Product, architecture, security and delivery plans
├── scripts/                    # Conformance and safe database operations
├── .github/workflows/          # CI pipelines
├── compose.yaml
└── README.md
```

The backend is a modular monolith. REST, MCP, provider integrations, persistence, and scheduled work share one deployable application while remaining separated by package boundaries.

## Research sources

The implemented backend providers are OpenAlex for default discovery; disabled-by-default DataCite thesis/dissertation metadata discovery; disabled-by-default DOAJ v4 article discovery; a separately licence-gated, disabled-by-default CORE API v3 metadata adapter; Unpaywall for exact DOI access evidence; and arXiv for exact identifier access evidence. DataCite is keyless metadata discovery and deliberately emits no open-access or PDF claim. DOAJ contributes metadata and source-reported links only. CORE discards full text/download URLs and never calls document-download endpoints. None of these adapters authorizes OpenScholar to copy or retain an underlying work. PubMed Central, OATD, Shodhganga, and compatible institutional repositories remain external follow-ups.

The platform will use supported APIs and legal repository links. It will not bypass paywalls, authentication, CAPTCHAs, robots restrictions, or publisher controls.

## Documentation

- [Project plan](docs/PROJECT_PLAN.md)
- [Product requirements](docs/PRODUCT_REQUIREMENTS.md)
- [Architecture](docs/ARCHITECTURE.md)
- [Technical prerequisites](docs/TECHNICAL_PREREQUISITES.md)
- [Data model](docs/DATA_MODEL.md)
- [REST and MCP contracts](docs/API_AND_MCP.md)
- [OpenAPI 3.1 REST specification](docs/openapi.yaml)
- [MCP quickstart](docs/MCP_QUICKSTART.md)
- [Security, privacy, and legal boundaries](docs/SECURITY_AND_LEGAL.md)
- [Hosted deployment template](docs/DEPLOYMENT.md)
- [Operations runbook](docs/OPERATIONS_RUNBOOK.md)
- [Hosted threat model](docs/THREAT_MODEL.md)
- [Supply-chain security](docs/SUPPLY_CHAIN_SECURITY.md)
- [Testing strategy](docs/TESTING_STRATEGY.md)
- [Search quality baseline](docs/SEARCH_QUALITY.md)
- [Local performance evidence](docs/PERFORMANCE_EVIDENCE.md)
- [Portfolio demo evidence](docs/PORTFOLIO_DEMO.md)
- [Delivery roadmap](docs/ROADMAP.md)
- [Local development plan](docs/DEVELOPMENT.md)
- [Official references](docs/REFERENCES.md)
- [Architecture decisions](docs/decisions/)

## Initial success criteria

The original local MVP success criteria are complete: a user can search an academic topic, receive normalized/deduplicated results, resolve legal full-text availability through Unpaywall or arXiv, revisit cached results, save papers to a collection, open supported PDFs in the web reader, and invoke the core read operations through an MCP client. Hosted release criteria remain separate and require published and verified project-owned runtime images, an actual identity-provider registration, deployment, restore/alert/load/accessibility/security evidence, and provider/legal approval.

## Run the full stack

With Docker running:

```bash
cp .env.example .env
# Set MCP_LOCAL_API_KEY in .env to a long random value before using /mcp.
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

## Remaining work

Typed publication/citation metadata and durable REST/UI refresh jobs are implemented. MCP job-handle/cancellation tools remain deferred; the existing durable jobs are operational refresh records, not MCP Tasks. The local MCP boundary enforces bearer authentication, exact Origin checks, bounded per-address request rates, request IDs, and safe response headers. Hosted MCP accepts audience/scope-validated JWTs and publishes OAuth protected-resource metadata, but client disconnects and MCP `notifications/cancelled` still do not propagate through the pinned stateless SDK. Offline and real-Compose Playwright coverage, WCAG 2.2 axe evidence, local performance/deduplication evidence, screenshots, immutable third-party Action/container references, locked conformance tooling, checked-in hardened proxy/prober builds, a production image-policy preflight, expiring scoped VEX validation, CSP, operations validation, and guarded backup/restore tests are checked in. CI-built, registry-published, digest-rescanned, signed/attested backend, frontend, Caddy, and blackbox-exporter images; a configured alert receiver; real OIDC-provider interoperability; managed backup/PITR decisions; target-environment load/assistive-technology/penetration/disaster-recovery exercises; and an actual deployment remain launch gates.

## License

No open-source license has been selected yet. Until one is added, normal copyright rules apply.
