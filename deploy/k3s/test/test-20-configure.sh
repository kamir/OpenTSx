#!/usr/bin/env bash
# Runs deploy/k3s/20-configure.sh SAAS=true with SAAS_ADMIN_PASSWORD, SAAS_DB_PASSWORD and SAAS_SECRET_KEY at the
# limits the SaaS backend enforces (opentsx-saas-backend/app/core/config.py). Writes only into a temporary directory
# and needs no kubectl and no cluster.
#
#   bash deploy/k3s/test/test-20-configure.sh
set -euo pipefail
K3S_DIR="$(cd "$(dirname "$0")/.." && pwd)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
failures=0

configure() {
  local name="$1"; shift
  env -i PATH="$PATH" HOME="$WORK" KUBECTL=false \
    OPENTSX_ENV_FILE="$WORK/$name.env" OPENTSX_OVERLAY="$WORK/$name/overlays/local" \
    bash "$K3S_DIR/20-configure.sh" KAFSCALE_BOOTSTRAP=10.0.0.5:9092 SAAS=true "$@" > "$WORK/$name.out" 2>&1
}

refused() {
  local name="$1" message="$2"; shift 2
  if configure "$name" "$@"; then
    echo "FAIL $name: exit 0, expected the refusal: $message"; failures=$((failures + 1))
  elif ! grep -qF "$message" "$WORK/$name.out"; then
    echo "FAIL $name: expected the message: $message"; sed 's/^/    /' "$WORK/$name.out"; failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

accepted() {
  local name="$1" key="$2" value="$3"
  if ! configure "$name" "$key=$value"; then
    echo "FAIL $name: refused"; sed 's/^/    /' "$WORK/$name.out"; failures=$((failures + 1))
  elif ! grep -qxF "$key=$value" "$WORK/$name/overlays/local/saas-secrets.env"; then
    echo "FAIL $name: $key is not in saas-secrets.env"; failures=$((failures + 1))
  else
    echo "ok   $name"
  fi
}

refused admin-11-characters "SAAS_ADMIN_PASSWORD: shorter than 12 characters; set at least 12 characters" \
  SAAS_ADMIN_PASSWORD=abcdefghijk
accepted admin-12-characters SAAS_ADMIN_PASSWORD abcdefghijkl
refused admin-6-two-byte-characters "SAAS_ADMIN_PASSWORD: shorter than 12 characters; set at least 12 characters" \
  SAAS_ADMIN_PASSWORD="$(printf 'ä%.0s' {1..6})"
refused admin-73-bytes "SAAS_ADMIN_PASSWORD: longer than 72 bytes; set at most 72 bytes in UTF-8" \
  SAAS_ADMIN_PASSWORD="$(printf 'ä%.0s' {1..36})a"
accepted admin-72-bytes SAAS_ADMIN_PASSWORD "$(printf 'ä%.0s' {1..36})"
refused db-11-characters "SAAS_DB_PASSWORD: shorter than 12 characters; set at least 12 letters and digits" \
  SAAS_DB_PASSWORD=abcdefghijk
accepted db-12-characters SAAS_DB_PASSWORD abcdefghijkl
refused secret-16-two-byte-characters "SAAS_SECRET_KEY needs at least 32 characters" \
  SAAS_SECRET_KEY="$(printf 'ä%.0s' {1..16})"

(( failures == 0 )) || { echo "$failures failed"; exit 1; }
echo "all passed"
