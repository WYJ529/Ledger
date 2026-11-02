# Frontend non-functional requirement tests

No dedicated frontend NFR acceptance test currently exists here. This directory preserves the course template; it does not establish that an NFR has been verified.

If a selected NFR is implemented in the frontend, add its Android `*Test.kt` class here with package `com.example.cpen321application.nfr` and document the acceptance criteria and logs in `documentation/Testing.md`. Do not move mocked UI tests here solely because they touch security or persistence.

The course runner is `scripts/run-frontend-nfr-tests.ps1` (or `.sh`). `-ListOnly` / `--list-only` reports the empty suite without starting an emulator; a normal run fails until tests exist.
