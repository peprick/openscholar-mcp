# Project Plan

## 1. Vision

OpenScholar MCP turns a topic into an evidence-backed reading workspace. It combines scholarly discovery, legal full-text resolution, persistent caching, personal knowledge management, and agent-compatible MCP tools without pretending that every indexed paper is freely downloadable.

The target workflow is:

1. Understand a topic and explicit filters.
2. Reuse sufficiently fresh local results.
3. Search external scholarly providers only when needed.
4. Normalize and deduplicate records from different providers.
5. Resolve and verify legal full-text locations.
6. Explain ranking and provenance.
7. Save reusable papers, searches, collections, and notes.
8. Offer the same capabilities through REST and MCP.

## 2. Scope

### Original MVP scope

- Single-user local installation.
- Topic, title, author, and keyword search.
- Year, document type, open-access, language, and citation filters; provider/source filtering is post-MVP.
- OpenAlex discovery integration.
- Unpaywall legal-access resolution.
- arXiv exact-identifier legal-access evidence and PDF links.
- PostgreSQL persistence and query-result caching.
- DOI, arXiv, OpenAlex-ID, and provider-record deduplication; conservative title/fuzzy reconciliation is post-MVP.
- Search results, paper details, collections, and a basic PDF reader.
- BibTeX and CSL-JSON export.
- MCP tools for search, details, stored legal full text, saved-library lookup, and citation export.
- Docker Compose development environment.

The original local MVP is implemented. The project has since added typed publication metadata, optional provider fan-out, durable refresh jobs, owner-scoped hosted identity, privacy endpoints, and deployment/operations artifacts.

### Implemented expansions

- Disabled-by-default DataCite thesis/dissertation and DOAJ article discovery.
- Disabled-by-default, separately licence-gated CORE metadata discovery.
- Provider-neutral concurrent fan-out, partial-success snapshots, exact-identifier merging, and reciprocal-rank fusion.
- Versioned abstract/title embeddings plus a default-off, independently evaluated hybrid related-paper read.
- OIDC issuer+subject identities, private libraries/search snapshots, and privacy export/account deletion.
- Durable operational metadata/access refresh jobs, optional scheduling, UI inspection, and manual retry.
- Hosted OIDC resource-server and browser-BFF code plus single-host deployment, blackbox monitoring, and backup/restore templates.

### Deferred or external scope

- PubMed Central, OATD, Shodhganga, and institutional repositories.
- Highlights, annotations, and page-level reading progress. Collection membership already records unread/reading/completed status.
- Optional summaries and question answering over user-selected documents.
- Object storage for documents that are legally permitted to be retained.
- Personalized recommendations and research-gap exploration.
- Real IdP/provider registrations, cloud deployment, managed data/secret services, and launch evidence.

### Explicit non-goals

- Bypassing paywalls, login walls, CAPTCHAs, rate limits, or robots policies.
- Redistributing documents without an appropriate licence.
- Claiming that citation count equals quality.
- Autonomous purchasing, subscription management, or institutional-login automation.
- Generating academic claims without source-level citations.
- Training models on stored documents unless the applicable licence permits it.

## 3. Users and core journeys

### Independent learner

Searches a topic, filters for open-access material, opens a paper, saves it, and returns later to continue reading.

### Student or researcher

Builds a structured reading list, exports citations, adds notes, and compares papers while retaining traceable sources.

### AI-agent user

Connects an MCP-compatible host and asks it to find papers, retrieve details, inspect saved research, or export citations through narrowly scoped tools.

### Maintainer

Inspects provider errors, stale links, ingestion runs, cache effectiveness, and tool-call audit events.

## 4. Functional workstreams

### Discovery

- Query parsing and validation.
- Concurrent provider fan-out with per-adapter deadlines/body limits, partial failures, and provider rate-limit translation. General interactive retries/circuit breakers remain deferred; durable jobs have their own bounded retry policy.
- Result normalization into a canonical paper model.
- Deduplication and version reconciliation.
- Transparent result ranking.

### Access resolution

- Determine whether a work has an open, repository, preprint, abstract-only, or restricted location.
- Prefer canonical repository or publisher URLs supplied by trusted indexes.
- Verify links without crawling restricted content.
- Record licence and provenance when available.

### Persistent knowledge

- Cache normalized paper metadata independent of individual search caches.
- Store query fingerprints, filters, result order, and freshness timestamps.
- Associate papers with later topics; versioned title/abstract embedding storage already exists as an opt-in offline path.
- Preserve user collections, notes, and reading state until the user deletes them.

### Reader and library

- Render supported PDFs with PDF.js.
- Open external landing pages when browser embedding is prohibited.
- Save, tag, and export papers; annotations/highlights remain planned.
- Show access and licence information beside every document action.

### MCP

- Publish typed, read-oriented research tools.
- Keep write tools separate and make confirmation expectations explicit.
- Return structured content with stable identifiers and provenance.
- Avoid passing full conversation history to integrations.

## 5. Delivery strategy

The project was built from this first vertical slice before provider breadth:

```text
topic -> local cache -> OpenAlex -> normalize -> PostgreSQL -> REST -> result UI
```

Subsequent slices added legal-access resolution/reading, the persistent library and batch citation export, MCP, measured semantic retrieval, optional provider fan-out, durable jobs, and hosted identity/privacy boundaries. AI-assisted analysis remains deferred. Hosted deployment is now an evidence and external-service integration phase, not an unimplemented application-authentication phase.

## 6. Quality gates

Each milestone must satisfy:

- Database changes are delivered through Flyway migrations.
- Provider clients have contract tests using synthetic or permitted fixtures.
- No external API is required for the deterministic test suite.
- REST and MCP inputs are schema-validated.
- Logs do not contain secrets or complete copyrighted documents.
- Access decisions include provider, URL, licence if known, and verification time.
- User-visible rankings include an explanation payload.
- Docker Compose can start the milestone from a clean checkout.

## 7. Major risks

| Risk | Mitigation |
|---|---|
| Provider API changes or outages | Adapter interface, whole-exchange/body bounds, fixtures, partial results, caching; circuit breakers/general interactive retries remain deferred |
| Duplicate records and versions | Exact identifier reconciliation and deterministic multi-provider fusion; conservative fuzzy matching remains deferred |
| Stale or misleading PDF URLs | Store multiple locations, verify periodically, retain last-known status |
| Copyright or licence ambiguity | Store links by default; retain PDFs only when permission is explicit |
| Prompt injection inside documents | Treat documents as untrusted data and isolate them from tool instructions |
| Poor semantic ranking | Launch with explainable lexical/metadata ranking and evaluate vectors separately |
| Excessive project complexity | Modular monolith, default-off optional providers/workers/hybrid mode, narrow MCP surface, deferred LLM features |
| API rate limits | Owner-scoped cache, bounded fan-out, provider response translation, access cooldown, durable-job retry; aggregate quotas and cross-instance coordination remain deployment work |

## 8. Public portfolio definition of done

These remain release gates; repository artifacts alone do not satisfy them.

- Reproducible setup from a clean clone.
- Public architecture, security, and data-source documentation.
- Search/full-text demo covering success, partial failure, and restricted access.
- REST OpenAPI specification and documented MCP tools.
- Automated unit, integration, contract, end-to-end, and MCP conformance checks.
- Measured search latency, cache-hit ratio, provider error rate, and deduplication quality.
- Screenshots or a short demo video.
- No embedded secrets or unlicensed sample PDFs.
