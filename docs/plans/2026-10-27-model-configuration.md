# Model configuration migration Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development to implement this plan task-by-task. Steps use checkbox syntax for tracking.

**Goal:** Complete native provider/model configuration and use the actual app in an emulator against the user-authorized DeepSeek connection.

**Architecture:** Keep the existing independent Java checkout and native Views. Separate provider profiles/Keystore credentials, cancellable model discovery and probes, and draft UI. Preserve the existing single-provider data and never export credentials. Source Expo checkout remains read-only.

**Tech Stack:** Java17, Android Views/Keystore, HttpURLConnection, existing JUnit and instrumentation runners. No new runtime dependencies.

Final status: all tasks below implemented and verified. 101 JVM tests,2 model offline groups,17 existing device regression groups and5 combined live-use groups pass. See docs/verification/MODEL-CONFIGURATION.md. Local commit and sync are the final delivery step.

## Task 1: Profiles and credentials (profile worker)
- [x] Add pure ProviderProfiles with five source presets, stable IDs, validated sanitized metadata, add/update/switch/remove custom behavior; write failing JVM tests then implement.
- [x] Extend SecureSettings with getProviders(), selectProvider(id), addProvider(config,key) returning ID, deleteProvider(id), getApiKey(id), getApiKeyFor(draft,enteredKey), keeping getProvider()/saveProvider()/getApiKey() compatible.
- [x] Migrate existing provider/key atomically into profile-specific encrypted slots. Blank key preserves only that profile; switching never reuses another key. Changed destination requires re-entering key for draft tests. No keys in provider JSON/backups.

## Task 2: Catalog and probes (network worker)
- [x] Add ModelsApi(provider,key): fetchModels()->JSONArray, testConnection(boolean vision)->JSONObject, cancel(). Methods use bounded reads, HTTPS, no redirects, finite timeout, sanitized errors, no automatic retries.
- [x] Normalize model list into objects {id,name,vision_known,supports_vision,reasoning_levels?}. Dedupe/sort, filter explicitly incompatible non-chat models, retain unknown chat candidates without claiming vision. Support /models relative to a base or full chat endpoint.
- [x] Add ModelOptions.apply(payload,provider) and tests: DeepSeek/Kimi/GLM thinking toggle, Qwen enable_thinking, generic optional reasoning_effort; use in AiClient and probes. Probe uses tiny text or generated color image; verify vision response rather than only HTTP200.

## Task 3: Native configuration UI and lifecycle (root)
- [x] Replace single editor with provider chooser/add/remove, draft name/base/model/key/vision/effort, manual model ID fallback and refreshed searchable model picker. Show capability metadata and honest probe status.
- [x] Draft testing uses current input without saving; save is explicit. State survives ordinary rerenders and clears credentials on provider switch. Explicit cancel and lifecycle cleanup; late responses cannot affect another provider/draft.
- [x] Replace stale about text. Write device checks for legacy migration/key isolation, draft tests/model picker, cancellation and invalid credentials using offline fixtures.

## Task 4: Review and actual emulator use (root + reviewers)
- [x] Run full JVM/lint/build plus existing17 device groups. Review spec and quality, repair concrete findings.
- [x] Start Pixel9a, configure DeepSeek through actual UI or test-only instrumentation, discover available models, text/vision probes and actual lookup + streaming conversation. Live credentials supplied only through a temporary test channel; no source literals/logs, then remove test credential and temporary channel.
- [x] Maximum3 attempts for any long timeout/download/permission failure unless a known cause is fixed. Keep paid calls small; no broad benchmark.
- [x] Record sanitized results/screenshots and limitations, update docs/planning, commit. Previously authorized public repository push remains available, but this turn delivers tested changes first.

## Acceptance
Model changes and per-provider credentials survive restart; ordinary data restore never overwrites local credentials. Save, discovery and probes validate current drafts. Unknown capability is displayed honestly. Tests and UI never expose keys. Actual service test results distinguish success from limits or invalid credentials.
