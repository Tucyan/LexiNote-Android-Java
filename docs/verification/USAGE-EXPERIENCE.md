# 模拟数据实际使用审查

日期：2026-10-28。应用源码基线 e05c186，测试开始时仓库 HEAD 1416ecb。本轮只记录问题及建议，未修改应用功能，也没有调用模型 API。

## 结论与测试条件

用同一份 80 单词、40 短语、8 笔记、10 最近查询、5 临时会话、8 保存会话、54 消息的备份继续实测，确认 **9 组问题：2 组 P1、5 组 P2、2 组 P3**。优先修复普通本地查询占用 AI 临时额度、导入后按数组位置错误淘汰记录，再处理复习状态、搜索及筛选。以下均有设备操作证据；源码用于解释原因，不代替实测。此报告描述当前原生版的行为，不将所有问题都断言为相对原版的退化。

设备 Pixel_9a / API 37 / 1080×2424，浅色、绿色强调色。先保存本轮开始时的词库、服务私有偏好和恢复前快照，再通过应用恢复入口及 Android 文件选择器重新导入原始 ZIP，以排除上轮打开会话改变顺序的影响。[实际恢复确认页](usage-experience/screens/01-original-restore-confirmation.png)。

日期测试将模拟器时区从 GMT 临时改为 Asia/Singapore；未修改系统时间。其余检查使用标准字号，最后用应用内“大”字号补查列表和双义项详情。所有改动在结束时恢复，包括时区和“回到上次恢复前”的原有快照。

## 设备确认的问题与建议

| 编号 / 优先级 | 操作与实际结果 | 影响及建议 |
| --- | --- | --- |
| UX-01 / P1 | 原始备份已有 5 条临时 AI 会话，仅在首页查询本地已存在的 budget，没有打开 AI 助手。新记录仍标为 temporary，占用五条临时额度，旧会话被删除。 | 普通查词挤占 AI 问答。区分 query / temporary / saved 保留策略；仅真正进入临时 AI 会话时参与五条额度。 |
| UX-02 / P1 | 在同一次 budget 查询后，被删的是最新 agenda 会话 usage-query-10（01:53Z），而非最旧临时会话；10 条最近查询同样删掉最新 agenda，保留较旧 deadline。消息数 54→50。 | 原版备份按新到旧排列，原生淘汰假设数组按旧到新。列表排序和淘汰统一按解析后的时间及稳定 ID 处理，不能依赖导入数组位置。 |
| UX-03 / P2 | 默认笔记先显示 20 词 / 熟练 8 / 待复习 12。第一张 agenda 选“简单”；第二张 budget 原有 review_count=2，选“再次”后变成 3。重启后全部词库“已熟练掌握”48→49。 | 忘记的词被计入熟练。将尝试次数与掌握程度分开；“再次”降低或重置掌握状态，只有满足明确的成功复习规则才计入熟练。 |
| UX-04 / P2 | 词库搜索 source 并提交，再批量全选，显示“操作 (120)”。按内容、释义、例句、来源和批注等文字值检查，实际只应命中 4 条：source、derive、look up、be based on。 | 搜索命中了每个实体的 JSON 键 source，扩大批量操作范围。只搜索明确业务字段，并显示结果数量；ID、字段名、时间戳不参与普通文字检索。 |
| UX-05 / P2 | 在搜索栏输入 meeting，未按键盘提交就点“短语”，输入文字消失，显示全部短语。 | 搜索草稿未同步到筛选状态。实时更新搜索状态，必要时对筛选计算去抖；切换类型、笔记或标签时保留当前文字。 |
| UX-06 / P2 | Asia/Singapore 时区下，以“最近使用日期”筛选 2026-10-28 至当天，仅显示 get the hang of、follow up on 两条，实际 8 条保存会话都属于本地当天。日期展示也直接显示 UTC 数字且不标时区。 | 跨 UTC 零点的 6 条会话被漏掉。将时间戳转换为用户时区，再计算含首尾的本地日期范围；筛选、排序和显示统一规则。 |
| UX-07 / P2 | 最近查询弹窗中删除 context，私有存储 recent_count 已为 9 且不含该 ID，弹窗仍保留 context 行和删除按钮。 | 用户看不出删除是否生效，容易重复操作。删除完成后立即更新弹窗列表、计数和空态；重新打开不应成为刷新手段。 |
| UX-08 / P3 | 搜索 zzzzNoMatch 得到零结果，提示“此分类暂无词汇记录”并引导查词入库；主题学习下已经有四个各 30 条的子笔记，也出现同样提示。 | 将无匹配、父分类和真正空笔记混为一种状态。分别显示“未找到匹配内容 / 清除筛选”“请选择子笔记”和真实空笔记引导。父分类数量明确标为直接词条数或汇总数。 |
| UX-09 / P3 | 大字号、搜索 budget 后，点击词条标题 budget 没有进入详情；同一张卡片点击释义摘要或编辑图标可以打开“词库完整内容”。 | 卡片不同区域响应不一致。为标题区提供明确点击行为，处理标题横向滚动与整卡点击的冲突；保留标签独立筛选。 |

优先级含义：P1 为错误自动淘汰或数据保留风险；P2 为核心结果、统计或操作反馈错误；P3 为入口与文案体验问题。

## 复现证据

- UX-01 / UX-02：[本地命中的 budget](usage-experience/screens/11-local-budget-query.png)、[查询后的首页](usage-experience/screens/12-home-after-local-query.png)、[最近查询](usage-experience/screens/13-recent-after-local-query.png)。[前后差异 JSON](usage-experience/local-query-comparison.json)记录新增 budget、移除 agenda、消息 54→50；[前状态](usage-experience/before-new-local-query.json)、[后状态](usage-experience/after-new-local-query.json)可独立核对。Library.newSession 固定 retention=temporary；touchSession 从数组尾部限五条，并从最近查询数组头部裁十条。原版 query 保留策略与五条 temporary 额度分离，见原项目 storage/conversations.ts 和 QUERY_AGENT.md。
- UX-03：[初始默认笔记统计](usage-experience/screens/15-review-default-baseline.png)、[budget 评级前](usage-experience/screens/17-review-budget-before-again.png)、[评级后下一张](usage-experience/screens/18-review-after-again.png)、[重启后熟练 49](usage-experience/screens/19-review-mastery-increased.png)。[评级数据](usage-experience/after-review-again.json)保留 budget 的 review_grade=再次、review_count=3。ReviewUi 对所有评级增加 review_count，统计又以 count>=3 判定掌握。再次入队后分母还会从 10 变为 11；建议明确区分“本组不同词数”和“尝试次数”，不要让进度含义随评级变化。
- UX-04：[source 全选 120](usage-experience/screens/07-search-source-select-all.png)、[文字字段预期命中 4 条](usage-experience/search-expectation.json)。NotebookUi.visible 对整个 v.toString() 搜索。
- UX-05：[已输入 meeting](usage-experience/screens/08-search-unsubmitted.png)、[切换短语后文字消失](usage-experience/screens/09-search-lost-after-type-switch.png)。NotebookUi 仅在 EditorAction 时保存 filter。
- UX-06：[日期输入](usage-experience/screens/03-date-filter-input.png)、[只有两条结果且已到临时会话标题](usage-experience/screens/05-date-filter-two-results.png)、[8 条 UTC 与本地日期逐项对比](usage-experience/date-filter-comparison.json)。QueryUi.sessionDate 只替换 T/Z，matchesDate 截取前十位。例：evidence 的 2026-10-27T23:00Z 是本地 10-28 07:00，应包含在当天。
- UX-07：[删除后弹窗仍有 context](usage-experience/screens/14-recent-stale-after-delete.png)、[存储已变 9 条](usage-experience/after-recent-delete.json)。删除回调重新渲染 Activity 背景，但没有重建当前弹窗行。
- UX-08：[零匹配错误空态](usage-experience/screens/10-no-search-results.png)、[四个子笔记与空态并存](usage-experience/screens/20-parent-notebook-empty-state.png)。两者落入 NotebookUi 相同的 rows.isEmpty 分支。
- UX-09：[点击前](usage-experience/screens/24-before-word-title-tap.png)、[点击标题后仍是列表](usage-experience/screens/25-after-word-title-tap.png)、[点击摘要后正常打开](usage-experience/screens/26-after-summary-tap.png)。标题被放入 HorizontalScrollView；该组合是需定位的候选原因，不能只修改数据或增加提示来规避。

本轮 UI 辅助脚本为 scripts/audit-usage-ui.ps1。点击均依据当时设备可访问性树定位，截图保留对应 XML。文件名编号表示取证步骤，不代表严格性能计时。

## 可正常使用的部分与后续优化

- 原始 ZIP 通过真实文件选择器完成导入，数量正确；词库分类切换、类型筛选按钮、批量全选可以操作。此次没有执行批量移动或全局删除。
- budget 本地命中可打开两条释义和例句，无需模型服务；英文释义中包含 budget 的 bring up 也出现在搜索结果，这是合理的内容检索。
- 应用内“大”字号下，[词库列表](usage-experience/screens/21-notebook-large-font.png)和[双义项完整详情](usage-experience/screens/23-budget-large-font-detail.png)可读，未确认明显文字裁切。标题和标签共用横向空间，较多标签会被横向隐藏；建议让词条标题优先可读，将标签换行或提供“+N”展开。详情字段可增加统一左右留白和分组间距。
- 笔记独立显示设置与“词库完整内容”属于不同视图，完整内容显示未在当前笔记中勾选的字段不直接认定为数据丢失或缺陷。建议增加“按当前笔记显示 / 查看完整词条”的明确入口，以利遮义复习。
- 120 条列表当前能滚动，但没有用 ADB 操作耗时推断流畅度。建议后续用 RecyclerView 或明确的性能采样评估 500/2000 条、长例句、多批注和长会话，记录首屏、帧耗时与内存后再优化。
- 下一轮先补“原版倒序备份→普通本地查询→临时 AI 第六条→历史第十一条”的持久化回归，再补跨时区日期、三次“再次”不变熟练、搜索与筛选组合以及弹窗删除刷新。

## 数据恢复与验证边界

本轮结束已强制停止应用，将开始时的 library.json、provider_private.xml、before-restore.json 三份快照分别复制回原路径，并在设备上 cmp 逐字节比较成功。原有 before-normal-usage-20261028.json 额外回退文件保留。时区恢复 GMT，临时审查快照删除。重启截图见[恢复后首页](usage-experience/screens/27-after-data-restoration.png)；[恢复检查](usage-experience/restoration-verification.json)及[恢复后的计数](usage-experience/restored-original-state.json)确认 120 词、8 笔记、10 最近查询、5 临时 / 8 保存会话、54 消息、熟练 48。服务私有偏好没有导出到报告。

本次执行离线命令 .\gradlew.bat testDebugUnitTest lintDebug assembleDebug --offline --console=plain，退出 0，BUILD SUCCESSFUL；50 个任务中 49 个复用缓存。现有单测报告为 117 项、0 失败 / 0 错误，不能将其解释成以上场景已有测试覆盖。[构建日志](usage-experience/gradle-verification.txt)。

未调用在线模型、登录、支付或真实相机；样本不含图片，未测真实设备、系统超大字体和旋转。本轮未逐项重新执行批量移动、字段划词批注或长会话恢复，已有基础验证见 [NORMAL-USAGE.md](NORMAL-USAGE.md)。未观察到本轮已执行流程发生应用崩溃。
