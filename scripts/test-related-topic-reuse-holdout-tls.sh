#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_directory="$(cd -- "${script_directory}/.." && pwd)"
compose_file="${repository_directory}/backend/src/test/holdout-tls/compose.yaml"
project_name="${OPENSCHOLAR_HOLDOUT_TLS_PROJECT:-openscholar-holdout-tls}"

fail() {
  printf 'holdout-tls-test: %s\n' "$*" >&2
  exit 1
}

case "${project_name}" in
  ''|*[!a-z0-9_-]*)
    fail "OPENSCHOLAR_HOLDOUT_TLS_PROJECT must use lowercase letters, digits, underscores, or hyphens"
    ;;
esac

command -v docker >/dev/null 2>&1 \
  || fail "Docker is required"
docker compose version >/dev/null 2>&1 \
  || fail "Docker Compose v2 is required"
[ -f "${compose_file}" ] \
  || fail "holdout TLS Compose file is absent"

compose() {
  docker compose \
    --project-name "${project_name}" \
    --file "${compose_file}" \
    "$@"
}

cleanup() {
  status=$?
  trap - EXIT INT TERM
  compose down --volumes --remove-orphans --timeout 30 >/dev/null 2>&1 || true
  exit "${status}"
}
trap cleanup EXIT INT TERM

# Start from empty volumes even after an interrupted manual invocation. No
# generated credential or private CA key may survive between test runs.
compose down --volumes --remove-orphans --timeout 30 >/dev/null 2>&1 || true
compose config --quiet
compose build --pull runner
compose up \
  --detach \
  --wait \
  --wait-timeout 120 \
  tls-postgres \
  plaintext-postgres

# Dependencies are already healthy. --no-deps prevents Compose from recreating
# them, and the service command selects the otherwise undiscovered *IT class.
compose run --rm --no-deps runner

printf '%s\n' 'holdout-tls-test: live PostgreSQL TLS integration test passed'
