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
| Product ranking | The endpoint remains the measured PostgreSQL full-text implementation; vector, HNSW, and hybrid ranking are not active |

`TITLE_ABSTRACT` input-policy v1 renders exact canonical metadata as:

```text
Title: <title>
Abstract: <abstract or empty>
```

Each field is stripped, CRLF/CR becomes LF, and text is normalized to Unicode NFC. The lowercase SHA-256 checksum covers the exact rendered UTF-8. Inputs above 24 KiB are rejected rather than truncated, and title/abstract updates delete the paper's stored embeddings. This makes source changes observable and prevents a silently truncated input from masquerading as the same embedding version.

The implemented runtime profile uses a full-digest-pinned [`Qwen3-Embedding-0.6B`](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B) artifact through exactly Ollama `0.31.1`, with native 1024-dimensional output and cosine distance. Its profile key and model revision include both the complete digest and runtime version. As verified on 2026-08-20, the explicit Ollama `qwen3-embedding:0.6b` tag identified a 639 MB Q8_0 artifact, while the bare/`latest` alias selected the 8B model and was not acceptable provenance. OpenScholar does not pull the model, accept a short digest, or contact Ollama unless the adapter and one bounded backfill invocation are explicitly enabled. Requests are restricted to a numeric loopback address, bypass system proxies, reject redirects, and cap responses; the operator must also confirm that `OLLAMA_NO_CLOUD=1` is active on the Ollama server. The Qwen technical report and model card show promising multilingual retrieval, but those published results alone do not establish the quality of the quantized Ollama artifact on this fixture. Local inference avoids a per-token bill while still consuming local compute, memory, download, and maintenance resources.

OpenAI [`text-embedding-3-large`](https://developers.openai.com/api/docs/models/text-embedding-3-large), explicitly shortened to 1024 dimensions, is reserved for opt-in evaluation. It is not a runtime default or automatic failover, and its vectors are a separate space even though the dimension matches. As checked on 2026-08-19, OpenAI lists USD 0.13 per million input tokens. API data is not used for training by default, but standard abuse-monitoring logs may retain content for up to 30 days unless an eligible account has modified or zero data retention. Hosted aliases also require canary/drift evidence before their output is treated as an immutable profile.

## Database-only retrieval rule

Embedding generation and refresh must occur outside `GET /api/v1/papers/{paperId}/related`. A future vector or hybrid implementation may read stored source/candidate vectors only. If the required profile or paper vector is absent or was invalidated by a metadata update, the endpoint returns the existing lexical ranking; it does not contact Ollama/OpenAI and does not fail solely because semantic data is unavailable.

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

## Remaining evaluation gates

Candidate work includes:

- compare `english`, `simple`, and language-aware lexical configurations;
- expand the relevance corpus and freeze a hybrid transform/weight before scoring held-out query groups;
- compare HNSW against exact neighbors with an explicit ANN-recall and latency gate;
- record feature-level reasons and persist them if hybrid results enter immutable search snapshots;
- bump the search pipeline/fingerprint version before blending local retrieval into provider-backed topic search.

A replacement hybrid should exceed the current macro nDCG of `0.857`, improve both adversarial query groups currently at `0.665`, and preserve the current recall result. If it does not, the lexical implementation remains the product ranker and the vector experiment remains documented evidence rather than an unmeasured feature.

Deduplication quality remains a separate evaluation concern because full-text retrieval operates on already-canonical `paper` rows. DOI duplicates and preprint/published pairs belong in a dedicated reconciliation fixture.
