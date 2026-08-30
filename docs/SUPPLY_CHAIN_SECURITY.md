# Supply-Chain Security

## Automated checks

`.github/workflows/security.yml` adds layered dependency, static-analysis, source, and runtime-image gates:

- GitHub dependency review rejects newly introduced dependencies with known high/critical severity on pull requests.
- CodeQL analyzes Java/Kotlin and JavaScript/TypeScript and publishes code-scanning findings.
- Trivy scans the repository dependency manifests, lockfiles, secrets, and configuration; it uploads severity-limited SARIF and retains a CycloneDX JSON source SBOM for 30 days. The static supply-chain gate requires every SARIF scan to keep the action's output and exit status constrained to its declared high/critical policy.
- On pull requests, main-branch pushes, the weekly schedule, and manual runs, CI builds and scans the final backend, frontend, project-owned Caddy, and project-owned blackbox-exporter runtime stages. It separately scans the exact digest-pinned PostgreSQL, Prometheus, and Alertmanager images. It generates a CycloneDX SBOM for every image, retains/uploads findings outside pull requests, and enforces the checked-in high/critical Trivy policy in every lane.
- On an exact stable release tag or an explicit manual retry from that same tag ref, a separate protected workflow can publish the four project-owned images. It uses a closed image-to-context/repository mapping, source-SHA-only tags, pre-push and exact-registry-digest Trivy gates, keyless Cosign signatures, and separate GitHub provenance and CycloneDX attestations. It produces evidence only and contains no deployment step.

`.github/workflows/operations-validation.yml` runs `scripts/validate-supply-chain.sh` whenever workflows, Dockerfiles, Compose/deployment image references, the Maven Wrapper distribution, or validator scripts change. The portable static gate requires:

- every external GitHub Action to use a full reviewed commit SHA with a readable release comment;
- every checkout step to disable persisted Git credentials;
- every external Dockerfile `FROM`, literal Compose/workflow image, production third-party image override, and operations-validator image to use `tag@sha256:<64 lowercase hex>` syntax;
- backend, frontend, Caddy, and blackbox-exporter production variables to require an approved project repository plus immutable digest while the example file retains obvious `replace-me` placeholders instead of fabricated release digests;
- the Maven Wrapper distribution to use the exact Maven Central binary URL and one lowercase SHA-256 checksum; and
- each digest-pinned third-party production image and each project-owned runtime build to have a matching Trivy matrix entry;
- the MCP conformance CLI to be installed from its dedicated frozen pnpm lockfile with registry integrity and lifecycle scripts disabled; and
- the production Compose image-policy wrapper, seven-service policy, mutation tests, and time-bounded vulnerability-exception registry to remain mutually consistent.

The Maven Wrapper verifies its configured distribution checksum when a clean runner downloads Maven. The official MCP conformance `0.1.16` CLI and its transitive packages are separately locked under `tools/mcp-conformance`; runtime `npx` fetching of that CLI is forbidden. Digest pins select immutable indexes; the readable tag is only maintenance context and does not control the selected content. Production Compose, production-targeted CI image builds, and runtime scans all select `linux/amd64` explicitly so a multi-platform index cannot silently resolve to a child manifest outside the reviewed evidence.

The documentation workflow and clean-source verifier also run `scripts/validate-license-metadata.mjs`. That gate binds the exact reviewed root notice; the retained Apache Maven Wrapper licence, notice, scripts, and versioned properties; the permission-scoped README and contribution statements; the closed npm/Dockerfile inventory; private npm manifests and canonical repository coordinates; exact Maven and OpenAPI declarations; and the absence of an aggregate OCI licence claim on dependency-bearing application images. Mutation tests exercise hidden Markdown/HTML, contradictory claims outside the License section, wrapper and workflow-trigger drift, new unreviewed metadata surfaces, duplicate sections, and notice drift. The gate detects missing or changed expected repository-owned metadata; it is not a complete third-party licence audit, legal review, or substitute for retaining every notice required by upstream packages and container contents. Application-image publication still requires a reviewed component-specific distribution-notice bundle; lockfiles and generated SBOMs are inventory inputs, not a substitute for upstream licence obligations.

`scripts/production-compose.sh` resolves the minimum and observability profiles before every delegated deployment command. It rejects an unexpected service set, a service outside the reviewed `linux/amd64` target, floating or digest-only references, unreviewed third-party substitutions, project-owned images outside the approved repositories, the checked-in `replace-me` values, Compose-global configuration injection, dangerous volume-deleting `down` options, and ambient shell overrides that do not satisfy `deploy/production-images.lock`. The frontend's secret-reading entrypoint is the image default and is also explicit in production Compose, so executable deployment behavior is covered by that image's digest and later signature/attestation.

## Hardened proxy and probe images

The official Caddy and blackbox-exporter runtime images currently fail this repository's high/critical runtime-image policy, so they are not production defaults and have no vulnerability exception. The checked-in [Caddy Dockerfile](../deploy/images/caddy/Dockerfile), [blackbox-exporter Dockerfile](../deploy/images/blackbox-exporter/Dockerfile), and [build notes](../deploy/images/README.md) produce minimal scratch final stages from checksum-pinned source commits and reviewed module graphs. The security workflow's `hardened-runtime-security` matrix runs the complete upstream tests in mandatory build ancestry, validates each checked-in runtime configuration under the intended restrictions, generates an SBOM, and Trivy-scans both local outputs. The protected release workflow repeats the local gate before publishing, then pulls, inspects, and rescans the exact returned digest before signing and attesting it. A successful workflow still does not promote or deploy that digest: an operator must review the retained evidence and manually place all four approved `tag@sha256` references in the ignored `deploy/production.env`. Until then, the example placeholders deliberately block the edge and observability deployment.

Blackbox-exporter `0.28.0` uses an unsigned upstream tag/commit. Its exact commit, source-archive checksum, module-graph hashes, toolchain image, and compiled dependency versions prevent silent drift relative to the reviewed values, but they do not independently authenticate the upstream publisher. Retain that evidence with the release, record this limitation in approval, and prefer signed upstream provenance if it becomes available.

## Scoped vulnerability exceptions

The security scan consumes OpenVEX only for findings demonstrated not to be on the executed production path. `security/vulnerability-exceptions.json` records the exact image, `linux/amd64` platform, in-image component path and SHA-256, Trivy package/version, product and subcomponent PURLs, vulnerability set, evidence, owner, and expiry. `scripts/validate-vulnerability-exceptions.sh` rejects missing/mismatched VEX, duplicate records, expired exceptions, and review windows longer than 45 days. Before applying VEX, CI also pulls the exact `linux/amd64` image, extracts and hashes the registered binary, captures an unsuppressed Trivy JSON report, and requires every excepted finding to occur exactly once at that registered binary/package/version scope.

Two scoped exceptions currently expire on **2026-09-22**:

- `pgvector/pgvector:pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f` — findings in `/usr/local/bin/gosu` with SHA-256 `52c8749d0142edd234e9d6bd5237dff2d81e71f43537e2f4f66f75dd4b243dd0` are `not_affected` with `vulnerable_code_not_in_execute_path`; upstream binary-mode `govulncheck` reported zero symbol-reachable vulnerabilities for that exact binary.
- `prom/alertmanager:v0.34.0@sha256:690c7b525f4367aa91f73e2f91c632206d32e97c6384bdbf2fb7a861b420340d` — the two recorded findings occur only in `/bin/amtool` with SHA-256 `a42bdb03d527f4dc1045e105740944a2f7747838c988dc7a3138f1d4a5c626a0`; production executes `/bin/alertmanager`, while the repository invokes `amtool` only in an isolated, network-disabled validation of trusted checked-in configuration.

These are not claims that the packages were fixed. Reproduce/review the evidence, replace the image or renew the narrowly scoped decision before expiry, and never use either VEX document for another digest, platform, component, or vulnerability set.

`.github/dependabot.yml` proposes grouped weekly Maven, pnpm/npm, GitHub Actions, and Dockerfile updates. Coordinated production Compose/security-matrix digest bumps remain reviewed maintenance changes. Updates still require normal CI, security review, and domain tests; automatic proposal is not automatic deployment.

`.github/CODEOWNERS` assigns the current repository owner to the full tree and repeats high-risk workflow, dependency, image, migration, deployment, backup, and security-policy paths. It becomes an enforcement boundary only after branch protection requires CODEOWNERS review; replace or extend the handle when maintainership changes.

The source SBOM describes dependencies discoverable in the checked-out repository. Image jobs inventory the four project-owned final runtimes and separately record each externally supplied production database/monitoring image. These checked-in jobs and VEX gates are reproducible controls, not evidence that an unpushed revision passed on GitHub, that a local image equals the later registry artifact, or that an expired exception remains acceptable.

## Protected image publication

`.github/workflows/release-images.yml` accepts automatic `vMAJOR.MINOR.PATCH` tags and controlled manual retries from the same stable tag ref; a shell guard rejects prerelease/build suffixes, forks, unprotected refs, mismatched manual refs, release labels that do not resolve to the exact source commit, and source commits not reachable from `origin/main`. Its four static jobs call `.github/workflows/release-one-image.yml`, which maps only `backend`, `frontend`, `caddy`, and `blackbox-exporter` to the contexts and GHCR repositories allowed by `deploy/production-images.lock`. The worker repeats the caller, repository, ref, tag-to-SHA, and main-ancestry checks before publishing. Images are tagged only as `sha-${GITHUB_SHA}` and release outputs always bind that tag to the returned `sha256` digest after the complete evidence chain succeeds.

Every reusable worker runs in the protected `image-release` environment. Configure required reviewers, prevent self-review and administrator bypass where supported, allow only stable release tags, and define the case-sensitive environment-level variable `IMAGE_RELEASE_ENABLED=true`. Do not define that variable at repository or organization scope: GitHub exposes a visible `vars` value without its source scope. The job rejects an unset or non-`true` value before checkout, build, registry login, or publication; this explicit enablement supplements, but cannot statically prove, the external environment protection. Existing GHCR packages must inherit repository access or explicitly grant this repository Actions write access. The workflow uses only short-lived `GITHUB_TOKEN` package access and GitHub OIDC for Cosign and `actions/attest`; do not add a long-lived GHCR or signing secret.

The worker builds one `linux/amd64` image, generates its CycloneDX SBOM, and fails on fix-available high or critical vulnerabilities plus high or critical secret and misconfiguration findings before login. `ignore-unfixed: true` deliberately leaves unfixed vulnerability records outside the automated rejection gate. The worker publishes the source-SHA tag, captures and pulls the returned digest, verifies the platform and source label, and fails on the same scan policy at the exact registry digest. It separately retains the non-gating `registry-vulnerabilities.json` report for that digest, covering fix-available and unfixed vulnerabilities at every severity, so operators have concrete evidence for the required review before promotion. Only then does it keylessly sign and verify the digest and publish SLSA provenance plus CycloneDX SBOM attestations. Both attestations are digest-bound and pushed to the registry with `create-storage-record: false`.

Successful workers retain complete per-image evidence, including both gate SARIF files and the all-severity fix-available/unfixed registry vulnerability JSON, for 90 days; failed workers retain only files produced before failure. The all-four `release-images.env` manifest exists only when all four workers and evidence uploads succeed and is never written into the repository or used to deploy. Because workers run independently, a failed run may leave any subset of unsigned, partially signed, unattested, or otherwise incomplete SHA tags in GHCR. Any run that is not wholly green is unapproved regardless of which artifacts exist; review and retry the same protected tag rather than promoting a partial result. Manual signature, provenance, SBOM, scan, platform, source, and unfixed-vulnerability review is required before copying the four digest references into ignored deployment configuration.

## Release requirements

1. Build from a protected stable release tag whose exact source commit is reachable from protected `main`, using an ephemeral runner with least-privilege, short-lived credentials.
2. Review lockfile and workflow/action changes as executable supply-chain changes.
3. Preserve the checked-in digest pins and immutable action commits; review every automated update as executable code.
4. Scan the locally built final images; after publication, rescan the exact returned registry digests, correlate findings with the source SBOM, and document any time-bounded exception with owner and compensating control.
5. Run publication only through the reviewed `image-release` environment, sign all four exact registry digests, and publish both provenance and CycloneDX attestations.
6. Retain and review source/image SBOMs, exact digests, local and registry findings, signatures, attestations, and release approval according to policy; independently verify every promoted digest again at deployment.
7. Rebuild rather than patching a running container; revoke the old deployment digest when compromised.

## Required repository settings

- Branch protection with reviewed pull requests and required backend, frontend, E2E, MCP, operations/supply-chain, CodeQL, and Trivy checks.
- GitHub secret scanning/push protection and private vulnerability reporting where available.
- Restricted Actions allow-list, read-only default token, protected `image-release` environment with required reviewers/ref restrictions and `IMAGE_RELEASE_ENABLED=true`, and no long-lived registry/cloud key.
- CODEOWNERS review for workflows, Dockerfiles, dependency manifests/lockfiles, migrations, deploy files, and security policy.
- Renovation SLA based on exploitability and exposure, not only numeric severity.

The checked-in workflows use immutable third-party action commits, but a commit pin does not establish publisher trust by itself. Configure the organization Actions allow-list, required checks, branch/CODEOWNERS protection, the protected release environment, GHCR policy, and artifact retention before treating a successful run as release evidence. Registry publication is automated; evidence review, manual promotion, deployment-time verification, and the broader hosted launch decision remain external release gates.
