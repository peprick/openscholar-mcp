#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
repository_directory="$(cd -- "${script_directory}/.." && pwd)"
production_compose_file="${COMPOSE_FILE:-${repository_directory}/deploy/compose.production.yaml}"
production_env_file="${COMPOSE_ENV_FILE:-${repository_directory}/deploy/production.env}"
backup_directory="${BACKUP_DIR:-}"
age_recipient="${BACKUP_AGE_RECIPIENT:-}"
allow_unencrypted="${ALLOW_UNENCRYPTED_BACKUP:-false}"

fail() {
  printf 'backup: %s\n' "$*" >&2
  exit 1
}

[[ -f "${production_compose_file}" ]] || fail "Compose file not found: ${production_compose_file}"
[[ -f "${production_env_file}" ]] || fail "Compose environment file not found: ${production_env_file}"
[[ -n "${backup_directory}" ]] || fail "BACKUP_DIR is required and must be an absolute, dedicated backup directory"
[[ "${backup_directory}" = /* ]] || fail "BACKUP_DIR must be absolute"

if [[ -z "${age_recipient}" && "${allow_unencrypted}" != "true" ]]; then
  fail "Set BACKUP_AGE_RECIPIENT, or explicitly set ALLOW_UNENCRYPTED_BACKUP=true for encrypted storage"
fi

command -v docker >/dev/null 2>&1 || fail "docker is required"
if [[ -n "${age_recipient}" ]]; then
  command -v age >/dev/null 2>&1 || fail "age is required when BACKUP_AGE_RECIPIENT is set"
fi

mkdir -p -- "${backup_directory}"
backup_directory="$(cd -- "${backup_directory}" && pwd -P)"
[[ "${backup_directory}" != "/" ]] || fail "BACKUP_DIR must not resolve to the filesystem root"
chmod 0700 "${backup_directory}"

timestamp="$(date -u +%Y%m%dT%H%M%SZ)"
plain_name="openscholar-${timestamp}.dump"
plain_path="${backup_directory}/${plain_name}"
partial_path="${plain_path}.partial.$$"
final_path="${plain_path}"

[[ ! -e "${plain_path}" && ! -e "${plain_path}.age" ]] || fail "a backup with timestamp ${timestamp} already exists"

cleanup() {
  local status=$?
  rm -f -- "${partial_path}" "${plain_path}.age.partial.$$" || true
  return "${status}"
}
trap cleanup EXIT

compose=(docker compose --env-file "${production_env_file}" -f "${production_compose_file}")
running_services="$("${compose[@]}" ps --services --status running)"
grep -qx 'postgres' <<<"${running_services}" || fail "the postgres Compose service is not running"

# The database variables expand inside the PostgreSQL container, not in this shell.
# shellcheck disable=SC2016
"${compose[@]}" exec -T postgres sh -eu -c \
  'pg_dump --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --format=custom --compress=6 --no-owner --no-acl' \
  >"${partial_path}"

[[ -s "${partial_path}" ]] || fail "pg_dump produced an empty file"
"${compose[@]}" exec -T postgres pg_restore --list <"${partial_path}" >/dev/null
chmod 0600 "${partial_path}"

if [[ -n "${age_recipient}" ]]; then
  encrypted_path="${plain_path}.age"
  encrypted_partial="${encrypted_path}.partial.$$"
  age --encrypt --recipient "${age_recipient}" --output "${encrypted_partial}" "${partial_path}"
  [[ -s "${encrypted_partial}" ]] || fail "age produced an empty encrypted backup"
  chmod 0600 "${encrypted_partial}"
  mv -- "${encrypted_partial}" "${encrypted_path}"
  rm -f -- "${partial_path}"
  final_path="${encrypted_path}"
else
  mv -- "${partial_path}" "${plain_path}"
fi

final_directory="$(dirname -- "${final_path}")"
final_name="$(basename -- "${final_path}")"
if command -v sha256sum >/dev/null 2>&1; then
  (cd -- "${final_directory}" && sha256sum -- "${final_name}" >"${final_name}.sha256")
elif command -v shasum >/dev/null 2>&1; then
  (cd -- "${final_directory}" && shasum -a 256 -- "${final_name}" >"${final_name}.sha256")
else
  fail "sha256sum or shasum is required"
fi
chmod 0600 "${final_path}" "${final_path}.sha256"
trap - EXIT

printf 'Backup created: %s\n' "${final_path}"
printf 'Checksum created: %s\n' "${final_path}.sha256"
printf 'No retention deletion or remote upload was performed.\n'
