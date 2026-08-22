#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_directory="$(cd -- "${script_directory}/.." && pwd)"
compose_file="${repository_directory}/deploy/compose.production.yaml"
image_policy_file="${repository_directory}/deploy/production-images.lock"

fail() {
  printf 'production-compose: %s\n' "$*" >&2
  exit 1
}

usage() {
  printf '%s\n' \
    "Usage: ${0##*/} ENV_FILE [--check | [--profile observability] COMMAND [ARGUMENT ...]]" \
    'Allowed commands: config, down, exec, logs, ps, pull, stop, up' >&2
}

is_immutable_image_reference() {
  local reference="$1"
  local tagged_name
  local final_component
  local digest

  [[ "${reference}" == *@sha256:* ]] || return 1
  tagged_name="${reference%@sha256:*}"
  final_component="${tagged_name##*/}"
  [[ "${final_component}" == *:* && -n "${final_component##*:}" ]] || return 1
  digest="${reference##*@sha256:}"
  [[ "${#digest}" -eq 64 ]] || return 1
  [[ "${digest}" != *[!0-9a-f]* ]]
}

policy_for_service() {
  local service="$1"
  local match_count

  match_count="$(awk -F '|' -v service="${service}" '
    $0 !~ /^[[:space:]]*#/ && $1 == service { count += 1 }
    END { print count + 0 }
  ' "${image_policy_file}")"
  [[ "${match_count}" -eq 1 ]] \
    || fail "image policy must contain exactly one entry for service ${service}"
  awk -F '|' -v service="${service}" \
    '$0 !~ /^[[:space:]]*#/ && $1 == service { print $2 "|" $3 }' \
    "${image_policy_file}"
}

[[ "$#" -ge 2 ]] || {
  usage
  exit 2
}

environment_file="$1"
shift

if [[ "${environment_file}" != /* ]]; then
  environment_file="${repository_directory}/${environment_file}"
fi
[[ -f "${environment_file}" && ! -L "${environment_file}" ]] \
  || fail "ENV_FILE must be a regular, non-symlink file: ${environment_file}"
[[ -r "${environment_file}" ]] || fail "ENV_FILE is not readable: ${environment_file}"
[[ -f "${image_policy_file}" && ! -L "${image_policy_file}" ]] \
  || fail "reviewed image policy is missing or is not a regular file"

check_only=false
selected_observability_profile=false
delegated_compose_arguments=()

if [[ "$#" -eq 1 && "$1" == "--check" ]]; then
  check_only=true
else
  if [[ "${1:-}" == "--profile" ]]; then
    [[ "$#" -ge 3 && "$2" == "observability" ]] \
      || fail "only the reviewed observability profile may be selected"
    selected_observability_profile=true
    shift 2
  elif [[ "${1:-}" == --profile=* ]]; then
    [[ "${1#--profile=}" == "observability" ]] \
      || fail "only the reviewed observability profile may be selected"
    selected_observability_profile=true
    shift
  fi

  delegated_command="${1:-}"
  shift || true
  case "${delegated_command}" in
    config | down | exec | logs | ps | pull | stop | up) ;;
    *)
      fail "delegated Compose command must be one of: config, down, exec, logs, ps, pull, stop, up"
      ;;
  esac

  # Compose persistent options can be accepted after a subcommand. Reject them
  # throughout the delegated option section so callers cannot replace the
  # environment, file, project, or validated profile after this wrapper's
  # preflight. An explicit `--` ends Compose option parsing for exec payloads.
  for argument in "$@"; do
    [[ "${argument}" == "--" ]] && break
    if [[ "${delegated_command}" == down ]]; then
      case "${argument}" in
        -v | -v?* | --volumes | --volumes=*)
          fail "down may not delete production volumes: ${argument}"
          ;;
      esac
    fi
    case "${argument}" in
      --all-resources | --ansi | --ansi=* | --compatibility | --dry-run \
        | --env-file | --env-file=* | -f | -f?* | --file | --file=* \
        | --parallel | --parallel=* | -p | -p?* | --profile | --profile=* \
        | --progress | --progress=* | --project-directory \
        | --project-directory=* | --project-name | --project-name=*)
        fail "delegated Compose arguments may not include global option: ${argument}"
        ;;
    esac
  done

  delegated_compose_arguments=("${delegated_command}" "$@")
  if [[ "${selected_observability_profile}" == true ]]; then
    delegated_compose_arguments=(
      --profile observability
      "${delegated_compose_arguments[@]}"
    )
  fi
fi

command -v docker >/dev/null 2>&1 || fail "docker is required"
command -v jq >/dev/null 2>&1 || fail "jq is required"

compose_arguments=(
  compose
  --env-file "${environment_file}"
  --file "${compose_file}"
)

# Resolve all profiles so optional monitoring images cannot bypass the same
# policy. Docker Compose applies ambient overrides before emitting this mapping,
# so a mutable or unreviewed shell override is rejected here as well.
if ! resolved_services="$({
  docker "${compose_arguments[@]}" --profile observability config --services
} 2>&1)"; then
  fail "Docker Compose could not resolve the production model: ${resolved_services}"
fi

service_count=0
while IFS='|' read -r expected_service _policy _value; do
  [[ -n "${expected_service}" && "${expected_service}" != \#* ]] || continue
  service_count=$((service_count + 1))
  resolved_count="$(grep -Fxc -- "${expected_service}" <<<"${resolved_services}" || true)"
  [[ "${resolved_count}" -eq 1 ]] \
    || fail "resolved production model must contain service ${expected_service} exactly once"
done <"${image_policy_file}"

resolved_service_count="$(sed '/^[[:space:]]*$/d' <<<"${resolved_services}" | wc -l | tr -d '[:space:]')"
[[ "${resolved_service_count}" -eq "${service_count}" ]] \
  || fail "resolved production service set does not match the reviewed image policy"

if ! resolved_model_json="$({
  docker "${compose_arguments[@]}" --profile observability config --format json
} 2>&1)"; then
  fail "Docker Compose could not render the resolved production model: ${resolved_model_json}"
fi
if ! jq --exit-status \
  --argjson expected_service_count "${service_count}" \
  '((.services | type) == "object")
    and ((.services | length) == $expected_service_count)
    and all(.services[]; .platform == "linux/amd64")' \
  >/dev/null 2>&1 <<<"${resolved_model_json}"; then
  fail "resolved production model must set platform linux/amd64 for every reviewed service"
fi

while IFS= read -r service; do
  [[ -n "${service}" ]] || continue
  if ! image_reference="$(jq --exit-status --raw-output \
    --arg service "${service}" \
    '.services[$service].image | select(type == "string" and length > 0)' \
    2>/dev/null <<<"${resolved_model_json}")"; then
    fail "service ${service} must resolve exactly one image"
  fi
  if ! is_immutable_image_reference "${image_reference}"; then
    fail "resolved image for ${service} is not a readable tag@sha256:<64-lowercase-hex> reference: ${image_reference}"
  fi

  policy_record="$(policy_for_service "${service}")"
  policy_kind="${policy_record%%|*}"
  policy_value="${policy_record#*|}"
  case "${policy_kind}" in
    exact)
      [[ "${image_reference}" == "${policy_value}" ]] \
        || fail "resolved image for ${service} differs from the reviewed third-party reference"
      ;;
    repository)
      tagged_reference="${image_reference%@sha256:*}"
      resolved_repository="${tagged_reference%:*}"
      [[ "${resolved_repository}" == "${policy_value}" ]] \
        || fail "resolved image for ${service} is outside reviewed repository ${policy_value}"
      ;;
    *)
      fail "unsupported image policy ${policy_kind} for service ${service}"
      ;;
  esac
done <<<"${resolved_services}"

printf 'Validated %d immutable production image references against the reviewed service policy.\n' "${service_count}"

if [[ "${check_only}" == true ]]; then
  exit 0
fi

exec docker "${compose_arguments[@]}" "${delegated_compose_arguments[@]}"
