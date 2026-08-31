#!/bin/sh
set -eu

umask 077

fail() {
  printf '%s\n' "holdout-tls-provision: $*" >&2
  exit 1
}

: "${POSTGRES_USER:?POSTGRES_USER is required}"
: "${PGDATA:?PGDATA is required}"

transport="${HOLDOUT_TRANSPORT:?HOLDOUT_TRANSPORT is required}"
private_directory="${HOLDOUT_PRIVATE_DIRECTORY:-/holdout/private}"
runtime_password_file="${private_directory}/runtime-password"
migration_file="${HOLDOUT_MIGRATION_FILE:-/holdout/bootstrap/V1__create_related_topic_reuse_first_run_ledger.sql}"

case "${transport}" in
  tls|plaintext)
    ;;
  *)
    fail "transport must be tls or plaintext"
    ;;
esac

[ -f "${runtime_password_file}" ] \
  || fail "runtime password file is absent"
[ -f "${migration_file}" ] \
  || fail "ledger migration file is absent"

runtime_password="$(dd if="${runtime_password_file}" bs=1024 count=1 2>/dev/null)"
case "${runtime_password}" in
  ''|*[!0-9a-f]*)
    fail "runtime password has an invalid closed format"
    ;;
esac
[ "${#runtime_password}" -eq 64 ] \
  || fail "runtime password has an invalid length"

{
  # The value is a validated 64-character lowercase hex string. Supplying it on
  # psql's standard input keeps it out of command arguments and the environment.
  printf "\\set runtime_password '%s'\n" "${runtime_password}"
  cat <<'SQL'
CREATE ROLE openscholar_holdout_ledger_bootstrap_v1
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOLOGIN
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1;
CREATE ROLE openscholar_holdout_ledger_owner_v1
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT NOLOGIN
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1;
CREATE ROLE openscholar_holdout_ledger_runtime_v1
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT LOGIN
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1
    PASSWORD :'runtime_password';
CREATE ROLE openscholar_holdout_ledger_auditor_v1
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOINHERIT LOGIN
    NOREPLICATION NOBYPASSRLS CONNECTION LIMIT -1;

REVOKE ALL PRIVILEGES ON DATABASE postgres FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE template0 FROM PUBLIC;
REVOKE ALL PRIVILEGES ON DATABASE template1 FROM PUBLIC;
SQL
} | psql \
  --username "${POSTGRES_USER}" \
  --dbname postgres \
  --no-password \
  --set ON_ERROR_STOP=1

createdb \
  --username "${POSTGRES_USER}" \
  --no-password \
  --owner openscholar_holdout_ledger_owner_v1 \
  --template template0 \
  openscholar_holdout_ledger_v1

psql \
  --username "${POSTGRES_USER}" \
  --dbname openscholar_holdout_ledger_v1 \
  --no-password \
  --set ON_ERROR_STOP=1 \
  --file "${migration_file}"

if [ "${transport}" = plaintext ]; then
  cat >"${PGDATA}/pg_hba.conf" <<'EOF'
# Deliberately plaintext SCRAM target used only to prove direct-TLS downgrade
# refusal. This service is reachable solely on the internal test network.
local   all                              postgres                                  trust
local   all                              all                                       reject
host    openscholar_holdout_ledger_v1    openscholar_holdout_ledger_runtime_v1      0.0.0.0/0    scram-sha-256
host    openscholar_holdout_ledger_v1    openscholar_holdout_ledger_runtime_v1      ::/0         scram-sha-256
host    all                              all                                       0.0.0.0/0    reject
host    all                              all                                       ::/0         reject
EOF
  chmod 0600 "${PGDATA}/pg_hba.conf"
fi

runtime_password=''
printf '%s\n' "holdout-tls-provision: provisioned ${transport} ledger target"
