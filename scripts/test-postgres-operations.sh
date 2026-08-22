#!/usr/bin/env bash
set -Eeuo pipefail

umask 077

script_directory="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"
backup_script="${script_directory}/postgres-backup.sh"
restore_script="${script_directory}/postgres-restore.sh"
test_driver="${script_directory}/$(basename -- "${BASH_SOURCE[0]}")"

mock_docker() {
  local mode="${POSTGRES_OPS_MOCK_MODE:-unexpected}"
  local log_file="${POSTGRES_OPS_MOCK_LOG:?POSTGRES_OPS_MOCK_LOG is required}"

  printf '%q ' docker "$@" >>"${log_file}"
  printf '\n' >>"${log_file}"

  [[ "${1:-}" == "compose" ]] || {
    printf 'mock docker received a non-Compose command\n' >&2
    return 97
  }
  shift
  while [[ "${1:-}" == "--env-file" || "${1:-}" == "-f" ]]; do
    shift 2
  done

  case "${1:-}" in
    ps)
      case "${mode}" in
        no-postgres)
          return 0
          ;;
        restore-active)
          printf 'postgres\nbackend\nfrontend\n'
          ;;
        *)
          printf 'postgres\n'
          ;;
      esac
      ;;
    exec)
      shift
      [[ "${1:-}" == "-T" ]] || return 96
      shift
      [[ "${1:-}" == "postgres" ]] || return 95
      shift
      # These patterns intentionally match container-side shell source literally.
      # shellcheck disable=SC2016
      case "$*" in
        *'pg_dump --username'*'--format=custom'*)
          [[ "${mode}" == "backup-success" ]] || return 94
          printf 'synthetic custom-format backup fixture\n'
          ;;
        'pg_restore --list')
          while IFS= read -r _line; do :; done
          ;;
        *'printf %s "$POSTGRES_DB"'*)
          if [[ "${mode}" == "restore-system-database" ]]; then
            printf 'postgres'
          else
            printf 'openscholar'
          fi
          ;;
        *'dropdb --username'*'createdb --username'*)
          [[ "${mode}" == "restore-success" ]] || return 93
          ;;
        *'pg_restore --username'*'--exit-on-error'*)
          [[ "${mode}" == "restore-success" ]] || return 92
          while IFS= read -r _line; do :; done
          ;;
        *'psql --username'*'SELECT 1'*)
          [[ "${mode}" == "restore-success" ]] || return 91
          ;;
        *)
          printf 'unexpected mocked Compose exec: %s\n' "$*" >&2
          return 90
          ;;
      esac
      ;;
    *)
      printf 'unexpected mocked Compose operation: %s\n' "${1:-<empty>}" >&2
      return 89
      ;;
  esac
}

if [[ "${1:-}" == "--mock-docker" ]]; then
  shift
  mock_docker "$@"
  exit
fi

fail() {
  printf 'postgres-operations-test: %s\n' "$*" >&2
  exit 1
}

expect_failure() {
  local name="$1"
  local expected="$2"
  shift 2
  local output
  local status

  set +e
  output="$("$@" 2>&1)"
  status=$?
  set -e

  [[ "${status}" -ne 0 ]] || fail "${name}: command unexpectedly succeeded"
  grep -Fq -- "${expected}" <<<"${output}" \
    || fail "${name}: expected failure text was absent: ${expected}"
  printf 'PASS: %s\n' "${name}"
}

calculate_sha256() {
  if command -v sha256sum >/dev/null 2>&1; then
    sha256sum -- "$1" | awk '{ print $1 }'
  else
    shasum -a 256 -- "$1" | awk '{ print $1 }'
  fi
}

[[ -x "${backup_script}" ]] || fail "backup script is missing or not executable"
[[ -x "${restore_script}" ]] || fail "restore script is missing or not executable"

test_root="$(mktemp -d "${TMPDIR:-/tmp}/openscholar-postgres-operations.XXXXXX")"
cleanup() {
  local status=$?
  if [[ -n "${test_root:-}" && -d "${test_root}" ]]; then
    rm -rf -- "${test_root}" || true
  fi
  return "${status}"
}
trap cleanup EXIT

fixture_directory="${test_root}/fixtures"
mock_bin_directory="${test_root}/bin"
backup_directory="${test_root}/backups"
compose_file="${fixture_directory}/compose.production.yaml"
compose_env_file="${fixture_directory}/production.env"
mock_log="${test_root}/mock-docker.log"
mkdir -p -- "${fixture_directory}" "${mock_bin_directory}" "${backup_directory}"
: >"${compose_file}"
: >"${compose_env_file}"
: >"${mock_log}"

# Preserve these variables for expansion when the generated wrapper executes.
# shellcheck disable=SC2016
printf '%s\n' \
  '#!/usr/bin/env bash' \
  'exec "${POSTGRES_OPS_TEST_DRIVER:?}" --mock-docker "$@"' \
  >"${mock_bin_directory}/docker"
chmod 0700 "${mock_bin_directory}/docker"

common_environment=(
  env
  "PATH=${mock_bin_directory}:${PATH}"
  "POSTGRES_OPS_TEST_DRIVER=${test_driver}"
  "POSTGRES_OPS_MOCK_LOG=${mock_log}"
  "COMPOSE_FILE=${compose_file}"
  "COMPOSE_ENV_FILE=${compose_env_file}"
)

unset BACKUP_DIR BACKUP_AGE_RECIPIENT ALLOW_UNENCRYPTED_BACKUP
unset RESTORE_FILE RESTORE_CHECKSUM_FILE CONFIRM_RESTORE RESTORE_TEMP_DIR

expect_failure \
  "backup rejects a relative destination" \
  "BACKUP_DIR must be absolute" \
  "${common_environment[@]}" \
  BACKUP_DIR=relative \
  ALLOW_UNENCRYPTED_BACKUP=true \
  "${backup_script}"

expect_failure \
  "backup requires encryption or an explicit exception" \
  "Set BACKUP_AGE_RECIPIENT" \
  "${common_environment[@]}" \
  "BACKUP_DIR=${backup_directory}" \
  "${backup_script}"

expect_failure \
  "backup refuses a stopped database" \
  "the postgres Compose service is not running" \
  "${common_environment[@]}" \
  POSTGRES_OPS_MOCK_MODE=no-postgres \
  "BACKUP_DIR=${backup_directory}" \
  ALLOW_UNENCRYPTED_BACKUP=true \
  "${backup_script}"

backup_output="$(
  "${common_environment[@]}" \
    POSTGRES_OPS_MOCK_MODE=backup-success \
    "BACKUP_DIR=${backup_directory}" \
    ALLOW_UNENCRYPTED_BACKUP=true \
    "${backup_script}"
)"
grep -Fq -- "Backup created:" <<<"${backup_output}" \
  || fail "mocked backup did not report success"
backup_file="$(find "${backup_directory}" -maxdepth 1 -type f -name '*.dump' -print)"
[[ -n "${backup_file}" && -s "${backup_file}" ]] \
  || fail "mocked backup did not create one non-empty dump"
[[ -s "${backup_file}.sha256" ]] \
  || fail "mocked backup did not create a checksum"
printf 'PASS: mocked backup success path creates a dump and checksum\n'

restore_file="${fixture_directory}/restore.dump"
restore_checksum_file="${restore_file}.sha256"
printf 'synthetic restore fixture\n' >"${restore_file}"
restore_digest="$(calculate_sha256 "${restore_file}")"
printf '%s  %s\n' "${restore_digest}" "$(basename -- "${restore_file}")" \
  >"${restore_checksum_file}"

expect_failure \
  "restore requires the exact confirmation" \
  "set CONFIRM_RESTORE=restore-openscholar" \
  "${common_environment[@]}" \
  "RESTORE_FILE=${restore_file}" \
  "${restore_script}"

restore_symlink="${fixture_directory}/restore-link.dump"
ln -s -- "${restore_file}" "${restore_symlink}"
expect_failure \
  "restore refuses a symlink payload" \
  "RESTORE_FILE must be a regular, non-symlink file" \
  "${common_environment[@]}" \
  CONFIRM_RESTORE=restore-openscholar \
  "RESTORE_FILE=${restore_symlink}" \
  "RESTORE_CHECKSUM_FILE=${restore_checksum_file}" \
  "${restore_script}"

mismatch_checksum_file="${fixture_directory}/mismatch.sha256"
printf '%064d  %s\n' 0 "$(basename -- "${restore_file}")" \
  >"${mismatch_checksum_file}"
expect_failure \
  "restore rejects a checksum mismatch" \
  "backup checksum mismatch" \
  "${common_environment[@]}" \
  CONFIRM_RESTORE=restore-openscholar \
  "RESTORE_FILE=${restore_file}" \
  "RESTORE_CHECKSUM_FILE=${mismatch_checksum_file}" \
  "${restore_script}"

expect_failure \
  "restore refuses running application clients" \
  "backend/frontend must be stopped before restore" \
  "${common_environment[@]}" \
  POSTGRES_OPS_MOCK_MODE=restore-active \
  CONFIRM_RESTORE=restore-openscholar \
  "RESTORE_FILE=${restore_file}" \
  "RESTORE_CHECKSUM_FILE=${restore_checksum_file}" \
  "${restore_script}"

expect_failure \
  "restore refuses a PostgreSQL system database" \
  "refusing to replace PostgreSQL system database postgres" \
  "${common_environment[@]}" \
  POSTGRES_OPS_MOCK_MODE=restore-system-database \
  CONFIRM_RESTORE=restore-openscholar \
  "RESTORE_FILE=${restore_file}" \
  "RESTORE_CHECKSUM_FILE=${restore_checksum_file}" \
  "${restore_script}"

: >"${mock_log}"
restore_output="$(
  "${common_environment[@]}" \
    POSTGRES_OPS_MOCK_MODE=restore-success \
    CONFIRM_RESTORE=restore-openscholar \
    "RESTORE_FILE=${restore_file}" \
    "RESTORE_CHECKSUM_FILE=${restore_checksum_file}" \
    "${restore_script}"
)"
grep -Fq -- "Restore completed." <<<"${restore_output}" \
  || fail "mocked restore did not report success"
grep -Fq -- "dropdb" "${mock_log}" \
  || fail "mocked restore did not reach the reviewed replacement command"
grep -Fq -- "pg_restore" "${mock_log}" \
  || fail "mocked restore did not validate and apply the fixture"
if grep -Fq -- "--force" "${mock_log}"; then
  fail "restore unexpectedly requested forced connection termination"
fi
printf 'PASS: mocked restore success path uses reviewed non-forcing commands\n'

printf 'PostgreSQL operation guard tests passed using only a fake Docker executable.\n'
