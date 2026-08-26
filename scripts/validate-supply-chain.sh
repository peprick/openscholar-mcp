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

# These checks intentionally match literal shell/GitHub expressions inside workflow files,
# and grouped conjunctions intentionally share one diagnostic for a single invariant.
# shellcheck disable=SC2015,SC2016,SC2126
validate_release_image_workflows() {
  local orchestrator='.github/workflows/release-images.yml'
  local worker='.github/workflows/release-one-image.yml'
  local request_case_line mkdir_line local_scan_line login_line push_line registry_scan_line
  local sign_line provenance_line sbom_line upload_line approve_line

  if [[ ! -f "${orchestrator}" || -L "${orchestrator}" \
    || ! -f "${worker}" || -L "${worker}" ]]; then
    report_failure "release image workflows are missing or are not regular files"
    return
  fi

  [[ "$(grep -Fc -- 'uses: ./.github/workflows/release-one-image.yml' "${orchestrator}")" -eq 4 ]] \
    || report_failure "${orchestrator}: exactly four static image jobs must call the closed worker"
  for image_key in backend frontend caddy blackbox-exporter; do
    [[ "$(grep -Ec "^[[:space:]]+image_key:[[:space:]]+${image_key}$" "${orchestrator}")" -eq 1 ]] \
      || report_failure "${orchestrator}: release image key ${image_key} must appear exactly once"
  done
  grep -Fq -- '- "v*.*.*"' "${orchestrator}" \
    || report_failure "${orchestrator}: stable tag events must reach the fail-closed release validator"
  grep -Fq -- 'workflow_dispatch:' "${orchestrator}" \
    || report_failure "${orchestrator}: protected manual release retry is missing"
  grep -Fq -- 'group: release-images' "${orchestrator}" \
    && grep -Fq -- 'cancel-in-progress: false' "${orchestrator}" \
    || report_failure "${orchestrator}: releases must serialize globally without cancellation"
  grep -Fq -- 'GITHUB_REPOSITORY}" != "peprick/openscholar-mcp' "${orchestrator}" \
    || report_failure "${orchestrator}: canonical repository guard is missing"
  grep -Fq -- 'GITHUB_REF_PROTECTED:-false}" != "true' "${orchestrator}" \
    || report_failure "${orchestrator}: protected release ref guard is missing"
  grep -Fq -- '"${GITHUB_REF}" == "refs/tags/${DISPATCH_RELEASE_TAG}"' "${orchestrator}" \
    || report_failure "${orchestrator}: manual retries must run from the exact protected release tag"
  grep -Fq -- '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)$' "${orchestrator}" \
    || report_failure "${orchestrator}: exact stable semantic-version guard is missing"
  grep -Fq -- 'refs/tags/${release_tag}^{commit}' "${orchestrator}" \
    && grep -Fq -- '"${tag_commit}" != "${GITHUB_SHA}"' "${orchestrator}" \
    || report_failure "${orchestrator}: release tag must resolve to the exact source commit"
  grep -Fq -- 'git merge-base --is-ancestor "${GITHUB_SHA}" refs/remotes/origin/main' "${orchestrator}" \
    || report_failure "${orchestrator}: release source must be reachable from origin/main"
  [[ "$(grep -Fc -- 'packages: write' "${orchestrator}")" -eq 4 \
    && "$(grep -Fc -- 'id-token: write' "${orchestrator}")" -eq 4 \
    && "$(grep -Fc -- 'attestations: write' "${orchestrator}")" -eq 4 ]] \
    || report_failure "${orchestrator}: each image caller needs only the declared release permissions"
  if grep -Fq -- 'secrets: inherit' "${orchestrator}" \
    || grep -Eq -- '(GHCR_TOKEN|PERSONAL_ACCESS_TOKEN|COSIGN_PASSWORD)' "${orchestrator}" "${worker}"; then
    report_failure "release image workflows must not inherit or require long-lived publication credentials"
  fi

  grep -Fq -- 'workflow_call:' "${worker}" \
    || report_failure "${worker}: worker must be callable only as a reusable workflow"
  grep -Fq -- 'environment: image-release' "${worker}" \
    || report_failure "${worker}: protected image-release environment is missing"
  grep -Fq -- 'GITHUB_REPOSITORY}" != "peprick/openscholar-mcp' "${worker}" \
    && grep -Fq -- 'GITHUB_REF_PROTECTED:-false}" != "true' "${worker}" \
    && grep -Fq -- '"${GITHUB_REF}" != "refs/tags/${RELEASE_TAG}"' "${worker}" \
    && grep -Fq -- '"${GITHUB_WORKFLOW_REF}" != "${expected_caller}"' "${worker}" \
    || report_failure "${worker}: direct calls must repeat the canonical caller, repository, and protected-tag guards"
  grep -Fq -- 'refs/tags/${RELEASE_TAG}^{commit}' "${worker}" \
    && grep -Fq -- '"${tag_commit}" != "${GITHUB_SHA}"' "${worker}" \
    && grep -Fq -- 'git merge-base --is-ancestor "${GITHUB_SHA}" refs/remotes/origin/main' "${worker}" \
    || report_failure "${worker}: direct calls must bind the stable tag to the exact main-reachable source"
  grep -Fq -- 'IMAGE_RELEASE_ENABLED: ${{ vars.IMAGE_RELEASE_ENABLED }}' "${worker}" \
    && grep -Fq -- '"${IMAGE_RELEASE_ENABLED}" != "true"' "${worker}" \
    || report_failure "${worker}: fail-closed release enablement guard is missing"
  if grep -Eq "^[[:space:]]*['\"]?if['\"]?[[:space:]]*:.*IMAGE_RELEASE_ENABLED" "${worker}"; then
    report_failure "${worker}: release enablement must fail, not skip the publishing job or step"
  fi
  if grep -Eq '^[[:space:]]{6}(context|dockerfile|repository):' "${worker}" \
    || grep -Eq '\$\{\{[[:space:]]*inputs[.](context|dockerfile|repository)' "${worker}"; then
    report_failure "${worker}: callers must not control a build context, Dockerfile, or repository"
  fi
  request_case_line="$({ grep -nF -- 'backend|frontend|caddy|blackbox-exporter)' "${worker}" || true; } | head -1 | cut -d: -f1)"
  mkdir_line="$({ grep -nF -- 'mkdir -p "${evidence_dir}"' "${worker}" || true; } | head -1 | cut -d: -f1)"
  if [[ -z "${request_case_line}" || -z "${mkdir_line}" \
    || "${request_case_line}" -ge "${mkdir_line}" ]] \
    || grep -Fq -- 'release-evidence/${{ inputs.image_key }}' "${worker}" \
    || ! grep -Fq -- 'path: ${{ steps.request.outputs.evidence_dir }}/' "${worker}"; then
    report_failure "${worker}: the image key must be closed before any evidence path or artifact is derived"
  fi

  while IFS='|' read -r image_key context dockerfile repository; do
    grep -Fq -- "${image_key})" "${worker}" \
      && grep -Fq -- "context=${context}" "${worker}" \
      && grep -Fq -- "dockerfile=${dockerfile}" "${worker}" \
      && grep -Fq -- "repository=${repository}" "${worker}" \
      || report_failure "${worker}: release image workflow repository mapping is incomplete for ${image_key}"
  done <<'EOF'
backend|backend|backend/Dockerfile|ghcr.io/peprick/openscholar-backend
frontend|frontend|frontend/Dockerfile|ghcr.io/peprick/openscholar-frontend
caddy|deploy/images/caddy|deploy/images/caddy/Dockerfile|ghcr.io/peprick/openscholar-caddy
blackbox-exporter|deploy/images/blackbox-exporter|deploy/images/blackbox-exporter/Dockerfile|ghcr.io/peprick/openscholar-blackbox-exporter
EOF

  [[ "$(grep -Fc -- 'docker build --pull' "${worker}")" -eq 1 ]] \
    || report_failure "${worker}: every runtime must be built exactly once by the closed worker"
  [[ "$(grep -Fc -- '--platform linux/amd64' "${worker}")" -eq 2 ]] \
    && grep -Fq -- 'DOCKER_DEFAULT_PLATFORM: linux/amd64' "${worker}" \
    && grep -Fq -- 'TRIVY_PLATFORM: linux/amd64' "${worker}" \
    || report_failure "${worker}: release images and scans must target only linux/amd64"
  grep -Fq -- '"${IMAGE_TAG}" != "sha-${GITHUB_SHA}"' "${worker}" \
    && grep -Fq -- 'printf '\''image_tag=sha-%s\n'\'' "${GITHUB_SHA}"' "${orchestrator}" \
    || report_failure "release image workflows must preserve the exact source-SHA tag guard"

  [[ "$(grep -Fc -- 'exit-code: "1"' "${worker}")" -eq 2 \
    && "$(grep -Fc -- 'severity: HIGH,CRITICAL' "${worker}")" -eq 2 \
    && "$(grep -Fc -- 'ignore-unfixed: true' "${worker}")" -eq 2 \
    && "$(grep -Fc -- 'limit-severities-for-sarif: true' "${worker}")" -eq 2 ]] \
    || report_failure "${worker}: local and registry fix-available high/critical scan gates must remain fail closed"
  [[ "$(grep -Fc -- 'image-ref: ${{ steps.publish.outputs.digest_ref }}' "${worker}")" -eq 2 \
    && "$(grep -Ec '^[[:space:]]+scanners: vuln$' "${worker}")" -eq 1 \
    && "$(grep -Fc -- 'severity: UNKNOWN,LOW,MEDIUM,HIGH,CRITICAL' "${worker}")" -eq 1 \
    && "$(grep -Fc -- 'ignore-unfixed: false' "${worker}")" -eq 1 \
    && "$(grep -Fc -- 'format: json' "${worker}")" -eq 1 \
    && "$(grep -Fc -- 'output: ${{ steps.image.outputs.evidence_dir }}/registry-vulnerabilities.json' "${worker}")" -eq 1 \
    && "$(grep -Fc -- 'exit-code: "0"' "${worker}")" -eq 1 ]] \
    || report_failure "${worker}: exact-digest vulnerability review evidence must include fix-available and unfixed findings at every severity"
  if grep -Eq "^[[:space:]]*['\"]?continue-on-error['\"]?[[:space:]]*:" "${worker}" \
    || grep -Fq -- '|| true' "${worker}" \
    || grep -Fq -- 'set +e' "${worker}" \
    || [[ "$(grep -Ec "^[[:space:]]*['\"]?if['\"]?[[:space:]]*:" "${worker}")" -ne 1 ]] \
    || ! grep -Fq -- "if: always() && steps.request.outcome == 'success'" "${worker}"; then
    report_failure "${worker}: release-critical steps must not be skippable or fail open"
  fi
  [[ "$(grep -Fc -- 'format: cyclonedx' "${worker}")" -eq 1 ]] \
    && grep -Fq -- 'sbom-path: ${{ steps.image.outputs.evidence_dir }}/image.cdx.json' "${worker}" \
    || report_failure "${worker}: digest-bound CycloneDX evidence is incomplete"
  [[ "$(grep -Fc -- 'image-ref: ${{ steps.publish.outputs.digest_ref }}' "${worker}")" -eq 2 ]] \
    && grep -Fq -- 'docker pull --platform linux/amd64 "${DIGEST_REF}"' "${worker}" \
    || report_failure "${worker}: returned registry digest is not pulled and rescanned"

  local_scan_line="$({ grep -nF -- 'output: ${{ steps.image.outputs.evidence_dir }}/local-image.sarif' "${worker}" || true; } | head -1 | cut -d: -f1)"
  login_line="$({ grep -nF -- 'uses: docker/login-action@' "${worker}" || true; } | head -1 | cut -d: -f1)"
  push_line="$({ grep -nF -- 'docker push "${PUBLISHED_REF}"' "${worker}" || true; } | head -1 | cut -d: -f1)"
  registry_scan_line="$({ grep -nF -- 'output: ${{ steps.image.outputs.evidence_dir }}/registry-image.sarif' "${worker}" || true; } | head -1 | cut -d: -f1)"
  review_scan_line="$({ grep -nF -- 'output: ${{ steps.image.outputs.evidence_dir }}/registry-vulnerabilities.json' "${worker}" || true; } | head -1 | cut -d: -f1)"
  sign_line="$({ grep -nF -- 'cosign sign --yes "${DIGEST_REF}"' "${worker}" || true; } | head -1 | cut -d: -f1)"
  provenance_line="$({ grep -nF -- 'id: provenance' "${worker}" || true; } | head -1 | cut -d: -f1)"
  sbom_line="$({ grep -nF -- 'id: sbom-attestation' "${worker}" || true; } | head -1 | cut -d: -f1)"
  upload_line="$({ grep -nF -- 'uses: actions/upload-artifact@' "${worker}" || true; } | head -1 | cut -d: -f1)"
  approve_line="$({ grep -nF -- 'id: approve' "${worker}" || true; } | head -1 | cut -d: -f1)"
  if [[ -z "${local_scan_line}" || -z "${login_line}" || -z "${push_line}" \
    || -z "${registry_scan_line}" || -z "${review_scan_line}" || -z "${sign_line}" \
    || -z "${provenance_line}" \
    || -z "${sbom_line}" || -z "${upload_line}" || -z "${approve_line}" \
    || "${local_scan_line}" -ge "${login_line}" \
    || "${login_line}" -ge "${push_line}" \
    || "${push_line}" -ge "${registry_scan_line}" \
    || "${registry_scan_line}" -ge "${review_scan_line}" \
    || "${review_scan_line}" -ge "${sign_line}" \
    || "${sign_line}" -ge "${provenance_line}" \
    || "${provenance_line}" -ge "${sbom_line}" \
    || "${sbom_line}" -ge "${upload_line}" \
    || "${upload_line}" -ge "${approve_line}" ]]; then
    report_failure "${worker}: scan, publish, rescan, sign, attest, retain, and approve order is unsafe"
  fi

  [[ "$(grep -Fc -- 'cosign sign --yes "${DIGEST_REF}"' "${worker}")" -eq 1 \
    && "$(grep -Fc -- 'release-one-image\\.yml@refs/tags/v[0-9]+' "${worker}")" -eq 1 \
    && "$(grep -Fc -- 'cosign-release: v3.1.3' "${worker}")" -eq 1 \
    && "$(grep -Fc -- 'uses: actions/attest@1e69f48acb82d1966a394da916b4c1698aa569d6 # v4.2.2' "${worker}")" -eq 2 \
    && "$(grep -Fc -- 'push-to-registry: true' "${worker}")" -eq 2 \
    && "$(grep -Fc -- 'create-storage-record: false' "${worker}")" -eq 2 ]] \
    || report_failure "${worker}: exact-digest signing and provenance/SBOM attestation chain is incomplete"
  grep -Fq -- 'deployment_ref: ${{ steps.approve.outputs.deployment_ref }}' "${worker}" \
    || report_failure "${worker}: deployment output must remain empty until the full evidence chain succeeds"
  [[ "$(grep -hF -- 'retention-days: 90' "${worker}" "${orchestrator}" | wc -l | tr -d '[:space:]')" -eq 2 ]] \
    && grep -Fq -- 'release-output/release-images.env' "${orchestrator}" \
    || report_failure "release image workflows must retain per-image evidence and the aggregate manifest for 90 days"
  while IFS='|' read -r variable repository; do
    grep -Fq -- "validate_ref ${variable} ${repository}" "${orchestrator}" \
      || report_failure "${orchestrator}: aggregate manifest must bind ${variable} to ${repository}"
  done <<'EOF'
BACKEND_IMAGE|ghcr.io/peprick/openscholar-backend
FRONTEND_IMAGE|ghcr.io/peprick/openscholar-frontend
CADDY_IMAGE|ghcr.io/peprick/openscholar-caddy
BLACKBOX_EXPORTER_IMAGE|ghcr.io/peprick/openscholar-blackbox-exporter
EOF
  for variable in BACKEND_IMAGE FRONTEND_IMAGE CADDY_IMAGE BLACKBOX_EXPORTER_IMAGE; do
    grep -Fq -- "printf '${variable}=%s\\n'" "${orchestrator}" \
      || report_failure "${orchestrator}: release-images.env is missing ${variable}"
  done
  if grep -Eq -- '(^|[[:space:]])(kubectl|helm|terraform|ssh|scp)[[:space:]]|docker[[:space:]]+compose|production-compose[.]sh|deploy/production[.]env' \
    "${orchestrator}" "${worker}"; then
    report_failure "release image workflows must produce evidence only and must not deploy"
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
validate_release_image_workflows
scripts/validate-vulnerability-exceptions.sh

if [[ "${failures}" -ne 0 ]]; then
  exit 1
fi

printf 'Supply-chain validation passed: immutable Actions, images, release evidence, wrapper, and scan coverage are consistent.\n'
