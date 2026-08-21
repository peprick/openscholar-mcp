# HNSW Evaluation Protocol

Status: frozen evaluation policy v1. This protocol does not activate approximate
ranking in the search or related-paper orchestration paths.

## Purpose and boundary

The stage-1 baseline remains an exact pgvector cosine scan. Stage 2 adds a
profile-specific HNSW index and a separately named store method so recall and
latency can be measured without changing product results. A later decision must
explicitly approve any caller migration to approximate search.

The machine-readable policy is
`backend/src/test/resources/search/relevance/paper-embedding-hnsw-policy-v1.json`.
The policy, migration, runtime constants, Compose services, and Testcontainers
all bind the following immutable inputs:

- PostgreSQL/pgvector image:
  `pgvector/pgvector:pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f`
- Embedding profile:
  `paper-semantic-v1-ac6da0dfba84a81fdbfbaf330198c33cd77c4cdfc53e8bc50eb581914a15621d-ollama-0-31-1`
- Vector space: 1024 dimensions with cosine distance
- HNSW build parameters: `m=16`, `ef_construction=64`
- Query parameters: `hnsw.ef_search=1000`,
  `hnsw.iterative_scan=strict_order`, and
  `hnsw.max_scan_tuples=20000`
- ANN candidate pool: four times the requested result count, clamped to
  `[100, 400]`

The expression and partial predicate in migration V11 are deliberate. The base
column uses pgvector's variable-dimensional `vector` type because profiles can
have different dimensions. HNSW therefore indexes only the pinned profile after
casting that profile's values to `vector(1024)`. A different model, artifact,
runtime, dimension, or distance metric needs a new profile, policy, and index.

## Exact and approximate contracts

`findNearestExact` is the oracle. It applies `SET LOCAL enable_indexscan=off`,
scans the entire requested profile, sorts by exact cosine distance and then
paper UUID, and restores the caller's setting before returning.

`findNearestApproximate` rejects every profile except the pinned v1 profile. It
uses the HNSW index to retrieve an oversampled candidate set, then recomputes
exact cosine distance for that set and orders by exact distance and paper UUID.
The final rerank makes ordering deterministic for the candidates HNSW returns;
it does not turn an omitted candidate into an exact result.

Both methods exclude the source paper and return contiguous one-based ranks.
They are intentionally separate rather than controlled by an ambient flag.

## Automated checks

Normal test runs cover the policy/resource contract, V10 isolation, the V11
index definition and data preservation, pinned-profile enforcement, exact
ordering, ANN deterministic reranking, and an `EXPLAIN` assertion that the
approximate query can use the named HNSW index.

The recall and latency gate is opt-in because its result depends on allocated
hardware and background load:

```sh
cd backend
RUN_HNSW_EVALUATION=true ./mvnw -Dtest=PaperEmbeddingHnswEvaluationTests test
```

The test builds a deterministic 10,000-vector, 1024-dimensional dense
pseudo-random corpus and issues 20 fixed, evenly distributed queries at cutoff
25. This corpus tests ANN mechanics, not scholarly relevance,
production-vector geometry, or the quality of Qwen embeddings. Semantic
relevance continues to be evaluated by the separately frozen relevance fixtures.

For every query, the test compares ANN paper IDs with the exact oracle and
requires recall at 25 of at least 0.90. Macro recall must be at least 0.95. It
performs two warm-up runs and five measured runs and requires:

- approximate p95 latency no greater than 50 ms; and
- exact-to-approximate p95 speedup of at least 1.5 times.

## Reference environment and interpreting results

The latency gate is defined for Docker with 4 CPUs, 8 GiB memory, a warm
database cache, local storage, and no concurrent workload. Record the CPU,
memory, storage, container runtime, pgvector image digest, corpus size, warm-up
count, run count, recall, exact p95, approximate p95, and speedup with every
result.

A result from a materially different or contended machine is diagnostic only:
do not label the latency gate passed or failed. Re-run on the reference shape or
create and review a new versioned policy. A recall failure is still actionable
because inputs and query IDs are deterministic, but it must be reproduced with
the pinned image before tuning `ef_search`, candidate-pool bounds, or index build
parameters.

The pgvector behavior used here—expression/partial indexes for mixed dimensions,
`enable_indexscan=off` for exact recall comparison, iterative scans for filtered
ANN queries, and HNSW tuning—is documented by the
[pgvector project](https://github.com/pgvector/pgvector).
