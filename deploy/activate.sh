#!/usr/bin/env bash
# Run as root on ledger-backend. DB and SMTP secrets stay in the VM's root-only .env.
set -euo pipefail
umask 077
cd /opt/ledger
if [ ! -f .env ]; then
    : "${GOOGLE_CLIENT_ID:?Set the existing Web OAuth client ID}"
    : "${LEDGER_HOST:?Set the static IP hostname}"
    : "${ANDROID_SHA256_FINGERPRINTS:?Set the installed APK certificate}"
    : "${SMTP_HOST:?Set the SMTP server hostname}"
    : "${SMTP_FROM:?Set the authorized SMTP sender address}"
    export GOOGLE_CLIENT_ID LEDGER_HOST ANDROID_SHA256_FINGERPRINTS SMTP_HOST SMTP_FROM
    export SMTP_PORT="${SMTP_PORT:-587}" SMTP_USER="${SMTP_USER:-}" SMTP_PASSWORD="${SMTP_PASSWORD:-}"
    python3 - <<'PY'
import os
import secrets
from pathlib import Path

keys = ('GOOGLE_CLIENT_ID', 'LEDGER_HOST', 'ANDROID_SHA256_FINGERPRINTS',
        'SMTP_HOST', 'SMTP_PORT', 'SMTP_USER', 'SMTP_PASSWORD', 'SMTP_FROM')
values = {key: os.environ[key] for key in keys}
values['MONGO_PASSWORD'] = secrets.token_hex(32)
# Compose single-quoted values preserve dollar signs and hash characters in credentials.
def quote(value):
    if '\n' in value or '\r' in value or '\\' in value:
        raise SystemExit('Initial configuration must use single-line values without backslashes; configure .env manually otherwise.')
    return "'" + value.replace("'", "\\'") + "'"

content = ''.join(f'{key}={quote(value)}\n' for key, value in values.items())
with Path('.env').open('x') as output:
    output.write(content)
PY
    chmod 600 .env
fi
docker compose -f compose.cloud.yaml config --quiet
docker compose -f compose.cloud.yaml up --build -d --wait --wait-timeout 240
docker compose -f compose.cloud.yaml ps
