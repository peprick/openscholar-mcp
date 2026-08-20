# Related-Paper Holdout Evaluation Protocol

## Purpose

This protocol freezes the first related-paper hybrid candidate before its independently authored holdout fixture is scored. It prevents the holdout judgments from becoming another tuning set.

The development fixture selected semantic weight `w = 0.50` for evaluation only. The live REST and MCP behavior remains PostgreSQL full-text ranking.

## Frozen scoring rule

For every non-seed candidate:

- `L` is the database `ts_rank_cd(..., 32)` score, or `0` when the lexical query does not retrieve the candidate;
- `V = clamp((cosine + 1) / 2, 0, 1)` uses the pinned exact-vector profile;
- `H = 0.50L + 0.50V` is the frozen holdout score.

The evaluator ranks with unrounded scores. Exact hybrid-score ties use the fixture key only. Relevance judgments cannot affect candidate selection, component scaling, scoring, or tie-breaking.

## First-run controls

The holdout database contains only its synthetic fixture. Each seed is evaluated against every other fixture paper so this phase measures reranking, not approximate candidate recall.

The same run reports:

1. lexical control (`w = 0`);
2. exact-vector control (`w = 1`);
3. the frozen hybrid (`w = 0.50`).

Each path records per-query and macro Recall@K, nDCG@K, Precision@1, and reciprocal rank. Structural checks require complete embedding population, exact pinned profile provenance, source exclusion, unique candidates, bounded finite scores, stable repeated reads, and endpoint equivalence.

## Predeclared advancement criteria

The frozen hybrid may advance to the later HNSW and product-readiness gates only if the first holdout run:

- has per-query Recall@K of at least `0.50`;
- preserves lexical Recall@K for every query;
- has per-query nDCG@K of at least `0.60`;
- preserves the lexical macro Recall@K;
- improves lexical macro nDCG@K by at least `0.03`;
- does not reduce lexical macro Precision@1;
- does not reduce lexical mean reciprocal rank;
- strictly improves nDCG for at least two query groups;
- regresses nDCG for at most one query group, by no more than `0.10`.

The exact-vector control is a comparison, not a target used to retune the weight. Passing these criteria does not activate hybrid ranking.

## No-retuning rule

After the first holdout run, do not change the weight, transform, fixture papers, judgments, or cutoffs to improve this result. A failure remains documented evidence. Further tuning requires a new development fixture and a separately authored, version-bumped holdout that remains unread until the next candidate is frozen.

The holdout is still a small synthetic corpus. It cannot establish production generalization, absence of embedding-model training overlap, ANN recall, latency, or ranking fairness.
