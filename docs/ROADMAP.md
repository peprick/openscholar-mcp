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

Status: the search application pipeline, owner-scoped immutable result snapshots, multi-provider continuation UI, canonical paper-details UI, and typed publication metadata are complete. Transport-lifecycle cancellation and final hardening remain.

- Canonical paper/search domain models and initial Flyway migrations.
- OpenAlex adapter with rate limits and resilience.
- Query normalization, fingerprinting, cache policy, normalization, and exact-ID deduplication.
- Implemented: `POST /api/v1/searches`, immutable snapshots, and canonical paper details with stored provenance/access summary.
- Implemented: server-derived cursor continuation that preserves the stored query and filters across immutable result pages, with cached replay and an accessible web control.
- Implemented: bounded, single-instance coordination with an in-lock cache recheck and a configurable 12-second default acquisition limit. A timed-out waiter never invokes duplicate work or cancels the leader; it reuses a newly fresh exact snapshot, falls back to an available stale snapshot with `SEARCH_COORDINATION_TIMEOUT`, or returns the retryable timeout error when no snapshot exists. Interrupted waits preserve their distinct retryable `SEARCH_COORDINATION_INTERRUPTED` contract.
- Implemented: configurable 8 MiB OpenAlex response-body limit before JSON deserialization, including declared-length and streaming enforcement with a stable non-retryable oversized-response error.
- Implemented: configurable 10-second OpenAlex whole HTTP exchange deadline from request transmission through response-body consumption, with stable retryable timeout translation. It is independent of the coordination-acquisition limit and does not bound database or persistence work, final response serialization, or the full REST/MCP operation.
- Implemented: configurable 18-second `SearchResearchUseCase` execution deadline shared by REST and MCP, using a context-propagating virtual-thread worker, interrupting cancellation, and cooperative checkpoints. It returns retryable `SEARCH_DEADLINE_EXCEEDED` or `SEARCH_EXECUTION_INTERRUPTED`, starts no post-deadline fallback, and does not claim transport serialization/socket or guaranteed in-flight JDBC persistence cancellation.
- Implemented: paper-specific credited-name snapshots and publication date/year integrity.
- Implemented: accessible search/result-detail UI with provenance, ranking rationale, provider coverage, warnings, and cache status.

Exit: persisted results, repeated-query reuse, and useful partial/failure responses.

## Milestone 2 — Legal access and reader

Estimated effort: 1–2 weeks.

Status: backend legal-access resolution, verified-version UI, supported-source PDF.js reader, external link fallback, typed single/batch citation downloads, automated keyboard/reader scenarios, and local WCAG 2.2 axe evidence are complete. Target-environment assistive-technology review remains a launch gate.

- Implemented: exact DOI resolution through Unpaywall with optional backend-email configuration.
- Implemented: exact arXiv-ID lookup with canonical response matching and provider-compliant request pacing.
- Implemented: paper-version/access persistence, 24-hour cache, cooldown-protected forced refresh, stale fallback, and provider isolation.
- Implemented: safe link-only verification for landing pages and PDF candidates without retaining PDF bytes.
- Implemented: deterministic single-paper BibTeX and CSL-JSON downloads without provider calls.
- Implemented: direct PDF.js reading for fresh, verified, CORS-compatible HTTPS PDF locations, with no byte proxy or retention and an external fallback.
- Implemented: browser citation actions for BibTeX and CSL-JSON through a same-origin proxy.
- Implemented: citation metadata hardening with credited-name snapshots, typed publication fields, identifier preference, and schema fixtures.
- Implemented: restricted, unavailable, repository-copy, and arXiv-preprint classification tests.

Exit: users identify and open legal versions in the UI, and export citations, without bypassing controls.

## Milestone 3 — Library

Estimated effort: 1 week.

Status: complete. Local bootstrap and OIDC issuer+subject identities, owner-scoped collection management, saved-paper status/tags, lexical library search, and ordered batch citation downloads are implemented through REST and the Next.js UI.

- Implemented: collections, reading status, and normalized tags.
- Implemented: saved-library search and citation batch export.
- Implemented: fixed local-user bootstrap for local mode and lazy issuer+subject user provisioning for hosted OIDC mode, with negative cross-user authorization coverage.

Exit: saved research survives restarts and is manageable through the UI.

## Milestone 4 — MCP server

Estimated effort: 1–2 weeks.

Status: complete for the current five-tool protocol surface. The Spring AI 2.0 stateless WebMVC transport, five read-oriented tool adapters, local bearer/Origin boundary, hosted JWT audience/scope boundary and protected-resource metadata, bounded per-address/principal rate limiting, request IDs, response safety headers, Micrometer request metrics, raw JSON-RPC calls for every database-only tool, an MCP Inspector smoke run, and the applicable official conformance scenarios are implemented. Inspector/conformance evidence remains local; a real hosted authorization-server/client interoperability run is still required.

- Implemented: Spring AI WebMVC MCP server starter.
- Implemented: stateless Streamable HTTP and five bounded, non-destructive, read-oriented tools. Search is correctly marked non-read-only/non-idempotent because it may fetch and persist cache/catalog data; the other four tools are database-only reads.
- Deferred post-MVP: job-handle tools if provider breadth creates genuinely long-running searches.
- Implemented: local API-key security, Origin validation, bounded inbound rate limiting, metrics, and request logging context.
- Implemented: hosted `openscholar.mcp` JWT authorization, issuer/audience validation, issuer+subject ownership, hashed-principal rate-limit identity, and OAuth protected-resource metadata. Inbound tokens are never forwarded to providers.
- Implemented: the shared 18-second application deadline bounds `search_research` execution and exposes safe deadline/interruption codes.
- Known limitation: the configured 20-second MCP request timeout is not enforced by the stateless MCP Java SDK 2.0 path. MCP framework parsing/serialization, socket lifetime, client disconnects, and `notifications/cancelled` do not yet cancel the tool worker.
- Implemented: pinned official conformance `server-initialize` and `tools-list` scenarios run with `--spec-version 2025-11-25` through a loopback bearer-injection proxy; both pass without warnings and discover exactly five tools.
- The fixture-only full conformance suite is intentionally not a production target because it requires synthetic tools/resources/prompts and capabilities OpenScholar does not advertise.
- Implemented: documented MCP Inspector connection plus live `tools/list` and `search_saved_library` smoke calls.

Exit: an MCP client discovers/calls tools with the same policies as REST.

## Milestone 5 — Search quality and pgvector

Estimated effort: 1–2 weeks.

Status: in progress. A versioned synthetic related-paper relevance corpus, PostgreSQL full-text vector/GIN migration, deterministic database-only ranker, bounded REST endpoint, and focused PostgreSQL/API tests are implemented. The provider/model/input decision, immutable `V10` embedding-profile and exact-store foundation, artifact-and-runtime-pinned local Ollama adapter, bounded offline backfill, opt-in exact-vector evaluation, exploratory hybrid sensitivity sweep, frozen independent holdout validation, pinned `V11` HNSW gate, and default-off production-readiness hybrid path are implemented. Generation remains opt-in and no model is downloaded by the project.

- Implemented: the related-paper relevance sets plus a separate frozen exact-identifier deduplication fixture covering DOI/arXiv/OpenAlex normalization, provider replay, common-title false positives, DOI-less theses, and separate preprint/published records.
- Implemented: PostgreSQL full-text search baseline over weighted canonical title, abstract, and venue metadata.
- Implemented: local-first provider decision—full-digest-pinned Qwen3-Embedding-0.6B at 1024 dimensions on pinned Ollama `0.31.1`, with OpenAI `text-embedding-3-large` shortened to 1024 only as a future opt-in evaluation profile.
- Implemented: provider-neutral immutable profile registry, deterministic title/abstract v1 input, checksum-guarded vector store, source invalidation, and exact same-profile cosine lookup.
- Implemented: disabled-by-default local Ollama inference with exact runtime/tag/full-digest verification, digest/runtime-derived profile identity, fixed non-truncating request parameters, no-proxy bounded transport, output validation, and no model-pull lifecycle.
- Implemented: explicit non-web cursor-paged offline backfill with same-profile advisory locking, short database transactions around source/save operations, bounded retries, systemic-failure aborts, per-paper failure accounting, and nonzero incomplete-run exits.
- Implemented: measured lexical baseline (macro Recall 1.000 and macro nDCG 0.857) and exact vector-only baseline (macro Recall 1.000 and macro nDCG 0.934) on the synthetic v1 fixture. The vector run is gated, uses an ephemeral Testcontainer and a locally installed full-digest-pinned model, and is skipped by ordinary CI.
- Implemented: label-independent fixed-scale hybrid sensitivity at five predeclared weights. Weight 0.50 has the highest observed in-sample macro result on the five synthetic query groups, but no production weight or hybrid regression floor is selected.
- Implemented: the independently authored 26-paper, seven-query holdout was scored only after freezing the `w = 0.50` transform and gates. Lexical macro results were Recall 0.857, nDCG 0.648, Precision@1 0.286, and MRR 0.571; the frozen hybrid reached 1.000, 0.917, 0.857, and 0.929 respectively, for gains of `+0.143`, `+0.269`, `+0.571`, and `+0.357`. It strictly improved five query-group nDCG values, regressed none, and passed every frozen gate.
- Implemented: the frozen HNSW mechanics gate achieved macro Recall@25 1.0000 with approximate p95 20.082 ms versus exact p95 47.491 ms (`2.365x`) on its reference-shaped run.
- Implemented: a default-off hybrid path over bounded lexical/HNSW pools, exact lexical-candidate vector-coverage checks, frozen 50/50 scoring, deterministic UUID ties, typed component values, and explicit lexical fallback modes. Database/provider calls remain outside the read path.
- Next: evaluate related-topic reuse, multilingual lexical configuration, and a larger representative relevance set before considering any default change.

Exit: measured retrieval improvement without hiding ranking rationale.

## Milestone 6 — Provider expansion and jobs

Estimated effort: 2 weeks.

Status: the planned provider/job foundation is implemented. OpenAlex plus optional DataCite, DOAJ, and licence-gated CORE participate in concurrent partial-success fan-out and deterministic fusion. Durable operational refresh jobs, UI/manual retry, metrics, and optional stale-target scheduling exist. Remaining items are production provider authorization/quotas, client cancellation, optional legally permitted storage, and further sources.

- Implemented: disabled-by-default CORE API v3 work discovery with an explicit operator licence-confirmation gate, metadata-only mapping, bounded transport, and adapter-owned pagination. Actual deployment authorization remains external.
- Implemented: disabled-by-default DOAJ v4 article discovery with keyless metadata/link mapping, bounded transport, and adapter-owned pagination.
- Implemented: disabled-by-default DataCite thesis/dissertation metadata discovery with keyless DOI API access, provider-owned paging, and deliberately no discovery PDF/open-access claim.
- Implemented: concurrent provider fan-out, isolated partial failures, exact-identifier merging, combined opaque cursors, provider-set cache fingerprints, reciprocal-rank fusion, and provider metrics.
- PubMed Central remains a target-audience-dependent follow-up.
- Implemented: PostgreSQL-backed `SEARCH_METADATA`/`PAPER_ACCESS` operational jobs with active-target deduplication, expiring leases, bounded retry/backoff, default-off worker/scheduling, REST/UI dashboard, and manual retry. They are not per-user MCP job handles.
- Deferred: client-disconnect/MCP-notification cancellation propagation and MCP Tasks/job handles.
- Optional permitted-document storage remains deferred; the current product stores links only and retains no PDF bytes.

Exit: improved coverage with isolated partial failures.

## Milestone 7 — Hosted portfolio release

Estimated effort: 1–2 weeks.

Status: the application and single-host deployment artifacts are implemented and tested locally/synthetically, but there is no live cloud deployment and the public-release evidence gate remains open.

- Implemented: Spring Security OIDC resource server, issuer/audience/scope validation, issuer+subject-owned searches/libraries, and a Next.js authorization-code/PKCE BFF with encrypted HttpOnly sessions.
- Implemented: hosted MCP authorization challenges/scopes and protected-resource metadata.
- Implemented: no-store privacy export and confirmed account deletion with documented shared-catalog/reprovision semantics.
- Implemented as templates: hardened single-host Compose/Caddy topology, blackbox monitoring, guarded checksum/encryption-capable PostgreSQL backup/restore, threat model, and supply-chain workflow.
- Implemented locally: deterministic Compose-backed Playwright coverage, WCAG 2.2 axe checks, a frozen exact-identifier deduplication gate, and a reproducible loopback performance harness with passing synthetic reference evidence.
- External: real IdP registration/interoperability/rotation, public DNS/TLS/ingress, immutable signed images, managed PostgreSQL/PITR/HA decision, secret manager, off-host backups/restore drills, and an actual deployment.
- External: working alerts/on-call, privacy/licence/provider approval, target-environment assistive-technology/load/penetration/disaster-recovery evidence.
- Implemented locally: reproducible search/paper/collection portfolio screenshots and an evidence page. An optional video and public architecture/results publication remain external presentation work.

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

The core portfolio story remains query → normalized evidence → legal access → persistent reuse → UI → MCP. Provider fan-out and hosted identity boundaries now exist; the next priority is proving them in a real deployment rather than expanding claims. Embedding work continues to follow the measured lexical baseline, and LLM summaries remain later work subject to citation, prompt-injection, privacy, and document-rights controls.
