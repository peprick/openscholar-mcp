#!/usr/bin/env bash
set -Eeuo pipefail

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_directory="$(cd -- "${script_directory}/.." && pwd)"
failures=0

report_failure() {
  printf 'supply-chain-validation: %s\n' "$*" >&2
  failures=1
}

is_sha256_image_reference() {
  local reference="$1"
  local digest

  [[ "${reference}" == *@sha256:* ]] || return 1
  digest="${reference##*@sha256:}"
  [[ "${#digest}" -eq 64 ]] || return 1
  case "${digest}" in
    *[!0-9a-f]*) return 1 ;;
  esac
}

validate_action_references() {
  local workflow line reference revision line_number

  while IFS= read -r workflow; do
    line_number=0
    while IFS= read -r line || [[ -n "${line}" ]]; do
      line_number=$((line_number + 1))
      reference="$(printf '%s\n' "${line}" | sed -nE 's/^[[:space:]]*(-[[:space:]]*)?uses:[[:space:]]*([^[:space:]#]+).*/\2/p')"
      [[ -n "${reference}" ]] || continue

      case "${reference}" in
        ./*)
          ;;
        docker://*)
          if ! is_sha256_image_reference "${reference#docker://}"; then
            report_failure "${workflow}:${line_number}: Docker action is not pinned by sha256 digest: ${reference}"
          fi
          ;;
        *)
          revision="${reference##*@}"
          if [[ "${reference}" == "${revision}" || "${#revision}" -ne 40 ]]; then
            report_failure "${workflow}:${line_number}: action is not pinned to a full 40-character commit SHA: ${reference}"
          elif [[ "${revision}" == *[!0-9a-f]* ]]; then
            report_failure "${workflow}:${line_number}: action pin is not a lowercase hexadecimal commit SHA: ${reference}"
          fi
          if ! printf '%s\n' "${line}" | grep -Eq '#[[:space:]]+v?[0-9]+([.][0-9]+)*([^[:space:]]*)?[[:space:]]*$'; then
            report_failure "${workflow}:${line_number}: immutable action pin needs a readable release comment"
          fi
          ;;
      esac
    done < "${workflow}"
  done < <(find .github/workflows -type f \( -name '*.yml' -o -name '*.yaml' \) -print | LC_ALL=C sort)
}

validate_dockerfiles() {
  local dockerfile

  while IFS= read -r dockerfile; do
    if ! awk '
      toupper($1) == "FROM" {
        image_index = 2
        if ($2 ~ /^--platform=/) {
          image_index = 3
        }
        image = $image_index
        if (!(image in stages)) {
          marker = "@sha256:"
          marker_position = index(image, marker)
          digest = marker_position == 0 ? "" : substr(image, marker_position + length(marker))
          if (marker_position == 0 || length(digest) != 64 || digest ~ /[^0-9a-f]/) {
            printf "supply-chain-validation: %s:%d: external FROM is not pinned by sha256 digest: %s\n", FILENAME, FNR, image > "/dev/stderr"
            invalid = 1
          }
        }
        for (field = image_index + 1; field <= NF; field++) {
          if (toupper($field) == "AS" && field < NF) {
            stages[$(field + 1)] = 1
          }
        }
      }
      END { exit invalid }
    ' "${dockerfile}"; then
      failures=1
    fi
  done < <(find backend frontend -maxdepth 2 -type f -name 'Dockerfile*' -print | LC_ALL=C sort)
}

validate_compose_images() {
  local compose_file line value reference line_number

  while IFS= read -r compose_file; do
    line_number=0
    while IFS= read -r line || [[ -n "${line}" ]]; do
      line_number=$((line_number + 1))
      value="${line#"${line%%[![:space:]]*}"}"
      [[ "${value}" == image:* ]] || continue
      value="${value#image:}"
      value="${value#"${value%%[![:space:]]*}"}"
      value="${value%%[[:space:]]#*}"
      value="${value%"${value##*[![:space:]]}"}"
      if [[ "${value}" == \"*\" || "${value}" == \'*\' ]]; then
        value="${value:1:${#value}-2}"
      fi
      [[ -n "${value}" ]] || continue

      if [[ "${value}" == "\${BACKEND_IMAGE:?"* || "${value}" == "\${FRONTEND_IMAGE:?"* ]]; then
        if [[ "${value}" != *digest* ]]; then
          report_failure "${compose_file}:${line_number}: application image requirement must explicitly request a digest"
        fi
        continue
      fi

      reference="${value}"
      if [[ "${reference}" == "\${"*":-"*"}" ]]; then
        reference="${reference#*:-}"
        reference="${reference%\}}"
      elif [[ "${reference}" == "\${"* ]]; then
        report_failure "${compose_file}:${line_number}: image variable has no reviewed digest-pinned default: ${reference}"
        continue
      fi

      if ! is_sha256_image_reference "${reference}"; then
        report_failure "${compose_file}:${line_number}: image is not pinned by sha256 digest: ${reference}"
      fi
    done < "${compose_file}"
  done < <(find . -path './.git' -prune -o -path './frontend/node_modules' -prune -o -path './backend/target' -prune -o -type f \( -name '*compose*.yml' -o -name '*compose*.yaml' \) -print | LC_ALL=C sort)
}

validate_operations_validator_images() {
  local line reference line_number=0 validator='scripts/validate-operations.sh'

  [[ -f "${validator}" ]] || return
  while IFS= read -r line || [[ -n "${line}" ]]; do
    line_number=$((line_number + 1))
    reference="$(printf '%s\n' "${line}" | sed -nE 's/^[a-z_]+_image="\$\{[A-Z0-9_]+:-([^}]*)\}"$/\1/p')"
    [[ -n "${reference}" ]] || continue
    if ! is_sha256_image_reference "${reference}"; then
      report_failure "${validator}:${line_number}: validator image is not pinned by sha256 digest: ${reference}"
    fi
  done < "${validator}"
}

validate_maven_wrapper() {
  local properties='backend/.mvn/wrapper/maven-wrapper.properties'
  local checksum distribution_url

  checksum="$(sed -n 's/^distributionSha256Sum=//p' "${properties}")"
  distribution_url="$(sed -n 's/^distributionUrl=//p' "${properties}")"
  if [[ "${#checksum}" -ne 64 || "${checksum}" == *[!0-9a-f]* ]]; then
    report_failure "${properties}: distributionSha256Sum must be one lowercase 64-character SHA-256 value"
  fi
  if [[ "${distribution_url}" != https://repo.maven.apache.org/maven2/*-bin.zip ]]; then
    report_failure "${properties}: distributionUrl must be an exact Maven Central binary archive URL"
  fi
}

validate_runtime_scan_coverage() {
  local line value reference security_workflow='.github/workflows/security.yml'

  [[ -f "${security_workflow}" ]] || {
    report_failure "${security_workflow} is missing"
    return
  }

  while IFS= read -r line || [[ -n "${line}" ]]; do
    value="$(printf '%s\n' "${line}" | sed -nE 's/^[[:space:]]*image:[[:space:]]*([^[:space:]#]+).*/\1/p')"
    [[ "${value}" == "\${"*":-"*"}" ]] || continue
    reference="${value#*:-}"
    reference="${reference%\}}"
    is_sha256_image_reference "${reference}" || continue
    if ! grep -Fq -- "${reference}" "${security_workflow}"; then
      report_failure "${security_workflow}: production runtime image lacks a digest-matched Trivy scan: ${reference}"
    fi
  done < deploy/compose.production.yaml
}

cd -- "${repository_directory}"

validate_action_references
validate_dockerfiles
validate_compose_images
validate_operations_validator_images
validate_maven_wrapper
validate_runtime_scan_coverage

if [[ "${failures}" -ne 0 ]]; then
  exit 1
fi

printf 'Supply-chain validation passed: immutable Actions, images, wrapper, and scan coverage are consistent.\n'
