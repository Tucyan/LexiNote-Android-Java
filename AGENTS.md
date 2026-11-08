# LexiNote Android Java
This is an independent native Android Java repository, migrated from ../LexiNote. Do not apply Expo/CNG rules here. All application code is Java; use native Android views.
Preserve source backup field names and vocabulary many-to-many references. Never store API keys in plaintext or export them in backups. Run `./gradlew testDebugUnitTest lintDebug assembleDebug` before claiming completion. Use Aliyun Maven mirrors before upstream. Commands/downloads that time out or lack permissions: at most three attempts unless a known fix resolves the cause; otherwise hand execution to the user. If pushing GitHub, use GitHub CLI authenticated as Tucyan. Do not publish a repository unless asked.

Subagents: use model `gpt-6.1-terra` with reasoning effort `high` for subsequent delegated tasks, per the user's updated instruction on 2026-10-27.

AI视觉标注规则：猜词（Guess）和复习（Review）模块暂时先不做任何视觉标注更改；后续如果用户要求更改，须先向用户确认并同步更改。
