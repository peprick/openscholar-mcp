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

The next retrieval version should improve those two nDCG values without reducing recall. Candidate work must be evaluated against this baseline before replacing it.

## Embedding decision and implemented foundation

[ADR 0005](decisions/0005-versioned-embedding-profiles.md) selects a local-first model policy but separates that decision from what is currently executable:

| Layer | Current state |
|---|---|
| Profile and vector schema | Implemented in `V10`; immutable model/input provenance, dimensions, checksum, and cosine distance |
| Provider-neutral store | Implemented; prepare deterministic input, reject stale saves, idempotently store vectors, and run exact same-profile cosine lookup |
| Local inference | Implemented but disabled by default; direct Spring AI/Ollama adapter with exact runtime/tag/full-digest verification, digest/runtime-derived identity, fixed 1024 dimensions, and `truncate=false` |
| Offline population | Implemented; explicit non-web cursor-paged run, same-profile advisory lock, bounded retry/stale handling, fail-fast systemic errors, and no REST/MCP/scheduler trigger |
| Hosted comparison | Deferred; no OpenAI dependency, key, call, or production fallback is configured |
| Product ranking | The measured PostgreSQL full-text implementation remains the default; a production-readiness hybrid path is implemented behind an explicit default-off flag |

`TITLE_ABSTRACT` input-policy v1 renders exact canonical metadata as:

```text
Title: <title>
Abstract: <abstract or empty>
```

Each field is stripped, CRLF/CR becomes LF, and text is normalized to Unicode NFC. The lowercase SHA-256 checksum covers the exact rendered UTF-8. Inputs above 24 KiB are rejected rather than truncated, and title/abstract updates delete the paper's stored embeddings. This makes source changes observable and prevents a silently truncated input from masquerading as the same embedding version.

The implemented runtime profile uses a full-digest-pinned [`Qwen3-Embedding-0.6B`](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B) artifact through exactly Ollama `0.31.1`, with native 1024-dimensional output and cosine distance. Its profile key and model revision include both the complete digest and runtime version. As verified on 2026-08-20, the explicit Ollama `qwen3-embedding:0.6b` tag identified a 639 MB Q8_0 artifact, while the bare/`latest` alias selected the 8B model and was not acceptable provenance. OpenScholar does not pull the model, accept a short digest, or contact Ollama unless the adapter and one bounded backfill invocation are explicitly enabled. Requests are restricted to a numeric loopback address, bypass system proxies, reject redirects, and cap responses; the operator must also confirm that `OLLAMA_NO_CLOUD=1` is active on the Ollama server. The Qwen technical report and model card show promising multilingual retrieval, but those published results alone do not establish the quality of the quantized Ollama artifact on this fixture. Local inference avoids a per-token bill while still consuming local compute, memory, download, and maintenance resources.

OpenAI [`text-embedding-3-large`](https://developers.openai.com/api/docs/models/text-embedding-3-large), explicitly shortened to 1024 dimensions, is reserved for opt-in evaluation. It is not a runtime default or automatic failover, and its vectors are a separate space even though the dimension matches. As checked on 2026-08-19, OpenAI lists USD 0.13 per million input tokens. API data is not used for training by default, but standard abuse-monitoring logs may retain content for up to 30 days unless an eligible account has modified or zero data retention. Hosted aliases also require canary/drift evidence before their output is treated as an immutable profile.

## Database-only retrieval rule

Embedding generation and refresh must occur outside `GET /api/v1/papers/{paperId}/related`. The opt-in hybrid implementation reads stored source/candidate vectors only. If the required profile or source vector is absent, or a lexical candidate lacks its vector after metadata invalidation, the endpoint returns the existing lexical ranking; it does not contact Ollama/OpenAI and does not fail solely because semantic data is unavailable.

The selected Qwen model supports instructed query embeddings, but related-paper v1 will first measure symmetric stored document vectors. A separate precomputed query content kind is justified only if it materially improves the fixture enough to offset doubled generation/storage. On-demand query inference is not compatible with the database-only endpoint decision.

## Measured exact-vector baseline

Measured on 2026-08-20 with the same 18-paper synthetic fixture, PostgreSQL 17/pgvector exact cosine lookup, Ollama `0.31.1`, and `qwen3-embedding:0.6b` digest `ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d`. The resulting immutable profile is `paper-semantic-v1-ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d-ollama-0-31-1`.

| Query group | Cutoff | Recall@K | nDCG@K | Precision@1 | Reciprocal rank |
|---|---:|---:|---:|---:|---:|
| Clinical multi-agent RL | 5 | 1.000 | 0.834 | 1.000 | 1.000 |
| Rare-disease graph learning | 5 | 1.000 | 1.000 | 1.000 | 1.000 |
| Maternal-health causal inference | 5 | 1.000 | 1.000 | 1.000 | 1.000 |
| Spanish clinical ML | 3 | 1.000 | 1.000 | 1.000 | 1.000 |
| Incomplete metadata | 3 | 1.000 | 0.834 | 1.000 | 1.000 |
| **Macro exact vector** | — | **1.000** | **0.934** | **1.000** | **1.000** |
| **Macro lexical baseline** | — | **1.000** | **0.857** | **0.600** | **0.800** |

On this fixture, exact vector-only retrieval preserves macro recall, raises macro nDCG by `0.077`, and raises both Precision@1 and MRR. It improves the two lexical adversarial groups, but lowers incomplete-metadata nDCG from `1.000` to `0.834`; this is evidence for a measured hybrid, not permission to replace the live lexical endpoint.

The opt-in regression gate requires per-query vector Recall@K of at least `0.90`, per-query vector nDCG@K of at least `0.80`, macro Recall of at least `0.95`, macro nDCG of at least `0.90`, macro Precision@1 of at least `0.80`, and MRR of at least `0.90`. It also requires a complete 18-row backfill and stable repeated exact-neighbor results. These numbers describe one small synthetic corpus and one pinned local artifact; they are not a claim about production relevance, approximate-index recall, latency, or a broader scholarly corpus.

## Exploratory hybrid sensitivity sweep

The same opt-in run performs a label-independent score interpolation over all 17 non-seed fixture papers. It uses the PostgreSQL score already returned by `ts_rank_cd(..., 32)` as `L` (or `0` for no lexical match), maps cosine to `V = clamp((cosine + 1) / 2, 0, 1)`, and computes `H = (1 - w)L + wV`. The five weights were fixed before the corrected run; relevance judgments affect metrics only, never candidate selection, scaling, scoring, or tie-breaking.

| Semantic weight `w` | Clinical nDCG | Rare-disease nDCG | Maternal nDCG | Spanish nDCG | Incomplete nDCG | Macro Recall | Macro nDCG | Precision@1 | MRR |
|---:|---:|---:|---:|---:|---:|---:|---:|---:|---:|
| 0.00 (lexical control) | 0.665 | 0.956 | 0.665 | 1.000 | 1.000 | 1.000 | 0.857 | 0.600 | 0.800 |
| 0.25 | 0.665 | 1.000 | 0.956 | 1.000 | 1.000 | 1.000 | 0.924 | 0.800 | 0.900 |
| 0.50 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 | 1.000 |
| 0.75 | 0.834 | 1.000 | 1.000 | 1.000 | 0.834 | 1.000 | 0.934 | 1.000 | 1.000 |
| 1.00 (vector control) | 0.834 | 1.000 | 1.000 | 1.000 | 0.834 | 1.000 | 0.934 | 1.000 | 1.000 |

Weight `0.50` has the highest observed in-sample metrics on these five synthetic query groups. It is not an optimal, validated, or selected product weight. The same fixture has already informed lexical and vector decisions, there is no held-out query group, unjudged papers are treated as grade zero in this closed corpus, and model-training overlap cannot be established. No hybrid quality floor or runtime default is created. A production candidate needs a larger independently authored fixture, whole query groups held out, and a weight frozen before the holdout is scored.

## Frozen independent holdout result

The [`related-hybrid-policy-v1`](HOLDOUT_EVALUATION_PROTOCOL.md) transform, semantic weight `w = 0.50`, candidate rule, tie-break, and acceptance gates were frozen before scoring the independently authored `related-metadata-holdout-v1` fixture. The holdout contains 26 synthetic papers and seven disjoint query groups; each query is reranked over all 25 non-seed candidates. The run used the same exact pinned Ollama/Qwen profile described above. The compact cells below report `Recall@5 / nDCG@5 / Precision@1 / reciprocal rank`.

| Holdout query | Lexical control | Exact-vector control | Frozen hybrid |
|---|---:|---:|---:|
| Reef recovery soundscapes | 1.000 / 0.983 / 1.000 / 1.000 | 1.000 / 0.710 / 1.000 / 1.000 | 1.000 / 1.000 / 1.000 / 1.000 |
| Photonic bosonic correction | 1.000 / 0.665 / 0.000 / 0.500 | 1.000 / 1.000 / 1.000 / 1.000 | 1.000 / 0.956 / 1.000 / 1.000 |
| French urban heat | 1.000 / 0.631 / 0.000 / 0.500 | 1.000 / 1.000 / 1.000 / 1.000 | 1.000 / 1.000 / 1.000 / 1.000 |
| Sodium-metal interphases | 1.000 / 0.983 / 1.000 / 1.000 | 1.000 / 0.710 / 1.000 / 1.000 | 1.000 / 0.983 / 1.000 / 1.000 |
| Japanese ukiyo-e pigments | 0.000 / 0.000 / 0.000 / 0.000 | 1.000 / 0.834 / 1.000 / 1.000 | 1.000 / 0.834 / 1.000 / 1.000 |
| Temperate exoplanet clouds | 1.000 / 0.631 / 0.000 / 0.500 | 1.000 / 1.000 / 1.000 / 1.000 | 1.000 / 1.000 / 1.000 / 1.000 |
| Software build provenance | 1.000 / 0.644 / 0.000 / 0.500 | 1.000 / 1.000 / 1.000 / 1.000 | 1.000 / 0.644 / 0.000 / 0.500 |
| **Macro** | **0.857 / 0.648 / 0.286 / 0.571** | **1.000 / 0.893 / 1.000 / 1.000** | **1.000 / 0.917 / 0.857 / 0.929** |
| **Hybrid minus lexical** | — | — | **+0.143 / +0.269 / +0.571 / +0.357** |

The reported cutoff rankings, using fixture keys, were:

| Holdout query | Lexical ranking | Exact-vector ranking | Frozen-hybrid ranking |
|---|---|---|---|
| Reef recovery soundscapes | `reef-larval`, `bridge-acoustic-negative`, `reef-title-only`, `exoplanet-source` | `reef-title-only`, `reef-larval`, `bridge-acoustic-negative`, `urban-biodiversity-negative`, `sodium-abstract-only` | `reef-larval`, `reef-title-only`, `bridge-acoustic-negative`, `exoplanet-source`, `urban-biodiversity-negative` |
| Photonic bosonic correction | `fiber-code-negative`, `photonic-loss`, `photonic-thesis` | `photonic-loss`, `photonic-thesis`, `fiber-code-negative`, `reef-title-only`, `bridge-acoustic-negative` | `photonic-loss`, `fiber-code-negative`, `photonic-thesis`, `reef-title-only`, `bridge-acoustic-negative` |
| French urban heat | `urban-biodiversity-negative`, `urban-heat-shade`, `earth-cloud-negative` | `urban-heat-shade`, `urban-biodiversity-negative`, `earth-cloud-negative`, `exoplanet-reflected`, `exoplanet-source` | `urban-heat-shade`, `urban-biodiversity-negative`, `earth-cloud-negative`, `exoplanet-reflected`, `exoplanet-source` |
| Sodium-metal interphases | `sodium-cryo`, `sodium-catalyst-negative`, `sodium-abstract-only` | `sodium-abstract-only`, `sodium-cryo`, `sodium-catalyst-negative`, `ukiyoe-portable`, `ukiyoe-source` | `sodium-cryo`, `sodium-catalyst-negative`, `sodium-abstract-only`, `ukiyoe-portable`, `ukiyoe-source` |
| Japanese ukiyo-e pigments | no lexical matches | `ukiyoe-portable`, `ukiyoe-hyperspectral`, `ukiyoe-recommendation-negative`, `sodium-source`, `provenance-german` | `ukiyoe-portable`, `ukiyoe-hyperspectral`, `ukiyoe-recommendation-negative`, `sodium-source`, `provenance-german` |
| Temperate exoplanet clouds | `earth-cloud-negative`, `exoplanet-reflected` | `exoplanet-reflected`, `earth-cloud-negative`, `urban-heat-shade`, `ukiyoe-hyperspectral`, `urban-biodiversity-negative` | `exoplanet-reflected`, `earth-cloud-negative`, `urban-heat-shade`, `ukiyoe-hyperspectral`, `urban-biodiversity-negative` |
| Software build provenance | `food-attestation-negative`, `provenance-graphs`, `provenance-german` | `provenance-graphs`, `provenance-german`, `food-attestation-negative`, `ukiyoe-hyperspectral`, `ukiyoe-source` | `food-attestation-negative`, `provenance-graphs`, `provenance-german`, `ukiyoe-hyperspectral`, `ukiyoe-source` |

The frozen hybrid passed every predeclared gate: it preserved or improved recall for every query, improved macro nDCG by `0.269` against the required `0.030`, strictly improved nDCG for five query groups, and produced zero nDCG regressions. It also raised macro Precision@1 by `0.571` and MRR by `0.357`.

The first invocation stopped before complete metrics were available because the valid zero-match Japanese lexical control triggered an overstrict harness assertion. Commit `288bf4f` corrected that assertion label-independently so an empty lexical control is measured as zero while the vector and frozen-hybrid paths still require complete candidate pools. The policy, scoring rule, fixture papers, judgments, cutoffs, and acceptance gates did not change before the successful run.

Passing this small synthetic holdout allowed the frozen candidate to advance to the separately pinned HNSW and production-readiness gates; it did not by itself activate hybrid ranking.

## HNSW gate and default-off hybrid mode

`V11` adds an expression/partial HNSW index only for the pinned 1024-dimensional Qwen/Ollama profile. Its frozen policy uses `m=16`, `ef_construction=64`, `hnsw.ef_search=1000`, strict iterative scans, at most 20,000 scanned tuples, and a four-times candidate oversampling rule clamped to `100..400`. Approximate candidates are reranked by exact cosine distance and paper UUID. The exact oracle remains a separately named store operation with index scans disabled.

The opt-in 10,000-vector mechanics run passed its frozen gate on the reference-shaped environment: macro Recall@25 was `1.0000` across 20 queries, exact p95 was `47.491 ms`, approximate p95 was `20.082 ms`, and the exact-to-approximate speedup was `2.365x`. These synthetic dense vectors validate ANN mechanics and latency, not scholarly relevance; relevance remains grounded in the independent holdout above.

The production-readiness implementation remains off by default. With `RELATED_PAPERS_HYBRID_ENABLED=false`, result IDs, order, scores, and lexical feature values are unchanged. When explicitly enabled, it:

1. reads a bounded lexical pool and a bounded pinned-profile HNSW pool;
2. obtains exact cosine values for every lexical candidate and falls back if even one is unavailable;
3. reranks the bounded union with the frozen formula `H = 0.50L + 0.50V`, where `L` is the normalized PostgreSQL score and `V = clamp((cosine + 1) / 2, 0, 1)`;
4. breaks final ties by canonical UUID text; and
5. reports typed `POSTGRES_FULL_TEXT` and `CLAMPED_COSINE` values for hybrid results.

The pool setting is validated in `25..100` and defaults to 100. A missing profile, missing source vector, or incomplete lexical-candidate vector coverage returns the same lexical result slice with an explicit typed fallback reason. Only those expected availability exceptions are translated; JDBC and other operational failures propagate. The request path reads precomputed data only and never invokes Ollama or a hosted embedding provider.

## Remaining evaluation gates

Candidate work includes:

- compare `english`, `simple`, and language-aware lexical configurations;
- evaluate the default-off mode on a larger and more representative relevance set before considering a default change;
- persist feature-level reasons if hybrid results enter immutable search snapshots;
- bump the search pipeline/fingerprint version before blending local retrieval into provider-backed topic search.

The frozen hybrid has cleared the first independent holdout plus the pinned HNSW mechanics gate and now has deterministic database-only fallback behavior and honest feature values. Lexical remains the default product ranker until an explicit later review changes the flag default.

## Frozen exact-identifier deduplication baseline

`paper-deduplication-baseline-v1.json` is a 12-record synthetic fixture evaluated against the unchanged catalog persistence path with the declared `exact-identifiers-and-provider-records-only` policy. It covers DOI normalization, arXiv URL/prefix/version normalization, OpenAlex URL normalization, provider-record replay, a DOI-less thesis, common-title false positives, and an intentionally separate preprint/published pair.

Across all 66 record pairs, the reference run produced `tp=5`, `fp=0`, `fn=0`, and `tn=61`: pairwise precision `1.000`, recall `1.000`, and F1 `1.000`. The integration test requires all three scores to remain exactly `1.000`, explicitly requires the common-title records to remain separate, and explicitly requires the preprint/published records to remain separate.

This small frozen fixture demonstrates the declared conservative identity policy; it does not estimate performance on the full scholarly graph or justify fuzzy title/author merges. Any future reconciliation heuristic needs an expanded independently reviewed fixture and a separately frozen gate before activation.
