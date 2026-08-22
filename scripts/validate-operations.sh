#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_directory="$(cd -- "${script_directory}/.." && pwd)"

shellcheck_image="${SHELLCHECK_IMAGE:-koalaman/shellcheck-alpine:v0.11.0@sha256:9955be09ea7f0dbf7ae942ac1f2094355bb30d96fffba0ec09f5432207544002}"
actionlint_image="${ACTIONLINT_IMAGE:-rhysd/actionlint:1.7.12@sha256:b1934ee5f1c509618f2508e6eb47ee0d3520686341fec936f3b79331f9315667}"
caddy_image='openscholar-caddy:operations-validation'
prometheus_image="${PROMETHEUS_VALIDATION_IMAGE:-prom/prometheus:v3.14.0@sha256:5ce7540c3c00ef4ab0c9d2c995c6a5b9c421f44b4a115d97a2c7af3b1c21cbb0}"
alertmanager_image="${ALERTMANAGER_VALIDATION_IMAGE:-prom/alertmanager:v0.34.0@sha256:690c7b525f4367aa91f73e2f91c632206d32e97c6384bdbf2fb7a861b420340d}"
blackbox_image='openscholar-blackbox-exporter:operations-validation'

fail() {
  printf 'operations-validation: %s\n' "$*" >&2
  exit 1
}

prometheus_validation_directory=""
cleanup() {
  local status=$?
  if [[ -n "${prometheus_validation_directory}" ]]; then
    rm -rf -- "${prometheus_validation_directory}" || true
  fi
  return "${status}"
}
trap cleanup EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

run_read_only_image() {
  local image="$1"
  local entrypoint="$2"
  shift 2
  local docker_arguments=(
    run --rm
    --network none
    --read-only
    --cap-drop ALL
    --security-opt no-new-privileges
    --volume "${repository_directory}:/repo:ro"
    --workdir /repo
  )
  if [[ -n "${entrypoint}" ]]; then
    docker_arguments+=(--entrypoint "${entrypoint}")
  fi
  docker "${docker_arguments[@]}" "${image}" "$@"
}

validate_public_targets() {
  local target_file="$1"
  jq -e '
    type == "array"
    and length > 0
    and all(.[ ];
      type == "object"
      and (.targets | type == "array" and length > 0)
      and (.labels | type == "object")
      and (.labels.component | type == "string" and length > 0)
      and all(.targets[ ];
        type == "string"
        and startswith("https://")
        and (contains("@") | not)
        and (contains("?") | not)
        and (contains("#") | not)
      )
    )
    and (([.[ ].targets[ ]] | length) == ([.[ ].targets[ ]] | unique | length))
  ' "${target_file}" >/dev/null
}

require_command docker
require_command jq
docker info >/dev/null 2>&1 || fail "the Docker daemon is required for isolated config validators"
docker compose version >/dev/null 2>&1 || fail "Docker Compose v2 is required"

cd -- "${repository_directory}"

printf 'Building tested hardened Caddy and blackbox-exporter validators...\n'
docker build --file "${repository_directory}/deploy/images/caddy/Dockerfile" --tag "${caddy_image}" "${repository_directory}/deploy/images/caddy"
docker build --file "${repository_directory}/deploy/images/blackbox-exporter/Dockerfile" --tag "${blackbox_image}" "${repository_directory}/deploy/images/blackbox-exporter"

shell_files=()
while IFS= read -r shell_file; do
  shell_files+=("${shell_file}")
done < <(find deploy scripts -type f -name '*.sh' -print | LC_ALL=C sort)
[[ "${#shell_files[@]}" -gt 0 ]] || fail "no shell scripts were found"

printf 'Validating %d shell scripts with ShellCheck...\n' "${#shell_files[@]}"
run_read_only_image "${shellcheck_image}" /bin/shellcheck "${shell_files[@]}"

printf 'Validating GitHub Actions workflows with actionlint...\n'
run_read_only_image "${actionlint_image}" ""

printf 'Rendering the production Compose model without starting services...\n'
PUBLIC_PROBE_TARGETS_FILE=./prometheus/targets/public-endpoints.example.json \
  docker compose \
    --env-file deploy/production.env.example \
    --file deploy/compose.production.yaml \
    config --quiet
PUBLIC_PROBE_TARGETS_FILE=./prometheus/targets/public-endpoints.example.json \
  docker compose \
    --env-file deploy/production.env.example \
    --file deploy/compose.production.yaml \
    --profile observability \
    config --quiet

printf 'Validating the Caddy configuration...\n'
docker run --rm \
  --network none \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --tmpfs /tmp:size=16m,mode=1777 \
  --env PUBLIC_HOST=research.example.com \
  --env ACME_EMAIL=operator@example.com \
  --env XDG_CONFIG_HOME=/tmp/caddy-config \
  --env XDG_DATA_HOME=/tmp/caddy-data \
  --volume "${repository_directory}/deploy/Caddyfile:/etc/caddy/Caddyfile:ro" \
  "${caddy_image}" \
  caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile

printf 'Validating Prometheus configuration and alert rules...\n'
prometheus_validation_directory="$(mktemp -d)"
chmod 0755 "${prometheus_validation_directory}"
cp -R "${repository_directory}/deploy/prometheus/." "${prometheus_validation_directory}/"
cp \
  "${prometheus_validation_directory}/targets/public-endpoints.example.json" \
  "${prometheus_validation_directory}/targets/public-endpoints.json"
docker run --rm \
  --network none \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --entrypoint /bin/promtool \
  --volume "${prometheus_validation_directory}:/etc/prometheus:ro" \
  "${prometheus_image}" \
  check config /etc/prometheus/prometheus.yml
docker run --rm \
  --network none \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --entrypoint /bin/promtool \
  --volume "${repository_directory}/deploy/prometheus:/etc/prometheus:ro" \
  "${prometheus_image}" \
  check rules /etc/prometheus/alerts.yml

printf 'Validating Alertmanager configuration...\n'
docker run --rm \
  --network none \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --entrypoint /bin/amtool \
  --volume "${repository_directory}/deploy/alertmanager/alertmanager.yml:/etc/alertmanager/alertmanager.yml:ro" \
  "${alertmanager_image}" \
  check-config /etc/alertmanager/alertmanager.yml

printf 'Validating blackbox-exporter configuration...\n'
docker run --rm \
  --network none \
  --read-only \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --volume "${repository_directory}/deploy/prometheus/blackbox.yml:/etc/blackbox_exporter/config.yml:ro" \
  "${blackbox_image}" \
  --config.file=/etc/blackbox_exporter/config.yml \
  --config.check

printf 'Validating public Prometheus file-discovery targets...\n'
validate_public_targets \
  "${repository_directory}/deploy/prometheus/targets/public-endpoints.example.json"

printf 'Operations validation passed without starting a service or touching a volume.\n'
