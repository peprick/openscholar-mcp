# Roadmap

OpenScholar's local web, REST, PostgreSQL, legal-access, library, citation, and five-tool MCP flows are implemented. This roadmap lists future outcomes only; completed milestones are represented by the code, tests, architecture decisions, and quality evidence in this repository.

## 1. Prove a hosted release

- Build, scan, sign, attest, publish, and digest-pin the backend, frontend, Caddy, and blackbox-exporter images.
- Register and test a real OIDC provider, including key rotation, logout, audience, scope, and recovery behavior.
- Select a production PostgreSQL, backup, point-in-time recovery, and high-availability strategy.
- Configure off-host backups, alert routing, incident ownership, and restore drills.
- Validate the target environment with load, accessibility/assistive-technology, penetration, and disaster-recovery exercises.
- Complete provider, privacy, and legal review for the intended audience and jurisdiction.

The checked-in deployment stack is a hardened template, not evidence of a public production deployment.

## 2. Broaden research coverage carefully

- Evaluate PubMed Central and audience-relevant institutional repositories through supported APIs.
- Add providers only after documenting terms, attribution, quotas, failure isolation, and allowed metadata/document handling.
- Improve exact-identifier lookup so DOI, arXiv, and OpenAlex identifiers can resolve directly to canonical papers.
- Measure provider-specific usefulness before enabling additional sources by default.

OpenScholar will not add publisher scraping, access-control bypasses, CAPTCHA workarounds, or indiscriminate PDF collection.

## 3. Improve retrieval quality

- Expand the independently labelled relevance and deduplication corpus beyond the current synthetic/reference-shaped sets.
- Evaluate multilingual lexical configuration and related-topic reuse.
- Revisit the default-off hybrid pgvector path only after representative evaluation shows stable gains.
- Add performance budgets for the target deployment rather than extrapolating from loopback measurements.

## 4. Strengthen the agent experience

- Improve structured MCP errors without exposing provider payloads or internal infrastructure.
- Propagate client cancellation when the Java MCP transport provides a reliable supported path.
- Consider MCP Tasks or owned job handles only for workflows that become genuinely long-running.
- Keep collection mutations disabled until authorization and explicit host/user confirmation behavior are proven across supported clients.

## Later product work

- Notes and highlights with explicit ownership and deletion semantics.
- Evidence-linked summaries and paper comparison with prompt-injection and citation safeguards.
- Citation graphs, research maps, and collaborative collections.
- A read-only installable web shell for explicitly selected metadata, after server-backed local search is stable; browser cache is not an authoritative store.
- Connectivity-aware refresh deferral with reliable outage classification, capped backoff, and result linking; retryable provider errors are not automatically treated as an offline signal.
- Additional offline embedding profiles for legally retained private content.
- Mobile-reader and assistive-technology refinements.

## Release policy

The first tagged release should include a clean-clone verification, current screenshots, green CI/security workflows, an explicit licence decision, and honest known limitations. Public-production claims require deployment-specific evidence; passing local or synthetic tests is not a substitute.
