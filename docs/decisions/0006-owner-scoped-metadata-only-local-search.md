# ADR 0006: Add owner-scoped metadata-only local search

- Status: accepted
- Date: 2026-08-24

## Context

OpenScholar already retains canonical scholarly metadata, immutable owner-scoped
search snapshots, and saved collections in PostgreSQL. Those records remain useful
when discovery providers are unreachable, but the provider-backed topic-search path
previously returned only an exact stale snapshot or an availability error. A user
could not submit a different topic and search metadata already available to their
account.

Offline retrieval must not imply that a provider was contacted, that metadata was
refreshed, or that a provider-reported external link is currently reachable. Hosted
mode must also avoid revealing another principal's research activity through the
shared canonical-paper table. This milestone does not retain, proxy, download, or
index PDF bytes.

## Decision

### Expose one search contract with three modes

REST and the existing MCP `search_research` tool accept an optional mode:

- `AUTO` is the default. It uses provider-backed cache/fetch behavior and may fall
  back to owner-scoped local-catalog retrieval when provider-backed data cannot
  satisfy the request.
- `ONLINE` uses provider-backed cache/fetch behavior and never falls back to the
  local catalog. `forceRefresh=true` remains the explicit instruction to bypass an
  exact fresh provider cache entry.
- `LOCAL` is database-only and is incompatible with `forceRefresh=true`.

The response retains `cacheDisposition` and adds both `requestedMode` and an actual
`executionSource`: `PROVIDER_FETCH`, `EXACT_CACHE`, `STALE_CACHE`, or
`LOCAL_CATALOG`. A newly generated local snapshot uses `LOCAL_RESULT`. This keeps
caller intent separate from what actually satisfied an `AUTO` request.

### Preserve immutable provenance

Migration `V16` stores `requested_mode` and an internal `result_origin` of
`PROVIDER` or `LOCAL_CATALOG` on every immutable search snapshot. Existing rows are
backfilled as `AUTO`/`PROVIDER`. The requested mode participates in the new v2
fingerprint, so `AUTO` and `ONLINE` cannot alias one immutable snapshot resource
while reporting different caller intent. Legacy v1 snapshots remain readable and
can be refreshed explicitly, but automatic stale scheduling selects only the
current fingerprint version to avoid repeatedly targeting an obsolete cache key.
Local snapshots also use the separately versioned `local-catalog-v1` pipeline
rather than pretending to be entries in the enabled-provider cache.

Local results preserve deterministic stored provider provenance and its retrieval
time. `providerCoverage` is empty because no provider participated in the local
execution. The system does not create a synthetic `LOCAL` provider identity.
Provider-reported open-access evidence remains a discovery hint with its original
freshness; local search does not perform legal-access verification.

An opaque local cursor is distinct from the combined provider cursor. It is bound
to the normalized query/filter/mode scope and carries a bounded remainder of the
first page's ranked paper IDs. Later pages preserve that ordering while rechecking
owner eligibility, so newly discovered or reranked catalog rows cannot introduce
duplicates or skips. Continuation of a local snapshot remains local even when the
original request mode was `AUTO`, so reconnecting cannot send a local cursor to an
external provider.

### Enforce owner visibility before ranking

Local candidates are limited to canonical papers that the current owner previously
received in one of their search snapshots or saved in one of their collections.
The fixed local-development principal follows the same ownership rule. Ranking and
pagination are deterministic, and the database remains the authoritative store.
Browser service-worker caching, offline mutations, synchronization, PDF storage,
OCR, and document-content search are separate decisions.

### Keep refresh deferral out of this slice

An AUTO local fallback does not itself create an unbounded durable refresh job.
The existing refresh worker retains its bounded attempt and backoff policy. A true
"wait until connected without consuming an attempt" state needs reliable outage
classification, capped/jittered scheduling, operator visibility, and result-linking;
it is not inferred from every retryable provider error.

## Consequences

- Existing callers that omit mode receive AUTO behavior.
- Users and agents can search previously visible metadata without a provider call.
- Every response states whether it came from providers, an exact/stale cache, or
  the local catalog; local retrieval is never presented as fresh provider evidence.
- Provider and local fingerprints/snapshots cannot shadow one another.
- Hosted local discovery remains owner-scoped even though canonical bibliographic
  rows are shared.
- MCP tool annotations remain conservative for the whole tool:
  `readOnlyHint=false`, `idempotentHint=false`, and `openWorldHint=true`. MCP does
  not support per-invocation annotations for LOCAL mode.
- ADR 0004 still governs the separate related-paper endpoint. Its instruction not
  to change topic search applied to that baseline milestone and is superseded only
  for the owner-scoped topic-search behavior defined here.

## Validation

The implementation must prove that LOCAL makes no provider call, AUTO falls back
only under its documented conditions, ONLINE never returns a local result, local
and provider continuations cannot be confused, local result ordering is stable,
cross-owner candidates are excluded, stored reads retain execution provenance, and
REST/MCP schemas expose the same mode/source vocabulary.
