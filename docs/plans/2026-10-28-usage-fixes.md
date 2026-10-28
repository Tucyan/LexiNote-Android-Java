# Usage experience fixes implementation plan

**Goal:** Correct all9 device-confirmed issue groups in USAGE-EXPERIENCE.md and reproduce their original triggers on the same sample.
**Architecture:** Library owns retention/time ordering; QueryUi owns promotion and fresh recent dialog rows; SessionDates converts timestamps to local dates. NotebookUi keeps input state independently of row refresh and uses explicit text fields; ReviewUi uses learning success distinct from attempts.
**Tech stack:** Java17, Android native View, org.json, JUnit4, offline Gradle, Pixel_9a instrumentation and actual adb UI.

- [x] Retention worker: write failing Library regressions for query quota, reversed imports, sixth temporary, stable ties/legacy/fallback timestamps. Fix Library only; verify isolated red/green.
- [x] Notebook worker: write failing field-search/empty-state tests, then fix search fields, preserved draft/live results, parent/empty states, consistent title tap. Root verifies native interactions.
- [x] Review worker: write failing mastery/progress tests, implement success-based mastery with old-data fallback and fixed group denominator. Root rates agenda and budget.
- [x] Root writes device regression suite before QueryUi fixes. Run existing date and recent-deletion behavior to observe red. Add SessionDates unit tests for offsets/DST/invalid input.
- [x] Integrate query→temporary only on AI entry/send; sort visible lists explicitly and filter query records out of temporary list. Verify saved/legacy cases and ordinary local lookup preservation.
- [x] Convert display/filter by local timezone; update recent dialog rows after delete/clear and persist immediately. Validate inclusive days and invalid ranges.
- [x] Full offline unit/lint/debug APK/instrumentation builds. Investigate failures before changing implementation; no API calls required.
- [x] Run all9 regression groups plus existing smoke/feature/configuration groups; record output and actual screenshots. Inspect date, notebook search, title tap, mastery and local lookup by UI.
- [x] Restore byte-identical pre-test library, private preferences and rollback; restore device timezone, retain original recovery snapshots; cleanup owned emulator.
- [x] Review final diff, update findings/release notes/report, commit locally and deliver verified APK path. No unrelated UI redesign or unmeasured performance claims.

Workers gpt-6.1-terra/high; independent file ownership. Root alone uses Gradle and emulator. Original Expo repository remains unchanged after staging cleanup. At most3 long-running/permission failure attempts unless a known fix explains retry.


Final semantics: empty assistant drafts stay in memory; nonempty query context explicitly opened in assistant is promoted; first send always promotes. Old indexed queries can contain initial user messages without consuming AI quota. Historical explicit Again/Hard count-only mastery is recalculated, giving16 in this fixture, not48. Device test asserts16 before and after grading. All original data files restored byte-for-byte.
