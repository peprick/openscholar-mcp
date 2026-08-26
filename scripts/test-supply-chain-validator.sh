#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_directory="$(cd -- "${script_directory}/.." && pwd)"

fail() {
  printf 'supply-chain-validator-test: %s\n' "$*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

require_command git
require_command perl
require_command tar

test_root="$(mktemp -d "${TMPDIR:-/tmp}/openscholar-supply-chain.XXXXXX")"
archive="${test_root}/repository.tar"
fixture="${test_root}/fixture"
cleanup() {
  local status=$?
  if [[ -n "${test_root:-}" && -d "${test_root}" ]]; then
    rm -rf -- "${test_root}" || true
  fi
  return "${status}"
}
trap cleanup EXIT

(
  cd -- "${repository_directory}"
  while IFS= read -r -d '' repository_path; do
    [[ -e "${repository_path}" || -L "${repository_path}" ]] \
      && printf '%s\0' "${repository_path}"
  done < <(git ls-files --cached --others --exclude-standard -z) \
    | tar --null --files-from - --create --file "${archive}"
)

reset_fixture() {
  rm -rf -- "${fixture}"
  mkdir -p -- "${fixture}"
  tar --extract --file "${archive}" --directory "${fixture}"
}

expect_failure() {
  local name="$1"
  local expected="$2"
  local output
  local status

  set +e
  output="$(
    cd -- "${fixture}"
    EXCEPTION_VALIDATION_DATE=2026-08-22 scripts/validate-supply-chain.sh 2>&1
  )"
  status=$?
  set -e
  [[ "${status}" -ne 0 ]] || fail "${name}: mutated fixture unexpectedly passed"
  grep -Fq -- "${expected}" <<<"${output}" \
    || fail "${name}: expected diagnostic was absent: ${expected}"
  printf 'PASS: %s\n' "${name}"
}

reset_fixture
(
  cd -- "${fixture}"
  EXCEPTION_VALIDATION_DATE=2026-08-22 scripts/validate-supply-chain.sh >/dev/null
)
printf 'PASS: unmodified repository fixture passes\n'

release_worker_fixture="${fixture}/.github/workflows/release-one-image.yml"
release_orchestrator_fixture="${fixture}/.github/workflows/release-images.yml"

reset_fixture
perl -0pi -e \
  's#repository=ghcr[.]io/peprick/openscholar-backend#repository=ghcr.io/unreviewed/openscholar-backend#' \
  "${release_worker_fixture}"
expect_failure \
  "release worker cannot publish outside the reviewed repository" \
  "release image workflow repository mapping is incomplete for backend"

reset_fixture
perl -0pi -e \
  's/--platform linux\/amd64/--platform linux\/arm64/' \
  "${release_worker_fixture}"
expect_failure \
  "release build platform cannot drift from reviewed linux amd64" \
  "release images and scans must target only linux/amd64"

reset_fixture
perl -0pi -e \
  's/exit-code: "1"/exit-code: "0"/' \
  "${release_worker_fixture}"
expect_failure \
  "release image scan cannot become fail-open" \
  "local and registry fix-available high/critical scan gates must remain fail closed"

reset_fixture
perl -0pi -e \
  's/ignore-unfixed: false/ignore-unfixed: true/' \
  "${release_worker_fixture}"
expect_failure \
  "release evidence cannot omit unfixed vulnerabilities" \
  "exact-digest vulnerability review evidence must include fix-available and unfixed findings at every severity"

reset_fixture
perl -0pi -e \
  's/(          exit-code: "1")/$1\n        continue-on-error: true/' \
  "${release_worker_fixture}"
expect_failure \
  "release image scan cannot ignore a failing action" \
  "release-critical steps must not be skippable or fail open"

reset_fixture
perl -0pi -e \
  's/(          exit-code: "1")/$1\n        "continue-on-error" : true/' \
  "${release_worker_fixture}"
expect_failure \
  "quoted release image scan key cannot ignore a failing action" \
  "release-critical steps must not be skippable or fail open"

reset_fixture
perl -0pi -e \
  's/(      - name: Enforce the local high and critical vulnerability gate\n)/$1        if: false\n/' \
  "${release_worker_fixture}"
expect_failure \
  "release image scan cannot be conditionally skipped" \
  "release-critical steps must not be skippable or fail open"

reset_fixture
perl -0pi -e \
  's/(      - name: Enforce the local high and critical vulnerability gate\n)/$1        "if" : false\n/' \
  "${release_worker_fixture}"
expect_failure \
  "quoted release image scan key cannot be conditionally skipped" \
  "release-critical steps must not be skippable or fail open"

reset_fixture
perl -0pi -e \
  's/cosign sign --yes "\$\{DIGEST_REF\}"/cosign sign --yes "\${DIGEST_REF}" || true/' \
  "${release_worker_fixture}"
expect_failure \
  "release signature cannot be made fail-open" \
  "release-critical steps must not be skippable or fail open"

reset_fixture
perl -0pi -e \
  's/GITHUB_WORKFLOW_REF/GITHUB_WORKFLOW_SHA/g' \
  "${release_worker_fixture}"
expect_failure \
  "release worker rejects an unreviewed direct caller" \
  "direct calls must repeat the canonical caller, repository, and protected-tag guards"

reset_fixture
perl -0pi -e \
  's/deployment_ref: \$\{\{ steps[.]approve[.]outputs[.]deployment_ref \}\}/deployment_ref: \${{ steps.publish.outputs.deployment_ref }}/' \
  "${release_worker_fixture}"
expect_failure \
  "release output cannot escape before the evidence chain succeeds" \
  "deployment output must remain empty until the full evidence chain succeeds"

reset_fixture
perl -0pi -e \
  's/validate_ref BACKEND_IMAGE ghcr[.]io\/peprick\/openscholar-backend/validate_ref BACKEND_IMAGE ghcr.io\/peprick\/openscholar-frontend/' \
  "${release_orchestrator_fixture}"
expect_failure \
  "aggregate release manifest cannot cross-wire image repositories" \
  "aggregate manifest must bind BACKEND_IMAGE to ghcr.io/peprick/openscholar-backend"

reset_fixture
perl -0pi -e \
  's/"sha-\$\{GITHUB_SHA\}"/"\$\{RELEASE_TAG\}"/' \
  "${release_worker_fixture}"
expect_failure \
  "release publication tag must stay bound to the source SHA" \
  "release image workflows must preserve the exact source-SHA tag guard"

reset_fixture
perl -0pi -e \
  's/cosign sign --yes "\$\{DIGEST_REF\}"/cosign version/' \
  "${release_worker_fixture}"
expect_failure \
  "exact registry digest signing cannot be removed" \
  "exact-digest signing and provenance/SBOM attestation chain is incomplete"

reset_fixture
perl -0pi -e \
  's/actions\/attest\@1e69f48acb82d1966a394da916b4c1698aa569d6/actions\/attest\@0000000000000000000000000000000000000000/' \
  "${release_worker_fixture}"
expect_failure \
  "reviewed provenance and SBOM attestation action cannot drift" \
  "exact-digest signing and provenance/SBOM attestation chain is incomplete"

reset_fixture
perl -0pi -e \
  's/"\$\{IMAGE_RELEASE_ENABLED\}" != "true"/-z "\$\{IMAGE_RELEASE_ENABLED\}"/' \
  "${release_worker_fixture}"
expect_failure \
  "release environment enablement cannot be weakened" \
  "fail-closed release enablement guard is missing"

reset_fixture
perl -0pi -e \
  's/GITHUB_REF_PROTECTED/GITHUB_REF_NAME/' \
  "${release_orchestrator_fixture}"
expect_failure \
  "unprotected release refs are rejected" \
  "protected release ref guard is missing"

reset_fixture
perl -0pi -e \
  's/set -Eeuo pipefail/set -Eeuo pipefail\n          docker compose up/' \
  "${release_worker_fixture}"
expect_failure \
  "release evidence workflow cannot gain a deployment command" \
  "release image workflows must produce evidence only and must not deploy"

reset_fixture
perl -0pi -e \
  's/uses: actions\/checkout\@[0-9a-f]{40}/"uses" : actions\/checkout\@v6/' \
  "${fixture}/.github/workflows/backend-ci.yml"
expect_failure \
  "quoted spaced action key cannot bypass immutable revision check" \
  "action is not pinned to a full 40-character commit SHA"

reset_fixture
perl -0pi -e 's/\n[[:space:]]+persist-credentials: false//' \
  "${fixture}/.github/workflows/backend-ci.yml"
expect_failure \
  "checkout without credential cleanup is rejected" \
  "every checkout step must set persist-credentials: false"

reset_fixture
perl -0pi -e \
  's/^    image: pgvector\/pgvector:[^\n]+$/    "image" : postgres:latest/m' \
  "${fixture}/compose.yaml"
expect_failure \
  "quoted spaced Compose image key cannot hide a floating tag" \
  "image is not pinned by sha256 digest: postgres:latest"

reset_fixture
workflow_image_fixture="${fixture}/.github/workflows/security.yml"
workflow_image_target_count="$(grep -Ec \
  '^            image: pgvector/pgvector:pg17@sha256:[0-9a-f]{64}$' \
  "${workflow_image_fixture}" || true)"
[[ "${workflow_image_target_count}" -eq 1 ]] \
  || fail "workflow image mutation target is missing or no longer unique"
perl -0pi -e \
  's/^            image: pgvector\/pgvector:pg17\@sha256:[0-9a-f]{64}$/            "image" : pgvector\/pgvector:latest/m' \
  "${workflow_image_fixture}"
workflow_image_replacement_count="$(grep -Fxc \
  '            "image" : pgvector/pgvector:latest' \
  "${workflow_image_fixture}" || true)"
[[ "${workflow_image_replacement_count}" -eq 1 ]] \
  || fail "workflow image mutation did not replace the checked external image"
expect_failure \
  "quoted spaced workflow image key cannot hide a floating tag" \
  "workflow image is not pinned by sha256 digest: pgvector/pgvector:latest"

reset_fixture
mkdir -p -- "${fixture}/operations/images"
printf '%s\n' 'FROM alpine:latest' >"${fixture}/operations/images/Runtime.Dockerfile"
expect_failure \
  "nested alternate-name Dockerfile is scanned" \
  "external FROM is not pinned by readable tag@sha256 digest: alpine:latest"

reset_fixture
perl -0pi -e \
  's/FROM test AS build/FROM source AS build/' \
  "${fixture}/deploy/images/caddy/Dockerfile"
expect_failure \
  "hardened runtime cannot bypass its upstream tests" \
  "final binary build must descend from the tested stage"

reset_fixture
hardened_test_fixture="${fixture}/deploy/images/caddy/Dockerfile"
hardened_test_target_count="$(grep -Fxc \
  'RUN go test -count=1 -p 1 ./...' \
  "${hardened_test_fixture}" || true)"
[[ "${hardened_test_target_count}" -eq 1 ]] \
  || fail "hardened runtime test mutation target is missing or no longer unique"
perl -0pi -e \
  's/^RUN go test -count=1 -p 1 \.\/\.\.\.$/RUN go test -count=1 -p 1 .\/... || true/m' \
  "${hardened_test_fixture}"
hardened_test_replacement_count="$(grep -Fxc \
  'RUN go test -count=1 -p 1 ./... || true' \
  "${hardened_test_fixture}" || true)"
[[ "${hardened_test_replacement_count}" -eq 1 ]] \
  || fail "hardened runtime test mutation did not append the fail-open suffix"
expect_failure \
  "hardened runtime test command cannot be made fail-open" \
  "exact fail-closed upstream test command is required"

reset_fixture
runtime_fetch_command='n''px --yes @modelcontextprotocol/conformance@0.1.16'
RUNTIME_FETCH_COMMAND="${runtime_fetch_command}" perl -0pi -e \
  's/pnpm --dir tools\/mcp-conformance exec conformance/$ENV{RUNTIME_FETCH_COMMAND}/' \
  "${fixture}/.github/workflows/mcp-conformance.yml"
expect_failure \
  "runtime conformance package fetch is rejected" \
  "runtime npx fetching of the MCP conformance CLI is forbidden"

reset_fixture
perl -0pi -e \
  's/\n[[:space:]]+limit-severities-for-sarif: true//' \
  "${fixture}/.github/workflows/security.yml"
expect_failure \
  "SARIF scans cannot broaden their effective severity gate" \
  "every SARIF scan must limit output and exit status to its declared severity gate"

reset_fixture
perl -0pi -e \
  's#postgres\|exact\|pgvector/pgvector:[^\n]+#postgres|exact|postgres:latest#' \
  "${fixture}/deploy/production-images.lock"
expect_failure \
  "mutable production allowlist entry is rejected" \
  "postgres exact policy is not tag@sha256 pinned"

reset_fixture
perl -0pi -e \
  's/"expiresOn": "2026-09-22"/"expiresOn": "2026-08-21"/' \
  "${fixture}/security/vulnerability-exceptions.json"
expect_failure \
  "expired vulnerability exception is rejected" \
  "exception expired on 2026-08-21"

reset_fixture
perl -0pi -e \
  's/"status": "not_affected"/"status": "affected"/' \
  "${fixture}/security/vex/alertmanager-amtool.openvex.json"
expect_failure \
  "broadened vulnerability exception is rejected" \
  "VEX scope, status, justification, evidence, or expiry is invalid"

printf 'Supply-chain validator mutation tests passed.\n'
