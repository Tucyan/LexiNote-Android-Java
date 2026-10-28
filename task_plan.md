2026-10-28 normal-usage fixture delivered:80 words+40 phrases,128 meanings,12 annotations,8 notebooks,10 recent queries,5 temporary+8 saved conversations,54 messages. Source encoder/native decoder validated; actual SAF import and UI export performed on Pixel_9a. Roundtrip vocabulary/notebooks/message content/titles/IDs equal, provider XML unchanged. Data retained on emulator; restore-before snapshot retained. Findings for next app work: imported reverse chronological arrays display in reverse; notebook parent with children shows misleading empty state. App source remains e05c186. See docs/verification/NORMAL-USAGE.md.

2026-10-28 TokenDance continuation completed: built-in 34-model reference list and compatibility guard; PKCE callback/headless authorization, encrypted pending state and atomic activation; account/usage/help portal; payment state restoration, explicit user payment, late-return final-status check and recovery actions. Current worker preference gpt-6.1-terra/high. Verification:117 JVM tests,0 errors;lint0 errors/13 warnings;5 configuration+7 feature+10 smoke device groups pass. Real account login/payment untested. See docs/verification/TOKENDANCE.md and docs/plans/2026-10-28-tokendance-completion.md. No temporary credentials used; original private preferences/library restored by device suites.

# Selected feature migration

2026-10-27 follow-up: fresh actual-screen and experience comparison completed. See docs/ui-parity-2026-10-27/REPORT.md:16 actual screenshot pairs, identical testfixture, source-backed experience gaps and corrected prior spelling/drag claims. App data/settings restored; application source unchanged in this audit.

Current continuation (2026-10-27): native model configuration + real emulator/API use completed. Execution plan: docs/plans/2026-10-27-model-configuration.md. Prior four-group checklist below remains completed. Profiles/catalog/UI integration, spec and quality review,101 JVM tests,19 offline device groups and real DeepSeek use passed. Final delivery: commit and sync.

Baseline: b76b137. Scope: user annotation 1, four feature groups only. Keep the sibling Expo checkout unchanged. Continue on codex/android-native-java in this already independent repository.

## Acceptance checklist

- [x] Query: configurable automatic recording only for successful confirmed/new results; local results, chat, invalid proposals and failures do not auto-save. Per-result idempotence, saved status, default notebook/display preferences.
- [x] Query: AI supplement merges without duplicate meanings or stale-session writes; in-place meaning editing, selection, Chinese/English display choice, examples and notebook organization preserve complete shared entities.
- [x] Images: full-resolution camera output, native rectangular/freehand selection, zoom/pan, rotate, undo/redo/reset; original and selected output/region survive session save/reopen/backup independently.
- [x] Assistant: bounded/cancellable SSE, validated final actions only, safe incremental answer preview, background auto-title with manual-name/deletion/restore guards, 200-row virtualized list with per-session anchor restore, Markdown tables.
- [x] Notebooks: filtered selection/select-all, batch move/copy/remove/global-delete with confirmation, shared references and presentation preserved; tag chips; Markdown export; field text selection annotations with original field/text provenance.
- [x] Review: specification and quality audits, JVM tests, Android compile/lint, emulator workflows and regression tests; docs accurately describe actual results. Commit is the final step.

## Ownership and execution

Root: MainActivity, QueryUi, SettingsUi, query domain helpers/tests, shared integration, device tests, docs.
Image implementer: ImageCropView, ImageTools, new image editor/history/camera provider classes/resources/manifest; no MainActivity edits.
Notebook implementer: Library, NotebookUi, new batch/export/annotation helpers and their JVM tests; no QueryUi edits.
Assistant implementer: AiClient, MarkdownText and new SSE/title/chat-list/Markdown classes and tests; no QueryUi/MainActivity edits.

All delegated workers gpt-6-luna/high. New classes may refer to existing package-visible activity helpers; coordinate signatures before integration. Workers do not commit or run project-wide Gradle concurrently. Root performs integrated builds. Each worker writes meaningful tests first and records red/green evidence using isolated javac/JUnit if needed, then reports file ownership and hooks.

## Phases

1. Scope/architecture and tests: complete.
2. Independent implementations: complete.
3. Query workflow and activity integration: complete.
4. Specification/code quality repair: complete.
5. Emulator verification, documentation and local commit: complete.

## Constraints / error log

No paid model requests without explicit scope; local HTTP fixtures test transport. At most three timeout/download/permission attempts unless a known cause is fixed. Prefer cached dependencies and Aliyun mirrors. Preserve backup field names and unknown fields. Destructive app actions require concrete UI confirmation; never silently delete shared entities for a notebook removal.
