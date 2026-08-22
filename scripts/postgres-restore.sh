#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_directory="$(cd -- "${script_directory}/.." && pwd)"
production_compose_file="${COMPOSE_FILE:-${repository_directory}/deploy/compose.production.yaml}"
production_env_file="${COMPOSE_ENV_FILE:-${repository_directory}/deploy/production.env}"
restore_file="${RESTORE_FILE:-}"
checksum_file="${RESTORE_CHECKSUM_FILE:-${restore_file}.sha256}"
confirmation="${CONFIRM_RESTORE:-}"
restore_temp_directory="${RESTORE_TEMP_DIR:-/tmp}"
plaintext_path=""
temporary_plaintext="false"

fail() {
  printf 'restore: %s\n' "$*" >&2
  exit 1
}

cleanup() {
  local status=$?
  if [[ "${temporary_plaintext}" == "true" && -n "${plaintext_path}" ]]; then
    rm -f -- "${plaintext_path}" || true
  fi
  return "${status}"
}
trap cleanup EXIT

[[ "${confirmation}" == "restore-openscholar" ]] || fail "set CONFIRM_RESTORE=restore-openscholar after reading the runbook"
[[ -f "${production_compose_file}" ]] || fail "Compose file not found: ${production_compose_file}"
[[ -f "${production_env_file}" ]] || fail "Compose environment file not found: ${production_env_file}"
[[ -n "${restore_file}" && -f "${restore_file}" && ! -L "${restore_file}" ]] || fail "RESTORE_FILE must be a regular, non-symlink file"
[[ -f "${checksum_file}" && ! -L "${checksum_file}" ]] || fail "a regular checksum file is required: ${checksum_file}"
[[ -d "${restore_temp_directory}" ]] || fail "RESTORE_TEMP_DIR must already exist"
command -v docker >/dev/null 2>&1 || fail "docker is required"

restore_directory="$(cd -- "$(dirname -- "${restore_file}")" && pwd)"
restore_name="$(basename -- "${restore_file}")"
checksum_directory="$(cd -- "$(dirname -- "${checksum_file}")" && pwd)"
[[ "${restore_directory}" == "${checksum_directory}" ]] || fail "backup and checksum must be in the same directory"

expected_hash="$(awk 'NR == 1 { print $1 }' "${checksum_file}")"
[[ "${expected_hash}" =~ ^[[:xdigit:]]{64}$ ]] || fail "checksum file does not begin with one SHA-256 digest"
expected_hash="$(printf '%s' "${expected_hash}" | tr '[:upper:]' '[:lower:]')"
if command -v sha256sum >/dev/null 2>&1; then
  actual_hash="$(sha256sum -- "${restore_file}" | awk '{ print $1 }')"
elif command -v shasum >/dev/null 2>&1; then
  actual_hash="$(shasum -a 256 -- "${restore_file}" | awk '{ print $1 }')"
else
  fail "sha256sum or shasum is required"
fi
[[ "${actual_hash}" == "${expected_hash}" ]] || fail "backup checksum mismatch"

if [[ "${restore_name}" == *.age ]]; then
  command -v age >/dev/null 2>&1 || fail "age is required to decrypt this backup"
  plaintext_path="$(mktemp "${restore_temp_directory%/}/openscholar-restore.XXXXXX")"
  temporary_plaintext="true"
  chmod 0600 "${plaintext_path}"
  age --decrypt "${restore_file}" >"${plaintext_path}"
else
  plaintext_path="${restore_file}"
fi

[[ -s "${plaintext_path}" ]] || fail "the restore payload is empty"

compose=(docker compose --env-file "${production_env_file}" -f "${production_compose_file}")
running_services="$("${compose[@]}" ps --services --status running)"
grep -qx 'postgres' <<<"${running_services}" || fail "the postgres Compose service is not running"
if grep -Eq '^(backend|frontend)$' <<<"${running_services}"; then
  fail "backend/frontend must be stopped before restore; this script will not stop them automatically"
fi

"${compose[@]}" exec -T postgres pg_restore --list <"${plaintext_path}" >/dev/null

# The database variable expands inside the PostgreSQL container.
# shellcheck disable=SC2016
database_name="$("${compose[@]}" exec -T postgres sh -eu -c 'printf %s "$POSTGRES_DB"')"
case "${database_name}" in
  postgres | template0 | template1)
    fail "refusing to replace PostgreSQL system database ${database_name}"
    ;;
esac

printf 'Restoring will replace database %s. Services remain stopped afterward.\n' \
  "${database_name}"

# dropdb deliberately refuses active connections. The script does not terminate
# sessions, stop services, or force the drop on the operator's behalf.
# The database variables expand inside the PostgreSQL container.
# shellcheck disable=SC2016
"${compose[@]}" exec -T postgres sh -eu -c \
  'dropdb --username "$POSTGRES_USER" --if-exists "$POSTGRES_DB" && createdb --username "$POSTGRES_USER" --owner "$POSTGRES_USER" "$POSTGRES_DB"'

# shellcheck disable=SC2016
"${compose[@]}" exec -T postgres sh -eu -c \
  'pg_restore --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --exit-on-error --no-owner --no-acl' \
  <"${plaintext_path}"

# shellcheck disable=SC2016
"${compose[@]}" exec -T postgres sh -eu -c \
  'psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --no-psqlrc --set=ON_ERROR_STOP=1 --command="SELECT 1"' \
  >/dev/null

if [[ "${restore_file}" == *.age ]]; then
  rm -f -- "${plaintext_path}"
  plaintext_path=""
  temporary_plaintext="false"
fi
trap - EXIT

printf 'Restore completed. Verify Flyway history and application reads before restarting services.\n'
