# Review Plans Implementation Plan

> Execute inline with the executing-plans workflow; preserve the user's requirement to develop only data/business code. Do not modify review views.

**Goal:** Introduce notebook-bound review plans, independent progress, persistent daily quotas and auditable daily statistics.

**Architecture:** `Library.reviewPlans()` owns a versioned `review` document within the existing atomic library snapshot. `ReviewPlans` implements commands and queries, `ReviewPlanSchema` validates backups, and existing `ReviewLogic` remains the spacing algorithm. JSON/ZIP backup envelopes keep their existing versions; the review subdocument has its own schema version.

**Tech Stack:** Java 17, Android minSdk 26, org.json, JUnit 4, existing Gradle wrapper and cached dependencies.

### Task 1: Specify behavior in tests

- [x] Add `app/src/test/java/com/lexinote/nativeapp/ReviewPlansTest.java`.
- [x] Cover immutable notebook binding, per-plan independence, ISO weekdays and timezone boundaries, persisted quotas, repeated attempts, idempotency, event statistics, migration and corrupt backup rejection.
- [x] Run focused tests and confirm missing service/API compilation failure (cached Gradle used after wrapper attempted network).

### Task 2: Implement the domain service

- [x] Add `ReviewPlans.java`: `create`, `update`, `archive`, `list`, `dailyQueue`, `record`, `statistics`.
- [x] Add `ReviewPlanSchema.java`: strict review subdocument types, identities, references and counters.
- [x] Integrate initialization/validation in `Library`; prevent deletion of notebooks used by active plans before recursive deletion.
- [x] Preserve all legacy word fields. Explicit `importLegacy` creation seeds plan state without inventing events.

```java
String planId = library.reviewPlans().create("工作日", notebookId, 20, 50,
    new int[] {1, 2, 3, 4, 5}, "Asia/Shanghai", false, nowMillis);
JSONObject queue = library.reviewPlans().dailyQueue(planId, nowMillis);
```

### Task 3: Verify and hand off

- [x] Run focused tests and final full tests/lint/assembly: 150 tests passed, lint 0 errors. Dedicated Android persistence/restore runner also passed.
- [x] Add `docs/REVIEW_PLANS_HANDOFF.md` with exact API examples, fields, scheduling/counting rules, migration, persistence and client acceptance checklist.
- [x] Inspect the complete diff, including unchanged UI files and JSON/ZIP round trips.
- [x] Apply the verified patch to the confirmed Java mainline without overwriting unrelated changes.
