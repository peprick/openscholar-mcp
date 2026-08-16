# Product Requirements

## Problem statement

Research discovery is fragmented across indexes, journals, preprint archives, and university repositories. The same work may appear in several forms, access status is often unclear, and repeated searches rebuild the same result set. Users need one place to discover relevant work, identify legal full-text versions, read and organize it, and reuse that knowledge through both a web application and AI agents.

## Product principles

1. **Legal access first:** never imply that indexed means downloadable.
2. **Evidence over fluency:** show provenance, identifiers, and source links.
3. **Local reuse:** persisted results should improve future searches.
4. **Explainable ranking:** expose why a result was included.
5. **Progressive enrichment:** return useful metadata before every provider finishes.
6. **Safe agent access:** tools are narrow, typed, auditable, and read-first.
7. **User control:** saving, downloading, summarizing, and deleting are explicit actions.

## MVP functional requirements

### Search

- Accept a free-text topic between 3 and 500 characters.
- Support publication-year range, document type, open-access-only, source, language, minimum-citation, and maximum-result filters.
- Return local results immediately when cache coverage and freshness meet policy.
- Query enabled providers when local coverage is insufficient.
- Return partial results with provider warnings rather than failing the entire request.
- Preserve the exact user query and normalized query fingerprint.

### Paper records

- Display title, abstract when available, authors, year, venue, type, citation count, identifiers, topics, and source provenance.
- Reconcile multiple provider records into one canonical paper.
- Preserve all known versions and provider-specific identifiers.
- Mark metadata as missing rather than fabricating it.

### Full-text availability

- Classify locations as `OPEN_PDF`, `OPEN_LANDING_PAGE`, `REPOSITORY_COPY`, `PREPRINT`, `ABSTRACT_ONLY`, `RESTRICTED`, `UNKNOWN`, or `UNAVAILABLE`.
- Prefer a verified open version without hiding the canonical publisher page.
- Show source, host type, licence if known, and last verification time.
- Never attempt to download a restricted version.

### Library

- Create, rename, and delete collections.
- Add and remove papers from collections.
- Store reading status: unread, reading, or completed.
- Export selected papers as BibTeX and CSL-JSON.
- Persist personal notes in a later MVP increment if schedule permits.

### Reader

- Render PDFs when cross-origin access and licence rules permit.
- Fall back to opening the source landing page when embedding is not possible.
- Show document provenance and access classification in the reader.
- Do not proxy arbitrary user-provided URLs.

### MCP

- Expose search, paper details, full-text availability, and reading-list tools.
- Return stable internal paper IDs plus DOI/arXiv identifiers when present.
- Return JSON-compatible structured results.
- Make collection mutations a separate, disabled-by-default capability.

## Non-functional requirements

### Performance targets

- Cached search p95: under 500 ms on the local stack.
- First uncached partial results: under 4 seconds when at least one provider is healthy.
- Full provider fan-out deadline: 10 seconds by default.
- Cached paper detail p95: under 300 ms.

### Reliability

- One provider failure must not discard successful results from other providers.
- Every outbound request has a timeout, bounded retry policy, and rate-limit budget.
- Failed jobs are retryable and visible to maintainers.
- Search persistence is idempotent.

### Accessibility and privacy

- Target WCAG 2.2 AA and keyboard-operable controls.
- The single-user MVP runs locally without analytics.
- Multi-user mode isolates libraries and notes by user ID.
- Search history deletion is supported before public deployment.
- Providers receive only query data required for the search.

## Acceptance scenarios

### Cached related search

Given papers stored for “multi-agent reinforcement learning in healthcare,” when the user searches “clinical multi-agent RL,” the system returns relevant local results and only contacts external providers if freshness or coverage is inadequate.

### Paywalled canonical version with open repository copy

Given a DOI whose publisher page is restricted and whose repository copy is legal and open, the paper page shows both locations and recommends the open copy.

### No open full text

Given a discoverable but restricted paper, the system displays metadata and the canonical landing page, marks it `RESTRICTED`, and performs no PDF download.

### Provider outage

Given an OpenAlex timeout while arXiv succeeds, the search returns arXiv results and a machine-readable provider warning.

### MCP search

Given a valid `search_research` tool call, the server returns structured results with identifiers, access status, ranking reasons, and source provenance.

## Deferred decisions

- Hosted authentication provider.
- Embedding provider and model.
- Commercial versus non-commercial deployment.
- Retained-PDF storage policy by licence family.
- Open-source licence for this repository.
