# Search Quality Baseline

## Scope

Milestone 5 starts with a deliberately small, reproducible PostgreSQL full-text baseline for related-paper retrieval. It does not change OpenAlex-backed topic search, its exact-cache fingerprint, or its immutable result snapshots.

The endpoint uses a canonical paper as the seed:

```http
GET /api/v1/papers/{paperId}/related?limit=10
```

The current algorithm:

1. Parse the seed title with PostgreSQL's explicit `english` text-search configuration.
2. Combine the resulting lexemes with OR semantics.
3. Match a generated vector containing title (weight A), abstract (B), and venue (C).
4. Score with normalized `ts_rank_cd(..., 32)`.
5. Break equal-score ties by metadata quality, citation count, publication year, and UUID.
6. Exclude the seed and report the `POSTGRES_FULL_TEXT` ranking reason.

## Versioned fixture

`backend/src/test/resources/search/relevance/related-metadata-baseline-v1.json` contains 18 synthetic canonical papers and five graded query groups. Relevance judgments use grades `0..3` so the same qrels can compare lexical, vector, and hybrid retrieval later.

The cases cover:

- clinical multi-agent reinforcement learning with a highly cited warehouse-domain negative;
- rare-disease graph learning, including a DOI-less thesis and a social-network negative;
- maternal-health causal inference with a forest-health common-title negative;
- Spanish clinical machine-learning metadata;
- a relevant title-only thesis and an abstract-only match.

The fixture contains no paper PDFs, copied abstracts, or copyrighted corpus text. Tests ingest it through `PaperCatalog`, rather than schema-coupled fixture SQL.

## Measured PostgreSQL baseline

Measured on PostgreSQL 17 through Testcontainers:

| Query group | Cutoff | Recall@K | nDCG@K | Precision@1 | Reciprocal rank |
|---|---:|---:|---:|---:|---:|
| Clinical multi-agent RL | 5 | 1.000 | 0.665 | 0.000 | 0.500 |
| Rare-disease graph learning | 5 | 1.000 | 0.956 | 1.000 | 1.000 |
| Maternal-health causal inference | 5 | 1.000 | 0.665 | 0.000 | 0.500 |
| Spanish clinical ML | 3 | 1.000 | 1.000 | 1.000 | 1.000 |
| Incomplete metadata | 3 | 1.000 | 1.000 | 1.000 | 1.000 |
| **Macro** | — | **1.000** | **0.857** | **0.600** | **0.800** |

The regression gate requires per-query Recall@K of at least `0.50`, per-query nDCG@K of at least `0.60`, macro Recall of at least `0.90`, macro nDCG of at least `0.80`, macro Precision@1 of at least `0.60`, and mean reciprocal rank of at least `0.80`. It also verifies positive finite scores, stable repeat ordering/scores, source exclusion, contiguous ranks, and an explicit ranking reason. Exact score decimals and a total cross-platform ordering are intentionally not contracts.

## What the baseline shows

Recall is strong on this small corpus, including Spanish and incomplete records. Early precision is visibly weaker for two adversarial cases: highly cited warehouse and forest papers share many title terms with the seed and win deterministic tie-breaks despite being in the wrong domain. That is a useful measured weakness, not something to hide by tuning the fixture.

The next retrieval version should improve those two nDCG values without reducing recall. Candidate work must be evaluated against this baseline before replacing it:

- compare `english`, `simple`, and language-aware lexical configurations;
- choose an embedding provider/model, dimension, input policy, and immutable embedding version;
- add pgvector storage and HNSW only after the version/refresh policy is fixed;
- calibrate lexical and cosine scores before hybrid fusion;
- record feature-level reasons and persist them if hybrid results enter immutable search snapshots;
- bump the search pipeline/fingerprint version before blending local retrieval into provider-backed topic search.

Deduplication quality remains a separate evaluation concern because full-text retrieval operates on already-canonical `paper` rows. DOI duplicates and preprint/published pairs belong in a dedicated reconciliation fixture.
