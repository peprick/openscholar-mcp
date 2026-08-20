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

Status: the end-to-end search, immutable result snapshot, cursor-continuation UI, and canonical paper-details UI are complete. Operational request budgets, richer typed publication metadata, and final hardening remain.

- Canonical paper/search domain models and initial Flyway migrations.
- OpenAlex adapter with rate limits and resilience.
- Query normalization, fingerprinting, cache policy, normalization, and exact-ID deduplication.
- Implemented: `POST /api/v1/searches`, immutable snapshots, and canonical paper details with stored provenance/access summary.
- Implemented: server-derived cursor continuation that preserves the stored query and filters across immutable result pages, with cached replay and an accessible web control.
- Implemented: bounded, single-instance coordination of identical normal searches with an in-lock cache recheck, preventing concurrent cache misses from duplicating provider calls and snapshots.
- Implemented: configurable 8 MiB OpenAlex response-body limit before JSON deserialization, including declared-length and streaming enforcement with a stable non-retryable oversized-response error.
- Implemented: paper-specific credited-name snapshots and publication date/year integrity.
- Implemented: accessible search/result-detail UI with provenance, ranking rationale, provider coverage, warnings, and cache status.

Exit: persisted results, repeated-query reuse, and useful partial/failure responses.

## Milestone 2 — Legal access and reader

Estimated effort: 1–2 weeks.

Status: backend legal-access resolution, verified-version UI, supported-source PDF.js reader, external link fallback, and single-paper citation downloads are complete. Richer citation metadata, reader accessibility enhancements, and final hardening remain.

- Implemented: exact DOI resolution through Unpaywall with optional backend-email configuration.
- Implemented: exact arXiv-ID lookup with canonical response matching and provider-compliant request pacing.
- Implemented: paper-version/access persistence, 24-hour cache, cooldown-protected forced refresh, stale fallback, and provider isolation.
- Implemented: safe link-only verification for landing pages and PDF candidates without retaining PDF bytes.
- Implemented: deterministic single-paper BibTeX and CSL-JSON downloads without provider calls.
- Implemented: direct PDF.js reading for fresh, verified, CORS-compatible HTTPS PDF locations, with no byte proxy or retention and an external fallback.
- Implemented: browser citation actions for BibTeX and CSL-JSON through a same-origin proxy.
- Citation metadata hardening: credited-name snapshots, typed publication fields, identifier preference, and schema fixtures.
- Restricted/unavailable/repository-copy tests.

Exit: users identify and open legal versions in the UI, and export citations, without bypassing controls.

## Milestone 3 — Library

Estimated effort: 1 week.

Status: complete. The local-user bootstrap, owner-scoped collection management, saved-paper status/tags, lexical library search, and ordered batch citation downloads are implemented through REST and the Next.js UI.

- Implemented: collections, reading status, and normalized tags.
- Implemented: saved-library search and citation batch export.
- Implemented: fixed local-user bootstrap, with principal-scoped boundaries ready for later authentication.

Exit: saved research survives restarts and is manageable through the UI.

## Milestone 4 — MCP server

Estimated effort: 1–2 weeks.

Status: complete for the current five-tool local MVP. The Spring AI 2.0 stateless WebMVC transport, five read-oriented tool adapters, local bearer/Origin boundary, bounded per-address rate limiting, request IDs, response safety headers, Micrometer request metrics, raw JSON-RPC calls for every database-only tool, an MCP Inspector smoke run, and the applicable official conformance scenarios are implemented and verified locally.

- Implemented: Spring AI WebMVC MCP server starter.
- Implemented: stateless Streamable HTTP and five bounded, non-destructive, read-oriented tools. Search is correctly marked non-read-only/non-idempotent because it may fetch and persist cache/catalog data; the other four tools are database-only reads.
- Deferred post-MVP: job-handle tools if provider breadth creates genuinely long-running searches.
- Implemented: local API-key security, Origin validation, bounded inbound rate limiting, metrics, and request logging context.
- Implemented: pinned official conformance `server-initialize` and `tools-list` scenarios run with `--spec-version 2025-11-25` through a loopback bearer-injection proxy; both pass without warnings and discover exactly five tools.
- The fixture-only full conformance suite is intentionally not a production target because it requires synthetic tools/resources/prompts and capabilities OpenScholar does not advertise.
- Implemented: documented MCP Inspector connection plus live `tools/list` and `search_saved_library` smoke calls.

Exit: an MCP client discovers/calls tools with the same policies as REST.

## Milestone 5 — Search quality and pgvector

Estimated effort: 1–2 weeks.

Status: in progress. A versioned synthetic related-paper relevance corpus, PostgreSQL full-text vector/GIN migration, deterministic database-only ranker, bounded REST endpoint, and focused PostgreSQL/API tests are implemented. The provider/model/input decision, immutable `V10` embedding-profile and exact-store foundation, artifact-and-runtime-pinned local Ollama adapter, bounded offline backfill, opt-in exact-vector evaluation, exploratory hybrid sensitivity sweep, and frozen independent holdout validation are implemented. Generation remains opt-in and no model is downloaded by the project; HNSW and production-readiness evaluation remain.

- Implemented: first related-paper relevance evaluation set; dedicated provider/deduplication cases remain.
- Implemented: PostgreSQL full-text search baseline over weighted canonical title, abstract, and venue metadata.
- Implemented: local-first provider decision—full-digest-pinned Qwen3-Embedding-0.6B at 1024 dimensions on pinned Ollama `0.31.1`, with OpenAI `text-embedding-3-large` shortened to 1024 only as a future opt-in evaluation profile.
- Implemented: provider-neutral immutable profile registry, deterministic title/abstract v1 input, checksum-guarded vector store, source invalidation, and exact same-profile cosine lookup.
- Implemented: disabled-by-default local Ollama inference with exact runtime/tag/full-digest verification, digest/runtime-derived profile identity, fixed non-truncating request parameters, no-proxy bounded transport, output validation, and no model-pull lifecycle.
- Implemented: explicit non-web cursor-paged offline backfill with same-profile advisory locking, short database transactions around source/save operations, bounded retries, systemic-failure aborts, per-paper failure accounting, and nonzero incomplete-run exits.
- Implemented: measured lexical baseline (macro Recall 1.000 and macro nDCG 0.857) and exact vector-only baseline (macro Recall 1.000 and macro nDCG 0.934) on the synthetic v1 fixture. The vector run is gated, uses an ephemeral Testcontainer and a locally installed full-digest-pinned model, and is skipped by ordinary CI.
- Implemented: label-independent fixed-scale hybrid sensitivity at five predeclared weights. Weight 0.50 has the highest observed in-sample macro result on the five synthetic query groups, but no production weight or hybrid regression floor is selected.
- Implemented: the independently authored 26-paper, seven-query holdout was scored only after freezing the `w = 0.50` transform and gates. Lexical macro results were Recall 0.857, nDCG 0.648, Precision@1 0.286, and MRR 0.571; the frozen hybrid reached 1.000, 0.917, 0.857, and 0.929 respectively, for gains of `+0.143`, `+0.269`, `+0.571`, and `+0.357`. It strictly improved five query-group nDCG values, regressed none, and passed every frozen gate.
- Next: add an HNSW exact-recall/performance gate, evaluate related-topic reuse, and complete the ranking-reason, fallback, and production-readiness work. Passing the holdout did not activate hybrid ranking; the current related endpoint remains database-only and lexical until those later gates pass.

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
- Additional offline embedding profiles for permitted full text or private content, after retention/privacy review.
- Mobile reader improvements.

## Recommended order

The core portfolio story remains query → normalized evidence → legal access → persistent reuse → UI → MCP. Embedding work now follows the measured lexical baseline: establish immutable provenance and deterministic storage first, then adopt vector or hybrid ranking only when the versioned fixture demonstrates an improvement. LLM summaries, broad provider fan-out, and multi-user auth remain later concerns.
