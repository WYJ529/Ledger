#!/usr/bin/env bash
# Run as root on ledger-backend. Settings below are public; the DB secret stays on the VM.
set -euo pipefail
umask 077
cd /opt/ledger
if [ ! -f .env ]; then
    : "${GOOGLE_CLIENT_ID:?Set the existing Web OAuth client ID}"
    : "${LEDGER_HOST:?Set the static IP hostname}"
    : "${ANDROID_SHA256_FINGERPRINTS:?Set the installed APK certificate}"
    MONGO_PASSWORD=$(python3 -c 'import secrets; print(secrets.token_hex(32))')
    printf 'GOOGLE_CLIENT_ID=%s\nLEDGER_HOST=%s\nANDROID_SHA256_FINGERPRINTS=%s\nMONGO_PASSWORD=%s\n' \
        "$GOOGLE_CLIENT_ID" "$LEDGER_HOST" "$ANDROID_SHA256_FINGERPRINTS" "$MONGO_PASSWORD" > .env
    chmod 600 .env
fi
docker compose -f compose.cloud.yaml config --quiet
docker compose -f compose.cloud.yaml up --build -d --wait --wait-timeout 240
docker compose -f compose.cloud.yaml ps
