#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_directory="$(cd -- "${script_directory}/.." && pwd)"
compose_file="${repository_directory}/backend/src/test/holdout-tls/compose.yaml"
timeout_compose_file="${repository_directory}/backend/src/test/holdout-tls/compose.timeouts.yaml"
project_name="${OPENSCHOLAR_HOLDOUT_TLS_TIMEOUT_PROJECT:-openscholar-holdout-tls-timeouts}"

fail() {
  printf 'holdout-tls-timeout-test: %s\n' "$*" >&2
  exit 1
}

case "${project_name}" in
  ''|*[!a-z0-9_-]*)
    fail "OPENSCHOLAR_HOLDOUT_TLS_TIMEOUT_PROJECT must use lowercase letters, digits, underscores, or hyphens"
    ;;
esac

command -v docker >/dev/null 2>&1 \
  || fail "Docker is required"
docker compose version >/dev/null 2>&1 \
  || fail "Docker Compose v2 is required"
[ -f "${compose_file}" ] \
  || fail "holdout TLS Compose file is absent"
[ -f "${timeout_compose_file}" ] \
  || fail "holdout TLS timeout Compose overlay is absent"

compose() {
  docker compose \
    --project-name "${project_name}" \
    --file "${compose_file}" \
    --file "${timeout_compose_file}" \
    "$@"
}

cleanup() {
  status=$?
  trap - EXIT INT TERM
  compose down --volumes --remove-orphans --timeout 30 >/dev/null 2>&1 || true
  exit "${status}"
}
trap cleanup EXIT INT TERM

# This explicit-only harness starts from empty generated materials and removes
# every container, network, and named volume after either success or failure.
compose down --volumes --remove-orphans --timeout 30 >/dev/null 2>&1 || true
compose config --quiet
compose build --pull runner
compose up \
  --detach \
  --wait \
  --wait-timeout 120 \
  tls-postgres

# The timeout IT owns its bounded in-process stall fixtures. --no-deps keeps the
# base Compose file from starting the unrelated plaintext-negative service.
compose run --rm --no-deps runner

printf '%s\n' 'holdout-tls-timeout-test: live PostgreSQL TLS timeout integration test passed'
