# Related-topic reuse scale protocol

Status: frozen diagnostic protocol v1. It does not enable or implement a product path.

## Purpose and boundary

This protocol measures the database and in-process mechanics of the test-only
owner-scoped related-topic reuse candidate at a larger deterministic scale. The
unchanged production `LOCAL` / `local-catalog-v1` result remains the control. The
candidate still lives under test sources and does not create a new search pipeline,
fingerprint, immutable snapshot contract, REST endpoint, MCP tool, UI surface, or
runtime setting.

The benchmark is not a relevance evaluation. Its synthetic records contain no
relevance judgments, and latency does not establish that the expansion improves a
reader's results. It is also not activation evidence, a capacity claim, or
target-deployment performance evidence. A separately authored blind holdout and a
qualified target-environment run with reviewed budgets remain required before any
product decision.

## Frozen inputs

The machine-readable policy is
`backend/src/test/resources/search/relevance/related-topic-reuse-scale-policy-v1.json`.
It binds the benchmark to:

- the exact frozen `related-topic-reuse-policy-v1` candidate;
- the digest-pinned PostgreSQL 17/pgvector Testcontainers image used by the backend
  integration suite;
- deterministic synthetic metadata generator
  `related-topic-reuse-scale-corpus-v1` with seed `20260829` and a digest over
  every set-based generator statement;
- four fixed workload definitions;
- two warm-up runs followed by 30 measured runs;
- one caller, a warm database cache, `System.nanoTime`, and nearest-rank
  percentiles; and
- schema-versioned diagnostic JSON written only to standard output.

The frozen corpus contains 100,000 canonical papers partitioned into disjoint
visibility cohorts:

| Cohort | Papers | Purpose |
|---|---:|---|
| Target owner, prior searches | 40,000 | Exercise snapshot-derived owner visibility |
| Target owner, collection only | 10,000 | Exercise collection-derived owner visibility |
| Other owner only | 25,000 | Detect cross-owner feedback leakage |
| Catalog only | 25,000 | Detect shared-catalog leakage |

All content is deterministic synthetic metadata. The benchmark fetches no provider
response, PDF, full text, supplementary file, or other external resource.

## Workloads

The four workloads exercise distinct mechanics without assigning relevance:

| Workload | Expected seeds | Purpose |
|---|---:|---|
| No-seed owner-scope control | 0 | Exact fallback and invisible exact-match decoys |
| One-seed sparse feedback | 1 | Selective source-title expansion |
| Two-seed broad feedback | 2 | Maximum frozen seed count and a broad lexical candidate set |
| Fully filtered selective feedback | 1 | Year, document type, open-access, citation, and language filters together |

For every workload, the benchmark obtains the control through the production LOCAL
path, derives only policy-eligible seeds, runs the test-only owner- and
filter-scoped feedback query, and applies the frozen weighted reciprocal-rank
fusion. Control, feedback, and fusion stages rotate during measurement to reduce a
fixed stage-order bias.

## Measurements and structural gates

After two warm-up runs, the benchmark performs 30 measured runs at concurrency one
with a warm database cache. It records p50, p95, and p99 using the nearest-rank
method for the LOCAL control, transaction-inclusive feedback stage, in-memory
fusion, and their explicitly labelled diagnostic composition. For the no-seed
control, the feedback stage measures a fresh read-only transaction that performs
no feedback SQL. The JSON report also records the bound policy,
runtime, corpus shape, workload shape, sample counts, and structural counters.
Every measured database stage uses a fresh PostgreSQL-enforced read-only
transaction, so an ORM persistence context is not reused across simulated
requests. Corpus seeding is committed atomically before `ANALYZE` and measurement.

Latency values are record-only: policy v1 defines no latency threshold and no
pass/fail performance verdict. Testcontainers timing varies with host allocation,
storage, background load, JVM state, and container caching. The composed timing is
not an observed product endpoint because the candidate has no production snapshot
or serialization path.

The enforced gates are structural:

- repeated control rankings remain identical;
- repeated feedback lists remain identical;
- repeated fused rankings remain identical;
- the no-seed workload preserves the control exactly at the frozen cutoff;
- every seeded workload produces feedback;
- owner-scope leaks and filter violations remain zero;
- discovery-provider calls remain zero; and
- the experimental candidate creates no search snapshot.

A structural failure fails the test. A slow run remains a recorded diagnostic and
must not be relabelled as a failed target SLO.

## Run the diagnostic

From `backend/`, run:

```bash
RUN_RELATED_TOPIC_REUSE_SCALE_EVALUATION=true \
  ./mvnw -Dtest=OwnerScopedRelatedTopicReuseScaleEvaluationTests test
```

The opt-in test seeds and analyzes its disposable Testcontainers database before
measurement. Ordinary test runs validate the strict policy, generator, metrics,
and gate contracts without creating the 100,000-paper corpus.

The runner emits one schema-versioned JSON report to standard output. It does not
write or retain a benchmark artifact. Redirecting console output is an operator
convenience, not a repository-defined evidence bundle, signature, trusted
timestamp, or immutable-retention workflow.

## Interpretation and next evidence

This run can expose mechanics problems such as visibility-set growth, broad lexical
matching, or filter cost. It cannot establish scholarly usefulness, realistic term
distributions, multi-client throughput, connection-pool behavior, a production
latency budget, or a safe rollout contract.

Advancement still requires all of the following:

1. a disjoint holdout authored independently of the frozen candidate and scored
   without further candidate tuning;
2. representative scale and concurrency selected for the intended deployment;
3. reviewed latency and resource budgets measured in that qualified target
   environment; and
4. a separately reviewed design for immutable ranking explanations, provenance,
   fallback, rollout, and rollback.
