#!/bin/sh
set -eu

read_secret() {
	secret_file=$1
	variable_name=$2
	if [ ! -r "$secret_file" ]; then
		echo "Required frontend secret is not readable: $secret_file" >&2
		exit 1
	fi
	secret_value=$(sed -e 's/[[:space:]]*$//' "$secret_file")
	if [ -z "$secret_value" ]; then
		echo "Required frontend secret is empty: $secret_file" >&2
		exit 1
	fi
	export "$variable_name=$secret_value"
}

auth_mode=${OPENSCHOLAR_AUTH_MODE:-local}
if [ "$auth_mode" = "local" ]; then
	exec "$@"
fi
if [ "$auth_mode" != "oidc" ]; then
	echo "OPENSCHOLAR_AUTH_MODE must be local or oidc." >&2
	exit 1
fi

read_secret /run/secrets/OPENSCHOLAR_AUTH_SESSION_SECRET OPENSCHOLAR_AUTH_SESSION_SECRET
read_secret /run/secrets/OPENSCHOLAR_OIDC_CLIENT_SECRET OPENSCHOLAR_OIDC_CLIENT_SECRET

exec "$@"
