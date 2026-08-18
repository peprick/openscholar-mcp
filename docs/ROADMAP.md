# Delivery Roadmap

Milestones are outcome-based. Estimates assume one developer learning parts of the stack and should be adjusted after the first vertical slice.

## Milestone 0 — Executable skeleton

Estimated effort: 2–4 days.

Status: complete. Backend, Next.js client, pnpm lockfile, local connectivity, and the root full-stack Compose profile are implemented.

- Generate Spring Boot 4.1/Java 21 Maven project.
- Implemented: Next.js 16.2 App Router project with strict TypeScript.
- Add Maven wrapper and pnpm lockfile.
- Implemented: standalone backend PostgreSQL Compose and root PostgreSQL/backend/frontend stack.
- Configure Flyway, Actuator, formatting, CI, and environment examples.
- Implemented: health/status endpoints and a visible backend connectivity state.

Exit: clean clone starts through documented commands and CI passes.

## Milestone 1 — Cached OpenAlex search

Estimated effort: 1–2 weeks.

Status: the end-to-end search, immutable result snapshot, and canonical paper-details UI are complete. Operational request budgets, pagination continuity, richer typed publication metadata, and final hardening remain.

- Canonical paper/search domain models and initial Flyway migrations.
- OpenAlex adapter with rate limits and resilience.
- Query normalization, fingerprinting, cache policy, normalization, and exact-ID deduplication.
- Implemented: `POST /api/v1/searches`, immutable snapshots, and canonical paper details with stored provenance/access summary.
- Implemented: paper-specific credited-name snapshots and publication date/year integrity.
- Implemented: accessible search/result-detail UI with provenance, ranking rationale, provider coverage, warnings, and cache status.

Exit: persisted results, repeated-query reuse, and useful partial/failure responses.

## Milestone 2 — Legal access and reader

Estimated effort: 1–2 weeks.

Status: backend legal-access resolution, verified-version UI, external link fallback, and single-paper citation downloads are complete. The in-app PDF.js reader, richer citation metadata, and final hardening remain.

- Implemented: exact DOI resolution through Unpaywall with optional backend-email configuration.
- Implemented: exact arXiv-ID lookup with canonical response matching and provider-compliant request pacing.
- Implemented: paper-version/access persistence, 24-hour cache, cooldown-protected forced refresh, stale fallback, and provider isolation.
- Implemented: safe link-only verification for landing pages and PDF candidates without retaining PDF bytes.
- Implemented: deterministic single-paper BibTeX and CSL-JSON downloads without provider calls.
- Implemented: verified external PDF/repository actions with explicit link-only handling; in-app PDF.js reading remains.
- Implemented: browser citation actions for BibTeX and CSL-JSON through a same-origin proxy.
- Citation metadata hardening: credited-name snapshots, typed publication fields, identifier preference, and schema fixtures.
- Restricted/unavailable/repository-copy tests.

Exit: users identify and open legal versions in the UI, and export citations, without bypassing controls.

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
