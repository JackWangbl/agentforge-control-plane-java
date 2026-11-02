#!/usr/bin/env sh

set -eu

project_dir=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
secrets_dir="$project_dir/.docker-secrets"

random_secret() {
    if command -v openssl >/dev/null 2>&1; then
        openssl rand -hex 32
        return
    fi
    if command -v od >/dev/null 2>&1; then
        od -An -N32 -tx1 /dev/urandom | tr -d ' \n'
        printf '\n'
        return
    fi
    echo "Error: openssl or od is required to generate local Docker secrets." >&2
    exit 1
}

ensure_secret() {
    secret_file="$1"
    if [ ! -s "$secret_file" ]; then
        random_secret >"$secret_file"
    fi
    chmod 600 "$secret_file"
}

umask 077
mkdir -p "$secrets_dir"
ensure_secret "$secrets_dir/db_password"
ensure_secret "$secrets_dir/db_root_password"

echo "Local Docker secrets are ready in .docker-secrets/ (mode 600, ignored by Git)."

if [ "${1:-}" = "--prepare-only" ]; then
    exit 0
fi

cd "$project_dir"
exec docker compose up --build "$@"

