#!/usr/bin/env bash
# Run in the owner's Cloud Shell only after approval of resources and ingress.
# Creates one isolated demo environment; it never modifies another app's VPC.
set -euo pipefail
cd "$(dirname "$0")/.."
PROJECT=cpen321-m1-pixel
REGION=us-west1
ZONE=us-west1-b
GCLOUD=(gcloud --project="$PROJECT" --quiet)
if ! "${GCLOUD[@]}" compute networks describe ledger-network >/dev/null 2>&1; then
    "${GCLOUD[@]}" compute networks create ledger-network --subnet-mode=custom
fi
if ! "${GCLOUD[@]}" compute networks subnets describe ledger-subnet --region="$REGION" >/dev/null 2>&1; then
    "${GCLOUD[@]}" compute networks subnets create ledger-subnet --network=ledger-network --range=10.50.0.0/24 --region="$REGION"
fi
if ! "${GCLOUD[@]}" compute firewall-rules describe ledger-web >/dev/null 2>&1; then
    "${GCLOUD[@]}" compute firewall-rules create ledger-web --network=ledger-network --direction=INGRESS --allow=tcp:80,tcp:443 --source-ranges=0.0.0.0/0 --target-tags=ledger-web
fi
if ! "${GCLOUD[@]}" compute firewall-rules describe ledger-iap-ssh >/dev/null 2>&1; then
    "${GCLOUD[@]}" compute firewall-rules create ledger-iap-ssh --network=ledger-network --direction=INGRESS --allow=tcp:22 --source-ranges=35.235.240.0/20 --target-tags=ledger-iap
fi
if ! "${GCLOUD[@]}" compute addresses describe ledger-ip --region="$REGION" >/dev/null 2>&1; then
    "${GCLOUD[@]}" compute addresses create ledger-ip --region="$REGION" --network-tier=PREMIUM
fi
LEDGER_IP=$("${GCLOUD[@]}" compute addresses describe ledger-ip --region="$REGION" --format='value(address)')
if ! "${GCLOUD[@]}" compute instances describe ledger-backend --zone="$ZONE" >/dev/null 2>&1; then
    "${GCLOUD[@]}" compute instances create ledger-backend --zone="$ZONE" --machine-type=e2-small \
        --image-family=ubuntu-2404-lts-amd64 --image-project=ubuntu-os-cloud \
        --boot-disk-size=30GB --boot-disk-type=pd-standard --no-boot-disk-auto-delete \
        --subnet=ledger-subnet --address="$LEDGER_IP" --tags=ledger-web,ledger-iap \
        --no-service-account --no-scopes --shielded-secure-boot \
        --metadata-from-file=startup-script=deploy/install-docker.sh
fi
printf '\nLedger VM: ledger-backend (%s), static IP: %s\n' "$ZONE" "$LEDGER_IP"
printf 'HTTPS host: %s.sslip.io\n' "${LEDGER_IP//./-}"
