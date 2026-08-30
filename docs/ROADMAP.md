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

- Keep Europe PMC default-off while the completed synthetic gate, checked-in comparative evaluator, retained-run semantic verifier, private longitudinal comparison and atomic external custody handoff, and retained longitudinal-report verifier are used with a clean reviewed raw-candidate capture, a disjoint independently authored holdout, and actual multiple time-separated live diagnostics for the intended audience. The workflow exists; the repository intentionally contains no real retained labels, holdout, multi-capture cohort, or enablement evidence.
- Select and validate approved external controls for authenticity/signing, trusted timestamps, confidentiality, immutable or versioned retention, deletion, access history, and audit evidence for operator-only comparative run-seal and longitudinal-report bundles. The implemented standalone verifiers prove local SHA-256 integrity and semantic replay under an operator-controlled, non-concurrently-mutated filesystem assumption; they are not a substitute for those external controls, and the bundles must never be sent to the independent reviewer.
- Evaluate audience-relevant institutional repositories through supported APIs.
- Add providers only after documenting terms, attribution, quotas, failure isolation, and allowed metadata/document handling.
- Measure provider-specific usefulness before enabling additional sources by default.

OpenScholar will not add publisher scraping, access-control bypasses, CAPTCHA workarounds, or indiscriminate PDF collection.

## 3. Improve retrieval quality

- Expand the independently labelled relevance and deduplication corpus beyond the current synthetic/reference-shaped sets.
- Add an independently authored multilingual holdout, robust language detection, supported-tokenizer coverage, and an indexing/migration design before considering any versioned lexical change; the current development-only comparator is not activation evidence.
- Add an independently authored blind related-topic reuse holdout and target-deployment performance evidence before considering any product path. The current candidate was tuned against visible synthetic `DEVELOPMENT` labels, then frozen for regression; despite measured development gains, its expansion still surfaced owner-visible off-topic controls below rank one and is not activation evidence. The [holdout protocol](RELATED_TOPIC_REUSE_HOLDOUT_PROTOCOL.md) freezes a future input schema, metric semantics, decision gates, fail-closed staged parser, label-free ranking capability, and coordinator-issued raw-score evidence bound to the exact manifest and judgment commitment. A versioned canonical digest binds each score identity to its complete ranking snapshot, and one staged `VerifiedCorpus` permits only one atomic judgment release. A dedicated, INSERT-only PostgreSQL ledger provides durable runtime-append-only cross-process first-run finality, and the ranking coordinator requires its collector-bound committed capability. A package-private, in-memory, filesystem-write-free workflow now composes clean-checkout collection, staged intake, the one-shot claim, ranking, judgment verification, scoring, and exact evidence verification without exposing retry or publication. Evidence report schema v2 binds the durable first-run run key, evaluation protocol and policy, freeze schema version, source inventory ID, and exact ranking snapshot into the canonical four-artifact report identity. The [operator runbook](RELATED_TOPIC_REUSE_HOLDOUT_OPERATOR_RUNBOOK.md) documents the dedicated-cluster, four-role, TLS, secret, migration, exact-toolchain, ordering, and external-custody requirements; it does not implement them. There is still no real external holdout; no live isolated command or build launcher; no TLS connection factory, provisioning automation, bounded initial-acquisition/connect/socket implementation, or authenticated endpoint isolation; no protection against database administrator or storage authority; no final evaluator freeze; and no safe publisher. External custody, live execution, and activation remain unauthorized. A future publisher needs a reviewed native exclusive-rename boundary because portable Java NIO cannot guarantee atomic no-replace directory promotion. The frozen [100,000-paper scale protocol](RELATED_TOPIC_REUSE_PERFORMANCE_PROTOCOL.md) is a single-caller, warm-cache Testcontainers diagnostic with record-only latency and does not satisfy either requirement.
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

The first tagged release should include a successful `scripts/verify-clean-clone.sh` run for the tagged commit, current screenshots, green GitHub CI/security/SBOM workflows, a recorded real backup/restore drill, the reviewed [all-rights-reserved notice](../LICENSE) and any required third-party notices, and honest known limitations. The verifier uses a detached clean-source clone, controlled provider fixtures, temporary host configuration, and uniquely scoped Docker cleanup, but it may reuse Docker caches and is not a cold-build or deployment proof. Run it only for trusted commits because Docker-socket access is privileged; evaluate untrusted pull requests in a disposable runner. Public-production claims require deployment-specific evidence, live identity/provider validation, and legal approval.
