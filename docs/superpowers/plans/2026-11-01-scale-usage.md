# Scale usage implementation plan

> Execute inline in the existing user-selected branch. Preserve the pre-existing review layout changes.

Goal: Make 1000/3000-entry notebook interactions bounded, retain complete search/selection/export, and verify actual emulator use.
Architecture: Keep the native ScrollView design with explicit 40-row pagination; use per-operation ID sets for membership checks. Persist review task generation only when new daily queues are created. No new dependencies.
Tech stack: Java 17, Android Views, org.json, Gradle, ADB.

- [x] RED: ScaleInstrumentation opens 1000 entries and asserts <=55 content children. Actual baseline:1000, failure;8411ms to idle.
- [ ] Add 40-row page controls in NotebookUi.renderResults. Reset on scope/filter/sort changes; clamp after deletion; retain page after detail. Keep visible() full for selection/export and convert reference membership to Set.
- [ ] Replace ReviewPlans.pool repeated library.find with a vocabulary ID set rebuilt per call, preserving live adds/deletes.
- [ ] RED: Instrumentation checks repeated review rendering does not rewrite library.json, and new-plan empty-state text is attached.
- [ ] Add ReviewPlans.hasDailyQueue and persist generated queues once in ReviewUi.getSortedActivePlans; remove render-only unconditional persist. Attach the existing empty/paused cards to body.
- [ ] GREEN: Offline testDebugUnitTest lintDebug assembleDebug and scale device checks for1000/3000. Verify final-page reachability, full selection, search, review,600-message history, ZIP roundtrip.
- [ ] Install release APK, import fixture through actual file picker, tap/type/swipe and collect gfxinfo/logcat. Preserve original data and settings, leave a usable synthetic dataset plus rollback.
- [ ] Document remaining latency/capacity constraints and APK/fixture hashes. No commit or push requested.
