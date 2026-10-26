# Review record

2026-10-26, native Java migration first release.

- Specification review: PASS after canonical global-index repair and missing system-notebook normalization. Default notebook no longer offers unsupported child creation.
- Code quality review: PASS after adding a usable corruption recovery screen, preserving corrupt bytes before confirmed restore, enforcing mobile heap budgets, durable pending exports across Activity reconstruction, and counting checksum bytes in archive capacity.
- Final targeted review: PASS for assistant lookup confirmation scope/revision, target consistency, consumption of local confirmations, and per-session image isolation.
- Additional device regression: ICU on Android rejects the JVM-only inline Unicode regex flag; normalization now uses the portable whitespace/separator character class and the device suite passes.
- Actual original-source archive encoder fixture is included and imported in JVM tests; source partial-meaning imports are explicitly covered.
- Latest evidence: 44 JVM tests with zero failures/errors; Android lint 0 errors / 6 warnings; Java/Debug APK/androidTest APK assembly success; 8 device smoke checks pass on Pixel_9a API 37.
- Real paid-provider requests, Android 8.0 hardware, complete Expo feature parity and production signing are outside this verification. See MIGRATION.md for the specific remaining functional differences.

## UI parity pass

The follow-up layout audit used gpt-6-luna / high subagents for source analysis, UI implementation and independent review. Three important findings were fixed and re-reviewed: hidden images entering text results or guess requests; stale guess stages/source transfer modes; and an empty review scope with no route back to all words. Final screenshot automation also found a selectable header label intercepting the history tap; interactive container labels now allow the parent action.

Final evidence for this pass: 44 JVM tests, lint 0 errors / 8 warnings, APK and instrumentation assembly; 10 device smoke checks; 12 paired screenshot states. The final additional state-only fix clears a hidden draft image when submitting text, and the device suite checks that subsequent assistant requests cannot inherit it. See [UI report](../ui-parity/REPORT.md), [device log](ui-device-smoke.txt) and [artifact hash](artifact.txt). Pixel identity and full feature parity are not claimed.
