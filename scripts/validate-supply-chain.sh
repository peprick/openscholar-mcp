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
  local tagged_name
  local final_component

  [[ "${reference}" == *@sha256:* ]] || return 1
  tagged_name="${reference%@sha256:*}"
  final_component="${tagged_name##*/}"
  [[ "${final_component}" == *:* && -n "${final_component##*:}" ]] || return 1
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
      reference="$(printf '%s\n' "${line}" | sed -nE "s/^[[:space:]]*(-[[:space:]]*)?['\"]?uses['\"]?[[:space:]]*:[[:space:]]*['\"]?([^[:space:]#'\"]+).*/\\2/p")"
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

validate_checkout_credentials() {
  local workflow checkout_count persistence_count

  while IFS= read -r workflow; do
    checkout_count="$(sed -nE "/^[[:space:]]*(-[[:space:]]*)?['\"]?uses['\"]?[[:space:]]*:[[:space:]]*['\"]?actions\/checkout@/p" "${workflow}" | wc -l | tr -d '[:space:]')"
    persistence_count="$(sed -nE "/^[[:space:]]*['\"]?persist-credentials['\"]?[[:space:]]*:[[:space:]]*['\"]?false['\"]?([[:space:]#].*)?$/p" "${workflow}" | wc -l | tr -d '[:space:]')"
    if [[ "${checkout_count}" -ne "${persistence_count}" ]]; then
      report_failure "${workflow}: every checkout step must set persist-credentials: false"
    fi
    if grep -Eq "^[[:space:]]*['\"]?persist-credentials['\"]?[[:space:]]*:[[:space:]]*['\"]?true['\"]?([[:space:]#].*)?$" "${workflow}"; then
      report_failure "${workflow}: checkout credentials must never be persisted"
    fi
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
        if (!(image in stages) && image != "scratch") {
          marker = "@sha256:"
          marker_position = index(image, marker)
          tagged_image = marker_position == 0 ? "" : substr(image, 1, marker_position - 1)
          final_component = tagged_image
          sub(/^.*\//, "", final_component)
          tag_position = index(final_component, ":")
          digest = marker_position == 0 ? "" : substr(image, marker_position + length(marker))
          if (marker_position == 0 || tag_position == 0 || tag_position == length(final_component) || length(digest) != 64 || digest ~ /[^0-9a-f]/) {
            printf "supply-chain-validation: %s:%d: external FROM is not pinned by readable tag@sha256 digest: %s\n", FILENAME, FNR, image > "/dev/stderr"
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
  done < <(
    find . \
      -path './.git' -prune -o \
      -path './frontend/node_modules' -prune -o \
      -path './backend/target' -prune -o \
      -type f \( -name 'Dockerfile' -o -name 'Dockerfile.*' -o -name '*.Dockerfile' \) \
      -print | LC_ALL=C sort
  )
}

validate_compose_images() {
  local compose_file line value reference line_number

  while IFS= read -r compose_file; do
    line_number=0
    while IFS= read -r line || [[ -n "${line}" ]]; do
      line_number=$((line_number + 1))
      value="$(printf '%s\n' "${line}" | sed -nE "s/^[[:space:]]*['\"]?image['\"]?[[:space:]]*:[[:space:]]*(.*)$/\\1/p")"
      [[ -n "${value}" ]] || continue
      value="${value#"${value%%[![:space:]]*}"}"
      value="${value%%[[:space:]]#*}"
      value="${value%"${value##*[![:space:]]}"}"
      if [[ "${value}" == \"*\" || "${value}" == \'*\' ]]; then
        value="${value:1:${#value}-2}"
      fi
      [[ -n "${value}" ]] || continue

      if [[ "${value}" == "\${BACKEND_IMAGE:?"* \
        || "${value}" == "\${FRONTEND_IMAGE:?"* \
        || "${value}" == "\${CADDY_IMAGE:?"* \
        || "${value}" == "\${BLACKBOX_EXPORTER_IMAGE:?"* ]]; then
        if [[ "${value}" != *@sha256:* || "${value}" != *digest* ]]; then
          report_failure "${compose_file}:${line_number}: application image requirement must explicitly request tag@sha256 digest syntax"
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

validate_workflow_image_references() {
  local workflow line reference line_number

  while IFS= read -r workflow; do
    line_number=0
    while IFS= read -r line || [[ -n "${line}" ]]; do
      line_number=$((line_number + 1))
      reference="$(printf '%s\n' "${line}" | sed -nE "s/^[[:space:]]*['\"]?([A-Z0-9_]+_IMAGE|image)['\"]?[[:space:]]*:[[:space:]]*['\"]?([^[:space:]#'\"]+).*/\\2/p")"
      [[ -n "${reference}" ]] || continue
      if ! is_sha256_image_reference "${reference}"; then
        report_failure "${workflow}:${line_number}: workflow image is not pinned by sha256 digest: ${reference}"
      fi
    done < "${workflow}"
  done < <(find .github/workflows -type f \( -name '*.yml' -o -name '*.yaml' \) -print | LC_ALL=C sort)
}

validate_production_env_images() {
  local env_file='deploy/production.env.example'
  local variable reference placeholder

  while IFS='=' read -r variable reference; do
    case "${variable}" in
      BACKEND_IMAGE | FRONTEND_IMAGE | CADDY_IMAGE | BLACKBOX_EXPORTER_IMAGE)
        case "${variable}" in
          BACKEND_IMAGE) placeholder='ghcr.io/replace-me/openscholar-backend:reviewed' ;;
          FRONTEND_IMAGE) placeholder='ghcr.io/replace-me/openscholar-frontend:reviewed' ;;
          CADDY_IMAGE) placeholder='ghcr.io/replace-me/openscholar-caddy:reviewed' ;;
          BLACKBOX_EXPORTER_IMAGE) placeholder='ghcr.io/replace-me/openscholar-blackbox-exporter:reviewed' ;;
        esac
        if [[ "${reference}" != "${placeholder}" ]] \
          && ! is_sha256_image_reference "${reference}"; then
          report_failure "${env_file}: ${variable} must remain the explicit placeholder or use a real tag@sha256 digest"
        fi
        ;;
      *_IMAGE)
        if ! is_sha256_image_reference "${reference}"; then
          report_failure "${env_file}: ${variable} is not pinned by sha256 digest: ${reference}"
        fi
        ;;
    esac
  done < "${env_file}"
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

validate_locked_mcp_conformance_cli() {
  local manifest='tools/mcp-conformance/package.json'
  local lockfile='tools/mcp-conformance/pnpm-lock.yaml'
  local workflow='.github/workflows/mcp-conformance.yml'

  [[ -f "${manifest}" && -f "${lockfile}" ]] || {
    report_failure "locked MCP conformance manifest/lockfile is missing"
    return
  }
  if ! grep -Eq '"@modelcontextprotocol/conformance"[[:space:]]*:[[:space:]]*"0[.]1[.]16"' "${manifest}"; then
    report_failure "${manifest}: conformance CLI must remain an exact reviewed version"
  fi
  if ! grep -A2 -Fq -- "'@modelcontextprotocol/conformance@0.1.16':" "${lockfile}" \
    || ! grep -A2 -F "'@modelcontextprotocol/conformance@0.1.16':" "${lockfile}" | grep -Fq 'integrity: sha512-'; then
    report_failure "${lockfile}: conformance CLI package must retain registry integrity metadata"
  fi
  if grep -ERq --exclude-dir=node_modules \
    'npx[[:space:]].*@modelcontextprotocol/conformance' .github scripts tools; then
    report_failure "runtime npx fetching of the MCP conformance CLI is forbidden"
  fi
  grep -Fq -- 'pnpm install --frozen-lockfile --ignore-scripts' "${workflow}" \
    || report_failure "${workflow}: conformance CLI installation must use the frozen lockfile with lifecycle scripts disabled"
  grep -Fq -- 'pnpm --dir tools/mcp-conformance exec conformance' "${workflow}" \
    || report_failure "${workflow}: conformance scenarios must execute the installed locked CLI"
}

validate_production_image_preflight() {
  local policy_file='deploy/production-images.lock'
  local wrapper='scripts/production-compose.sh'
  local test_script='scripts/test-production-compose.sh'
  local service policy value field_count
  local entry_count=0

  [[ -x "${wrapper}" && -x "${test_script}" && -f "${policy_file}" ]] || {
    report_failure "production image preflight, policy, or mutation test is missing"
    return
  }
  # The nested read is intentional: it detects duplicate policies while the
  # outer loop validates each entry. Both file descriptors are read-only.
  # shellcheck disable=SC2094
  while IFS='|' read -r service policy value; do
    [[ -n "${service}" && "${service}" != \#* ]] || continue
    field_count="$(awk -F '|' -v service="${service}" '$0 !~ /^[[:space:]]*#/ && $1 == service { print NF }' "${policy_file}")"
    [[ "${field_count}" == 3 ]] \
      || report_failure "${policy_file}: malformed or duplicate policy for ${service}"
    case "${policy}" in
      exact)
        is_sha256_image_reference "${value}" \
          || report_failure "${policy_file}: ${service} exact policy is not tag@sha256 pinned"
        ;;
      repository)
        [[ "${value}" == ghcr.io/peprick/openscholar-* && "${value}" != *:* && "${value}" != *@* ]] \
          || report_failure "${policy_file}: ${service} application repository is outside the reviewed namespace"
        ;;
      *)
        report_failure "${policy_file}: unsupported policy ${policy} for ${service}"
        ;;
    esac
    entry_count=$((entry_count + 1))
  done <"${policy_file}"
  [[ "${entry_count}" -eq 7 ]] \
    || report_failure "${policy_file}: expected seven production service policies"
  grep -Fq -- '--profile observability config --services' "${wrapper}" \
    || report_failure "${wrapper}: preflight must resolve every profiled service"
  grep -Fq -- '--profile observability config --format json' "${wrapper}" \
    || report_failure "${wrapper}: preflight must render the resolved service model as JSON"
  if ! grep -Fq -- "--arg service \"\${service}\"" "${wrapper}" \
    || ! grep -Fq -- ".services[\$service].image" "${wrapper}"; then
    report_failure "${wrapper}: preflight must extract each service image from the resolved JSON model"
  fi
  if grep -Fq -- 'config --images' "${wrapper}"; then
    report_failure "${wrapper}: dependency-expanded config --images output cannot validate one service image"
  fi
}

validate_runtime_scan_coverage() {
  local line value reference security_workflow='.github/workflows/security.yml'

  [[ -f "${security_workflow}" ]] || {
    report_failure "${security_workflow} is missing"
    return
  }

  while IFS= read -r line || [[ -n "${line}" ]]; do
    value="$(printf '%s\n' "${line}" | sed -nE "s/^[[:space:]]*['\"]?image['\"]?[[:space:]]*:[[:space:]]*['\"]?([^[:space:]#'\"]+).*/\\1/p")"
    [[ "${value}" == "\${"*":-"*"}" ]] || continue
    reference="${value#*:-}"
    reference="${reference%\}}"
    is_sha256_image_reference "${reference}" || continue
    if ! grep -Fq -- "${reference}" "${security_workflow}"; then
      report_failure "${security_workflow}: production runtime image lacks a digest-matched Trivy scan: ${reference}"
    fi
  done < deploy/compose.production.yaml
}

validate_sarif_severity_gates() {
  local security_workflow='.github/workflows/security.yml'
  local sarif_count limited_count

  sarif_count="$(grep -Ec '^[[:space:]]+format:[[:space:]]+sarif([[:space:]#].*)?$' "${security_workflow}")"
  limited_count="$(grep -Ec '^[[:space:]]+limit-severities-for-sarif:[[:space:]]+true([[:space:]#].*)?$' "${security_workflow}")"
  [[ "${sarif_count}" -gt 0 && "${limited_count}" -eq "${sarif_count}" ]] \
    || report_failure "${security_workflow}: every SARIF scan must limit output and exit status to its declared severity gate"
}

validate_production_platform_policy() {
  local compose_file='deploy/compose.production.yaml'
  local security_workflow='.github/workflows/security.yml'
  local platform_count

  if grep -E '^[[:space:]]+platform:' "${compose_file}" \
    | grep -Ev '^[[:space:]]+platform:[[:space:]]+linux/amd64([[:space:]#].*)?$' >/dev/null; then
    report_failure "${compose_file}: every declared production platform must be linux/amd64"
  fi
  platform_count="$(grep -Ec '^[[:space:]]+platform:[[:space:]]+linux/amd64([[:space:]#].*)?$' "${compose_file}")"
  [[ "${platform_count}" -eq 2 ]] \
    || report_failure "${compose_file}: linux/amd64 must be declared once in the locked-down service anchor and once for PostgreSQL"
  awk '
    /^x-locked-down-service:/ { in_anchor = 1 }
    /^services:/ { in_anchor = 0 }
    in_anchor && /^[[:space:]]+platform:[[:space:]]+linux\/amd64([[:space:]#].*)?$/ { found = 1 }
    END { exit(found ? 0 : 1) }
  ' "${compose_file}" \
    || report_failure "${compose_file}: the locked-down service anchor must require linux/amd64"
  awk '
    /^  postgres:/ { in_postgres = 1; next }
    in_postgres && /^  [[:alnum:]_-]+:/ { in_postgres = 0 }
    in_postgres && /^    platform:[[:space:]]+linux\/amd64([[:space:]#].*)?$/ { found = 1 }
    END { exit(found ? 0 : 1) }
  ' "${compose_file}" \
    || report_failure "${compose_file}: PostgreSQL must require linux/amd64"

  [[ "$(grep -Fc -- 'DOCKER_DEFAULT_PLATFORM: linux/amd64' "${security_workflow}")" -eq 2 ]] \
    || report_failure "${security_workflow}: application and hardened builds must target linux/amd64 explicitly"
  [[ "$(grep -Fc -- 'TRIVY_PLATFORM: linux/amd64' "${security_workflow}")" -eq 3 ]] \
    || report_failure "${security_workflow}: every runtime scan job must target linux/amd64 explicitly"
  grep -Fq -- "docker build --platform linux/amd64 --file \"\${HARDENED_CONTEXT}/Dockerfile\"" "${security_workflow}" \
    || report_failure "${security_workflow}: hardened runtime builds must select linux/amd64 explicitly"
}

validate_hardened_runtime_builds() {
  local dockerfile context local_ref runtime_user test_command
  local security_workflow='.github/workflows/security.yml'
  local operations_validator='scripts/validate-operations.sh'

  while IFS='|' read -r context local_ref runtime_user test_command; do
    dockerfile="${context}/Dockerfile"
    [[ -f "${dockerfile}" && ! -L "${dockerfile}" ]] || {
      report_failure "${dockerfile}: hardened runtime Dockerfile is missing or is not regular"
      continue
    }
    grep -Fxq -- 'FROM source AS test' "${dockerfile}" \
      || report_failure "${dockerfile}: hardened runtime must retain a dedicated test stage"
    grep -Fxq -- 'FROM test AS build' "${dockerfile}" \
      || report_failure "${dockerfile}: final binary build must descend from the tested stage"
    grep -Fxq -- "${test_command}" "${dockerfile}" \
      || report_failure "${dockerfile}: exact fail-closed upstream test command is required"
    grep -Fxq -- 'FROM scratch' "${dockerfile}" \
      || report_failure "${dockerfile}: final runtime must remain scratch"
    awk '/^FROM / { final_from = $0 } END { exit(final_from == "FROM scratch" ? 0 : 1) }' "${dockerfile}" \
      || report_failure "${dockerfile}: scratch must remain the final runtime stage"
    grep -Fq -- 'COPY --from=build /out/rootfs/ /' "${dockerfile}" \
      || report_failure "${dockerfile}: final scratch image must copy only the reviewed rootfs"
    grep -Fxq -- "USER ${runtime_user}" "${dockerfile}" \
      || report_failure "${dockerfile}: final runtime must declare USER ${runtime_user}"
    grep -Fq -- "context: ${context}" "${security_workflow}" \
      || report_failure "${security_workflow}: ${dockerfile} is absent from the hardened build matrix"
    grep -Fq -- "local_ref: ${local_ref}" "${security_workflow}" \
      || report_failure "${security_workflow}: ${local_ref} is absent from the hardened scan matrix"
  done <<'EOF'
deploy/images/caddy|openscholar-caddy:security|10001:10001|RUN go test -count=1 -p 1 ./...
deploy/images/blackbox-exporter|openscholar-blackbox-exporter:security|65534:65534|RUN go test -count=1 ./...
EOF

  grep -Fq -- "image-ref: \${{ matrix.local_ref }}" "${security_workflow}" \
    || report_failure "${security_workflow}: hardened local images are not passed to Trivy"
  grep -Fq -- 'id: hardened_image_scan' "${security_workflow}" \
    || report_failure "${security_workflow}: hardened scan outcome gate is missing"
  if grep -Eq 'image:[[:space:]]+(caddy:|prom/blackbox-exporter:)' "${security_workflow}"; then
    report_failure "${security_workflow}: vulnerable upstream edge/probe images must not remain production scan targets"
  fi

  grep -Fq -- "docker build --file \"\${repository_directory}/deploy/images/caddy/Dockerfile\" --tag \"\${caddy_image}\" \"\${repository_directory}/deploy/images/caddy\"" "${operations_validator}" \
    || report_failure "${operations_validator}: operations validation must build the checked-in hardened Caddy image"
  grep -Fq -- "docker build --file \"\${repository_directory}/deploy/images/blackbox-exporter/Dockerfile\" --tag \"\${blackbox_image}\" \"\${repository_directory}/deploy/images/blackbox-exporter\"" "${operations_validator}" \
    || report_failure "${operations_validator}: operations validation must build the checked-in hardened blackbox-exporter image"
  if grep -Eq '(^|[="[:space:]])(caddy:|prom/blackbox-exporter:)' "${operations_validator}"; then
    report_failure "${operations_validator}: vulnerable upstream edge/probe images must not remain validation fallbacks"
  fi
}

cd -- "${repository_directory}"

validate_action_references
validate_checkout_credentials
validate_dockerfiles
validate_compose_images
validate_operations_validator_images
validate_workflow_image_references
validate_production_env_images
validate_maven_wrapper
validate_locked_mcp_conformance_cli
validate_production_image_preflight
validate_runtime_scan_coverage
validate_sarif_severity_gates
validate_production_platform_policy
validate_hardened_runtime_builds
scripts/validate-vulnerability-exceptions.sh

if [[ "${failures}" -ne 0 ]]; then
  exit 1
fi

printf 'Supply-chain validation passed: immutable Actions, images, wrapper, and scan coverage are consistent.\n'
