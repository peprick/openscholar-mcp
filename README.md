# OpenScholar MCP

[![Backend CI](https://github.com/peprick/openscholar-mcp/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/peprick/openscholar-mcp/actions/workflows/backend-ci.yml)
[![Frontend CI](https://github.com/peprick/openscholar-mcp/actions/workflows/frontend-ci.yml/badge.svg)](https://github.com/peprick/openscholar-mcp/actions/workflows/frontend-ci.yml)
[![End-to-end tests](https://github.com/peprick/openscholar-mcp/actions/workflows/frontend-e2e.yml/badge.svg)](https://github.com/peprick/openscholar-mcp/actions/workflows/frontend-e2e.yml)
[![Security](https://github.com/peprick/openscholar-mcp/actions/workflows/security.yml/badge.svg)](https://github.com/peprick/openscholar-mcp/actions/workflows/security.yml)

OpenScholar is a self-hosted research discovery workspace and MCP server. It searches scholarly metadata, combines duplicate records, caches reusable results, verifies legal open-access links, and helps readers organize papers and export citations.

OpenScholar stores metadata, search and library state, and verified links—not research PDFs. It does not scrape publisher pages, bypass paywalls, or proxy document bytes.

![OpenScholar search results with canonical papers and source provenance](docs/images/readme-preview.png)

## What it does

- Searches OpenAlex by default, with optional DataCite, DOAJ, and licence-gated CORE discovery adapters.
- Normalizes and merges records by DOI, arXiv ID, OpenAlex ID, and provider identity.
- Stores owner-scoped, immutable search snapshots in PostgreSQL so repeated searches can reuse prior results.
- Verifies legal full-text candidates through exact DOI/arXiv evidence from Unpaywall and arXiv.
- Provides collections, reading status, tags, saved-library search, and BibTeX or CSL-JSON exports.
- Opens fresh, verified, HTTPS, CORS-compatible PDFs in a browser PDF.js reader and falls back to the source site when embedded reading is not supported.
- Exposes five bounded research tools to agents over stateless Streamable HTTP MCP.

## Architecture

```mermaid
flowchart LR
    Reader["Reader"] --> Web["Next.js UI and BFF"]
    Agent["MCP client or agent"] -->|"Streamable HTTP /mcp"| App["Spring Boot modular monolith"]
    Web -->|"Server-side REST"| App
    App --> DB[("PostgreSQL + pgvector")]
    App --> Discovery["OpenAlex · DataCite · DOAJ · CORE"]
    App --> Access["Unpaywall · arXiv"]
```

The browser talks to same-origin Next.js route handlers. Those handlers and the MCP adapter call the same Spring application use cases, so authentication, ownership, validation, and provider policies stay centralized.

## Quick start

You need Git and Docker Desktop or Docker Engine with Compose v2.

```bash
git clone https://github.com/peprick/openscholar-mcp.git
cd openscholar-mcp
cp .env.example .env
docker compose up --build
```

Open [http://127.0.0.1:3000](http://127.0.0.1:3000). PostgreSQL, Spring Boot, and Next.js bind to loopback by default. The web application works without provider credentials or an MCP key.

Stop the stack without deleting your library or cached searches:

```bash
docker compose down
```

Delete the local PostgreSQL volume only when you intentionally want a clean database:

```bash
docker compose down --volumes
```

### Optional local configuration

Edit the ignored `.env` file to enable additional capabilities:

| Variable | Purpose | Required? |
|---|---|---|
| `OPENALEX_API_KEY` | Raises the OpenAlex allowance | No |
| `UNPAYWALL_EMAIL` | Enables DOI-based legal-access lookup | Only for Unpaywall |
| `MCP_LOCAL_API_KEY` | Enables the local `/mcp` endpoint | Only for MCP |
| `DATACITE_ENABLED` | Adds thesis/dissertation metadata discovery | No; default `false` |
| `DOAJ_ENABLED` | Adds DOAJ article metadata discovery | No; default `false` |
| `CORE_ENABLED`, `CORE_LICENSE_CONFIRMED` | Adds CORE metadata discovery | Both must be `true` after a separate licence review |

Generate a local MCP key with `openssl rand -hex 32`. Never commit the generated value. The complete configuration surface is documented in [.env.example](.env.example).

## Use it from an agent

With `MCP_LOCAL_API_KEY` configured, connect a Streamable HTTP client to `http://127.0.0.1:8080/mcp`:

```json
{
  "mcpServers": {
    "openscholar": {
      "type": "streamable-http",
      "url": "http://127.0.0.1:8080/mcp",
      "headers": {
        "Authorization": "Bearer replace-with-your-local-key"
      }
    }
  }
}
```

The server advertises:

- `search_research`
- `get_paper_details`
- `get_legal_full_text`
- `search_saved_library`
- `export_citations`

See the [MCP quickstart](docs/MCP_QUICKSTART.md) for Inspector commands, raw protocol examples, security behavior, and the supported conformance subset.

## Develop locally

### Backend

Requires JDK 21 and Docker for PostgreSQL/Testcontainers.

```bash
cd backend
./mvnw spring-boot:run
```

```bash
cd backend
./mvnw --batch-mode --no-transfer-progress verify
```

### Frontend

Requires Node.js 22.13 or newer; Node.js 24 LTS is recommended. Start the backend first.

```bash
cd frontend
corepack enable
pnpm install --frozen-lockfile
cp .env.example .env.local
pnpm dev
```

```bash
cd frontend
pnpm check
```

`pnpm check` runs the container-entrypoint check, ESLint, strict TypeScript, Vitest, and a production Next.js build. See the [development guide](docs/DEVELOPMENT.md) for component and browser-test workflows.

## Repository layout

```text
backend/      Spring Boot REST/MCP application, providers, jobs, and persistence
frontend/     Next.js UI, BFF route handlers, PDF.js reader, and browser tests
deploy/       Hardened single-host deployment and private monitoring templates
docs/         Architecture, contracts, operations, security, and evidence
scripts/      Conformance, supply-chain, performance, backup, and restore tooling
security/     Checked vulnerability exceptions and OpenVEX records
```

Start with the [documentation index](docs/README.md), or jump directly to:

- [Architecture](docs/ARCHITECTURE.md)
- [REST and MCP contracts](docs/API_AND_MCP.md) and [OpenAPI 3.1](docs/openapi.yaml)
- [Data model](docs/DATA_MODEL.md)
- [Security and legal boundaries](docs/SECURITY_AND_LEGAL.md)
- [Deployment guide](docs/DEPLOYMENT.md)
- [Testing strategy](docs/TESTING_STRATEGY.md)

## Current status

| Surface | Status |
|---|---|
| Local web application, REST API, and PostgreSQL persistence | Implemented |
| Five-tool MCP server | Implemented |
| Optional hosted OIDC mode | Implemented; synthetically tested |
| Single-host deployment and monitoring templates | Implemented and locally validated |
| Public hosted deployment | Not published |
| PDF storage or proxying | Intentionally not implemented |

Production use still requires real identity-provider registration, published and reviewed runtime images, alert routing, backups, provider/legal approval, and target-environment security and accessibility validation. See the concise [roadmap](docs/ROADMAP.md).

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Report vulnerabilities through the private process in [.github/SECURITY.md](.github/SECURITY.md), not through a public issue.

## License

No open-source license has been selected. Until the repository owner adds one, normal copyright rules apply; public source availability does not grant permission to copy, modify, or redistribute the project.
