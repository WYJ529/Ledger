# Backend non-functional requirement tests

- `accessControl.test.ts`: NFR-1 Access Control and Privacy.
- `realTimeResponsiveness.test.ts`: NFR-2 Real-Time Responsiveness, at 100 group members and 200 item units.

Both suites use the real HTTP app, Socket.IO server and an isolated in-memory MongoDB. Run them from the repository root with `./scripts/run-backend-nfr-tests.ps1` or `bash scripts/run-backend-nfr-tests.sh`. The verification method and logs are in `documentation/Testing.md`.
