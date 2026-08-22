#!/bin/sh
set -eu

script_directory=$(CDPATH='' cd -- "$(dirname -- "$0")" && pwd)
entrypoint="${script_directory}/../docker-entrypoint.sh"

fail() {
	printf 'docker-entrypoint-test: %s\n' "$*" >&2
	exit 1
}

default_output=$(unset OPENSCHOLAR_AUTH_MODE; "$entrypoint" /bin/sh -c 'printf default-entrypoint-ok')
[ "$default_output" = "default-entrypoint-ok" ] \
	|| fail "the default local mode did not execute the application command"

local_output=$(OPENSCHOLAR_AUTH_MODE=local "$entrypoint" /bin/sh -c 'printf local-entrypoint-ok')
[ "$local_output" = "local-entrypoint-ok" ] \
	|| fail "explicit local mode did not execute the application command"

if oidc_output=$(OPENSCHOLAR_AUTH_MODE=oidc "$entrypoint" /bin/true 2>&1); then
	fail "OIDC mode started without mounted secret files"
fi
case "$oidc_output" in
	'Required frontend secret is not readable: /run/secrets/OPENSCHOLAR_AUTH_SESSION_SECRET') ;;
	*) fail "OIDC mode returned an unexpected missing-secret error: ${oidc_output}" ;;
esac

if invalid_output=$(OPENSCHOLAR_AUTH_MODE=invalid "$entrypoint" /bin/true 2>&1); then
	fail "an unknown authentication mode was accepted"
fi
[ "$invalid_output" = "OPENSCHOLAR_AUTH_MODE must be local or oidc." ] \
	|| fail "unknown authentication mode returned an unexpected error: ${invalid_output}"

printf 'Validated default/local startup plus fail-closed OIDC and unknown-mode handling.\n'
