# OpenScholar documentation

The root [README](../README.md) is the fastest path to running OpenScholar. Use this index for deeper implementation, operation, and evaluation details.

## Build and integrate

- [Development guide](DEVELOPMENT.md) — component workflows, configuration, tests, and change conventions.
- [MCP quickstart](MCP_QUICKSTART.md) — local client configuration, tool inventory, protocol smoke tests, and conformance.
- [REST and MCP contracts](API_AND_MCP.md) — public adapter behavior and security boundaries.
- [OpenAPI 3.1 specification](openapi.yaml) — machine-readable REST contract.

## Understand the system

- [Architecture](ARCHITECTURE.md) — modules, trust boundaries, provider fan-out, and deployment topology.
- [Data model](DATA_MODEL.md) — canonical papers, search snapshots, access evidence, library data, embeddings, and refresh jobs.
- [Architecture decisions](decisions/) — durable choices and their trade-offs.
- [Official references](REFERENCES.md) — source material for provider, protocol, library, and policy claims.

## Operate and secure

- [Deployment guide](DEPLOYMENT.md) — hardened single-host template and release gates.
- [Operations runbook](OPERATIONS_RUNBOOK.md) — health, alerts, recovery, and incident procedures.
- [Security and legal boundaries](SECURITY_AND_LEGAL.md) — access policy, privacy, retention, and legal constraints.
- [Threat model](THREAT_MODEL.md) — assets, trust boundaries, threats, controls, and residual risk.
- [Supply-chain security](SUPPLY_CHAIN_SECURITY.md) — immutable dependencies, image policy, scanning, SBOMs, and VEX.

## Verify quality

- [Testing strategy](TESTING_STRATEGY.md) — unit, integration, browser, contract, and policy coverage.
- [Search quality](SEARCH_QUALITY.md) — lexical, deduplication, vector, and hybrid evaluation results.
- [Provider quality](PROVIDER_QUALITY.md) — deterministic fusion mechanics plus fused-page and comparative raw-candidate live-capture protocols.
- [HNSW evaluation protocol](HNSW_EVALUATION_PROTOCOL.md) — approximate-nearest-neighbor mechanics gate.
- [Holdout evaluation protocol](HOLDOUT_EVALUATION_PROTOCOL.md) — frozen related-paper validation process.
- [Performance evidence](PERFORMANCE_EVIDENCE.md) — reproducible local performance measurements and their limits.
- [Portfolio demo evidence](PORTFOLIO_DEMO.md) — deterministic full-stack screenshots and reproduction steps.

## Direction

- [Roadmap](ROADMAP.md) — future work only; completed implementation belongs in code, tests, and the references above.
