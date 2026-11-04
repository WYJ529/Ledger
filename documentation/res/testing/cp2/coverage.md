# CP2 backend coverage evidence

Generated from the actual three interface runs; percentages are **S / B / F / L** (statements, branches, functions, lines). Combined means mock + no-mock only, with no unit-test coverage added.

`collectCoverageFrom: ["src/**/*.ts"]` includes every source file. No application file was excluded. A high total does not establish high no-mock coverage for each file.

| Source | Mock S/B/F/L % | No-mock S/B/F/L % | Combined S/B/F/L % |
| --- | --- | --- | --- |
| **All files** | 50.24 / 39.98 / 51.18 / 50.54 | 73.46 / 67.06 / 82.84 / 73.28 | 82.52 / 76.87 / 87.59 / 82.57 |
| `src/accountAuth.ts` | 94.59 / 84.84 / 100 / 94.59 | 78.37 / 66.66 / 100 / 78.37 | 94.59 / 84.84 / 100 / 94.59 |
| `src/accountSettings.ts` | 95.14 / 96.26 / 100 / 95.09 | 59.22 / 57.94 / 100 / 58.82 | 95.14 / 97.19 / 100 / 95.09 |
| `src/app.ts` | 76.63 / 61.53 / 61.11 / 78.26 | 85.04 / 69.23 / 83.33 / 86.95 | 89.71 / 75.38 / 83.33 / 92.39 |
| `src/currencies.ts` | 100 / 75 / 100 / 100 | 100 / 100 / 100 / 100 | 100 / 100 / 100 / 100 |
| `src/entrySharing.ts` | 24.28 / 12 / 25 / 25 | 95.71 / 92 / 100 / 95.58 | 95.71 / 92 / 100 / 95.58 |
| `src/googleAuth.ts` | 92.15 / 88.7 / 83.33 / 92.15 | 31.37 / 12.9 / 66.66 / 31.37 | 93.13 / 88.7 / 100 / 93.13 |
| `src/groupExpenses.ts` | 64 / 36.24 / 73.33 / 63.26 | 93.33 / 88.59 / 100 / 93.19 | 96 / 91.27 / 100 / 95.91 |
| `src/groupManagement.ts` | 67.18 / 48.41 / 70.83 / 68.44 | 95.31 / 87.3 / 100 / 95.18 | 97.91 / 92.06 / 100 / 97.86 |
| `src/groupMembershipEvents.ts` | 100 / 100 / 100 / 100 | 100 / 100 / 100 / 100 | 100 / 100 / 100 / 100 |
| `src/groupStore.ts` | 100 / 62.5 / 100 / 100 | 100 / 100 / 100 / 100 | 100 / 100 / 100 / 100 |
| `src/index.ts` | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| `src/ledgerSaveStore.ts` | 51.28 / 42.62 / 47.05 / 49.25 | 83.33 / 70.49 / 82.35 / 86.56 | 83.33 / 70.49 / 82.35 / 86.56 |
| `src/ledgerSaves.ts` | 80 / 71.15 / 94.44 / 79.28 | 73.1 / 63.46 / 100 / 72.14 | 90.34 / 85.57 / 100 / 90 |
| `src/password.ts` | 90.62 / 73.33 / 100 / 89.65 | 81.25 / 73.33 / 87.5 / 79.31 | 90.62 / 73.33 / 100 / 89.65 |
| `src/pixelRelay.ts` | 84.28 / 75 / 86.66 / 84.37 | 0 / 0 / 0 / 0 | 84.28 / 75 / 86.66 / 84.37 |
| `src/seedExpenseGroup.ts` | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| `src/serverInfo.ts` | 27.77 / 0 / 0 / 31.25 | 100 / 75 / 100 / 100 | 100 / 75 / 100 / 100 |
| `src/session.ts` | 91.17 / 96.42 / 100 / 91.17 | 91.17 / 78.57 / 100 / 91.17 | 91.17 / 96.42 / 100 / 91.17 |
| `src/splitSessionLive.ts` | 0 / 0 / 0 / 0 | 84.21 / 76.19 / 92.85 / 83.63 | 84.21 / 76.19 / 92.85 / 83.63 |
| `src/splitSessionStore.ts` | 92.85 / 0 / 50 / 92.85 | 100 / 100 / 100 / 100 | 100 / 100 / 100 / 100 |
| `src/splitSessions.ts` | 8.35 / 1.91 / 4.65 / 8.63 | 79.78 / 75.39 / 97.67 / 79.38 | 79.78 / 75.39 / 97.67 / 79.38 |
| `src/splitting.ts` | 29.77 / 27.27 / 21.87 / 30.7 | 93.12 / 85.12 / 100 / 92.1 | 93.12 / 85.12 / 100 / 92.1 |
| `src/userStore.ts` | 55.11 / 47.82 / 60.71 / 55.26 | 84.25 / 77.39 / 85.71 / 85.96 | 84.25 / 77.39 / 85.71 / 85.96 |
| `src/validation.ts` | 93.75 / 91.3 / 100 / 93.75 | 78.12 / 60.86 / 100 / 78.12 | 93.75 / 91.3 / 100 / 93.75 |
| `src/config/env.ts` | 0 / 0 / 100 / 0 | 0 / 0 / 100 / 0 | 0 / 0 / 100 / 0 |
| `src/ledger/csv.ts` | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 | 0 / 0 / 0 / 0 |
| `src/ledger/domain.ts` | 64.78 / 46.55 / 70.58 / 64.7 | 69.71 / 55.17 / 70.58 / 69.85 | 71.12 / 56.89 / 70.58 / 71.32 |
| `src/ledger/importPolicy.ts` | 100 / 100 / 100 / 100 | 100 / 100 / 100 / 100 | 100 / 100 / 100 / 100 |
| `src/ledger/providers.ts` | 96.26 / 90.51 / 100 / 96.06 | 76.11 / 62.06 / 90.9 / 75.59 | 96.26 / 90.51 / 100 / 96.06 |
| `src/ledger/router.ts` | 96.8 / 94.73 / 100 / 96.7 | 89.36 / 81.57 / 100 / 89.01 | 96.8 / 94.73 / 100 / 96.7 |
| `src/ledger/statements.ts` | 3.84 / 0 / 0 / 4.08 | 94.71 / 91.12 / 90.47 / 94.89 | 94.71 / 91.12 / 90.47 / 94.89 |

## Uncovered code and concrete limitations

Each row identifies actual zero-hit executable lines in all three runs. Branch-only gaps can exist even where line coverage is 100%; the JSON files include every zero-hit branch as `line:block:branch` and every zero-hit function. See the HTML source pages for exact highlighted conditions.

These are explanations of gaps, not waivers of the course requirement. In particular the 0% legacy CSV/bootstrap files and low no-mock coverage in other components prevent claiming whole-project compliance.

| File | Mock uncovered lines | No-mock uncovered lines | Combined uncovered lines | Reason / next action |
| --- | --- | --- | --- | --- |
| `src/accountAuth.ts` | 19, 24–25, 68 | 19, 24–25, 31–34, 47–48, 51–52, 55–56, 68, 76–77 | 19, 24–25, 68 | Unexercised registration/session error handling and alternate account states; existing interface suites do not force every store failure. |
| `src/accountSettings.ts` | 81, 125–126, 131–132 | 16, 22, 27, 31, 37, 42, 48, 52–61, 64, 73–78, 81, 90–91, 100–101, 106–107, 112–113, 118–119, 125–126, 131–132, 137–139 | 81, 125–126, 131–132 | Profile update/re-authentication alternatives lack a dedicated no-mock suite; the mock suite exercises only selected write failures. |
| `src/app.ts` | 10–11, 16, 21–23, 56, 81, 88–89, 92, 96–98, 101–105, 107 | 10–11, 16, 21–23, 56, 115–117, 119–120 | 10–11, 16, 21–23, 56 | Application-level dependency wiring and alternate invitation/error responses are not all traversed. |
| `src/currencies.ts` | none | none | none | All executable lines covered; consult branch/function columns for any remaining dimension. |
| `src/entrySharing.ts` | 20–26, 28–31, 33–35, 40–42, 44, 46–48, 50–51, 55, 57–60, 63–65, 69–71, 75–77, 81–84, 87–89, 91–94, 96–97, 103 | 64, 70, 76 | 64, 70, 76 | Some validation/race and changed-source branches remain; no dedicated mock counterpart. |
| `src/googleAuth.ts` | 14–15, 99, 111–112, 134–136 | 15, 23–24, 28–29, 40–42, 44–47, 58–61, 63, 67–73, 75–79, 81–83, 85–87, 89–91, 93–95, 98–100, 105–112, 114–118, 120–123, 125–129, 131, 134–136 | 15, 99, 111–112, 134–136 | Real successful Google token verification is not exercised with a real identity; some account-link failure branches remain. |
| `src/groupExpenses.ts` | 16, 24, 28, 34, 47–48, 61, 70–71, 93–94, 97–98, 112, 115, 118, 135–137, 139–140, 158–159, 162–163, 172, 177–178, 188–189, 194, 198–201, 203–205, 207–209, 211–213, 215–218, 220, 223–225, 227–228 | 131, 134, 147, 193, 204–205, 208–209, 217–218 | 204–205, 208–209, 217–218 | Selected invalid split/input and concurrent persistence alternatives remain outside the existing cases. |
| `src/groupManagement.ts` | 25–27, 29–30, 33, 46, 49, 52, 55, 64, 79–80, 86–87, 100–101, 111–112, 121–122, 146, 152–153, 160, 163–164, 172–173, 178, 181–182, 186–188, 193, 204–205, 210, 213–214, 218–219, 223–224, 234–235, 237, 242, 248, 250–253, 256–259, 261 | 55, 97, 152–153, 169–170, 178, 231–232 | 55, 152–153, 178 | Some invitation/lifecycle and store-failure branches remain; existing tests cover selected failures only. |
| `src/groupMembershipEvents.ts` | none | none | none | All executable statements/branches/functions/lines covered in all three phases. |
| `src/groupStore.ts` | none | none | none | Executable lines covered; conditional schema/default branches can remain unvisited. |
| `src/index.ts` | 2–8, 10–13, 15–20, 24–26 | 2–8, 10–13, 15–20, 24–26 | 2–8, 10–13, 15–20, 24–26 | Server process startup/listening/signal shutdown is not executed by createApp HTTP tests. This is a real coverage gap, not an exclusion. |
| `src/ledgerSaveStore.ts` | 10–11, 16, 21–23, 63, 68–71, 74–77, 80–84, 87–89, 92, 94–96, 99, 101–103, 106–108 | 10–11, 16, 21–23, 69, 75, 89 | 10–11, 16, 21–23, 69, 75, 89 | Cloud-store conflict/initialization and database failure alternatives are not all forced. |
| `src/ledgerSaves.ts` | 40, 55, 100–102, 149, 159, 164–165, 167–170, 173–176, 178–180, 182–186, 189–191, 208 | 24, 32, 38–40, 45, 47, 53–55, 58, 60, 62, 79, 83, 85, 87, 89, 94, 126–127, 132–133, 140–141, 149, 159, 169, 173–176, 179–180, 185, 190, 198–199, 208 | 40, 55, 149, 159, 169, 173–176, 179–180, 185, 190, 208 | Some invalid snapshot/slot and persistence failure alternatives remain. |
| `src/password.ts` | 16, 30, 33 | 16, 30, 33, 41–43 | 16, 30, 33 | Malformed stored-hash/parameter and crypto failure alternatives remain. |
| `src/pixelRelay.ts` | 27, 40–42, 46–49, 74, 87 | 2–4, 7–10, 13–16, 18–21, 26–29, 32–34, 36–37, 40–42, 44–49, 51–53, 55, 57–62, 64–65, 67–78, 81, 85–90 | 27, 40–42, 46–49, 74, 87 | Relay runs with replaced course upstream; external connection/reconnect failures are not all exercised without mocks. |
| `src/seedExpenseGroup.ts` | 2–7, 10–12, 15, 17–26, 28–32, 35, 38–40 | 2–7, 10–12, 15, 17–26, 28–32, 35, 38–40 | 2–7, 10–12, 15, 17–26, 28–32, 35, 38–40 | Standalone seed command is not executed by HTTP interface suites; it remains in the all-src denominator. |
| `src/serverInfo.ts` | 8–10, 13–17, 19–21 | none | none | All executable lines covered; consult branch/function columns. |
| `src/session.ts` | 55–57 | 55–57 | 55–57 | Some malformed/session-store failure paths remain beyond covered authentication and revocation. |
| `src/splitSessionLive.ts` | 2–3, 5–12, 15–21, 23–29, 31–34, 36–39, 41–45, 47, 50, 53–56, 60–61, 63–67, 73–77, 79–81, 83–85, 87, 89–91, 93–96, 99, 102–105, 109–112, 114–119, 121, 123–125, 127–130, 132–135, 143–155, 157 | 33–34, 38–39, 44–45, 50, 55–56, 94, 99, 103–105, 111, 115, 124–125 | 33–34, 38–39, 44–45, 50, 55–56, 94, 99, 103–105, 111, 115, 124–125 | Some disconnect, recovery, invalid event and event-delivery failure paths remain. |
| `src/splitSessionStore.ts` | 38 | none | none | Executable lines covered; consult schema/default branches. |
| `src/splitSessions.ts` | 29–31, 40, 47, 50, 58, 66–74, 76–77, 79–80, 82–89, 91–94, 96–97, 99–100, 102, 105–106, 108, 111–113, 116, 119, 122, 125, 128, 133–139, 141, 145, 148–153, 155–158, 160, 163–165, 173–174, 179–182, 184–185, 187–188, 193–195, 197–198, 201–203, 213–217, 219, 221–223, 231–235, 237–239, 241–245, 247, 249–250, 253, 255–258, 260–261, 263–264, 269–271, 274–275, 277–284, 286–287, 289, 291–293, 297–301, 305–308, 311–314, 316, 320, 322, 327–331, 333–340, 342–348, 350–351, 353–356, 358–360, 362–364, 366–369, 371–374, 376–378, 380–382, 384–387, 400–405, 407–408, 410–412, 415–418, 420–422, 426–430, 432–435, 437–446, 448–452, 454–455, 459–462, 465–469, 471–473, 475–477, 479–482, 484, 487–488, 493–496, 498, 500–504, 506–507, 510–511, 514–515, 517–520, 522–524, 529–530, 533–535, 537–539, 541–543, 547–551, 554, 557–559, 561–564, 566, 568–570, 572–574 | 69, 74, 80, 89, 92, 94, 158, 182, 194, 219, 234–235, 283–284, 289, 292, 300–301, 312, 355–356, 368–369, 377–378, 404–405, 416–418, 421, 429–430, 434–435, 445–446, 451–452, 461–462, 466–469, 471–473, 475–477, 481–482, 500–504, 506–507, 510–511, 517–520, 522–524, 534–535, 542, 563–564 | 69, 74, 80, 89, 92, 94, 158, 182, 194, 219, 234–235, 283–284, 289, 292, 300–301, 312, 355–356, 368–369, 377–378, 404–405, 416–418, 421, 429–430, 434–435, 445–446, 451–452, 461–462, 466–469, 471–473, 475–477, 481–482, 500–504, 506–507, 510–511, 517–520, 522–524, 534–535, 542, 563–564 | Some operation recovery/finalization races, validation and error alternatives remain. |
| `src/splitting.ts` | 18–20, 29, 34, 40–41, 43–47, 50–51, 53–54, 57–58, 60, 62–64, 71–74, 76–77, 85–92, 94–95, 97, 101, 103–106, 108–110, 112–127, 129–131, 133–135, 139, 142, 146, 151, 153, 162, 164, 166, 169, 173 | 19, 74, 89, 110, 116, 131, 139, 146, 166 | 19, 74, 89, 110, 116, 131, 139, 146, 166 | Some invalid/degenerate split inputs and fallback branches remain. |
| `src/userStore.ts` | 10–11, 16, 21–23, 84, 104–109, 114, 117–119, 124, 128, 131–134, 137–139, 143, 146, 149–151, 154–156, 167, 172–173, 178, 198–201, 206–212, 215–216 | 10–11, 16, 21–23, 105, 119, 124, 132, 139, 146, 156, 172–173, 216 | 10–11, 16, 21–23, 105, 119, 124, 132, 139, 146, 156, 172–173, 216 | Migration/linking and store failure alternatives are not all exercised. |
| `src/validation.ts` | 17, 32 | 17, 23, 25, 27, 32, 35, 41 | 17, 32 | Some type/length/normalization alternatives are not represented in the current input tables. |
| `src/config/env.ts` | 2–6, 9–10, 12–14, 16 | 2–6, 9–10, 12–14, 16 | 2–6, 9–10, 12–14, 16 | Interface suites inject configuration and do not run environment/bootstrap validation. This is not evidence that production configuration is verified. |
| `src/ledger/csv.ts` | 2–9, 19, 22–30, 32–36, 39, 42–45, 47, 49–55, 58, 61–65, 68, 70, 72–73, 75–80, 82–85, 87–89, 92–93, 96–100, 103–104, 107–112, 120–137, 139–140, 143, 146–148, 150–162, 164–165, 167–171, 173–178, 180–186, 188–191, 193–194, 196, 204, 207–208, 211–213, 215, 217–219, 222–227 | 2–9, 19, 22–30, 32–36, 39, 42–45, 47, 49–55, 58, 61–65, 68, 70, 72–73, 75–80, 82–85, 87–89, 92–93, 96–100, 103–104, 107–112, 120–137, 139–140, 143, 146–148, 150–162, 164–165, 167–171, 173–178, 180–186, 188–191, 193–194, 196, 204, 207–208, 211–213, 215, 217–219, 222–227 | 2–9, 19, 22–30, 32–36, 39, 42–45, 47, 49–55, 58, 61–65, 68, 70, 72–73, 75–80, 82–85, 87–89, 92–93, 96–100, 103–104, 107–112, 120–137, 139–140, 143, 146–148, 150–162, 164–165, 167–171, 173–178, 180–186, 188–191, 193–194, 196, 204, 207–208, 211–213, 215, 217–219, 222–227 | Legacy backend CSV parser has no production caller; CSV import now runs on Android. It remains visible at 0%; safe removal/refactoring requires follow-up, not a coverage exclusion. |
| `src/ledger/domain.ts` | 33, 42, 46, 50, 58, 64, 76, 84, 90, 97, 103, 107, 109–110, 125, 127, 129, 131, 133, 138, 150–152, 161, 170, 179–198, 200–202 | 33, 58, 76, 79, 84, 90, 97, 103, 107, 109–110, 123, 125, 129, 133, 151, 161, 170, 179–198, 200–202 | 33, 58, 76, 84, 90, 97, 103, 107, 109–110, 125, 129, 133, 151, 161, 170, 179–198, 200–202 | Only domain operations reached by current HTTP routes are counted here; Android owns personal review/commit. Pure unit tests are deliberately excluded from combined interface coverage. |
| `src/ledger/importPolicy.ts` | none | none | none | All executable statements/branches/functions/lines covered in all three phases. |
| `src/ledger/providers.ts` | 40, 74, 88, 113, 211 | 22–23, 40, 50–51, 58–60, 74, 88, 95, 100, 108, 113, 118–120, 146, 191, 193, 196, 204, 211, 214, 218, 228, 232–233, 235–237 | 40, 74, 88, 113, 211 | No-mock success uses actual FX/OpenAI, but upstream refusals, malformed JSON/schema, quota, stalls and network errors are deterministic mock cases. Remaining combined gaps include null response streams, cache eviction and unusual envelope alternatives. |
| `src/ledger/router.ts` | 25, 54–55 | 13, 25, 49, 54–55, 89, 101, 116–117, 124 | 25, 54–55 | Mock suite drives extraction concurrency/errors; real-provider no-mock does not force simultaneous slow extractions. Combined misses include the 2,000-client saturation guard and rare IP/error alternatives. |
| `src/ledger/statements.ts` | 17–23, 25, 27–37, 39–43, 47–69, 71, 73–77, 79, 81, 84–86, 89, 94–102, 104–108, 110–112, 114–116, 119–125, 127–134, 136–145, 148–150, 152–154, 156–160, 162–164, 166–168, 170–172, 174–175, 178–183, 185, 187–191, 193–201, 203–211, 213, 215–221, 223–225, 227, 229–232, 236–240, 242, 244–245, 247, 250, 253–254, 257, 259, 261, 268 | 19, 22, 34, 63, 69, 122, 224–225, 237, 240 | 19, 22, 34, 63, 69, 122, 224–225, 237, 240 | Real PDFs cover both banks and reconciliation failures. Uncovered guards include extreme text/amount limits, over-200 details and some foreign-spend/description/section alternatives; pure parser unit checks do not count as HTTP interface coverage. |

## Raw evidence

- [Actual console log](cp2-interface-run.txt); [unit log](cp2-unit.txt); [source/run manifest](run-manifest.json).
- [Mock summary](mock-coverage-summary.json), [no-mock summary](no-mock-coverage-summary.json), [combined summary](interface-coverage-summary.json).
- [Mock cases](mock-results.json), [no-mock cases](no-mock-results.json), [combined cases](interface-results.json). These preserve Jest status, names, counts and timestamps, without duplicating its large coverage map.
- [Actual generated HTML reports](coverage-html.zip): extract and open each mode's `index.html`, then drill into `src/` and `src/ledger/` for all files.
- [Mock uncovered](mock-uncovered.json), [no-mock uncovered](no-mock-uncovered.json), [combined uncovered](interface-uncovered.json).

Coverage screenshots remain pending: desktop capture failed with `failed to launch codex app-server: The system cannot find the path specified. (os error 3)`. The HTML archive and tables are actual report data, not screenshots.
