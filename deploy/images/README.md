# Hardened edge and probe images

The Caddy and blackbox-exporter Dockerfiles build minimal static `scratch`
images from checksum-verified upstream commit archives. Their mandatory build
ancestry runs the complete upstream test suites before producing a binary.
Reviewed Go security updates, the resulting `go.mod`/`go.sum` hashes, the Go
toolchain image, source commits, and source-archive hashes are all pinned.

These Dockerfiles do not make a local image ID a production identity. The
protected release workflow builds the backend, frontend, Caddy, and
blackbox-exporter final images for `linux/amd64`. Its reusable worker accepts a
closed image key and internally selects the reviewed context, Dockerfile, and
`ghcr.io/peprick/openscholar-*` repository; it never accepts an arbitrary build
or publication target.

Configure the GitHub `image-release` environment with required reviewers,
deployment-ref restrictions for stable `vMAJOR.MINOR.PATCH` tags,
and the case-sensitive environment variable `IMAGE_RELEASE_ENABLED=true`.
Do not define that name at repository or organization scope. Without a visible
exact value the job fails before checkout, build, registry login, or
publication; environment protection itself remains a required repository
setting. Stable tag events run automatically after validation; manual runs must
be dispatched from the same protected release tag with that exact release label.
Published tags are always `sha-${GITHUB_SHA}`, never the human release label.

For each image, Trivy creates a local CycloneDX SBOM and rejects fix-available
high/critical vulnerabilities plus high/critical secret and misconfiguration
findings before GHCR login. `ignore-unfixed: true` means this is not a
zero-vulnerability claim. The worker then pushes once, captures and pulls the
returned digest, checks its `linux/amd64` platform and source label, and repeats
that policy against the exact digest. It also retains an all-severity
`registry-vulnerabilities.json` report containing fix-available and unfixed findings for
the exact digest; operators review this non-gating evidence before promotion.
Only then is the digest keylessly signed with Cosign and given
separate GitHub provenance and CycloneDX attestations. Successful workers retain
the complete gate reports, vulnerability review, digest, signature-verification, and attestation set for 90
days; failed workers retain only the evidence available at failure. A combined
`release-images.env` artifact is produced only after all four workers and their
evidence uploads succeed; the workflow never deploys or changes tracked or
ignored production configuration.

A failure after push can leave a source-SHA tag without a complete registry
scan, signature, attestation set, or final manifest. Treat every non-green run
as unapproved. Review all retained evidence, independently verify each exact
`tag@sha256` reference, and only then manually copy the four image variables
into the ignored `deploy/production.env` and run the production wrapper's
`--check` command.

Blackbox-exporter `0.28.0` uses an unsigned upstream tag/commit. The exact
commit, archive checksum, module graph hashes, and compiled dependency versions
prevent silent drift, but they do not provide upstream signature authenticity.
