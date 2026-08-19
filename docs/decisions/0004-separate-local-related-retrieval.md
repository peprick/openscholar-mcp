# ADR 0004: Keep local related-paper retrieval separate from search snapshots

- Status: accepted
- Date: 2026-08-19

## Context

OpenScholar's provider-backed search cache is an immutable snapshot. Its fingerprint identifies the normalized query, filters, page, and `openalex-v1` pipeline, while every result retains provider-specific ranking and provenance. Silently mixing live catalog matches into that response would make an exact cache hit depend on mutable `paper` rows and would leave local matches without honest provider provenance.

Milestone 5 also needs a measurable PostgreSQL lexical baseline before choosing embeddings or a hybrid ranker.

## Decision

Implement local lexical retrieval as a separate, database-only related-paper use case and REST endpoint:

```http
GET /api/v1/papers/{paperId}/related?limit=10
```

The baseline uses a stored generated `tsvector` over canonical title, abstract, and venue fields, weighted A, B, and C respectively. PostgreSQL's explicit `english` text-search configuration supplies stemming and stop-word handling, and a GIN index supports matching. The source title is converted to normalized lexemes and combined with OR semantics; the source paper is excluded. `ts_rank_cd` supplies the lexical score, followed by deterministic metadata-quality, citation-count, publication-year, and UUID tie-breakers.

The result reports the `POSTGRES_FULL_TEXT` ranking reason. It has no provider coverage, search ID, cache disposition, or fabricated local provider identity. Query construction preserves source-title lexeme order and caps the seed at 16 distinct lexemes so unusually long titles cannot create unbounded query trees.

Do not change `SearchOrchestrator`, `QueryFingerprinter`, stored search snapshots, or the MCP `search_research` tool for this baseline.

## Consequences

- Exact provider-search replay remains immutable and backwards compatible.
- Related results reflect the current canonical catalog and require no external call.
- A versioned synthetic relevance corpus can measure the lexical baseline before vector work.
- English stemming is an explicit first-baseline limitation; `simple`, per-language configurations, and multilingual vector retrieval must be compared using the evaluation set.
- Author text is not indexed because it lives across tables; adding it requires a maintained search-document projection rather than this generated column.
- Adding the generated vector and building its GIN index takes an `ALTER TABLE` lock. The MVP catalog is small; a mature deployment must schedule a maintenance window or replace V9 with a staged backfill and concurrent-index rollout before applying the equivalent change at scale.
- The result limit bounds response size but not the number of catalog matches PostgreSQL may score. Scale testing, a database statement budget, and endpoint-level request controls remain required before high-traffic deployment.
- A future hybrid provider/local search must use a new pipeline/fingerprint version and persist ranking plus provenance in a new immutable snapshot contract.
