# M1 Historical Documentation

Archived from the repository README on 20 October 2026. These notes describe the earlier Pixel milestone, including its old server, disabled demo screens, and validation results. Use the [current Ledger README](../README.md) for current setup and deployment. Historical addresses and signing/build verification below do not establish the current release configuration.

# Pixel - CPEN 321 M1

Group bill splitting and summary (#16, #17, #19): see [Group expenses setup and API](../documentation/Group_Expenses.md).

Group creation, member roles and invitation links (#14/#15): see [Group invitations and Android testing](../documentation/Group_Invitations.md).

Ledger's independent cloud backend is `https://136-67-157-106.sslip.io`
([health check](https://136-67-157-106.sslip.io/health)). Configuration and maintenance:
[Google Cloud deployment](../documentation/Google_Cloud_Deployment.md). This environment
has its own database; the older M1 deployment below is unchanged.

Native Kotlin/Jetpack Compose frontend and Node.js/TypeScript backend for M1. The submitted release connects to `https://136-64-6-62.sslip.io` (public IP `136.64.6.62`).

## Requirements

Install the following before the frontend or backend setup steps:

- [git](https://git-scm.com/install/)


--- 

## Frontend Setup

### Requirements

- [Android Studio](https://developer.android.com/studio) (latest version)
- [Java 17](https://adoptium.net/temurin/releases/?version=17)
- [Android SDK](https://developer.android.com/studio#command-tools) Platform 35 for compilation, plus a Pixel 9 Android Baklava/API 36 emulator image

### Setup

1. **Open project**: Open the `frontend/` directory in Android Studio
2. **Sync Gradle**: Android Studio will automatically prompt you to sync the project. Click "Sync Now". You can also manually run `cd frontend && ./gradlew build` to trigger the sync and download the necessary dependencies.
3. **Configure Android SDK**: Install SDK Platform 35 and the Build Tools requested by Gradle. Compile/target SDK: 35; minimum SDK: 26. The test emulator runs API 36.
4. **Set up emulator/device**:
   - Create a new AVD (Android Virtual Device) by selecting Pixel 9 as the device and Android Baklava (API level 36) as the system image.
   - For M1, physical-device testing is not required according to the author's clarification from course staff.
5. **Setup app config**: Copy the example file, then fill in local values:
   ```bash
   cp frontend/local.properties.example frontend/local.properties
   ```
   If Android Studio has already created `local.properties`, preserve its `sdk.dir` rather than overwriting it. Set:
   - `sdk.dir`: path to your Android SDK. Android Studio usually writes this the first time you open `frontend/`. On Mac it is often `sdk.dir=/Users/<username>/Library/Android/sdk`.
   - `API_BASE_URL`: backend URL baked into the APK. For the submitted cloud setup, use `https://136-64-6-62.sslip.io`. For local debug development, use `http://10.0.2.2:3000` for the emulator (`10.0.2.2` is the host machine). For a physical device on the same Wi-Fi, use `http://<your-lan-ip>:3000`.
   - `GOOGLE_CLIENT_ID`: `543187156899-8i4lr8gsegf849ckevgjt1v3osipnei9.apps.googleusercontent.com` (Web OAuth client ID; see signing setup below). If the entry is missing or blank, the build uses this value automatically.


### Build and Run

- **Debug build**: Click the green play button in the toolbar, to compile the code, package a debug APK, and install it on the connected device or running emulator. Alternatively, from the project root, run `./scripts/run-frontend.sh`.
- **Release build**: Go to Build -> Generate Signed App Bundle or APK -> APK. Follow the on-screen instructions to create a key, and select the "release" build variant. You will then have to manually install the generated APK on your device or the running emulator.


### Backend Configuration

Ensure the backend server is running and update the base URL in the app configuration if needed.

---
## Backend Setup

You can run the backend in one of two ways:
* Locally via Node.js 
* Via Docker Compose

Both ways use the same `backend/.env` file (see below).

### Environment configuration

From the project root:

```bash
cp backend/.env.example backend/.env
```

Set at least:
- `PORT`: `3000`.
- `OWNER_FIRST_NAME`: `Joy`; `OWNER_LAST_NAME`: `Tao`.
- `GOOGLE_CLIENT_ID`: the same Web client ID as the frontend (shown above).
- `SERVER_PUBLIC_IP`: `136.64.6.62` for the current cloud deployment; leave empty for local development.
- `COURSE_WS_URL`: `wss://8.229.22.124`.
- `NODE_ENV`: `production` on the cloud or `development` locally.
- `MONGODB_URI`: MongoDB connection string for user accounts and sessions, e.g. `mongodb://localhost:27017/ledger`. Required; the server refuses to start without it. Docker Compose overrides it to use its own `mongo` service.

No JWT secret or OAuth client secret is needed: sessions are random bearer tokens whose SHA-256 hashes are stored in MongoDB.


### Option 1: Run locally

**Requirements:** 
- [Node.js](https://nodejs.org/en/download/) 22.x
- [npm](https://docs.npmjs.com/downloading-and-installing-node-js-and-npm) 10+
- [MongoDB](https://www.mongodb.com/try/download/community) 7+ running locally, reachable at `MONGODB_URI`

**Setup:** 
1. Install dependencies:

   ```bash
   cd backend
   npm ci --include=dev
   ```

2. **Development** (TypeScript with auto-reload):

   ```bash
   npm run dev
   ```

3. **Production build** (optional):

   ```bash
   npm run build
   npm start
   ```

### Option 2: Run with Docker Compose (optional template tooling)

The Compose stack starts the backend together with the MongoDB database used for user accounts.

**Requirements:** 
- [Docker](https://docs.docker.com/desktop/setup/install) and [Docker Compose](https://docs.docker.com/desktop/setup/install) v2.24+
- [curl](https://curl.se/download.html)

**Setup**
1. **Start** (from the project root):

   ```bash
   ./scripts/run-backend.sh
   ```

   Or run Compose directly:

   ```bash
   docker compose up --build -d
   ```

2. **Stop**:

   ```bash
   docker compose down
   ```

## M1 functionality

The app opens **My Expenses** and exposes **My Expenses**, **Group**, and **Me** in bottom navigation (see **Accounts and sign-in** below). The following M1-only screens are retained in source but their entry points/routes are disabled:
- Server info shows the backend's IP, time and developer name for the signed-in user.
- Live Updates connects through Socket.IO over WebSocket to this backend. The backend uses the Node.js built-in WebSocket client to receive the course pixel stream and forwards the original JSON text immediately.
- Timer accepts minutes and seconds, supports cancellation, and opens a three-question food quiz when it expires. The app scores sampled recipes against category, cuisine and pantry preferences, shows the top three with reasons and missing ingredients, and lets users expand full recipes or change their answers.
- The timer continues across in-app navigation and activity rotation. It is not a background alarm service; do not rely on it after force-stop or device reboot.

### Run locally on Windows

1. In `backend/`, create `.env` using the environment configuration above (including `GOOGLE_CLIENT_ID` and `MONGODB_URI`); leave `SERVER_PUBLIC_IP` empty for local development. Do not commit `.env`. Make sure MongoDB is running.
2. Run `npm.cmd ci --include=dev` if dependencies are missing, then `npm.cmd run dev`. Restart this process after editing `.env`.
3. Open `frontend/` in Android Studio. Keep `API_BASE_URL=http://10.0.2.2:3000` in `frontend/local.properties` for the emulator.
4. Build and run. Start recording in **My Expenses**, or tap **Group** or **Me** to sign in. Create an account with **New user? Sign up here!** (or sign in with Google). After sign-in, use **Me** for account details and **Group** to view or create groups. Personal records remain on the device and are not bound to the signed-in account.
5. For a physical device on the same Wi-Fi, use the development computer's LAN IP instead of `10.0.2.2`, then rebuild the APK. Allow the backend port through the local firewall if needed.

Local API routes:
- `GET /health`: backend health.
- `POST /api/auth/register`, `POST /api/auth/login`, `POST /api/auth/google`, `POST /api/auth/google/register`, `POST /api/auth/logout`, `GET /api/users/me`: accounts and sessions (see **Accounts and sign-in**).
- `GET /api/server/ip`: IP address and its source. Without `SERVER_PUBLIC_IP`, returns a clearly labelled local development address, not a claimed public address.
- `GET /api/server/time`: server time in `HH:mm:ss GMT+HH:mm` / `GMT-HH:mm` format and its ISO timestamp.
- `GET /api/server/name`: configured first and last name; missing configuration returns 503.

Validation completed by the author: fresh-clone build and execution using repository instructions/scripts; Google sign-in with a separate Google account; signed release APK on Pixel 9/API 36, including server information, successive live-pixel images, and the timer/quiz/Top 3 recipe flow. The author confirmed with course staff that M1 does not require physical-device testing.

Backend checks: `npm.cmd run typecheck`, `npm.cmd run build`, and `npm.cmd test -- --runInBand`.

### Live pixel stream

- Course upstream defaults to wss://8.229.22.124. Override COURSE_WS_URL only when testing a different upstream.
- The Socket.IO Java client uses WebSocket transport only (no HTTP polling). Its Android org.json dependency is excluded in favor of the Android platform implementation.
- One course connection is shared by current viewers. It closes after the last viewer leaves; reconnects use bounded exponential backoff.
- Pixel JSON is carried unchanged in the pixel event. Status messages use a separate stream-status event.
- The course protocol has no explicit frame marker. Android infers a new picture when the next pixel follows a gap of at least 3.5 seconds, based on the documented five-second pause. A long network stall can therefore also clear the canvas. An initial connection may join a picture in progress; wait for the next cycle to see a full picture.
- Leaving the page or putting the app in the background disconnects the viewer. Reopening resumes from a blank canvas. Reconnect is available for manual recovery.
- TLS verification is enabled for the course upstream; do not disable it globally. Cloud reverse-proxy configuration must forward Socket.IO WebSocket upgrades at /socket.io/.
- Backend build and 22 tests passed on the cloud VM with Node 22.23.2. Use Node 22.x as specified in package.json.

### Google sign-in setup

Use the same Google Cloud project for both OAuth clients. Configure Google Auth Platform branding/audience first (add permitted test users if the console requires them).

1. Create an Android OAuth client with package com.example.cpen321application and the SHA-1 of the key signing the APK you will run.
2. For the current local debug keystore, SHA-1 is 5A:00:A8:42:E2:BA:73:55:4E:47:3C:71:BC:B2:EF:FE:64:D0:FB:B1. The submitted release certificate SHA-1 is `1C:D3:DF:36:29:07:85:6F:94:9C:B6:D4:E3:E3:17:9E:B8:4D:A9:02`. A different keystore requires another Android client registration. Every developer's debug keystore has its own SHA-1; if it is not registered, tapping **Sign in with Google** shows the exact SHA-1 to add (Google error 28444).
3. Create a Web application OAuth client in the same project. Its Client ID (not Client Secret, and not the Android client ID) is GOOGLE_CLIENT_ID in both frontend/local.properties and backend/.env.
4. Restart the backend, sync Gradle and rebuild the APK. Run on an emulator with Google Play services and a Google account. **Sign in with Google** on the sign-in screen launches Google sign-in.
5. Credential Manager returns an ID token. POST /api/auth/google validates it using google-auth-library (signature, audience, issuer and expiry) and requires a verified Google email. The Google token is not saved or logged.
6. Local HTTP credential transport is restricted to debug builds. Use HTTPS for the final cloud APK. No Firebase or OAuth client secret is required.

### Accounts and sign-in

- **Sign in** (issue #22): opening Group or Me while signed out shows a screen accepting an email and password, or **Sign in with Google**, and linking to sign-up with "New user? Sign up here!". **Use without Login** returns to My Expenses. A successful sign-in opens My Expenses; an active invitation continues to its preview.
- **Sign up**: name, email, password (8-128 characters) and confirmation. Passwords are hashed with scrypt and a per-user salt; emails are stored lower-case and must be unique.
- **Google accounts** (issues #1, #21): after Google verifies the user, the backend looks up the Google ID. An existing account is signed in directly; otherwise the app shows a "Finish creating your account" step prefilled with the Google name, and `POST /api/auth/google/register` creates the account. A Google email that already belongs to a password account is refused (409) rather than linked automatically, because sign-up does not verify email ownership.
- **Sessions** (issue #25): every sign-in returns a random bearer token valid for 7 days. Only its SHA-256 hash is stored in the `sessions` collection, which has a TTL index. The app keeps the token in private storage excluded from backup and checks it with `GET /api/users/me` on launch. If the server rejects it, Group and Me require sign-in again; My Expenses remains available.
- **Account and logout** (issues #23, #25): **Me** shows the name, email, sign-in method, member-since date and session expiry from `GET /api/users/me`, with automatic loading and no manual Refresh or Create group button. **Log out** asks for confirmation, revokes the session with `POST /api/auth/logout`, clears the stored token and the Google account choice, and returns to My Expenses.
- **Database** (issue #24): MongoDB through Mongoose. `users` holds email (unique), display name, optional password hash and optional Google ID (unique, sparse); `sessions` holds token hash, user ID and expiry.
- **Tests**: `tests/mock/` replaces Google verification and the user database; `tests/no-mock/accountAuth.test.ts` runs against a real `mongod` started by mongodb-memory-server (downloaded on first run); `tests/unit/` covers hashing and validation. Android validation rules are unit-tested in `AccountValidationTest`.

Official references: https://developer.android.com/identity/sign-in/credential-manager-siwg-implementation and https://developers.google.com/identity/sign-in/android/backend-auth

### Timer recipe surprise (TheMealDB)

When the countdown expires, the user answers three quick questions: preferred meal category, cuisine and ingredients on hand. Each question permits no preference or an empty selection. The frontend fetches candidate IDs from TheMealDB's category/area `filter.php` endpoints and full details from `lookup.php?i=...`, using public educational key 1. It samples at most 12 unique candidates and loads details with at most three concurrent requests. When category and cuisine are both unrestricted, it samples one of five meal categories. Results are explicitly a sample, not a global search. No backend update, new library, database, or private API key is required. Official API details: https://www.themealdb.com/api.php.

Our Kotlin ranking algorithm awards 40 points for matching category, 30 for matching cuisine and up to 30 for pantry coverage (`floor(30 * matched unique ingredients / listed unique ingredients)`). Rankings break ties by name and ID. Scores are not probabilities. Each top-three card explains its score and lists recipe ingredients not selected on hand. Category/cuisine are soft preferences, not exclusion filters. Conservative aliases match common forms such as eggs/egg or basmati rice/rice, without treating rice vinegar as rice. Other ingredient variants may be missed; this is not an allergy or dietary-safety checker. No cooking-time or nutrition estimates are invented.

Users can expand any recommended meal for its photo, measured ingredients, original instructions and source link, or change their answers and generate a new sample. There is no feedback-learning feature or persistent user profile. Quiz choices survive activity recreation; candidates reload instead of storing large recipe payloads in Android's saved-state Bundle. Partial fetch failures show the available results with a notice; a complete failure offers retry. Missing photos do not hide recipe text. Internet access and TheMealDB availability are required.

Manual acceptance: set a 5-second timer; complete the three questions; check top-three scores, reasons and pantry lists; expand a recipe and its source link; change answers; try unrestricted/empty choices; test offline retry and rotation. Confirm Google sign-in and Live Updates still work. Run `:app:testDebugUnitTest` for ranking tests (preference changes, ingredient alias boundaries, deduplication, empty data and deterministic ties). The updated signed release APK has been tested by the author. Rebuild and retest it after future application code changes.
