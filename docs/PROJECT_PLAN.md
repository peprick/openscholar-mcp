# Project Plan

## 1. Vision

OpenScholar MCP turns a topic into an evidence-backed reading workspace. It combines scholarly discovery, legal full-text resolution, persistent caching, personal knowledge management, and agent-compatible MCP tools without pretending that every indexed paper is freely downloadable.

The complete workflow is:

1. Understand a topic and explicit filters.
2. Reuse sufficiently fresh local results.
3. Search external scholarly providers only when needed.
4. Normalize and deduplicate records from different providers.
5. Resolve and verify legal full-text locations.
6. Explain ranking and provenance.
7. Save reusable papers, searches, collections, and notes.
8. Offer the same capabilities through REST and MCP.

## 2. Scope

### MVP scope

- Single-user local installation.
- Topic, title, author, and keyword search.
- Year, document type, open-access, language, source, and citation filters.
- OpenAlex discovery integration.
- Unpaywall legal-access resolution.
- arXiv metadata and PDF integration.
- PostgreSQL persistence and query-result caching.
- DOI/arXiv/title-based deduplication.
- Search results, paper details, collections, and a basic PDF reader.
- BibTeX and CSL-JSON export.
- MCP tools for search, details, legal full text, and reading-list creation.
- Docker Compose development environment.

### Post-MVP scope

- CORE, PubMed Central, DOAJ, OATD, Shodhganga, and institutional repositories.
- Abstract and note embeddings with pgvector.
- Highlights, annotations, and reading progress.
- Multi-user authentication and private libraries.
- Background refresh and provider-health dashboards.
- Optional summaries and question answering over user-selected documents.
- Object storage for documents that are legally permitted to be retained.
- Personalized recommendations and research-gap exploration.

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

Connects an MCP-compatible host and asks it to find papers, retrieve details, or construct a reading list through narrowly scoped tools.

### Maintainer

Inspects provider errors, stale links, ingestion runs, cache effectiveness, and tool-call audit events.

## 4. Functional workstreams

### Discovery

- Query parsing and validation.
- Provider fan-out with deadlines, retries, and rate limits.
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
- Associate papers with topics and later semantic embeddings.
- Preserve user collections, notes, and reading state until the user deletes them.

### Reader and library

- Render supported PDFs with PDF.js.
- Open external landing pages when browser embedding is prohibited.
- Save, tag, annotate, and export papers.
- Show access and licence information beside every document action.

### MCP

- Publish typed, read-oriented research tools.
- Keep write tools separate and make confirmation expectations explicit.
- Return structured content with stable identifiers and provenance.
- Avoid passing full conversation history to integrations.

## 5. Delivery strategy

Build a vertical slice before adding provider breadth:

```text
topic -> local cache -> OpenAlex -> normalize -> PostgreSQL -> REST -> result UI
```

The second slice adds legal full-text resolution and reading. The third adds MCP. Semantic search, more providers, authentication, and AI-assisted analysis come afterward.

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
| Provider API changes or outages | Adapter interface, timeouts, circuit breakers, fixtures, partial results |
| Duplicate records and versions | Identifier-first reconciliation followed by conservative fuzzy matching |
| Stale or misleading PDF URLs | Store multiple locations, verify periodically, retain last-known status |
| Copyright or licence ambiguity | Store links by default; retain PDFs only when permission is explicit |
| Prompt injection inside documents | Treat documents as untrusted data and isolate them from tool instructions |
| Poor semantic ranking | Launch with explainable lexical/metadata ranking and evaluate vectors separately |
| Excessive project complexity | Modular monolith, three-provider MVP, deferred multi-user and LLM features |
| API rate limits | Local cache, bounded fan-out, backoff, request coalescing, provider budgets |

## 8. Portfolio definition of done

- Reproducible setup from a clean clone.
- Public architecture, security, and data-source documentation.
- Search/full-text demo covering success, partial failure, and restricted access.
- REST OpenAPI specification and documented MCP tools.
- Automated unit, integration, contract, end-to-end, and MCP conformance checks.
- Measured search latency, cache-hit ratio, provider error rate, and deduplication quality.
- Screenshots or a short demo video.
- No embedded secrets or unlicensed sample PDFs.
