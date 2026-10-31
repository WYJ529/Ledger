# Ledger on Google Cloud

## Environment

- Owner's project: `cpen321-m1-pixel`.
- VM: `ledger-backend`, zone `us-west1-b`, e2-small (2 GB), Ubuntu 24.04.
- Static IP: `136.67.157.106`, reserved as `ledger-ip` in `us-west1`.
- HTTPS API/invitation origin: `https://136-67-157-106.sslip.io`.
- Source/config directory on VM: `/opt/ledger`.
- Network: `ledger-network`, custom subnet `ledger-subnet` (`10.50.0.0/24`).
- Public inbound: TCP 80/443 for Caddy; SSH only from Google's IAP range
  `35.235.240.0/20`. Backend 3000 and MongoDB 27017 have no host port mapping.
- No VM service account or Google Cloud API scopes are assigned.

This is an independent environment. The older M1 server referenced in the root
README is not modified. Local accounts, sessions and expenses are not migrated.
Register an account on this backend, or use the existing Google sign-up flow.

## Runtime

`compose.cloud.yaml` starts Caddy, the Node 22 production backend, and MongoDB 7.
The backend runs compiled JavaScript as the unprivileged `node` user, with no
development dependencies. MongoDB requires a random password generated on the VM
by `deploy/activate.sh`; it is stored only in root-readable `/opt/ledger/.env`.
Do not print that file, copy it into Git, or change its Mongo password after the
database volume has initialized without a separate database credential migration.

Docker volumes `ledger-cloud_mongo-data`, `ledger-cloud_caddy-data` and
`ledger-cloud_caddy-config` preserve database files and HTTPS certificates.
Containers restart automatically after a VM reboot. The boot disk is retained if
the VM is deleted; stop/delete actions can still interrupt service. No automatic
database backups are configured by this demo deployment.

The MongoDB root account is used only inside the private Compose network. A
separate least-privilege application database account can be introduced before a
larger production rollout.

Password registration sends a one-time code through the configured SMTP service.
Set `SMTP_HOST`, `SMTP_PORT` (587 for STARTTLS or 465 for TLS), `SMTP_FROM`, and,
when required by the provider, `SMTP_USER` and `SMTP_PASSWORD` in the VM's
root-only `.env`. The backend stores only a scrypt hash of each code; codes expire
after 10 minutes, can be tried five times, and can be resent after 60 seconds.
Google registration is unchanged and already supplies Google's verified email.
On first activation, export these SMTP variables alongside the existing cloud
settings; `deploy/activate.sh` saves them in the new root-only `.env`. For an
existing deployment, add them to its existing `.env` before activating this
version. Preserve its MongoDB password. Cloud Compose sets `TRUST_PROXY_HOPS=1`
because only Caddy is reachable publicly, allowing separate clients to retain
separate registration rate limits. Local/direct deployments keep the default 0.

For an interactive deployment smoke check, use two different, unused inbox
aliases you control. The script creates disposable accounts that remain in the
database; choose fresh aliases on every run. From the repository root:

```bash
SMOKE_OWNER_EMAIL='your-owner-alias@your-domain.com' \
SMOKE_MEMBER_EMAIL='your-member-alias@your-domain.com' \
node deploy/smoke-check.mjs https://136-67-157-106.sslip.io
```

Enter each six-digit code after its email arrives (terminal input is hidden).
The check rejects direct registration, verifies that an unverified account
cannot sign in, and completes registration through the normal public endpoints.
It never reads codes from MongoDB or exposes a production verification bypass.
Results are saved to ignored `build/cloud/smoke-result.json`; codes and sessions
are not included. Run `node --test deploy/smoke-registration.test.mjs` locally
to check the script's registration sequence without contacting the deployment.

## Android configuration

The developer's ignored `frontend/local.properties` now contains:

```properties
API_BASE_URL=https://136-67-157-106.sslip.io
APP_LINK_HOST=136-67-157-106.sslip.io
```

Stop and Run in Android Studio to install the rebuilt APK. Stored local sessions
will be rejected by the new database; sign in/register again. To return to local
development, use `API_BASE_URL=http://10.0.2.2:3000` and rebuild.

The backend's public Android association lists the current developer's debug
certificate SHA-256 fingerprint. Another team member's debug keystore or a release
APK needs its fingerprint added to `ANDROID_SHA256_FINGERPRINTS` in the VM `.env`,
followed by backend recreation. The Web OAuth client ID remains the team's
existing public ID. Google login also requires each APK's SHA-1 to be registered
in the corresponding Android OAuth client, as described in the root README.

For Android 12+ link verification after installing the new APK:

```text
adb shell pm verify-app-links --re-verify com.example.cpen321application
adb shell pm get-app-links com.example.cpen321application
```

Android verification is asynchronous. If a messaging app opens the browser,
the invitation fallback provides Open in Ledger, and Join group accepts pasted
links. See [group invitation testing](Group_Invitations.md).

## Administration through Cloud Shell

```bash
gcloud compute ssh ledger-backend --project=cpen321-m1-pixel --zone=us-west1-b --tunnel-through-iap
```

Inside the VM:

```bash
cd /opt/ledger
sudo docker compose -f compose.cloud.yaml ps
sudo docker compose -f compose.cloud.yaml logs --tail=50 backend caddy
```

Keep logs private if troubleshooting with real user data. To update, upload an
archive containing only the reviewed backend source, package manifests, TypeScript
configs, production Dockerfile, `compose.cloud.yaml` and `deploy/` files. Never
include `.env`, local properties, private keystores, `node_modules` or `.git`.
Transfer it with `gcloud compute scp --tunnel-through-iap`, extract into
`/opt/ledger`, preserve `.env`, and run:

```bash
sudo bash /opt/ledger/deploy/activate.sh
```

The unique creation/submission IDs and MongoDB indexes remain intact across
updates. Never use `docker compose down -v`; that removes persistent data.
Preserve a MongoDB backup before migrations or replacing database storage.

`backend/Dockerfile.production` uses Node 22/npm 10. Regenerate the lockfile with
that runtime if changing dependencies; npm 11 on the workstation can produce
entries that fail `npm ci` under npm 10. The production `proxy-addr` dependency is
patched to 2.0.8 for GHSA-jqcg-44mw-7w3h. Production audit has no known advisories
at deployment time; existing development-tool advisories remain outside the
runtime image.

## Costs and trial

The user approved this VM, 30 GB standard disk, static IPv4 and web ingress.
Base infrastructure is estimated at roughly USD 20/month continuously running;
traffic and actual regional/account pricing can change the bill. This is not a
hard spending cap. Check the project's billing account for current trial credits,
expiry and charges. Eligible usage on a Free Trial billing account consumes trial
credit; changing to a paid billing account is a separate account-owner decision.

Stopping the VM stops its CPU/RAM charges, but retained disks and the static IP
can still incur costs. The static IP is retained to keep invite links stable.
Do not release it casually: doing so can break the current domain and links.

Official references: [trial rules](https://cloud.google.com/signup-faqs),
[VM pricing](https://cloud.google.com/products/compute/pricing/general-purpose?hl=en),
[IP/network pricing](https://cloud.google.com/vpc/network-pricing),
[Docker on Ubuntu](https://docs.docker.com/engine/install/ubuntu/), and
[Android App Links](https://developer.android.com/training/app-links/verify-applinks).

## Verified October 19, 2026

- Cloud VM is Running; MongoDB and backend pass Docker health checks. Only Caddy
  publishes host ports, 80/443. HTTPS `/health` returns `{ "status": "ok" }` with
  normal certificate validation.
- Live HTTPS smoke check passed registration/login, invite preview without
  joining, repeated acceptance without duplicate members, Owner/Member roles,
  member rename/invite, rejected member deletion, expense create/edit/totals,
  owner deletion, invalidated invites and Android certificate association.
- The disposable test group is marked deleted and all test sessions logged out.
  Its two randomly named test accounts and hidden group history remain in this
  database; no local/teammate data was imported or removed.
- Production image builds under Node 22/npm 10; production dependency audit is
  clear. Backend tests: 115 passed. Android cloud debug APK and 19 unit tests
  passed; generated API URL and manifest domain match this deployment.
- Live Google account sign-in and Android's on-device domain verification need
  the user's Android Studio walkthrough. A valid hosted association file alone
  does not prove Android has verified the link on a particular device.

## Integrated backend update on 2026-10-19 Vancouver time

Deployed main commit `8c48ed48380048a185cfd216c3dc80867f579b6c` plus a local npm 10 lockfile repair. The original lockfile failed clean installation under Node 22.23.3/npm 10.9.9 because nested optional Google dependency entries were missing. The repaired `backend/package-lock.json` is retained locally and has not been committed or pushed.

- Origin remains `https://136-67-157-106.sslip.io`; the Android API URL does not need to change.
- Added the integrated `/api/ledger` metadata, historical FX and local text-PDF statement parsing services. OpenAI recognition remains unconfigured and unavailable; no API key was added and no paid recognition calls were made.
- Only the backend container was recreated. The existing VM `.env`, MongoDB volume and Caddy container were preserved. Personal ledger transactions remain in device SQLite; this update does not add account-linked storage or synchronization.
- Before updating, saved the old image as `ledger-cloud-backend:before-8c48ed4` and saved source and MongoDB backups in root-only `/opt/ledger-backups/8c48ed4/`. Keep the database backup private on the VM.
- Deployment source archive SHA-256: `76f8a1548cec47a2f668ab6371847d581ddfe3f6e6b50865b198313e75b97dec`.
- Local Node 22/npm 10 clean install, typecheck, build and all 203 tests across 14 suites passed. Production-only dependency audit reported zero known vulnerabilities; development/build dependencies still have audit findings.
- The production Docker image built successfully on the VM and the recreated backend passed its container health check.
- Live HTTPS checks passed for account and group route availability, metadata, historical USD/CAD FX (2025-01-05 request: 1.4411, effective 2025-01-03, Frankfurter/ECB), and actual PDF.js parsing of a generated synthetic Chase statement. Five expected transactions reconciled; the repayment was excluded.
- Verified the AI endpoint returns 503 with recognition unconfigured; no paid request occurred.
- Existing deployment smoke checks passed for registration/login, invitations and roles, expense creation/editing/shared totals, owner deletion, invalidated invitations, and Android association. Checks used two new randomly named synthetic accounts and their own group; the test group was hidden and sessions revoked afterward. Existing user data was not cleared. Synthetic account records and hidden test-group history remain.

For rollback of this image update (no schema migration was performed), use on the VM:

```bash
cd /opt/ledger
sudo docker image tag ledger-cloud-backend:before-8c48ed4 ledger-cloud-backend:latest
sudo docker compose -f compose.cloud.yaml up -d --no-deps --wait --wait-timeout 120 backend
```

Do not rebuild the latest source when rolling back to the saved image. Source restoration, if needed, uses `source-before.tar.gz` in the backup directory; preserve the current `.env`. A database restore is not required for an ordinary image rollback.
