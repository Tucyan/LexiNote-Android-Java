# Normal usage experience audit — 2026-10-28

Baseline app e05c186, repository1416ecb;120 vocabulary/13 conversations fixture already imported. User asks for issue recording and optimization advice, not implementation changes.

- Snapshot emulator library, rollback snapshot and private provider XML; preserve the before-audit state and restore at the end.
- Re-import the original sample through the real UI for a deterministic starting point.
- Exercise lexical search/submission/no-result states, notebook scopes/display, shared membership and large font if feasible.
- Exercise session search/date filter, recent-query deletion and sixth temporary replacement using a local dictionary hit (no model API).
- Exercise review ratings and inspect mastery semantics; cancel or restore all data mutations afterward.
- Capture actual screenshots and diagnostic comparisons; distinguish device-confirmed bugs, source-confirmed risks and untested suggestions.
- Prioritize actual data loss/wrong learning state ahead of layout suggestions. No unsupported performance claims from adb latency or debug emulator behavior.

Parallel workers read code only with gpt-6.1-terra/high; root alone operates the device. Temporary staging is in the source checkout and will be removed; source app code stays unchanged.

Completed: real SAF re-import; local lookup/quota/eviction; lexical search and type switch; zero-result and parent states; local-day date filter; recent deletion; two review ratings; large-font list/detail and card tap areas.9 issue groups recorded. Shared membership edits, online AI, image capture and long conversation scrolling were not rerun in this audit. Original library/provider/rollback byte comparison succeeded; extra pre-fixture rollback retained; timezone GMT restored. Counts120 vocabularies/10 recent/5 temporary/8 saved/54 messages. Offline Gradle checks successful (cached117 tests). See docs/verification/USAGE-EXPERIENCE.md. No app implementation changes.
