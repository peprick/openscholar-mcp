# Hardened edge and probe images

The Caddy and blackbox-exporter Dockerfiles build minimal static `scratch`
images from checksum-verified upstream commit archives. Their mandatory build
ancestry runs the complete upstream test suites before producing a binary.
Reviewed Go security updates, the resulting `go.mod`/`go.sum` hashes, the Go
toolchain image, source commits, and source-archive hashes are all pinned.

These Dockerfiles do not make a local image ID a production identity. A release
pipeline must build the final images for `linux/amd64`, generate SBOMs, reject
unapproved high/critical findings, push the images to the project GHCR
repositories, rescan the returned registry digests, sign and attest them, and
then place the real `tag@sha256` references in `deploy/production.env`.

Blackbox-exporter `0.28.0` uses an unsigned upstream tag/commit. The exact
commit, archive checksum, module graph hashes, and compiled dependency versions
prevent silent drift, but they do not provide upstream signature authenticity.
