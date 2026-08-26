# Roadmap

OpenScholar's local web, REST, PostgreSQL, legal-access, library, citation, encrypted single-collection offline-pack, and six-tool MCP flows are implemented. This roadmap lists future outcomes only; completed milestones are represented by the code, tests, architecture decisions, and quality evidence in this repository.

## 1. Prove a hosted release

- Build, scan, sign, attest, publish, and digest-pin the backend, frontend, Caddy, and blackbox-exporter images.
- Register and test a real OIDC provider, including key rotation, logout, audience, scope, and recovery behavior.
- Select a production PostgreSQL, backup, point-in-time recovery, and high-availability strategy.
- Configure off-host backups, alert routing, and incident ownership; complete and record a real backup/restore drill in an isolated target-like environment.
- Validate the target environment with load, accessibility/assistive-technology, penetration, and disaster-recovery exercises.
- Complete provider, privacy, and legal review for the intended audience and jurisdiction.

The checked-in deployment stack is a hardened template, not evidence of a public production deployment.

## 2. Broaden research coverage carefully

- Measure the default-off Europe PMC metadata adapter's incremental coverage, deduplication, latency, failure rate, and result quality before considering any default enablement.
- Evaluate audience-relevant institutional repositories through supported APIs.
- Add providers only after documenting terms, attribution, quotas, failure isolation, and allowed metadata/document handling.
- Measure provider-specific usefulness before enabling additional sources by default.

OpenScholar will not add publisher scraping, access-control bypasses, CAPTCHA workarounds, or indiscriminate PDF collection.

## 3. Improve retrieval quality

- Expand the independently labelled relevance and deduplication corpus beyond the current synthetic/reference-shaped sets.
- Evaluate multilingual lexical configuration and related-topic reuse.
- Revisit the default-off hybrid pgvector path only after representative evaluation shows stable gains.
- Add performance budgets for the target deployment rather than extrapolating from loopback measurements.

## 4. Strengthen the agent experience

- Propagate client cancellation when the Java MCP transport provides a reliable supported path.
- Consider MCP Tasks or owned job handles only for workflows that become genuinely long-running.
- Keep collection mutations disabled until authorization and explicit host/user confirmation behavior are proven across supported clients.

## Later product work

- Notes and highlights with explicit ownership and deletion semantics.
- Evidence-linked summaries and paper comparison with prompt-injection and citation safeguards.
- Citation graphs, research maps, and collaborative collections.
- Connectivity-aware refresh deferral with reliable outage classification, capped backoff, and result linking; retryable provider errors are not automatically treated as an offline signal.
- Additional offline embedding profiles for legally retained private content.
- Mobile-reader and assistive-technology refinements.

## Release policy

The first tagged release should include a successful `scripts/verify-clean-clone.sh` run for the tagged commit, current screenshots, green GitHub CI/security/SBOM workflows, a recorded real backup/restore drill, an explicit licence decision, and honest known limitations. The verifier uses a detached clean-source clone, controlled provider fixtures, temporary host configuration, and uniquely scoped Docker cleanup, but it may reuse Docker caches and is not a cold-build or deployment proof. Run it only for trusted commits because Docker-socket access is privileged; evaluate untrusted pull requests in a disposable runner. Public-production claims require deployment-specific evidence, live identity/provider validation, and legal approval.
