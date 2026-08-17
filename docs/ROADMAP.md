# Delivery Roadmap

Milestones are outcome-based. Estimates assume one developer learning parts of the stack and should be adjusted after the first vertical slice.

## Milestone 0 — Executable skeleton

Estimated effort: 2–4 days.

Status: backend foundation complete; frontend generation and the backend/frontend connectivity check remain.

- Generate Spring Boot 4.1/Java 21 Maven project.
- Generate Next.js TypeScript project.
- Add Maven wrapper and pnpm lockfile.
- Add Compose with PostgreSQL/pgvector.
- Configure Flyway, Actuator, formatting, CI, and environment examples.
- Add health endpoints and backend/frontend connectivity check.

Exit: clean clone starts through documented commands and CI passes.

## Milestone 1 — Cached OpenAlex search

Estimated effort: 1–2 weeks.

Status: backend vertical slice complete; search/result UI, operational request budgets, and final milestone hardening remain.

- Canonical paper/search domain models and initial Flyway migrations.
- OpenAlex adapter with rate limits and resilience.
- Query normalization, fingerprinting, cache policy, normalization, and exact-ID deduplication.
- `POST /api/v1/searches` and paper details.
- Search/result-detail UI with provenance and cache status.

Exit: persisted results, repeated-query reuse, and useful partial/failure responses.

## Milestone 2 — Legal access and reader

Estimated effort: 1–2 weeks.

- Unpaywall DOI resolution and arXiv integration.
- Paper-version/access model and safe URL verification.
- PDF/landing-page reader experience.
- BibTeX and CSL-JSON export.
- Restricted/unavailable/repository-copy tests.

Exit: users identify/read legal open versions without bypassing controls.

## Milestone 3 — Library

Estimated effort: 1 week.

- Collections, reading status, and tags.
- Saved-library search and citation batch export.
- Optional local-user bootstrap.

Exit: saved research survives restarts and is manageable through the UI.

## Milestone 4 — MCP server

Estimated effort: 1–2 weeks.

- Spring AI WebMVC MCP server starter.
- Stateless Streamable HTTP and read-only tools.
- Job-handle tools for long searches.
- Local API-key security, Origin validation, and audit context.
- Official MCP conformance suite for revision `2025-11-25`.
- Connection example for at least one compatible host.

Exit: an MCP client discovers/calls tools with the same policies as REST.

## Milestone 5 — Search quality and pgvector

Estimated effort: 1–2 weeks.

- Relevance/deduplication evaluation sets.
- PostgreSQL full-text search baseline.
- Embedding-provider decision and versioned abstract embeddings.
- HNSW plus hybrid ranking and related-topic reuse.
- Measured comparison against lexical baseline.

Exit: measured retrieval improvement without hiding ranking rationale.

## Milestone 6 — Provider expansion and jobs

Estimated effort: 2 weeks.

- CORE and one thesis source.
- PubMed Central or DOAJ based on target audience.
- Scheduled metadata/access refresh.
- Job dashboard, retry controls, provider metrics, request budgets.
- Optional permitted-document storage.

Exit: improved coverage with isolated partial failures.

## Milestone 7 — Hosted portfolio release

Estimated effort: 1–2 weeks.

- OIDC and principal-scoped data.
- Hardened MCP authorization/scopes.
- Privacy/export/delete flows.
- Managed PostgreSQL and deployed containers.
- TLS, backups, alerts, and secret management.
- Accessibility, performance, threat-model, and licence reviews.
- Demo recording and architecture/results publication.

Exit: public demo is secure, reproducible, observable, and evidence-backed.

## Later backlog

- PDF notes/highlights.
- Evidence-backed summaries and paper comparison.
- Research maps and citation graphs.
- Collaborative collections.
- Provider plug-in framework.
- Offline embeddings.
- Mobile reader improvements.

## Recommended order

Do not start with embeddings, LLM summaries, every provider, or multi-user auth. The core portfolio story is query → normalized evidence → legal access → persistent reuse → UI → MCP. Later capabilities should improve a measured weakness in that path.
