#!/bin/sh
set -eu

umask 077

fail() {
  printf '%s\n' "holdout-tls-materials: $*" >&2
  exit 1
}

require_command() {
  command -v "$1" >/dev/null 2>&1 \
    || fail "required command is unavailable: $1"
}

for required_command in openssl postgres id sed; do
  require_command "${required_command}"
done

ca_directory="${HOLDOUT_CA_DIRECTORY:-/materials/ca}"
server_directory="${HOLDOUT_SERVER_DIRECTORY:-/materials/server}"
tls_private_directory="${HOLDOUT_TLS_PRIVATE_DIRECTORY:-/materials/tls-private}"
plaintext_private_directory="${HOLDOUT_PLAINTEXT_PRIVATE_DIRECTORY:-/materials/plaintext-private}"
runtime_secret_directory="${HOLDOUT_RUNTIME_SECRET_DIRECTORY:-/materials/runtime-secret}"
metadata_directory="${HOLDOUT_METADATA_DIRECTORY:-/materials/metadata}"
runner_target_directory="${HOLDOUT_RUNNER_TARGET_DIRECTORY:-/materials/runner-target}"
certificate_host="${HOLDOUT_CERTIFICATE_HOST:-ledger.holdout.test}"
runner_uid="${HOLDOUT_RUNNER_UID:-10001}"
runner_gid="${HOLDOUT_RUNNER_GID:-10001}"
postgres_uid="$(id -u postgres)"
postgres_gid="$(id -g postgres)"
working_directory="$(mktemp -d /tmp/openscholar-holdout-tls.XXXXXX)"

cleanup() {
  status=$?
  trap - EXIT HUP INT TERM
  if [ -n "${working_directory:-}" ] && [ -d "${working_directory}" ]; then
    rm -rf -- "${working_directory}" || true
  fi
  exit "${status}"
}
trap cleanup EXIT HUP INT TERM

case "${certificate_host}" in
  ''|*[!a-z0-9.-]*|.*|*.|*..*)
    fail "certificate host is not a closed lowercase DNS name"
    ;;
esac
case "${runner_uid}" in
  ''|*[!0-9]*) fail "runner UID must be numeric" ;;
esac
case "${runner_gid}" in
  ''|*[!0-9]*) fail "runner GID must be numeric" ;;
esac

for directory in \
  "${ca_directory}" \
  "${server_directory}" \
  "${tls_private_directory}" \
  "${plaintext_private_directory}" \
  "${runtime_secret_directory}" \
  "${metadata_directory}" \
  "${runner_target_directory}"; do
  [ -d "${directory}" ] || fail "required named-volume directory is absent"
  chmod 0700 "${directory}"
done

# The wrapper removes volumes after every invocation. Explicitly removing only
# known generated paths also makes a manually restarted initializer fail neither
# open nor into an unknown directory tree.
rm -f -- \
  "${ca_directory}/ca.crt" \
  "${ca_directory}/untrusted-ca.crt" \
  "${server_directory}/server.crt" \
  "${server_directory}/server.key" \
  "${tls_private_directory}/bootstrap-password" \
  "${tls_private_directory}/runtime-password" \
  "${plaintext_private_directory}/bootstrap-password" \
  "${plaintext_private_directory}/runtime-password" \
  "${runtime_secret_directory}/runtime-password" \
  "${runtime_secret_directory}/wrong-runtime-password" \
  "${metadata_directory}/server-version" \
  "${metadata_directory}/server-leaf-sha256"

runtime_password="$(openssl rand -hex 32)"
wrong_runtime_password="$(openssl rand -hex 32)"
tls_bootstrap_password="$(openssl rand -hex 32)"
plaintext_bootstrap_password="$(openssl rand -hex 32)"

[ "${runtime_password}" != "${wrong_runtime_password}" ] \
  || fail "random runtime passwords unexpectedly matched"

printf '%s' "${runtime_password}" \
  >"${runtime_secret_directory}/runtime-password"
printf '%s' "${wrong_runtime_password}" \
  >"${runtime_secret_directory}/wrong-runtime-password"
printf '%s' "${runtime_password}" \
  >"${tls_private_directory}/runtime-password"
printf '%s' "${runtime_password}" \
  >"${plaintext_private_directory}/runtime-password"
printf '%s' "${tls_bootstrap_password}" \
  >"${tls_private_directory}/bootstrap-password"
printf '%s' "${plaintext_bootstrap_password}" \
  >"${plaintext_private_directory}/bootstrap-password"

cat >"${working_directory}/server-extensions.cnf" <<EOF
basicConstraints=critical,CA:FALSE
keyUsage=critical,digitalSignature,keyEncipherment
extendedKeyUsage=serverAuth
subjectAltName=DNS:${certificate_host}
EOF

openssl req \
  -x509 \
  -newkey rsa:3072 \
  -nodes \
  -sha256 \
  -days 2 \
  -subj '/CN=OpenScholar holdout TLS test CA' \
  -keyout "${working_directory}/ca.key" \
  -out "${ca_directory}/ca.crt" \
  >/dev/null 2>&1

openssl req \
  -new \
  -newkey rsa:3072 \
  -nodes \
  -sha256 \
  -subj "/CN=${certificate_host}" \
  -keyout "${server_directory}/server.key" \
  -out "${working_directory}/server.csr" \
  >/dev/null 2>&1

openssl x509 \
  -req \
  -sha256 \
  -days 2 \
  -in "${working_directory}/server.csr" \
  -CA "${ca_directory}/ca.crt" \
  -CAkey "${working_directory}/ca.key" \
  -CAcreateserial \
  -extfile "${working_directory}/server-extensions.cnf" \
  -out "${server_directory}/server.crt" \
  >/dev/null 2>&1

openssl req \
  -x509 \
  -newkey rsa:3072 \
  -nodes \
  -sha256 \
  -days 2 \
  -subj '/CN=Untrusted OpenScholar holdout TLS test CA' \
  -keyout "${working_directory}/untrusted-ca.key" \
  -out "${ca_directory}/untrusted-ca.crt" \
  >/dev/null 2>&1

openssl verify \
  -CAfile "${ca_directory}/ca.crt" \
  "${server_directory}/server.crt" \
  >/dev/null 2>&1 \
  || fail "generated server certificate did not verify"
openssl x509 \
  -in "${server_directory}/server.crt" \
  -noout \
  -checkhost "${certificate_host}" \
  >/dev/null 2>&1 \
  || fail "generated server certificate has the wrong DNS SAN"

# Pin the canonical X.509 DER bytes, not the textual PEM encoding. The runner
# receives only this public digest; the private server-materials volume remains
# unavailable to it.
openssl x509 \
  -in "${server_directory}/server.crt" \
  -outform DER \
  -out "${working_directory}/server.der"
server_leaf_sha256="$(openssl dgst \
  -sha256 \
  -r \
  "${working_directory}/server.der" | sed 's/ .*$//')"
case "${server_leaf_sha256}" in
  ''|*[!0-9a-f]*)
    fail "generated server leaf SHA-256 has an invalid format"
    ;;
esac
[ "${#server_leaf_sha256}" -eq 64 ] \
  || fail "generated server leaf SHA-256 has an invalid length"
printf '%s' "${server_leaf_sha256}" \
  >"${metadata_directory}/server-leaf-sha256"

server_version="$(postgres --version | sed 's/^postgres (PostgreSQL) //')"
[ -n "${server_version}" ] || fail "PostgreSQL server version was empty"
printf '%s' "${server_version}" >"${metadata_directory}/server-version"

chown -R 0:0 "${ca_directory}" "${metadata_directory}"
chmod 0555 "${ca_directory}" "${metadata_directory}"
chmod 0444 \
  "${ca_directory}/ca.crt" \
  "${ca_directory}/untrusted-ca.crt" \
  "${metadata_directory}/server-version" \
  "${metadata_directory}/server-leaf-sha256"

chown -R "${postgres_uid}:${postgres_gid}" \
  "${server_directory}" \
  "${tls_private_directory}" \
  "${plaintext_private_directory}"
chmod 0700 \
  "${server_directory}" \
  "${tls_private_directory}" \
  "${plaintext_private_directory}"
chmod 0400 \
  "${server_directory}/server.key" \
  "${tls_private_directory}/bootstrap-password" \
  "${tls_private_directory}/runtime-password" \
  "${plaintext_private_directory}/bootstrap-password" \
  "${plaintext_private_directory}/runtime-password"
chmod 0444 "${server_directory}/server.crt"

chown -R "${runner_uid}:${runner_gid}" \
  "${runtime_secret_directory}" \
  "${runner_target_directory}"
chmod 0700 "${runtime_secret_directory}" "${runner_target_directory}"
chmod 0400 \
  "${runtime_secret_directory}/runtime-password" \
  "${runtime_secret_directory}/wrong-runtime-password"

# Drop all shell copies before the successful completion marker is emitted.
runtime_password=''
wrong_runtime_password=''
tls_bootstrap_password=''
plaintext_bootstrap_password=''
server_leaf_sha256=''

printf '%s\n' 'holdout-tls-materials: generated isolated runtime materials'
