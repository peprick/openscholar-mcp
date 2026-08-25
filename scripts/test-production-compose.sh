#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_directory="$(cd -- "${script_directory}/.." && pwd)"
production_compose_script="${script_directory}/production-compose.sh"
test_driver="${script_directory}/$(basename -- "${BASH_SOURCE[0]}")"

fail() {
  printf 'production-compose-test: %s\n' "$*" >&2
  exit 1
}

mock_docker() {
  local mode="${PRODUCTION_COMPOSE_MOCK_MODE:-valid}"
  local log_file="${PRODUCTION_COMPOSE_MOCK_LOG:?PRODUCTION_COMPOSE_MOCK_LOG is required}"
  local backend_digest='aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa'
  local frontend_digest='bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb'
  local caddy_digest='cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc'
  local blackbox_digest='dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd'
  local postgres_image='pgvector/pgvector:pg17@sha256:cf134a767f474095eeba57e0117be8e568e011a63f33fbf252f14c9b760f8e6f'
  local backend_image="ghcr.io/peprick/openscholar-backend:release@sha256:${backend_digest}"
  local frontend_image="ghcr.io/peprick/openscholar-frontend:release@sha256:${frontend_digest}"
  local proxy_image="ghcr.io/peprick/openscholar-caddy:release@sha256:${caddy_digest}"
  local blackbox_image="ghcr.io/peprick/openscholar-blackbox-exporter:release@sha256:${blackbox_digest}"
  local alertmanager_image='prom/alertmanager:v0.34.0@sha256:690c7b525f4367aa91f73e2f91c632206d32e97c6384bdbf2fb7a861b420340d'
  local prometheus_image='prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0'
  local backend_image_json
  local backend_service_fields='"platform":"linux/amd64"'

  printf '%q ' docker "$@" >>"${log_file}"
  printf '\n' >>"${log_file}"

  [[ "${1:-}" == compose ]] || return 97
  if [[ " $* " == *' config --services '* ]]; then
    case "${mode}" in
      missing-service)
        printf '%s\n' postgres backend frontend proxy blackbox-exporter alertmanager
        ;;
      extra-service)
        printf '%s\n' postgres backend frontend proxy blackbox-exporter alertmanager prometheus rogue
        ;;
      resolution-failure)
        printf 'required deployment variable is missing\n' >&2
        return 42
        ;;
      *)
        printf '%s\n' postgres backend frontend proxy blackbox-exporter alertmanager prometheus
        ;;
    esac
    return 0
  fi
  if [[ " $* " == *' config --format json '* ]]; then
    case "${mode}" in
      floating)
        backend_image='ghcr.io/peprick/openscholar-backend:latest'
        ;;
      digest-only)
        backend_image="ghcr.io/peprick/openscholar-backend@sha256:${backend_digest}"
        ;;
      uppercase-digest)
        backend_image='ghcr.io/peprick/openscholar-backend:release@sha256:AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA'
        ;;
      wrong-app-repository)
        backend_image="registry.attacker.example/openscholar-backend:release@sha256:${backend_digest}"
        ;;
      wrong-hardened-repository)
        proxy_image="registry.attacker.example/openscholar-caddy:release@sha256:${caddy_digest}"
        ;;
      wrong-third-party)
        postgres_image="postgres:17@sha256:${backend_digest}"
        ;;
      platform-mismatch)
        backend_service_fields='"platform":"linux/arm64"'
        ;;
      platform-nested-decoy)
        backend_service_fields='"labels":{"platform":"linux/amd64"}'
        ;;
    esac
    backend_image_json="\"${backend_image}\""
    if [[ "${mode}" == multiple-images ]]; then
      backend_image_json="[\"${backend_image}\",\"ghcr.io/peprick/openscholar-backend:other@sha256:${frontend_digest}\"]"
    fi
    printf '%s\n' \
      "{\"services\":{\"postgres\":{\"image\":\"${postgres_image}\",\"platform\":\"linux/amd64\"},\"backend\":{\"image\":${backend_image_json},${backend_service_fields}},\"frontend\":{\"image\":\"${frontend_image}\",\"platform\":\"linux/amd64\"},\"proxy\":{\"image\":\"${proxy_image}\",\"platform\":\"linux/amd64\"},\"blackbox-exporter\":{\"image\":\"${blackbox_image}\",\"platform\":\"linux/amd64\"},\"alertmanager\":{\"image\":\"${alertmanager_image}\",\"platform\":\"linux/amd64\"},\"prometheus\":{\"image\":\"${prometheus_image}\",\"platform\":\"linux/amd64\"}}}"
    return 0
  fi
  if [[ " $* " == *' config --images '* ]]; then
    # Modern Compose includes dependency images for a service selector. This
    # deliberately dependency-expanded response must never be used by the
    # wrapper's one-service policy check.
    printf '%s\n' "${postgres_image}" "${backend_image}"
    return 0
  fi

  [[ "${mode}" == valid ]] || return 95
}

if [[ "${1:-}" == --mock-docker ]]; then
  shift
  mock_docker "$@"
  exit
fi

expect_failure() {
  local name="$1"
  local mode="$2"
  local expected="$3"
  shift 3
  local output
  local status

  set +e
  output="$(
    env \
      "PATH=${mock_bin_directory}:${PATH}" \
      "PRODUCTION_COMPOSE_TEST_DRIVER=${test_driver}" \
      "PRODUCTION_COMPOSE_MOCK_LOG=${mock_log}" \
      "PRODUCTION_COMPOSE_MOCK_MODE=${mode}" \
      "${production_compose_script}" "$@" 2>&1
  )"
  status=$?
  set -e

  [[ "${status}" -ne 0 ]] || fail "${name}: command unexpectedly succeeded"
  grep -Fq -- "${expected}" <<<"${output}" \
    || fail "${name}: expected failure text was absent: ${expected}"
  printf 'PASS: %s\n' "${name}"
}

[[ -x "${production_compose_script}" ]] \
  || fail "production Compose wrapper is missing or not executable"

for privacy_export_contract in \
  "PRIVACY_EXPORT_GLOBAL_PERMITS|4" \
  "PRIVACY_EXPORT_PER_PRINCIPAL_PERMITS|1" \
  "PRIVACY_EXPORT_RETRY_AFTER|10s"; do
  IFS='|' read -r privacy_export_setting privacy_export_default \
    <<<"${privacy_export_contract}"
  compose_mapping="${privacy_export_setting}: \${${privacy_export_setting}:-${privacy_export_default}}"
  environment_mapping="${privacy_export_setting}=${privacy_export_default}"
  grep -Fq -- "${compose_mapping}" "${repository_directory}/compose.yaml" \
    || fail "development Compose is missing ${privacy_export_setting} with its safe default"
  grep -Fq -- "${compose_mapping}" "${repository_directory}/deploy/compose.production.yaml" \
    || fail "production Compose is missing ${privacy_export_setting} with its safe default"
  grep -Fxq -- "${environment_mapping}" "${repository_directory}/.env.example" \
    || fail "root environment example is missing ${privacy_export_setting} with its safe default"
  grep -Fxq -- "${environment_mapping}" "${repository_directory}/backend/.env.example" \
    || fail "backend environment example is missing ${privacy_export_setting} with its safe default"
  grep -Fxq -- "${environment_mapping}" "${repository_directory}/deploy/production.env.example" \
    || fail "production environment example is missing ${privacy_export_setting} with its safe default"
done
printf 'PASS: privacy-export admission settings follow every deployment configuration path\n'

test_root="$(mktemp -d "${TMPDIR:-/tmp}/openscholar-production-compose.XXXXXX")"
cleanup() {
  local status=$?
  if [[ -n "${test_root:-}" && -d "${test_root}" ]]; then
    rm -rf -- "${test_root}" || true
  fi
  return "${status}"
}
trap cleanup EXIT

mock_bin_directory="${test_root}/bin"
mock_log="${test_root}/mock-docker.log"
environment_file="${test_root}/production.env"
mkdir -p -- "${mock_bin_directory}"
: >"${mock_log}"
: >"${environment_file}"

# Preserve these variables for expansion when the generated wrapper executes.
# shellcheck disable=SC2016
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'exec "${PRODUCTION_COMPOSE_TEST_DRIVER:?}" --mock-docker "$@"' \
  >"${mock_bin_directory}/docker"
chmod 0700 "${mock_bin_directory}/docker"

check_output="$(
  env \
    "PATH=${mock_bin_directory}:${PATH}" \
    "PRODUCTION_COMPOSE_TEST_DRIVER=${test_driver}" \
    "PRODUCTION_COMPOSE_MOCK_LOG=${mock_log}" \
    PRODUCTION_COMPOSE_MOCK_MODE=valid \
    "${production_compose_script}" "${environment_file}" --check
)"
grep -Fq -- 'Validated 7 immutable production image references against the reviewed service policy.' <<<"${check_output}" \
  || fail "valid check did not report the resolved image count"
grep -Fq -- 'config --format json' "${mock_log}" \
  || fail "valid check did not render the resolved JSON model"
if grep -Fq -- 'config --images' "${mock_log}"; then
  fail "valid check used dependency-expanded config --images output"
fi
printf 'PASS: valid immutable references pass the dry-run preflight\n'

expect_delegated_command() {
  local name="$1"
  local expected="$2"
  shift 2

  : >"${mock_log}"
  env \
    "PATH=${mock_bin_directory}:${PATH}" \
    "PRODUCTION_COMPOSE_TEST_DRIVER=${test_driver}" \
    "PRODUCTION_COMPOSE_MOCK_LOG=${mock_log}" \
    PRODUCTION_COMPOSE_MOCK_MODE=valid \
    "${production_compose_script}" "${environment_file}" "$@" >/dev/null
  grep -Fq -- 'config --format json' "${mock_log}" \
    || fail "${name}: delegated command did not render the image preflight model"
  if grep -Fq -- 'config --images' "${mock_log}"; then
    fail "${name}: delegated preflight used dependency-expanded image output"
  fi
  grep -Fq -- "${expected}" <<<"$(tail -n 1 "${mock_log}")" \
    || fail "${name}: validated command was not delegated as expected"
  printf 'PASS: %s\n' "${name}"
}

expect_delegated_command \
  "pull is delegated after validation" \
  ' pull ' \
  pull
expect_delegated_command \
  "config is delegated after validation" \
  ' config --quiet ' \
  config --quiet
expect_delegated_command \
  "up is delegated after validation" \
  ' up --detach ' \
  up --detach
expect_delegated_command \
  "reviewed observability profile is normalized and delegated" \
  ' --profile observability up --detach ' \
  --profile observability up --detach

: >"${mock_log}"
expect_failure \
  "delegated environment-file override is rejected before preflight" \
  valid \
  "delegated Compose command must be one of" \
  "${environment_file}" --env-file "${test_root}/attacker.env" pull
[[ ! -s "${mock_log}" ]] \
  || fail "environment-file override reached Docker before rejection"

: >"${mock_log}"
expect_failure \
  "delegated Compose-file override is rejected before preflight" \
  valid \
  "delegated Compose command must be one of" \
  "${environment_file}" -f "${test_root}/attacker.yaml" up --detach
[[ ! -s "${mock_log}" ]] \
  || fail "Compose-file override reached Docker before rejection"

: >"${mock_log}"
expect_failure \
  "post-command global override is rejected before preflight" \
  valid \
  "may not include global option: --env-file=" \
  "${environment_file}" up "--env-file=${test_root}/attacker.env"
[[ ! -s "${mock_log}" ]] \
  || fail "post-command global override reached Docker before rejection"

: >"${mock_log}"
expect_failure \
  "down rejects short volume deletion after other options" \
  valid \
  "down may not delete production volumes: -v" \
  "${environment_file}" down --remove-orphans -v
[[ ! -s "${mock_log}" ]] \
  || fail "short down volume-deletion flag reached Docker before rejection"

: >"${mock_log}"
expect_failure \
  "down rejects long volume deletion before other options" \
  valid \
  "down may not delete production volumes: --volumes" \
  "${environment_file}" down --volumes --remove-orphans
[[ ! -s "${mock_log}" ]] \
  || fail "long down volume-deletion flag reached Docker before rejection"

expect_failure \
  "floating override is rejected" \
  floating \
  "is not a readable tag@sha256" \
  "${environment_file}" pull
expect_failure \
  "digest without a maintenance tag is rejected" \
  digest-only \
  "is not a readable tag@sha256" \
  "${environment_file}" up --detach
expect_failure \
  "uppercase digest is rejected" \
  uppercase-digest \
  "is not a readable tag@sha256" \
  "${environment_file}" --check
expect_failure \
  "missing profiled service is rejected" \
  missing-service \
  "must contain service prometheus exactly once" \
  "${environment_file}" --check
expect_failure \
  "Compose interpolation failure is surfaced" \
  resolution-failure \
  "could not resolve the production model" \
  "${environment_file}" --check
expect_failure \
  "unexpected service is rejected" \
  extra-service \
  "service set does not match" \
  "${environment_file}" --check
expect_failure \
  "service platform mismatch is rejected" \
  platform-mismatch \
  "must set platform linux/amd64 for every reviewed service" \
  "${environment_file}" --check
expect_failure \
  "nested platform decoy cannot hide a missing service platform" \
  platform-nested-decoy \
  "must set platform linux/amd64 for every reviewed service" \
  "${environment_file}" --check
expect_failure \
  "application repository substitution is rejected" \
  wrong-app-repository \
  "outside reviewed repository" \
  "${environment_file}" pull
expect_failure \
  "hardened runtime repository substitution is rejected" \
  wrong-hardened-repository \
  "outside reviewed repository" \
  "${environment_file}" pull
expect_failure \
  "third-party digest substitution is rejected" \
  wrong-third-party \
  "differs from the reviewed third-party reference" \
  "${environment_file}" pull
expect_failure \
  "multiple images for one service are rejected" \
  multiple-images \
  "must resolve exactly one image" \
  "${environment_file}" pull

if command -v docker >/dev/null 2>&1 \
  && docker compose version >/dev/null 2>&1; then
  real_environment_file="${test_root}/real-production.env"
  sed \
    -e 's|^BACKEND_IMAGE=.*|BACKEND_IMAGE=ghcr.io/peprick/openscholar-backend:test@sha256:aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa|' \
    -e 's|^FRONTEND_IMAGE=.*|FRONTEND_IMAGE=ghcr.io/peprick/openscholar-frontend:test@sha256:bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb|' \
    -e 's|^CADDY_IMAGE=.*|CADDY_IMAGE=ghcr.io/peprick/openscholar-caddy:test@sha256:cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc|' \
    -e 's|^BLACKBOX_EXPORTER_IMAGE=.*|BLACKBOX_EXPORTER_IMAGE=ghcr.io/peprick/openscholar-blackbox-exporter:test@sha256:dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd|' \
    "${repository_directory}/deploy/production.env.example" \
    >"${real_environment_file}"
  real_check_output="$(
    "${production_compose_script}" "${real_environment_file}" --check
  )"
  grep -Fq -- 'Validated 7 immutable production image references against the reviewed service policy.' <<<"${real_check_output}" \
    || fail "real Compose config-only preflight did not validate the resolved model"
  printf 'PASS: real Docker Compose config-only preflight validates service-bound images\n'
else
  printf 'SKIP: real Docker Compose config-only preflight (Compose unavailable)\n'
fi

linked_environment_file="${test_root}/linked-production.env"
ln -s -- "${environment_file}" "${linked_environment_file}"
expect_failure \
  "symlink environment file is rejected" \
  valid \
  "must be a regular, non-symlink file" \
  "${linked_environment_file}" --check

printf 'Production Compose preflight tests passed.\n'
