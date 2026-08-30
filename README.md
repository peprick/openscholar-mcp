# OpenScholar MCP

[![Backend CI](https://github.com/peprick/openscholar-mcp/actions/workflows/backend-ci.yml/badge.svg)](https://github.com/peprick/openscholar-mcp/actions/workflows/backend-ci.yml)
[![Frontend CI](https://github.com/peprick/openscholar-mcp/actions/workflows/frontend-ci.yml/badge.svg)](https://github.com/peprick/openscholar-mcp/actions/workflows/frontend-ci.yml)
[![End-to-end tests](https://github.com/peprick/openscholar-mcp/actions/workflows/frontend-e2e.yml/badge.svg)](https://github.com/peprick/openscholar-mcp/actions/workflows/frontend-e2e.yml)
[![Security](https://github.com/peprick/openscholar-mcp/actions/workflows/security.yml/badge.svg)](https://github.com/peprick/openscholar-mcp/actions/workflows/security.yml)
[![License: all rights reserved](https://img.shields.io/badge/license-all%20rights%20reserved-6b7280.svg)](LICENSE)

OpenScholar is a self-hosted research discovery workspace and MCP server. It searches scholarly metadata, combines duplicate records, caches reusable results, verifies legal open-access links, and helps readers organize papers and export citations.

OpenScholar stores metadata, search and library state, and verified links—not research PDFs. It does not scrape publisher pages, bypass paywalls, or proxy document bytes.

The setup, operation, and development instructions below are for copyright holders and people who have received prior written permission. Public source availability does not authorize running, hosting, modifying, or redistributing OpenScholar; see [LICENSE](LICENSE).

![OpenScholar search results with canonical papers and source provenance](docs/images/readme-preview.png)

## What it does

- Searches OpenAlex by default, with optional Europe PMC, DataCite, DOAJ, and licence-gated CORE discovery adapters.
- Searches owner-visible metadata locally when explicitly requested or when AUTO mode cannot return provider-backed results.
- Installs as a PWA with an account-neutral fallback and one explicit, passphrase-encrypted, metadata-only offline collection; server-backed search still requires the local or hosted OpenScholar stack.
- Normalizes and merges records by DOI, arXiv ID, OpenAlex ID, PMID, PMCID, and provider identity.
- Opens an owner-visible canonical paper directly from a DOI, arXiv, or OpenAlex reference without calling a provider.
- Stores owner-scoped, immutable search snapshots in PostgreSQL so repeated searches can reuse prior results.
- Verifies legal full-text candidates through exact DOI/arXiv evidence from Unpaywall and arXiv.
- Provides collections, reading status, tags, saved-library search, and BibTeX or CSL-JSON exports.
- Gives readers a plain-language privacy center for downloading their OpenScholar data or deleting their owned searches and library state.
- Opens fresh, verified, HTTPS, CORS-compatible PDFs in a browser PDF.js reader and falls back to the source site when embedded reading is not supported.
- Exposes six bounded research tools and three read-only JSON resource templates to agents over stateless Streamable HTTP MCP.
- Returns versioned, non-disclosing MCP tool errors with stable codes, actions, and optional retry guidance.

## Architecture

```mermaid
flowchart LR
    Reader["Reader"] --> Web["Next.js UI and BFF"]
    Agent["MCP client or agent"] -->|"Streamable HTTP /mcp"| App["Spring Boot modular monolith"]
    Web -->|"Server-side REST"| App
    App --> DB[("PostgreSQL + pgvector")]
    App --> Discovery["OpenAlex · Europe PMC · DataCite · DOAJ · CORE"]
    App --> Access["Unpaywall · arXiv"]
```

The browser talks to same-origin Next.js route handlers. Those handlers and the MCP adapter call the same Spring application use cases, so authentication, ownership, validation, and provider policies stay centralized.

Offline use is deliberately narrow: one selected collection can be saved as a read-only full snapshot and refreshed manually. The encrypted IndexedDB copy contains no PDF or access URL, can become stale, and may be evicted by the browser; it is not a backup or a replacement for PostgreSQL. A weak passphrase or a compromised browser/device can still expose it.

## Quick start

If you have the required written permission, the local stack needs Git and Docker Desktop or Docker Engine with Compose v2.

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
| `EUROPE_PMC_ENABLED` | Adds metadata-only journal-article discovery for records held in PMC | No; default `false` |
| `DATACITE_ENABLED` | Adds thesis/dissertation metadata discovery | No; default `false` |
| `DOAJ_ENABLED` | Adds DOAJ article metadata discovery | No; default `false` |
| `CORE_ENABLED`, `CORE_LICENSE_CONFIRMED` | Adds CORE metadata discovery | Both must be `true` after a separate licence review |

Generate a local MCP key with `openssl rand -hex 32`. Never commit the generated value. The complete configuration surface is documented in [.env.example](.env.example).

Europe PMC is an opt-in metadata source, not a document source. Its adapter uses only the REST `/search` route, maps DOI/PMID/PMCID and bibliographic metadata, leaves `pdfUrl` null, and never calls full-text, supplementary-file, PDF, or bulk-download routes. Any provider-reported open-access value remains an unverified hint; legal-access verification continues through the separate exact-identifier pipeline.

Its [provider-quality evaluation](docs/PROVIDER_QUALITY.md) is engineering-only. A deterministic synthetic PR gate exercises the real catalog/search-snapshot mechanics; one optional diagnostic samples already fused pages, and a separate opt-in Testcontainers evaluator fetches each provider once before replaying identical raw metadata through isolated and fused rollback-only scenarios. A manual offline workflow projects a verified capture into a blinded, packet-local review worksheet, verifies the exact reviewed packet during compilation and scoring, and scores only the resulting digest-bound judgments under the frozen evaluation policy; the repository includes no real labels. These paths retain no PDFs and expose no metrics to readers. Default enablement still requires clean reviewed captures, an independently authored holdout, time-separated live evidence, and an explicit maintainer decision.

The [owner-scoped LOCAL search-quality baseline](docs/SEARCH_QUALITY.md#owner-scoped-local-topic-search-baseline) is also engineering-only. It uses synthetic metadata made visible through prior searches and collections, exercises the production LOCAL path on PostgreSQL, and measures Recall@10, nDCG@10, Precision@1, and MRR behind zero-leak and zero-provider-call gates. It performs no provider-network access, stores no PDFs, exposes no reader-facing metrics, and leaves production's explicit `english` text-search configuration unchanged.

The [related-topic reuse development comparison](docs/SEARCH_QUALITY.md#related-topic-reuse-development-comparison) keeps that production LOCAL ranking as its control. An author-only control exposed feedback drift during label-visible development, so the resulting frozen regression policy admits only control results with `TITLE_EXACT`, `TITLE_PREFIX`, `TITLE_CONTAINS`, or `POSTGRES_FULL_TEXT` reasons as its first two seeds; an author-only query takes the exact ranking fallback when no eligible topic-signal seed remains. Bounded owner- and filter-scoped source-title feedback is fused with weighted reciprocal-rank fusion. On four judged synthetic queries, the focused reference run moved macro Recall@10 from `0.542` to `1.000` and macro nDCG@10 from `0.819` to `0.968`, added five relevant results absent from the control top ten, and improved all three opportunity queries. It recorded zero owner leaks, filter violations, rank-one adversaries, provider calls, or candidate-snapshot writes, but explicit adversary exposure at 10 moved from one in the control to three in the candidate. The two added owner-visible off-topic controls appeared at rank two, so this tuned development result is not activation evidence. The comparison calls neither the global related-paper endpoint nor a discovery provider, performs no PDF handling or external scholarly/provider call, and changes no product path, search-snapshot contract, UI, REST API, MCP tool, or runtime default; Testcontainers bootstrap can still pull its pinned Docker image. An independently authored blind holdout and target-deployment performance evidence remain required.

The separate [multilingual lexical comparison](docs/SEARCH_QUALITY.md#multilingual-lexical-configuration-comparison) evaluates PostgreSQL `english`, `simple`, and an allowlisted language-aware profile over digest-bound English, German, French, Spanish, and Japanese synthetic metadata. It is an evaluation-only Testcontainers experiment, not a product feature or migration. The language-aware profile uses `simple` as its Japanese fallback, Japanese is reported as unsupported rather than claimed as covered, and production remains on `english` pending representative independent evidence and an indexing/migration design.

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
- `resolve_paper_identifier`
- `get_paper_details`
- `get_legal_full_text`
- `search_saved_library`
- `export_citations`

The server also advertises three read-only, database-backed JSON resource templates for one paper, one owner-visible collection, or one owner-visible saved search. This surface provides no global resource enumeration, subscriptions, provider fetching, arbitrary URL access, filesystem access, or PDF bytes.

See the [MCP quickstart](docs/MCP_QUICKSTART.md) for Inspector commands, raw protocol examples, security behavior, and the supported conformance subset.

`search_research` accepts `AUTO`, `ONLINE`, and database-only `LOCAL` modes and reports the actual execution source independently from caller intent.

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

### Full clean-source verification

From a clean committed checkout with JDK 21+, Node.js 24+, pnpm `11.19.0`, Docker Compose, `jq`, and the standard Unix tooling installed:

```bash
scripts/verify-clean-clone.sh
```

The verifier checks out committed `HEAD` into a detached temporary clone, gives host build tools a minimal environment and temporary home, and composes the repository's backend, frontend, browser, Compose, MCP, policy, and operations checks. Dependency, browser, and container bootstrap may use the network; application searches use the checked-in provider fixture. A unique disposable Compose project is removed without touching a development stack.

Run it only for trusted committed revisions: the backend suite receives access to the privileged Docker socket. Use a disposable runner for untrusted pull requests. This is source-clean local evidence, not a cold build, and Docker caches may be reused. GitHub security/SBOM gates, published-image evidence, and a real backup/restore drill remain separate; see the [testing strategy](docs/TESTING_STRATEGY.md#release-verification).

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
- [Search quality](docs/SEARCH_QUALITY.md)
- [Provider quality evaluation](docs/PROVIDER_QUALITY.md)

## Current status

| Surface | Status |
|---|---|
| Local web application, REST API, and PostgreSQL persistence | Implemented |
| Metadata-only local search with explicit provenance | Implemented |
| Installable PWA and encrypted offline collection | Implemented; one opt-in metadata pack, no stored PDFs or offline mutations |
| Personal-data export and confirmed deletion in the web app | Implemented |
| Six-tool MCP server | Implemented |
| Three read-only MCP JSON resource templates | Implemented |
| Optional hosted OIDC mode | Implemented; synthetically tested |
| Single-host deployment and monitoring templates | Implemented and locally validated |
| Public hosted deployment | Not published |
| PDF storage or proxying | Intentionally not implemented |

Production use still requires real identity-provider registration, published and reviewed runtime images, alert routing, backups, provider/legal approval, and target-environment security and accessibility validation. See the concise [roadmap](docs/ROADMAP.md).

## Contributing and security

Read [CONTRIBUTING.md](CONTRIBUTING.md) before opening a pull request. Report vulnerabilities through the private process in [.github/SECURITY.md](.github/SECURITY.md), not through a public issue.

## License

OpenScholar is source-visible and **all rights reserved**. No open-source licence or permission to use, modify, host, or redistribute the project is granted. See [LICENSE](LICENSE) for the controlling notice and [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for directly retained third-party material. Other third-party components remain under their respective licences.
