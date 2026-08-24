# ADR 0008: Resolve exact identifiers only inside the owner's visible catalog

- Status: accepted
- Date: 2026-08-24

## Context

Researchers and agents often start with a DOI, arXiv identifier, or OpenAlex work
URL rather than an OpenScholar UUID. The canonical catalog already stores normalized
external identifiers, but only ingestion used that index. Callers therefore had to
repeat a topic search and inspect results before opening a known paper.

Canonical bibliographic rows are shared, while search snapshots and collections are
owner-scoped. A global exact lookup would let a hosted caller probe whether another
person had introduced a paper into the shared catalog. Provider-backed resolution
would also change a cheap local shortcut into an open-world discovery operation.

## Decision

OpenScholar exposes a database-only exact-identifier resolver through REST and MCP.
It accepts bounded DOI, arXiv, and OpenAlex work references, parses their canonical
forms, and uses the same normalization implementation as catalog ingestion.

A match is returned only when the paper is already visible through at least one
search snapshot or collection owned by the current principal. The fixed local user
follows the same rule. A valid missing reference and a reference attached only to
another owner's paper return the same `PAPER_IDENTIFIER_NOT_FOUND` result. Invalid
or unsupported syntax is rejected separately without echoing unsafe input.

The resolver never contacts a discovery or access provider, imports metadata,
verifies legal access, or retrieves document bytes. Hosted REST requires the search
scope; MCP uses the existing MCP authentication and owner context. The web flow
uses a same-origin BFF route and redirects a successful result to the existing
canonical paper page.

## Consequences

- A known visible paper can be opened directly from common scholarly references.
- Agents no longer need to manufacture a topic query when they already have an
  identifier.
- The shared canonical table is not exposed as a cross-owner enumeration oracle.
- Lookup results state the matched type and normalized value but do not imply that
  the source or its access links are current.
- Resolving an identifier that has never been discovered remains a later,
  explicitly provider-backed workflow.
- No schema migration or PDF-storage path is introduced.

## Validation

Tests must cover canonical and prefixed/URL forms, malformed and unsupported input,
arXiv version normalization, owner-visible search and collection matches,
cross-owner indistinguishability, hosted scope enforcement, and REST/MCP schema
contracts. Provider mocks must prove that lookup performs no external call.
