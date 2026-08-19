# ADR 0005: Use versioned embedding profiles with a local-first default

- Status: accepted
- Date: 2026-08-19

## Context

The PostgreSQL full-text baseline exposes two useful ranking failures in the
versioned related-paper fixture. Semantic retrieval is a candidate improvement,
but an embedding is meaningful only inside the exact vector space that produced
it. Provider, model artifact, input rendering, output dimensions, and distance
metric therefore need to be fixed before vectors are generated or an approximate
index is built.

The related-paper endpoint is also deliberately database-only under
[ADR 0004](0004-separate-local-related-retrieval.md). Adding semantic ranking
must not turn an interactive read into an Ollama or hosted-API call.

## Decision

### Establish provider-neutral persistence first

Migration `V10` creates an immutable `embedding_profile` registry and a
`paper_embedding` store without selecting or contacting an inference provider.
Each profile fixes:

- provider, model, and an immutable model revision;
- content kind and input-policy version;
- dimensions, currently bounded to `1..2000` for the planned pgvector HNSW path;
- cosine distance.

The database rejects mutable revision labels such as `latest`, prevents profile
updates/deletes, verifies each vector's dimensions and non-zero norm, and allows
one current vector per paper/profile. Title or abstract changes delete the
affected paper's stored embeddings. The application store also locks and
re-renders the paper before an upsert so a vector produced from stale content
cannot be committed.

`PaperEmbeddingStore` is the current provider-neutral boundary for profile
registration, deterministic source preparation, checksum-guarded storage, and
exact same-profile cosine lookup. `V10` intentionally creates no profile rows,
vectors, backfill, HNSW index, provider adapter, or public retrieval integration.

### Fix title/abstract input policy v1

`TITLE_ABSTRACT` input-policy version 1 renders exactly:

```text
Title: <canonical title>
Abstract: <canonical abstract or empty>
```

Each field is stripped, CRLF and CR are converted to LF, and text is normalized
to Unicode NFC. The SHA-256 checksum covers the exact UTF-8 bytes of the rendered
input. Inputs larger than 24 KiB are rejected rather than silently truncated;
changing that bound or truncation behavior requires a new input-policy version.
The byte bound is an application safety limit, not a promise that every hosted
provider accepts every rendered input under its own token limit.

### Choose a local-first v1 model

The first inference adapter and backfill should use the Apache-2.0
[`Qwen3-Embedding-0.6B`](https://huggingface.co/Qwen/Qwen3-Embedding-0.6B)
model locally through Ollama. It provides native 1024-dimensional output, a 32K
context window, and multilingual coverage suitable for the current English and
Spanish fixture. The Ollama deployment must select the explicit
`qwen3-embedding:0.6b` tag, verify and record its full artifact digest, disable
silent truncation, and run with cloud features disabled. It must not use the bare
or `latest` tag: the current Ollama library maps that alias to the materially
larger 8B model. The published model benchmarks do not establish the quality of
the quantized Ollama artifact; OpenScholar's fixture remains the acceptance gate.

Ollama reports a 639 MB Q8_0 artifact for the explicit 0.6B tag and states that
locally processed prompts are not sent to Ollama. Local inference has no
per-token fee, but consumes developer or deployment CPU/GPU, memory, and
operational time. These are deployment tradeoffs, not “free” compute.

### Keep a hosted model evaluation-only

OpenAI `text-embedding-3-large`, explicitly shortened to 1024 dimensions, is the
opt-in hosted comparison. It is not the default runtime and must not be an
automatic failover for the local profile. OpenAI currently lists a price of
USD 0.13 per million input tokens. API inputs are not used for training by
default, but standard abuse-monitoring logs may retain customer content for up
to 30 days unless an eligible organization is approved for modified or zero data
retention.

Hosted aliases must not be assumed to identify a permanently stable vector
space. An evaluation profile must record the strongest immutable revision or
response provenance the provider exposes, run fixed public canaries, and create a
new profile plus backfill if drift is detected. Vectors from local and hosted
profiles are never compared or mixed merely because both contain 1024 values.

### Preserve the database-only read path

Embedding generation and refresh happen outside the related-paper request. When
vector or hybrid retrieval is implemented, it may read only stored vectors for
the source and candidates. A missing or invalidated embedding yields the existing
lexical result rather than an inference call or endpoint failure. An instructed
query vector may be considered only as a separately versioned, precomputed
content kind if the evaluation fixture shows enough benefit to justify its extra
storage and generation work.

## Consequences

- The schema and store can be tested deterministically without downloading a
  model, holding an API key, or making CI network-dependent.
- A model, digest, dimension, distance, or input-policy change creates a new
  profile and requires a measured backfill; it cannot silently rewrite an old
  vector space.
- Exact cosine lookup is available as a persistence primitive, but it is not yet
  a product ranking claim.
- Ollama/OpenAI Spring AI adapters, digest verification, backfill scheduling,
  HNSW, vector/hybrid evaluation, ranking explanations, and REST/MCP integration
  remain separate follow-up work.
- Full-paper, PDF-chunk, note, and private user-content embeddings require new
  content kinds plus an explicit retention/privacy review.

## Primary references

- [Spring AI 2.0 Embedding Model API](https://docs.spring.io/spring-ai/reference/api/embeddings.html)
- [Spring AI Ollama embeddings](https://docs.spring.io/spring-ai/reference/api/embeddings/ollama-embeddings.html)
- [Qwen3 Embedding technical report, 2025-06-05](https://arxiv.org/abs/2506.05176)
- [Ollama Qwen3 embedding tags](https://ollama.com/library/qwen3-embedding/tags)
- [Ollama local/cloud privacy controls](https://docs.ollama.com/faq)
- [OpenAI `text-embedding-3-large`](https://developers.openai.com/api/docs/models/text-embedding-3-large)
- [OpenAI API data controls](https://platform.openai.com/docs/models/default-usage-policies-by-endpoint)
- [pgvector HNSW and dimension limits](https://github.com/pgvector/pgvector)
