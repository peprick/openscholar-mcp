# Supply-Chain Security

## Automated checks

`.github/workflows/security.yml` adds three independent gates:

- GitHub dependency review rejects newly introduced dependencies with known high/critical severity on pull requests.
- CodeQL analyzes Java/Kotlin and JavaScript/TypeScript and publishes code-scanning findings.
- Trivy scans the repository dependency manifests, lockfiles, secrets, and configuration; it uploads SARIF and retains a CycloneDX JSON source SBOM for 30 days.
- On main-branch pushes, the weekly schedule, and manual runs, CI also builds the final backend/frontend runtime stages, generates a CycloneDX SBOM for each image, uploads image SARIF, and rejects unapproved high/critical findings.

`.github/dependabot.yml` proposes grouped weekly Maven, pnpm/npm, GitHub Actions, and Dockerfile updates. Updates still require normal CI, security review, and domain tests; automatic proposal is not automatic deployment.

`.github/CODEOWNERS` assigns the current repository owner to the full tree and repeats high-risk workflow, dependency, image, migration, deployment, backup, and security-policy paths. It becomes an enforcement boundary only after branch protection requires CODEOWNERS review; replace or extend the handle when maintainership changes.

The source SBOM describes dependencies discoverable in the checked-out repository. The image jobs add the backend/frontend base operating-system packages. The selected production proxy/monitoring images must also be scanned and recorded by the release process because they are externally supplied rather than built by this repository.

## Release requirements

1. Build from a protected branch in an ephemeral runner with least-privilege, short-lived credentials.
2. Review lockfile and workflow/action changes as executable supply-chain changes.
3. Pin production images by digest and third-party actions by reviewed immutable commit where organizational policy permits.
4. Scan the final images, correlate findings with the source SBOM, and document any time-bounded exception with owner and compensating control.
5. Sign images and publish provenance/attestations through the selected registry, then verify them at deployment.
6. Retain source and image SBOMs, image digests, scanner versions, findings, signatures, and release approval according to policy.
7. Rebuild rather than patching a running container; revoke the old deployment digest when compromised.

## Required repository settings

- Branch protection with reviewed pull requests and required backend, frontend, E2E, MCP, CodeQL, and Trivy checks.
- GitHub secret scanning/push protection and private vulnerability reporting where available.
- Restricted Actions allow-list, read-only default token, protected deployment environment, and no long-lived registry/cloud key.
- CODEOWNERS review for workflows, Dockerfiles, dependency manifests/lockfiles, migrations, deploy files, and security policy.
- Renovation SLA based on exploitability and exposure, not only numeric severity.

The checked-in workflow references versioned third-party actions but does not establish organizational trust by itself. Resolve allowed publishers and immutable action commit pins before treating it as a production gate.
