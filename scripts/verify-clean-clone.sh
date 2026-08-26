#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

source_script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
source_repository_directory="$(cd -- "${source_script_directory}/.." && pwd)"
repository_directory="${source_repository_directory}"

expected_pnpm_version="11.19.0"
verify_postgres_port="${OPENSCHOLAR_VERIFY_POSTGRES_PORT:-55432}"
verify_backend_port="${OPENSCHOLAR_VERIFY_BACKEND_PORT:-8180}"
verify_frontend_port="${OPENSCHOLAR_VERIFY_FRONTEND_PORT:-3300}"
verify_playwright_app_port="${OPENSCHOLAR_VERIFY_PLAYWRIGHT_APP_PORT:-3100}"
verify_playwright_fixture_port="${OPENSCHOLAR_VERIFY_PLAYWRIGHT_FIXTURE_PORT:-4100}"
verify_playwright_pwa_release_port="${OPENSCHOLAR_VERIFY_PLAYWRIGHT_PWA_RELEASE_PORT:-5100}"
verify_mcp_proxy_port="${OPENSCHOLAR_VERIFY_MCP_PROXY_PORT:-6277}"

original_home="${HOME:-}"
original_path="${PATH:-}"
original_tmp_directory="${TMPDIR:-/tmp}"

revision=""
short_revision=""
run_nonce=""
project_name=""
verify_mcp_key=""
verify_postgres_password=""
verify_temp_parent=""
verify_temp_directory=""
verify_checkout_directory=""
verify_home_directory=""
verify_tmp_directory=""
verify_pnpm_store_directory=""
verify_playwright_browsers_directory=""
verify_env_file=""
proxy_log_file=""
proxy_pid=""
stack_touched=false
validator_images_touched=false
validator_tag_suffix=""
validator_caddy_image=""
validator_blackbox_image=""
host_env=()
git_env=()
compose=()

usage() {
  cat <<'EOF'
Usage: scripts/verify-clean-clone.sh

Runs local clean-source verification for the current committed revision:
repository policy/operations guards, backend and frontend checks, standalone PWA
browser tests, an isolated Compose browser workflow, and MCP conformance/client
smokes. The command creates a detached clone and disposable HOME, and downloads
locked dependencies and Chromium when needed. Docker build layers may be reused.

Run only a trusted committed revision on a developer workstation. Backend tests
and Compose use the privileged local Docker socket; use a disposable runner for
untrusted pull-request revisions.

Optional loopback-port overrides:
  OPENSCHOLAR_VERIFY_POSTGRES_PORT
  OPENSCHOLAR_VERIFY_BACKEND_PORT
  OPENSCHOLAR_VERIFY_FRONTEND_PORT
  OPENSCHOLAR_VERIFY_PLAYWRIGHT_APP_PORT
  OPENSCHOLAR_VERIFY_PLAYWRIGHT_FIXTURE_PORT
  OPENSCHOLAR_VERIFY_PLAYWRIGHT_PWA_RELEASE_PORT
  OPENSCHOLAR_VERIFY_MCP_PROXY_PORT

This command intentionally does not replace CI-native security/SBOM gates,
image publication, or deployment-specific production evidence.
EOF
}

fail() {
  printf 'clean-source-verification: %s\n' "$*" >&2
  exit 1
}

run_stage() {
  local label="$1"
  shift
  printf '\n==> %s\n' "${label}"
  "$@"
}

run_in_directory() {
  local directory="$1"
  shift
  (
    cd -- "${directory}"
    "$@"
  )
}

require_command() {
  command -v "$1" >/dev/null 2>&1 || fail "$1 is required"
}

validate_port() {
  local name="$1"
  local value="$2"
  if ! [[ "${value}" =~ ^[0-9]+$ ]]; then
    fail "${name} must be an integer from 1024 to 65535"
  fi
  if (( value < 1024 || value > 65535 )); then
    fail "${name} must be an integer from 1024 to 65535"
  fi
}

assert_distinct_ports() {
  local ports=(
    "${verify_postgres_port}"
    "${verify_backend_port}"
    "${verify_frontend_port}"
    "${verify_playwright_app_port}"
    "${verify_playwright_fixture_port}"
    "${verify_playwright_pwa_release_port}"
    "${verify_mcp_proxy_port}"
  )
  local left
  local right
  for ((left = 0; left < ${#ports[@]}; left++)); do
    for ((right = left + 1; right < ${#ports[@]}; right++)); do
      [[ "${ports[left]}" != "${ports[right]}" ]] \
        || fail "verification ports must be distinct: ${ports[left]} is repeated"
    done
  done
}

assert_ports_available() {
  "${host_env[@]}" node -e '
    const net = require("node:net");
    const ports = process.argv.slice(1).map(Number);
    Promise.all(ports.map((port) => new Promise((resolve, reject) => {
      const server = net.createServer();
      server.unref();
      server.once("error", (error) => reject(new Error("127.0.0.1:" + port + ": " + error.code)));
      server.listen({ host: "127.0.0.1", port, exclusive: true }, () => server.close(resolve));
    }))).catch((error) => {
      process.stderr.write("clean-source-verification: loopback port unavailable: " + error.message + "\n");
      process.exitCode = 1;
    });
  ' "$@"
}

assert_clean_tree() {
  local directory="$1"
  local context="$2"
  local status_output
  status_output="$(git -C "${directory}" status \
    --porcelain=v1 --untracked-files=all --ignore-submodules=none)"
  if [[ -n "${status_output}" ]]; then
    printf '%s\n' "${status_output}" >&2
    fail "${context}: tracked or untracked files are present"
  fi
}

assert_no_tracked_environment_files() {
  local candidates=()
  local candidate
  shopt -s nullglob
  candidates=(
    .env
    .env.*
    backend/.env
    backend/.env.*
    frontend/.env
    frontend/.env.*
    deploy/production.env
  )
  shopt -u nullglob

  for candidate in "${candidates[@]}"; do
    [[ -e "${candidate}" || -L "${candidate}" ]] || continue
    [[ "$(basename -- "${candidate}")" == ".env.example" ]] && continue
    fail "tracked local or production environment file found: ${candidate}"
  done
}

random_hex() {
  "${host_env[@]}" node -e \
    'process.stdout.write(require("node:crypto").randomBytes(Number(process.argv[1])).toString("hex"))' \
    "$1"
}

stop_proxy() {
  if [[ -z "${proxy_pid}" ]]; then
    return
  fi
  if kill -0 "${proxy_pid}" 2>/dev/null; then
    kill "${proxy_pid}" 2>/dev/null || true
  fi
  wait "${proxy_pid}" 2>/dev/null || true
  proxy_pid=""
}

remove_stack() {
  local down_status=0
  if [[ "${stack_touched}" != true ]]; then
    return
  fi
  if "${compose[@]}" down --volumes --remove-orphans --rmi local; then
    stack_touched=false
    return
  else
    down_status=$?
  fi
  return "${down_status}"
}

remove_validator_images() {
  local image
  local removal_status=0

  if [[ "${validator_images_touched}" != true ]]; then
    return
  fi
  for image in "${validator_caddy_image}" "${validator_blackbox_image}"; do
    if "${host_env[@]}" docker image inspect "${image}" >/dev/null 2>&1; then
      "${host_env[@]}" docker image rm "${image}" || removal_status=$?
    fi
  done
  if [[ "${removal_status}" -eq 0 ]]; then
    validator_images_touched=false
  fi
  return "${removal_status}"
}

remove_temp_directory() {
  if [[ -z "${verify_temp_directory}" || ! -d "${verify_temp_directory}" ]]; then
    verify_temp_directory=""
    return
  fi
  case "${verify_temp_directory}" in
    "${verify_temp_parent}"/openscholar-clean-source.??????) ;;
    *)
      printf 'clean-source-verification: refusing unexpected temporary path: %s\n' \
        "${verify_temp_directory}" >&2
      return 1
      ;;
  esac
  if ! cd -- "${source_repository_directory}"; then
    cd -- /
  fi
  rm -rf -- "${verify_temp_directory}"
  verify_temp_directory=""
}

print_manual_cleanup() {
  if [[ -z "${project_name}" || -z "${verify_env_file}" ]]; then
    return
  fi
  printf 'Scoped recovery command: DOCKER_HOST=%q docker compose --project-name %q --env-file %q --file %q --file %q down --volumes --remove-orphans --rmi local\n' \
    "${docker_endpoint:-}" \
    "${project_name}" \
    "${verify_env_file}" \
    "${repository_directory}/compose.yaml" \
    "${repository_directory}/deploy/compose.e2e.yaml"
}

wait_for_http() {
  local label="$1"
  local url="$2"
  local attempt
  for ((attempt = 1; attempt <= 90; attempt++)); do
    if "${host_env[@]}" curl \
      --fail --silent --output /dev/null --connect-timeout 1 --max-time 2 \
      "${url}"; then
      return
    fi
    sleep 2
  done
  fail "${label} did not become ready at ${url}"
}

assert_proxy_alive() {
  [[ -n "${proxy_pid}" ]] || fail "the MCP conformance proxy is not running"
  kill -0 "${proxy_pid}" 2>/dev/null \
    || fail "the MCP conformance proxy stopped unexpectedly"
}

cleanup() {
  local status=$?
  local cleanup_status=0
  local preserve_temp=false
  trap - EXIT INT TERM HUP
  set +e

  stop_proxy
  if [[ "${stack_touched}" == true && "${status}" -ne 0 ]]; then
    "${compose[@]}" logs --no-color --tail 300 || true
  fi
  if ! remove_stack; then
    cleanup_status=1
    preserve_temp=true
  fi
  if ! remove_validator_images; then
    cleanup_status=1
    preserve_temp=true
  fi

  if [[ "${preserve_temp}" == false ]]; then
    remove_temp_directory || cleanup_status=$?
  else
    printf 'clean-source-verification: cleanup was incomplete; retained %s\n' \
      "${verify_temp_directory}" >&2
    print_manual_cleanup >&2
  fi

  if [[ "${status}" -eq 0 && "${cleanup_status}" -ne 0 ]]; then
    status="${cleanup_status}"
  fi
  exit "${status}"
}

start_proxy() {
  local ready=false
  local expected_log_line
  local http_code
  local attempt

  assert_ports_available "${verify_mcp_proxy_port}"
  : >"${proxy_log_file}"
  "${host_env[@]}" \
    "MCP_LOCAL_API_KEY=${verify_mcp_key}" \
    "MCP_CONFORMANCE_TARGET_ORIGIN=http://127.0.0.1:${verify_backend_port}" \
    "MCP_CONFORMANCE_PROXY_PORT=${verify_mcp_proxy_port}" \
    node scripts/mcp-conformance-proxy.mjs \
    >"${proxy_log_file}" 2>&1 &
  proxy_pid=$!
  expected_log_line="MCP conformance proxy listening on http://127.0.0.1:${verify_mcp_proxy_port}/mcp"

  for ((attempt = 1; attempt <= 40; attempt++)); do
    if ! kill -0 "${proxy_pid}" 2>/dev/null; then
      break
    fi
    if grep -Fqx "${expected_log_line}" "${proxy_log_file}"; then
      http_code="$("${host_env[@]}" curl \
        --silent --output /dev/null --write-out '%{http_code}' \
        --connect-timeout 1 --max-time 1 \
        "http://127.0.0.1:${verify_mcp_proxy_port}/not-found" || true)"
      if [[ "${http_code}" == "404" ]] && kill -0 "${proxy_pid}" 2>/dev/null; then
        ready=true
        break
      fi
    fi
    sleep 0.25
  done

  if [[ "${ready}" != true ]]; then
    tail -n 100 "${proxy_log_file}" >&2 || true
    fail "the MCP conformance proxy did not become ready"
  fi
}

assert_project_absent() {
  local containers
  local networks
  local volumes
  containers="$("${host_env[@]}" docker ps --all --quiet \
    --filter "label=com.docker.compose.project=${project_name}")"
  networks="$("${host_env[@]}" docker network ls --quiet \
    --filter "label=com.docker.compose.project=${project_name}")"
  volumes="$("${host_env[@]}" docker volume ls --quiet \
    --filter "label=com.docker.compose.project=${project_name}")"
  [[ -z "${containers}${networks}${volumes}" ]] \
    || fail "Docker resources already exist for generated project ${project_name}"
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]]; then
  usage
  exit 0
fi
[[ "$#" -eq 0 ]] || fail "no arguments are supported; use --help for usage"

trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
trap 'exit 129' HUP

cd -- "${source_repository_directory}"

for command_name in git java node pnpm docker jq curl perl tar grep tail ln; do
  require_command "${command_name}"
done
if ! command -v sha256sum >/dev/null 2>&1 \
  && ! command -v shasum >/dev/null 2>&1; then
  fail "sha256sum or shasum is required"
fi
[[ -n "${original_home}" ]] || fail "HOME is required to resolve the Docker context"
[[ -n "${original_path}" ]] || fail "PATH is required"

assert_clean_tree "${source_repository_directory}" \
  "verification requires a clean source checkout"
revision="$(git rev-parse --verify HEAD)"
short_revision="$(git rev-parse --short=12 HEAD)"

docker_context="$(docker context show)"
docker_endpoint="$(docker context inspect "${docker_context}" \
  --format '{{ (index .Endpoints "docker").Host }}')"
[[ "${docker_endpoint}" == unix://* ]] \
  || fail "the active Docker context must use a local Unix socket; found ${docker_endpoint}"
docker_client_plugins="$(docker info --format '{{json .ClientInfo.Plugins}}')"
compose_plugin_path="$(printf '%s' "${docker_client_plugins}" | jq -er \
  '[.[] | select(.Name == "compose")][0].Path // empty')"
buildx_plugin_path="$(printf '%s' "${docker_client_plugins}" | jq -er \
  '[.[] | select(.Name == "buildx")][0].Path // empty')"
[[ "${compose_plugin_path}" == /* && -x "${compose_plugin_path}" ]] \
  || fail "could not resolve an executable Docker Compose plugin"
[[ "${buildx_plugin_path}" == /* && -x "${buildx_plugin_path}" ]] \
  || fail "could not resolve an executable Docker Buildx plugin"

verify_temp_parent="${original_tmp_directory%/}"
if [[ -z "${verify_temp_parent}" || "${verify_temp_parent}" != /* \
  || "${verify_temp_parent}" == "/" ]]; then
  verify_temp_parent="/tmp"
fi
verify_temp_directory="$(mktemp -d \
  "${verify_temp_parent}/openscholar-clean-source.XXXXXX")"
verify_checkout_directory="${verify_temp_directory}/repository"
verify_home_directory="${verify_temp_directory}/home"
verify_tmp_directory="${verify_temp_directory}/tmp"
verify_pnpm_store_directory="${verify_temp_directory}/pnpm-store"
verify_playwright_browsers_directory="${verify_temp_directory}/playwright-browsers"
verify_env_file="${verify_temp_directory}/compose.env"
proxy_log_file="${verify_temp_directory}/mcp-proxy.log"

mkdir -p \
  "${verify_home_directory}/.cache" \
  "${verify_home_directory}/.config" \
  "${verify_home_directory}/.docker/cli-plugins" \
  "${verify_home_directory}/.local/share/pnpm" \
  "${verify_home_directory}/.m2/repository" \
  "${verify_tmp_directory}" \
  "${verify_pnpm_store_directory}" \
  "${verify_playwright_browsers_directory}"
: >"${verify_home_directory}/.npmrc"
chmod 0600 "${verify_home_directory}/.npmrc"
ln -s "${compose_plugin_path}" \
  "${verify_home_directory}/.docker/cli-plugins/docker-compose"
ln -s "${buildx_plugin_path}" \
  "${verify_home_directory}/.docker/cli-plugins/docker-buildx"

git_env=(
  env -i
  "HOME=${verify_home_directory}"
  "PATH=${original_path}"
  "TMPDIR=${verify_tmp_directory}"
  'LANG=C'
  'LC_ALL=C'
  'GIT_CONFIG_NOSYSTEM=1'
  'GIT_CONFIG_GLOBAL=/dev/null'
)
host_env=(
  env -i
  "HOME=${verify_home_directory}"
  "PATH=${original_path}"
  "TMPDIR=${verify_tmp_directory}"
  'CI=true'
  'TZ=UTC'
  'LANG=C'
  'LC_ALL=C'
  'NEXT_TELEMETRY_DISABLED=1'
  "XDG_CACHE_HOME=${verify_home_directory}/.cache"
  "XDG_CONFIG_HOME=${verify_home_directory}/.config"
  "XDG_DATA_HOME=${verify_home_directory}/.local/share"
  "MAVEN_USER_HOME=${verify_home_directory}/.m2"
  "NPM_CONFIG_USERCONFIG=${verify_home_directory}/.npmrc"
  "PNPM_HOME=${verify_home_directory}/.local/share/pnpm"
  "PLAYWRIGHT_BROWSERS_PATH=${verify_playwright_browsers_directory}"
  "DOCKER_HOST=${docker_endpoint}"
  'GIT_CONFIG_NOSYSTEM=1'
  'GIT_CONFIG_GLOBAL=/dev/null'
)

printf '%s\n' \
  'This verification downloads dependencies and executes trusted build/test code.' \
  'Workstation tokens and user package-manager configuration are not inherited.' \
  'Docker uses the active local Unix socket; Docker layers and build cache may be reused.'

run_stage "Create a detached clean-source clone" \
  "${git_env[@]}" git clone --quiet --no-local --no-checkout \
    "${source_repository_directory}" "${verify_checkout_directory}"
run_stage "Check out the committed revision" \
  "${git_env[@]}" git -C "${verify_checkout_directory}" \
    -c advice.detachedHead=false checkout --quiet --detach "${revision}"

repository_directory="${verify_checkout_directory}"
cd -- "${repository_directory}"
[[ "$(git rev-parse --verify HEAD)" == "${revision}" ]] \
  || fail "the detached clone did not check out ${revision}"
assert_clean_tree "${repository_directory}" "the detached clone is not clean"
assert_no_tracked_environment_files

node_major="$("${host_env[@]}" node -p \
  'Number(process.versions.node.split(".")[0])')"
node_version="$("${host_env[@]}" node --version)"
if ! [[ "${node_major}" =~ ^[0-9]+$ ]] || (( node_major < 24 )); then
  fail "Node.js 24 or newer is required"
fi
pnpm_version="$("${host_env[@]}" pnpm --version)"
[[ "${pnpm_version}" == "${expected_pnpm_version}" ]] \
  || fail "pnpm ${expected_pnpm_version} is required; found ${pnpm_version}"
java_version_output="$("${host_env[@]}" java -version 2>&1)"
[[ "${java_version_output}" =~ version\ \"([0-9]+) ]] \
  || fail "could not determine the Java major version"
java_major="${BASH_REMATCH[1]}"
(( java_major >= 21 )) || fail "Java 21 or newer is required"
"${host_env[@]}" docker info >/dev/null 2>&1 \
  || fail "the local Docker daemon is required"
"${host_env[@]}" docker compose version >/dev/null 2>&1 \
  || fail "Docker Compose v2 could not run from the isolated plugin config"
compose_up_help="$("${host_env[@]}" docker compose up --help)"
compose_version="$("${host_env[@]}" docker compose version --short)"
[[ "${compose_up_help}" == *"--wait-timeout"* ]] \
  || fail "Docker Compose must support up --wait-timeout"

validate_port OPENSCHOLAR_VERIFY_POSTGRES_PORT "${verify_postgres_port}"
validate_port OPENSCHOLAR_VERIFY_BACKEND_PORT "${verify_backend_port}"
validate_port OPENSCHOLAR_VERIFY_FRONTEND_PORT "${verify_frontend_port}"
validate_port OPENSCHOLAR_VERIFY_PLAYWRIGHT_APP_PORT "${verify_playwright_app_port}"
validate_port OPENSCHOLAR_VERIFY_PLAYWRIGHT_FIXTURE_PORT "${verify_playwright_fixture_port}"
validate_port OPENSCHOLAR_VERIFY_PLAYWRIGHT_PWA_RELEASE_PORT \
  "${verify_playwright_pwa_release_port}"
validate_port OPENSCHOLAR_VERIFY_MCP_PROXY_PORT "${verify_mcp_proxy_port}"
assert_distinct_ports
assert_ports_available \
  "${verify_postgres_port}" \
  "${verify_backend_port}" \
  "${verify_frontend_port}" \
  "${verify_playwright_app_port}" \
  "${verify_playwright_fixture_port}" \
  "${verify_playwright_pwa_release_port}" \
  "${verify_mcp_proxy_port}"

run_nonce="$(random_hex 8)"
verify_mcp_key="$(random_hex 32)"
verify_postgres_password="$(random_hex 32)"
project_name="openscholar-verify-${short_revision}-${run_nonce}"
validator_tag_suffix="verify-${run_nonce}"
validator_caddy_image="openscholar-caddy:operations-validation-${validator_tag_suffix}"
validator_blackbox_image="openscholar-blackbox-exporter:operations-validation-${validator_tag_suffix}"

printf '%s\n' \
  'POSTGRES_DB=openscholar_verify' \
  'POSTGRES_USER=openscholar_verify' \
  "POSTGRES_PASSWORD=${verify_postgres_password}" \
  "POSTGRES_PORT=${verify_postgres_port}" \
  "BACKEND_PORT=${verify_backend_port}" \
  "FRONTEND_PORT=${verify_frontend_port}" \
  'OIDC_SECURITY_ENABLED=false' \
  'OPENALEX_API_KEY=' \
  'EUROPE_PMC_ENABLED=false' \
  'DOAJ_ENABLED=false' \
  'CORE_ENABLED=false' \
  'CORE_LICENSE_CONFIRMED=false' \
  'CORE_API_KEY=' \
  'DATACITE_ENABLED=false' \
  'UNPAYWALL_EMAIL=' \
  'UNPAYWALL_BASE_URL=http://openalex-fixture:8080/disabled-unpaywall' \
  'ARXIV_BASE_URL=http://openalex-fixture:8080/disabled-arxiv' \
  "MCP_LOCAL_API_KEY=${verify_mcp_key}" \
  'REFRESH_JOBS_WORKER_ENABLED=false' \
  'REFRESH_JOBS_SCHEDULED_ENABLED=false' \
  'OPENSCHOLAR_AUTH_MODE=local' \
  >"${verify_env_file}"

compose=(
  "${host_env[@]}"
  docker compose
  --project-name "${project_name}"
  --env-file "${verify_env_file}"
  --file "${repository_directory}/compose.yaml"
  --file "${repository_directory}/deploy/compose.e2e.yaml"
)

if "${host_env[@]}" docker image inspect "${validator_caddy_image}" \
  >/dev/null 2>&1; then
  fail "generated validator image tag already exists: ${validator_caddy_image}"
fi
if "${host_env[@]}" docker image inspect "${validator_blackbox_image}" \
  >/dev/null 2>&1; then
  fail "generated validator image tag already exists: ${validator_blackbox_image}"
fi

run_stage "Validate documentation links" \
  "${host_env[@]}" node scripts/validate-docs.mjs
run_stage "Validate immutable supply-chain policy" \
  "${host_env[@]}" scripts/validate-supply-chain.sh
validator_images_touched=true
run_stage "Validate workflows, shell, Compose, and monitoring" \
  "${host_env[@]}" \
    "OPENSCHOLAR_OPERATIONS_VALIDATION_TAG_SUFFIX=${validator_tag_suffix}" \
    scripts/validate-operations.sh
run_stage "Exercise PostgreSQL operation guards" \
  "${host_env[@]}" scripts/test-postgres-operations.sh
run_stage "Exercise production Compose guards" \
  "${host_env[@]}" scripts/test-production-compose.sh
run_stage "Mutation-test supply-chain enforcement" \
  "${host_env[@]}" scripts/test-supply-chain-validator.sh
run_stage "Verify the Java backend from clean output" run_in_directory backend \
  "${host_env[@]}" ./mvnw --batch-mode --no-transfer-progress \
    "-Duser.home=${verify_home_directory}" \
    "-Dmaven.repo.local=${verify_home_directory}/.m2/repository" \
    clean verify

run_stage "Install the frozen frontend dependency graph" \
  "${host_env[@]}" pnpm --dir frontend install --frozen-lockfile \
    --store-dir "${verify_pnpm_store_directory}"
run_stage "Verify and build the frontend" \
  "${host_env[@]}" pnpm --dir frontend check
run_stage "Install the pinned Playwright Chromium runtime" \
  "${host_env[@]}" pnpm --dir frontend exec playwright install chromium
assert_ports_available \
  "${verify_playwright_app_port}" \
  "${verify_playwright_fixture_port}" \
  "${verify_playwright_pwa_release_port}"
run_stage "Run standalone PWA and offline browser workflows" \
  "${host_env[@]}" \
    "PLAYWRIGHT_APP_PORT=${verify_playwright_app_port}" \
    "PLAYWRIGHT_FIXTURE_PORT=${verify_playwright_fixture_port}" \
    "PLAYWRIGHT_PWA_RELEASE_PORT=${verify_playwright_pwa_release_port}" \
    pnpm --dir frontend test:e2e

run_stage "Install locked MCP validation tooling" \
  "${host_env[@]}" pnpm --dir tools/mcp-conformance install \
    --frozen-lockfile --ignore-scripts \
    --store-dir "${verify_pnpm_store_directory}"

assert_ports_available \
  "${verify_postgres_port}" "${verify_backend_port}" "${verify_frontend_port}"
assert_project_absent
printf 'Isolated Compose project: %s\n' "${project_name}"
print_manual_cleanup
stack_touched=true
run_stage "Build and start the isolated full stack" \
  "${compose[@]}" up --build --detach --wait --wait-timeout 180
run_stage "Wait for backend readiness" wait_for_http \
  backend "http://127.0.0.1:${verify_backend_port}/actuator/health/readiness"
run_stage "Wait for frontend readiness" wait_for_http \
  frontend "http://127.0.0.1:${verify_frontend_port}/"
run_stage "Run the Compose-backed browser workflow" \
  "${host_env[@]}" \
    "PLAYWRIGHT_COMPOSE_ORIGIN=http://127.0.0.1:${verify_frontend_port}" \
    pnpm --dir frontend test:e2e:compose
run_stage "Run the official MCP SDK client smoke" \
  "${host_env[@]}" \
    "MCP_LOCAL_API_KEY=${verify_mcp_key}" \
    "OPENSCHOLAR_SMOKE_MCP_URL=http://127.0.0.1:${verify_backend_port}/mcp" \
    "OPENSCHOLAR_SMOKE_API_ORIGIN=http://127.0.0.1:${verify_backend_port}/" \
    pnpm --dir tools/mcp-conformance run sdk-smoke

run_stage "Start the authenticated MCP conformance proxy" start_proxy
assert_proxy_alive
run_stage "Run MCP initialize conformance" \
  "${host_env[@]}" pnpm --dir tools/mcp-conformance exec conformance server \
    --url "http://127.0.0.1:${verify_mcp_proxy_port}/mcp" \
    --scenario server-initialize \
    --spec-version 2025-11-25
assert_proxy_alive
run_stage "Run MCP tool-discovery conformance" \
  "${host_env[@]}" pnpm --dir tools/mcp-conformance exec conformance server \
    --url "http://127.0.0.1:${verify_mcp_proxy_port}/mcp" \
    --scenario tools-list \
    --spec-version 2025-11-25

run_stage "Stop the MCP conformance proxy" stop_proxy
run_stage "Remove the isolated stack, images, and disposable volume" remove_stack
run_stage "Remove verifier-owned operations images" remove_validator_images
[[ "$(git rev-parse --verify HEAD)" == "${revision}" ]] \
  || fail "the detached verification HEAD changed during the run"
assert_clean_tree "${repository_directory}" \
  "verification changed committed or untracked clean-source content"
run_stage "Remove the disposable clean-source checkout" remove_temp_directory

printf '\nLocal clean-source verification passed for committed revision %s.\n' "${revision}"
printf '%s\n' \
  "Validated the documented local gates with Java ${java_major}, Node ${node_version}, pnpm ${pnpm_version}, and Docker Compose ${compose_version}." \
  'Dependency state used a disposable HOME; Docker layers and build cache may have been reused, so this was not a cold or bit-for-bit reproducible build.' \
  'Not covered here: remote/push state, CI-native secret/dependency/CodeQL/Trivy/SBOM gates, signed image publication, hosted OIDC, real backup/restore, alert delivery, load/pentest/DR, or provider/legal approval.'
