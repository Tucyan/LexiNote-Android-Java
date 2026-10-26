# Selected feature migration

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
