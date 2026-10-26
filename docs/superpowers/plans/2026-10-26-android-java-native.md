# Android Java Native Migration Implementation Plan

> **For agentic workers:** Use subagent-driven-development to implement this plan task by task. The user explicitly requested Subagent execution and an independent repository.

**Goal:** Fork LexiNote into an independent Android Java application with functional query, notebooks, guessing, review and settings flows.

**Architecture:** A standalone Git repository (no shared .git, no remote inherited) containing a native Android application. Framework views and Java services replace Expo/React Native. JSON domain objects retain original backup fields; atomic private storage and Keystore protect local persistence and credentials.

**Tech Stack:** Java 17, Android SDK 36, AGP 8.13.2, Gradle 8.13, JUnit 4.13.2. No JavaScript runtime or Kotlin application sources.

## Task 1: Isolation and build
- [x] Inspect source tree and preserve all uncommitted changes.
- [x] Create sibling repository `LexiNote-Android-Java`, branch `codex/android-native-java`.
- [x] Configure wrapper, native manifest/resources and JVM tests; use cached tooling before network and Aliyun repositories before upstream.

## Task 2: Native domain and screens (Subagent)
- [x] First add failing tests for normalized vocabulary reuse, uniform notebook children, recent history cap, conversation retention, review grading and backup validation.
- [x] Implement Java domain repository using original field names, shared vocabulary references, atomic persistence and validated restore.
- [x] Implement five native tabs plus query results and full-screen assistant, explicit save and notebook assignment, notebook CRUD, editing, four-stage guessing, review grades and provider settings.
- [x] Keep backup operations user-selected via Android document intents; exclude credentials.

## Task 3: AI and credential services
- [x] Add tests first for protocol, target normalization and request URL validation.
- [x] Implement OpenAI-compatible HTTPS chat, bounded I/O, cancellation and friendly failures, optional vision with compressed images; use a background executor.
- [x] Encrypt API keys using Android Keystore AES/GCM. Implement native image crop selection and document backup/import.

## Task 4: Review and verification
- [x] Independent specification review, followed by independent code-quality review; repair findings.
- [x] Run JVM tests, Android lint and Java compilation/APK assembly. Run source Expo lint and TypeScript noEmit without editing source.
- [x] If an emulator/device is available, install, launch and smoke-test native screens.
- [x] Write README and migration coverage/limitations with exact evidence; commit verified changes on independent branch.

## Source provenance
Original repo: https://github.com/Tucyan/LexiNote, HEAD d465b969b76f9baaba3d098fb89342916e7efce9.
Migration audit also considered the working-tree edits on 2026-10-26; the source checkout remains untouched. No original credentials, local app data, signing keys or node_modules are copied.



## Delivery note
All first-native-release milestones above were implemented and verified. This does not imply full Expo feature parity: exact remaining UI/provider/advanced management differences and the adjusted mobile-safe backup limits are explicitly tracked in docs/MIGRATION.md. Final verification: 44 JVM tests, Android lint/Java/APK build, 8 device smoke checks. A real original Expo 2.2 archive fixture is included and exercised. The source checkout is clean at d465b969. No remote publication occurred.
