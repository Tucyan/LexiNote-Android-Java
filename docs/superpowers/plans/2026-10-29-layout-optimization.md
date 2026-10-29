# 页面布局逐项优化 Implementation Plan

> **For agentic workers:** Execute with subagent-driven-development in this session. Independent workers own separate files; root integrates and performs specification review, quality review and device verification.

**Goal:** Complete LC01–LC12 from LAYOUT-COMFORT.md while preserving query/AI/notebook/review data behavior.
**Architecture:** MainActivity provides density spacing, accessible action colors and minimum touch sizing. Pages use these shared rules; adaptive headers and optional controls use available width rather than shrinking user fonts. Reading, filtering and provider operations retain their existing domain contracts.
**Tech Stack:** Native Java17 Views, existing org.json/JUnit4, offline Gradle, Pixel_9a API37. No Expo source changes or new dependencies.

## Acceptance matrix

| Item | Result required | Evidence |
| --- | --- | --- |
| LC01 | Custom vocabulary dialog has20dp horizontal gutter and distinct meaning/field gaps; long content remains scrollable | standard/large/360dp detail screenshots and view padding |
| LC02 | Default query card is compact, optional context collapsed until requested; all three empty modes share a stable height; expanded context keeps draft | collapsed/expanded screenshots, editor state and height checks |
| LC03 | Review card uses available height with centered short content; useful controls stay fixed and ratings unchanged | reveal/unreveal on normal/narrow, existing review regressions |
| LC04 | Notebook default controls use fewer rows; filters expand on demand and selected filters are summarized | first-card position vs old557px controls; preserved input and filtered select-all |
| LC05 | TokenDance default path emphasizes connection/model, manual key/tests are optional; every account/payment/help action remains reachable | default/expanded and existing configuration tests |
| LC06 | Frequent buttons/chips/inputs use48dp targets; fixed small page parameters are updated | actual measured bounds at411/360/320dp |
| LC07 | Notebook/category/review ranges show discoverable additional choices and selected state | actual long-category/tag UI and scroll indicators/menu |
| LC08 | Markdown body/table/code follow app font scale once, alongside system scaling | same-message standard/large textSize and screenshots |
| LC09 | Comfortable/compact cause visible card spacing differences, neither reduces touch targets | same-data two-density geometry |
| LC10 |320/360dp large-font header actions and mode labels fit fully | bounds/text line layout and original PNG |
| LC11 | Latest-message action occupies an independent row rather than overlapping messages, with solid readable styling | keyboard/scroll screenshot and disjoint bounds |
| LC12 | Accent text has safe contrast on surfaces, button white text has>=4.5 contrast in both themes | pure color tests across presets/custom colors, light/dark captures |

## Shared interfaces and ownership

Root owns MainActivity.java, QueryUi.java, ReviewUi.java, new LayoutStyle.java/tests, all instrumentation/integration/docs/builds.
Notebook worker owns NotebookUi.java plus its own new helpers/tests.
Assistant worker owns AssistantUi.java, MarkdownView.java plus its own new helpers/tests.
Configuration worker owns ProviderSettingsUi.java, SettingsUi.java plus its own helpers/tests.
All workers use gpt-6.1-terra/high and do not run Gradle, emulator, commit or edit other owners' files.

Shared MainActivity API to be provided by root:
    boolean compact;
    int accent;      // readable foreground variant of saved accent
    int accentFill;  // fill with white-foreground contrast>=4.5
    int space(int comfortable, int compactValue);

Global button/chip/input minimum48dp; root.card uses space(16,12) padding and space(16,10) gap. Explicit page LayoutParams must also respect48dp. Saved user accent is unchanged; only rendered variants adapt for readability.

## Task1: Baseline and regression evidence

- [x] Snapshot current APK and preserve emulator library, encrypted preferences, rollback and display/font settings.
- [x] Add LayoutComfortInstrumentation before page changes, using existing public/package APIs. Assert detail gutter, Markdown font ratio, density geometry, touch sizes, compact context/default header, latest row and adaptive rendering.
- [x] Build/install test APK against old installed app and record expected behavior failures; archive previous source screenshots as the before evidence.
- [x] Unit-test LayoutStyle header/mode/density/color behavior. Examples:
    assertTrue(LayoutStyle.compactHeader(320,1.15f));
    assertTrue(LayoutStyle.contrast(LayoutStyle.textAccent(0xff10b981,0xffffffff),0xffffffff)>=5);
    assertTrue(LayoutStyle.contrast(LayoutStyle.fillAccent(0xff10b981),0xffffffff)>=4.5);
    assertTrue(LayoutStyle.cardPadding(false)>LayoutStyle.cardPadding(true));
  Run failing tests before implementation; retain real failure output.

## Task2: Shared rules and responsive query/review (root)

- [x] Implement pure LayoutStyle policies and pass focused JUnit tests.
- [x] In palette apply compact and rendered accent variants; maintain saved theme/brand values. Update base controls to48dp, card spacing by density.
- [x] Home header uses a flexible heading, hides version when crowded and keeps recent-query action fully visible. Set ellipsize only for long titles, never reduce chosen font size.
- [x] All default query modes use a common compact height; context toggle opens a scrollable editor without discarding text. Image/context expansion may grow when content requires it.
- [x] Replace review's360dp minimum empty card with an available-height/card-centered layout. Add range discovery and equal-weight48dp rating controls. Preserve ReviewLogic.

## Task3: Notebook reading and controls (worker)

- [x] Keep live search editor stable. Merge duplicate control rows; type/tag controls expand only when requested and a summary exposes the active state.
- [x] Category/tag overflow remains discoverable, selected category is scrolled into view or reachable through an explicit selector.
- [x] Add20dp dialog gutter, gaps between headings/values and meanings; retain selection annotation, save/membership/display behaviors.
- [x] Use shared density spacing in vocabulary cards and keep>=48dp controls. Run isolated meaningful helper tests if needed; root device tests cover layout.

## Task4: Assistant reading (worker)

- [x] Apply14sp*appScale to Markdown paragraphs/tables/code once and preserve relative heading/code spans.
- [x] Place latest-message action in an independent nonoverlay area; keep scrolling anchor, follow-tail and composer behavior.
- [x] Use accentFill for white-text user bubbles, solid action styling,>=48dp send/composer targets; sensible multiline height48..120dp.
- [x] Verify existing assistant domain logic and prepare focused device assertions for root integration.

## Task5: Configuration hierarchy (worker)

- [x] Keep TokenDance connection/model default. Collapse manual credentials/probes behind an explicit disclosure and move secondary service actions into a clear menu if needed.
- [x] Advanced/other provider flow still exposes all editing, discovery, save, tests and recovery actions; no credentials are logged and no network requests are triggered by rendering.
- [x] Enlarge explicit40/44dp targets to48dp and swatches to48dp effective regions, wrapping on narrow screens.
- [x] Font/density descriptions reflect behavior; model-picker text follows app scale and remains scrollable.

## Task6: Integrate, review, verify and deliver

- [x] Update existing device flows to open new disclosures while retaining their actual business assertions. Replace old312dp reference assertion with stable shared compact-mode height and no-clipping checks.
- [x] Run specification review for all12items, then independent code quality review; address material gaps before completion.
- [x] Run offline testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest. Run new layout suite and existing10smoke/7feature/5configuration/10usage groups.
- [x] Capture after screenshots with same mock data at411/360/320dp, standard/large, keyboard, light/dark, both density values; include long words/meanings and one system-large-font check if supported. Do not claim untested states.
- [x] Restore original emulator data/settings/rollback/display byte-for-byte and close only the owned emulator.
- [x] Write Chinese verification report with LC01–LC12 mapping, before/after images, exact results/remaining limits and APK checksum. Commit locally, provide plan/report/APK links.

Execution continues through all tasks without a new approval prompt: the user requested both planning and implementation. Commands/download/permission timeouts have a maximum of3 attempts unless a known fix explains another attempt. Use cached dependencies; no online model/payment calls needed.


## 执行结果（2026-10-29）

全部任务完成。135项JVM测试、四套各17项布局检查、32组原有业务回归及3组分类管理目标回归通过；0个Lint错误，13条既有警告。词库/加密偏好/回退文件在重启前后逐字节还原，屏幕和字号恢复原值。

复核中额外修复：语境折叠状态保存、固定筛选入口、完整可滚动筛选摘要、复习范围选中可见、选图动作裁切、系统大字号模式行高、复习数字绿色对比度、分类管理目标绑定。见[验收报告](../../verification/LAYOUT-OPTIMIZATION.md)。
