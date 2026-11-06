# 保持现有 UI 的性能与架构优化实施计划

> 2026-11-05 起，继续开发按 [Java 基准版开发计划](2026-11-05-dictionary-baseline.md) 排期。本文件保留原性能指标、复现命令与历史状态。旧笔记字段兼容的验收范围由新计划更新。

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 降低大词库下评分、查词、搜索和滚动的等待，解决整库 JSON 的写入与容量耦合，并保留当前 UI 的视觉与交互。

**Architecture:** 保留 Java 17、原生 Android View 和单 app 模块。先建立独立于 Activity 的串行数据提交入口，再逐步迁移到 SQLite 增量事务；页面继续使用现有样式，渲染优化以 trace 和截图回归为门槛。领域算法与备份字段兼容性继续复用现有实现。

**Tech Stack:** Java 17、Android framework View、ExecutorService、SQLiteOpenHelper、org.json、流式 JSON/ZIP、ADB、Perfetto；仅在实测需要列表回收时增加 AndroidX RecyclerView。

**状态：** 2026-11-05 执行中，尚未整体验收。实际本轮起点 `7073de93b2aea6e582b39eb1cbf4f6d164c30612`，分支 `codex/android-native-java`。Task 1/2 复核后补修异步确认、连续保存和生命周期；Task 3/4 已接入 SQLite 热路径及流式备份并进行设备回归；Task 5/6 已有索引、过期请求保护及局部勾选更新。完整 DTO、非高频命令增量化、视觉噪声校准、Release 重复测量和 trace 归因仍待完成，各任务未因局部实现而统一打勾。实际证据和后续顺序见 [执行进度](../../verification/PERFORMANCE-PRESERVE-UI.md)。`6026d85` 是原计划准备基线，旧 `f3bc5c2` 仅保留为历史来源。

---

## 1. 依据与边界

主依据为 [规模实际交互报告](../../verification/SCALE-USAGE.md)、[Release 画面](../../verification/scale-usage/release-contact-sheet.jpg)、[3000 条机器结果](../../verification/scale-usage/scale-final-3000/metrics.json)和[纯滚动统计](../../verification/scale-usage/release-interaction/gfxinfo-scroll-only.txt)。以下是历史规模报告的既有结果，不是本次修订新跑的性能数据。最新准备结果见 [工作区准备记录](../../verification/WORKSPACE-PREPARATION.md)：157 项 JVM、25 项设备检查通过，Lint 0 错误/27 警告，Debug/Release 及设备测试 APK 构建通过。新增暂停/仅归档入口回归已通过；无需再次实现归档管理入口。该轮 3000 条评分约 1074ms、本地查询约 851ms，仍为单次回归采样，不替换下表历史值或充当分位数基线。

| 当前结果 | 数值 | 对应决策 |
|---|---:|---|
| 3000 条实际评分到 idle | 1076ms | 优先移除主线程整库提交，并保证成功后推进 |
| 本地查词并持久化 | 794ms | 查询显示与历史持久化采用明确的异步状态 |
| 单次工作线程 Repository 保存 | 442ms | 只换线程不能解决总提交延迟，随后做增量事务 |
| 搜索输入到 idle | 401ms | 查询规范化复用、有限索引、请求代次保护 |
| 首次生成 12 个计划任务 | 767ms | 计算和持久化离开 UI 线程，已有任务保持只读 |
| 纯滚动 | 47.41% janky，P95 61ms | 先 trace 区分 measure/layout、draw、GPU 和 GC |
| 3000 条基础 JSON | 2,770,811 字节 | 不能以词条数作为容量保证，历史需增量存储 |

设备为 API37/x86_64 模拟器、1344×2992、576MB 应用最大堆；没有低端物理手机结论。原评分、搜索等数据为单次设备采样，不能充当后续 P95 基线。

不重新实现已完成的 40 条分页、引用 ID 集合、只读复习不写盘及标题点击修复。保留全筛选结果的批量选择与导出、共享词汇引用、每笔记独立显示、600 条消息留存和旧数据兼容规则。

## 2. 视觉与交互约束

将本报告 Release 作为视觉方向；实施前从当前工作区重新构建并冻结同数据、同状态截图作为逐项比对基线。

- 不调整颜色、字体及字重、图标、圆角、边框、卡片内外边距、阴影、按钮高度和导航位置。
- 保留紧凑/详细卡片、中文/英文及例句显示、标签样式、笔记树层级、复习翻面和横滑计划。
- 保留每页 40 条及首页/上一页/下一页/末页。内部使用 RecyclerView 时也不切换为无限滚动。
- 保留列表标题可点击、详情正文可选择、长按操作和批量勾选；不得用关闭全部文字选择换性能。
- 保存期间仅允许现有操作按钮进入暂时不可重复提交状态；不插入全屏加载页、不移动内容。无障碍提示复用现有提示通道。
- 先减少后台工作与无效重绘，再考虑列表容器。不得通过删减例句、标签、批注、装饰或内容密度来达到性能数字。
- 普通/大字号、浅色/深色、紧凑/详细、键盘显示、空态都需要对照。截图数据固定；只遮罩系统时钟、电量、网络状态和光标闪烁。
- 同设备对照时字号与换行一致，主要控件位置/尺寸偏差不超过 1dp。先对同一基线 APK、同数据同状态重复截图至少 5 次测量噪声，再固定像素比对阈值；初始参考值为色差超过 8/255 的像素不超过非遮罩区域的 0.5%。阈值校准必须在优化前记录，不能看过改版结果后放宽阈值或扩大遮罩。
- 背景/强调色、字体/字号/字重、图标、卡片尺寸与间距、文字换行分别检查；关键视觉回归即使落在全屏像素容差内也不通过。暂停与仅归档管理入口加入截图和触摸矩阵。

## 3. 分期交付与优先级

| 子项目 | 任务 | 可独立交付的结果 | 前置 |
|---|---|---|---|
| A：测量、存储契约与状态正确性 | 0–2 | 冻结跨后端协议，接入串行提交；失败不假成功、重复点击不重复评分 | 6026d85 |
| B：增量持久化与备份 | 3–4 | 评分/历史局部事务；可验证迁移；超过 4MiB 的库可备份恢复 | A |
| C：搜索与渲染 | 5–6 | 输入反馈和滚动改善，截图与交互一致 | 0；接入 A/B 的数据版本 |
| D：交付验收 | 7 | Release 实测、兼容性证据、视觉对照和剩余限制 | A/B/C |

建议按 0 → 1a（协议/契约测试）→ 1b（旧 JSON 适配与异步接入）→ 2 → 3 → 4 推进，再验收 5–6。A 先冻结读写、提交结果、事件查重、版本和恢复协议，B 只替换后端及增量实现，不再次改变 UI 调用协议。评分和本地查询优先接入，其余修改入口在 A 验收前全部收口，不能让同步界面写入与后台可写 Library 并存。C 的 trace 可在 A/B 之前采集，但不要同时大改数据层和页面容器。每个子项目留一个可恢复检查点。实施时的提交、推送和设备数据替换按用户授权范围处理；本次修订只保存文档的本地提交，不执行应用实现、推送或设备数据替换。

## 4. 文件职责地图

现有 Java 文件均位于 `app/src/main/java/com/lexinote/nativeapp/`，新增类型先留在同包，避免一次迁移所有 package-private 调用。

| 文件 | 职责/调整 |
|---|---|
| `MainActivity.java` | 保留生命周期、导航、公共 View 工厂；逐步委托提交/屏幕状态，取消同步整库落盘 |
| `Repository.java` | 迁移期保留旧 JSON 存储适配；读、写、恢复、回退全部经统一入口 |
| 新 `LibraryStore.java`、`LibraryCommand.java`、`CommitResult.java`、`StoreBackend.java` | 统一读写契约、串行提交、版本/恢复代次、持久化确认；UI 不接触后端或可写 Library；不持有 Activity/View |
| 新 `ReviewSessionState.java`、`NotebookScreenState.java` | 保存当前卡片、提交状态、统计、筛选、页码和阅读锚点 |
| `ReviewUi.java`、`QueryUi.java`、`NotebookUi.java` | 提交操作并绑定结果，避免直接修改共享实体或根据失败提交更新统计 |
| `ConversationTitles.java` | 通过 session ID、revision、epoch 应用命名结果，经过同一提交入口 |
| `Library.java`、`ReviewPlans.java`、`ReviewLogic.java`、`ReviewPlanSchema.java` | 保留业务规则；提取局部命令、受影响实体及增量校验，避免每次全库验证 |
| 新 `LibraryDatabase.java`、`SqliteLibraryStore.java`、`LegacyLibraryMigration.java` | SQLite schema、局部事务、首次迁移及一致性校验 |
| `BackupArchive.java`；新 `BackupStreamCodec.java`、`BackupLimits.java` | 旧内存接口继续供小样本测试；生产导入导出接入分段读写与明确配额 |
| `NotebookSearch.java`；新 `NotebookSearchIndex.java` | 同一规范化规则、按实体版本更新的字段索引，不改变匹配范围 |
| 新 `VocabularyCardView.java`；条件性新增 `NotebookResultsAdapter.java` | 保留卡片 View 构建参数，分离 create/bind；必要时回收列表 View |
| `ScaleInstrumentation.java`；新 `PersistenceInstrumentation.java`、`UiParityInstrumentation.java`、`PerformanceInstrumentation.java` | 独立数据目录下验证迁移/故障、截图、真实操作耗时 |
| 新 `scripts/audit-performance-preserve-ui.py` | 每次独立输出目录；固定动作、截图对照、分位数和产物摘要 |

不在本轮引入多 Gradle 模块、UI 框架切换或全项目文件搬迁。SQLite 先使用系统 `SQLiteOpenHelper`，避免为了优化强行增加 ORM/注解处理链；如实施时选择 Room，必须另记迁移测试、构建成本和依赖版本依据。

## 5. 执行任务

### Task 0：冻结可复现的性能和视觉基线

**文件：** 读取现有报告和样本；新增 `scripts/audit-performance-preserve-ui.py`、`app/src/androidTest/java/com/lexinote/nativeapp/PerformanceInstrumentation.java`；结果写入 `docs/verification/performance-preserve-ui/baseline/`。

- [ ] 从 `6026d85` 或其仅文档修订后继提交开始，记录实际 HEAD、diff、未跟踪文件清单、源码/APK/样本 SHA-256；先确认干净状态。有新的未提交修改则保留并列明来源，不运行 reset/clean。
- [ ] 测试默认使用唯一 cache 目录的数据仓库；若需修改已安装应用的数据，先保存实际存储文件、回退文件及加密配置并核验恢复，不能依赖测试 finally 作为唯一保护。
- [ ] 固定数据、时区、应用字号、系统字号、主题、分辨率、刷新率、动画和键盘状态；冷启动与热操作分开。
- [ ] 记录输入事件、首次可见反馈、业务提交成功和 UI idle 四个时间，避免把禁用按钮的快反馈等同于写入完成。
- [ ] 每场景 5 次热身、30 次测量、3 轮；独立记录 1000 条、3000 条、3000 条加 1 万评分事件。历史样本通过合法业务命令生成，不直接拼出不满足 schema 的事件。
- [ ] 评分/查询每个样本开始前恢复同一份隔离数据及页面状态；恢复、队列准备、清理和截图不计入动作耗时。固定计划、卡片、日期、评分、历史数量和缓存预热策略，避免连续 30 次评分改变队列、配额与数据大小。热身也在独立副本执行，不污染正式样本。
- [ ] 冷启动、首次队列生成、已有队列读取和纯滚动分别测量；采用相同 Release/benchmark 构建配置，保留原始样本并逐轮计算分位数，不把 Debug 到 idle、首次反馈和持久化成功混成一个指标。
- [ ] 超出旧 JSON 容量的历史样本，旧后端只记录领域/容量拒绝基线，增量后端完成后再测正常提交；不能先放宽生产上限把样本强行写入旧库。
- [ ] 采集笔记纯滚动的 Perfetto，标记 `command.prepare`、`command.persist`、`query.filter`、`card.bind`、`screen.render`；不同时采样键盘输入和滚动来推断卡片瓶颈。
- [ ] 截图覆盖书架、紧凑首/末页、详细列表、搜索结果、词汇详情、复习正/背面/评分后、空计划、长会话、设置。浅深色和大字号分别留基线。

**检查点：** 基线有来源与操作记录；不能将旧的单次结果重新标为多次 P95。实测前先读 [系统 tracing 文档](https://developer.android.com/topic/performance/tracing)。

### Task 1：先冻结跨后端契约，再建立串行异步提交入口

**文件：** 新建 `LibraryStore.java`、`LibraryCommand.java`、`CommitResult.java`、`StoreBackend.java`；修改 `Repository.java`、`MainActivity.java`；新增 `StorageContractTest.java`、`LibraryStoreTest.java` 与 `PersistenceInstrumentation.java`。

**Task 1a：先定义并测试公共契约。** `LibraryCommand` 表达业务动作，不以 `apply(Library workingCopy)` 作为界面协议；可写 Library 只存在于旧后端适配内部。JSON 和 SQLite 运行相同契约测试，UI 不感知后端切换。

| 操作 | 必须明确的约定 |
|---|---|
| 读取/查询 | 异步返回不可变展示 DTO、generation、revision；不得通过返回 JSONObject/JSONArray 泄露可写状态 |
| 提交命令 | 输入只含业务 ID、必要 payload、操作 ID、预期 generation；一个命令原子成功或失败，成功后返回已持久化 revision |
| 查询评分事件 | 按 generation 与 eventId 查询持久记录，并核对原评分、时长和发生时间；JSON 阶段查已保存事件，SQLite 阶段查唯一索引 |
| 恢复/回退 | 是带代次屏障的排队操作；旧命令拒绝而非静默应用到新数据；成功后发布新的 generation |
| 失败与解绑 | 返回明确错误且不发布成功状态；回调只交给当前有效订阅，Activity 销毁不取消已受理事务 |
| 生命周期重连 | 重新读取已提交视图；有可恢复 eventId 时查询结果，没有时不自动重发评分 |

- [ ] 为成功、失败、命令顺序、事件查重、恢复屏障、取消订阅和只读结果编写后端无关契约测试，先用可控假后端验证；明确 `CommitResult` 和读取 DTO 的字段再接入页面。

**Task 1b：实现 JSON 适配与串行入口。** 评分、本地查询先接入，并沿同一协议收口其余修改入口；SQLite 只实现既定 `StoreBackend` 及局部事务，避免重复改造调用链。

- [ ] 先写故障/调度测试：阻塞式假存储期间 UI 事件可以执行；失败不发布新版本；两个命令按序提交；恢复前排队命令不能覆盖恢复后的数据。
- [ ] 一个应用进程级 Store 持有独立的串行执行器；网络请求和 ZIP 编码继续使用各自工作线程，避免一个慢网络请求挡住评分。Activity 销毁仅解绑 UI 回调，不能关闭全局存储执行器。
- [ ] 所有可写 Library 归 Store 工作线程所有；旧 JSON 适配期间的候选复制、验证、编码和落盘也在该线程执行。UI 仅持有成功提交版本的只读展示数据。
- [ ] 审计 `Library.put`、`settings()`、`array()`、`find()` 返回对象的界面写入，以及 `persist()`/`change()` 全部调用点。最终禁止 UI 修改共享 JSONObject 后再让后台线程遍历它。
- [ ] 将渲染期间可能创建数据的 `dailyQueue()` 拆成显式准备命令和只读队列视图；`hasDailyQueue()` 的检查与创建必须在同一个串行命令内完成，不能让页面读操作悄悄写入。
- [ ] 读、保存、恢复、回退在同一入口排队；恢复作为代次屏障，拒绝旧代次命令和旧 AI 回调，避免先写盘再发现 Activity 失效。
- [ ] 持久化成功才发布版本和结果；失败保留之前版本。回调在主线程执行，已销毁页面不接收回调，新页面可读取最新已提交状态。
- [ ] 暂保留 JSON 容量和兼容行为；阶段 A 只能宣称主线程不再被保存占用，不能宣称 442ms 的存储耗时或 4MiB 限制已经消失。

**验收：** StrictMode/trace 无评分或本地查询触发的主线程磁盘读写及全库复制；并发、恢复屏障、失败回滚测试通过。不能仅把 `repository.save(library)` 放到 worker 就验收。

### Task 2：统一提交状态、失败处理和页面恢复

**文件：** 新建 `ReviewSessionState.java`、`NotebookScreenState.java`；修改 `ReviewUi.java`、`QueryUi.java`、`NotebookUi.java`、`MainActivity.java`、`ConversationTitles.java`；新增 `ReviewSessionStateTest.java`、`CommitFailureInstrumentation.java`。

- [ ] 先复现失败路径：存储拒绝评分时卡片、已完成数和汇总不变；词汇保存失败不出现成功提示；双击简单只产生一个事件；退出/重建后提交仅生效一次。
- [ ] 评分点击时生成固定 `eventId`、评分、时长和发生时间；同一次操作重试复用同一 payload，利用现有 `ReviewPlans.record` 幂等保护。成功后一起推进卡片、进度和汇总。
- [ ] 保存中禁用相关按钮并保留原卡片布局；失败恢复可操作状态，通过现有消息通道提示，用户重试前不显示下一张。
- [ ] 本地查询可先显示已命中的内容，但“已记录/已保存”必须等待提交成功；记录失败只影响记录状态，不能伪装已保存。
- [ ] 用 session ID/revision/epoch 替代跨异步边界的 JSONObject 对象身份判断；标题、AI 补充和查询结果只对正确的数据版本生效。
- [ ] 保存笔记筛选、排序、页码、锚点，保存复习计划/日期/当前任务 ID、正背面和会话汇总；禁止把整份库塞进 Bundle。
- [ ] 卡片耗时用单调时钟记录前台可见时间，退后台暂停；进程恢复从已持久化事件重建计数，并重置当前显示计时，避免 `cardStartTime=0` 被算成 24 小时。
- [ ] 页面状态只存已提交结果与提交标识；Activity 重建后重新绑定 Store。进程重启后通过统一存储接口查询固定 eventId 的已持久化记录；本任务先由 JSON 后端实现，Task 3 再由 SQLite 实现，不提前依赖数据库。若没有可恢复操作标识，只刷新已提交队列/统计，不自动重发评分。

**验收：** 在 JSON 后端完成保存失败、快速双击、旋转、深浅主题切换、进程死亡后返回、评分跨本地日期及恢复期间退出用例；Task 3 使用相同契约和设备用例验收 SQLite。暂停/仅归档管理入口原有回归继续通过；截图主内容保持原布局。

### Task 3：SQLite 增量事务与可恢复迁移

**文件：** 新建 `LibraryDatabase.java`、`SqliteLibraryStore.java`、`LegacyLibraryMigration.java`；修改 `LibraryStore.java`、`Library.java`、`ReviewPlans.java`、`ReviewPlanSchema.java`；测试 `LibraryMigrationInstrumentation.java`、`IncrementalReviewInstrumentation.java`。

先保留实体 JSON 以降低字段迁移风险；以下为数据粒度，不把整库 JSON 当作一个数据库 BLOB：

所有业务表的主键/唯一约束还包含 `generation`，查询限定当前活动 generation；下表省略该公共前缀。切换和上一版本指针保存在同一数据库事务内，避免依赖一个跨文件的启用标记。历史事件 ID 在不同 generation 中可保留原值。

| 表/索引 | 主键与顺序 | 保存内容 |
|---|---|---|
| entities | `(kind,id)`，显式 ordinal | 词汇、笔记、保存会话、最近查询各自完整字段；同 ID 跨集合不得误合并 |
| document_meta | key | settings、版本及未知顶层字段，不包含连接凭据 |
| review_plans | plan_id、ordinal | 计划完整字段 |
| review_states | `(plan_id,vocab_id)`、ordinal | 独立复习进度 |
| review_days | `(plan_id,date)`、ordinal | 固定当日任务、重试顺序与完成状态 |
| review_events | event_id 唯一、递增提交序号 | 每条答题事件，保留插入顺序 |
| derived_indexes | entity ID 和数据版本 | 词汇规范化内容、引用和搜索投影；只作派生索引，不成为第二份权威数据 |

- [ ] 先用旧 1.0/1.1 JSON、Expo 2.0–2.2 ZIP、3000 条含计划样本验证迁移前后语义等价；注入未知顶层/实体字段，检查没有丢失。
- [ ] 建立新数据库事务，逐实体写入并校验 ID、顺序、树结构、共享引用、显示配置、计划配额、事件幂等与 CRC。对迁移注入中断、空间不足和损坏输入，旧文件始终保留可读。
- [ ] 首次迁移只有在事务完成、计数/关键内容校验和复开校验成功后才启用新后端；原 `library.json` 作为迁移前只读副本保留。迁移失败继续使用旧后端并明确报错。
- [ ] 切换成功后只用一个权威后端，不长期双写 JSON 和 SQLite；自动切回过期 JSON 会丢新增记录，因此不作为数据库运行错误的默认回退方式。
- [ ] 评分事务只更新相关 state、day、event 及必要统计/索引；本地查询只更新相关会话和最近查询。不能为找差异遍历/序列化全库，也不能每次加载全部历史评分。
- [ ] 将全量 `Library.validate` 留给迁移/导入和完整校验；正常命令按受影响实体与约束验证。数据库事务完成前不得发布新 UI 状态。
- [ ] 每笔记显示、未知字段和顺序保留原协议。删除词汇后的历史评分按照现有业务规则保留，不能用级联删除静默清空学习历史。
- [ ] 导入和“回到上次恢复前”改用同一数据库的 generation 数据集：导入到非活动 generation，校验后事务切换活动指针；保存上一 generation 供回退。避免在打开的 WAL 数据库旁直接替换单个 .db 文件。
- [ ] 明确 generation 生命周期：活动版、最近恢复前版本和正在备份读取的版本不得清理；失败导入的暂存版本可清理。无读取引用且不再保留的旧版本在串行存储入口分批清理，测试中断后可继续，不能积累每次导入的全部历史副本。
- [ ] 迁移/恢复验证同时覆盖进程死亡、磁盘空间不足和重新打开。恢复指针和数据集必须一致；SQLite 后端重新执行 Task 1a 的全部契约测试及 Task 2 的设备状态回归。
- [ ] 旧 APK 回退必须配套恢复同一检查点的迁移前数据/图片/本机设置，并先保留迁移后的最新数据；不能只覆盖安装旧 APK 后让它读取过期 library.json。默认优先修复或前进恢复，禁止自动降级导致新增学习记录丢失。

**验收：** 1000/3000/评分历史增长三档评分写入的数据量基本恒定；超过 4MiB 的合法数据不阻止正常评级；迁移/导入中断不会损伤原库。此时仍需 Task 4 才能宣称大库备份闭环完成。

### Task 4：流式备份和兼容闭环

**文件：** 新建 `BackupStreamCodec.java`、`BackupLimits.java`；修改 `BackupArchive.java`、`MainActivity.java`、`SettingsUi.java`、`NotebookExport.java`；测试 `StreamingBackupInstrumentation.java` 和现有备份 JVM 套件。

- [ ] 先生成至少 8MiB 合法文本且包含复习历史的样本，建立导出—导入—回导的语义等价检查；另补真实图片、旧部分释义、未知字段样本。
- [ ] 按实体从一致的数据库读取事务流式输出，不构造全库 JSONObject、String 和 byte[]；ZIP CRC32 随写计算，checksums 条目在数据之后写入即可，不改变现有字段和归档格式。
- [ ] 用临时磁盘文件/非活动 generation 暂存导入，边读边核验字段、配额、图片与 CRC，全部通过后切换；失败清理本次暂存内容，不动活动库及上一回退版本。
- [ ] 分离本地数据库容量、导入文件容量、解压容量、单实体容量和工作缓冲预算。首轮继续保留 16MiB 总归档/解压预算，流式文本可使用该总预算，至少通过 8MiB 文本样本；图片计入同一总预算，不能宣称任意大小均可备份。
- [ ] 上述 16MiB 是拟定的首轮预算，不直接放宽旧内存解码路径。流式路径在受限堆环境通过前，继续保留原保护；若失败，先修复峰值内存，不把阈值调大使测试通过。
- [ ] 区分格式兼容与容量兼容：继续支持旧版字段/ZIP结构，但超过旧版 4MiB 或其设备动态容量的导出不承诺旧 APK 可读；用小于旧限值样本做旧解码器互通，用 8MiB 样本做新流式路径闭环，分别记录。
- [ ] 16MiB 是完整备份的首轮预算，不是数据库容量上限。超过预算时完整备份明确失败且保留原数据，不静默丢图、删评分或导出不完整库；现有筛选导出只能称为部分导出。将容量不足列为剩余限制，后续提高完整备份预算须另做内存/磁盘验证，不能宣称已解决任意规模备份。
- [ ] 导出使用临时目标，校验并完成后才报告成功；超限/取消不覆盖已有有效备份。长事务/版本读取结束后释放保留引用，再允许清理旧 generation。
- [ ] 不修改 SecureSettings 的本机隔离边界；备份脱敏、异常路径、缺图报错、跨设备图片重映射和恢复前预览继续有效。

**验收：** 8MiB 纯文本库可评分、备份、恢复和回导；大图/配额超限清楚拒绝；导出时连续评分不会造成混合版本备份。最低 API26 和受限堆设备/模拟环境分别检查。

### Task 5：搜索与局部更新

**文件：** 修改 `NotebookSearch.java`、`NotebookUi.java`；新增 `NotebookSearchIndex.java`、`NotebookSearchIndexTest.java`。

- [ ] 先测试 Unicode/NFKC、空白、大小写、释义/例句/批注/标签命中、树范围、共享引用与全量选择；结果和原 `NotebookSearch.matches` 一致。
- [ ] 每次输入只规范化 query 一次；实体字段按提交版本生成只读投影，修改词汇、标签、批注后只重建受影响 ID 的索引，导入/回退后按 generation 重建。
- [ ] 较重匹配在查询执行器运行；最大 100ms 输入合并窗口，使用 queryRevision+dataRevision+scope 丢弃旧结果。开始新查询时禁止在旧结果上触发批量删除/导出，结果就绪后恢复现有按钮，不改变搜索框布局。
- [ ] 保留全文字段子串匹配语义，不直接用 SQL LIKE/FTS 替换导致中文、短语或标点命中变化。SQL 只做安全的候选范围收缩。
- [ ] 结果只替换列表区域和数量标签，保留 EditText、键盘、焦点及输入法组合文本；范围/筛选改变仍回首页，详情返回保留原筛选与页码。

**验收：** 快速输入、撤销、输入时编辑词条、切换笔记、恢复数据都不出现旧结果覆盖；全选与导出范围始终等于完整筛选结果。

### Task 6：保留视觉的绘制优化

**文件：** 修改 `NotebookUi.java`、`MainActivity.java`；新增 `VocabularyCardView.java`；仅在 trace 指向列表构建/测量且优化不足时新增 `NotebookResultsAdapter.java` 并修改 `app/build.gradle`。

- [ ] 先使用 Task 0 trace 定位热路径；CPU measure/layout、重复 bind、draw、GPU 模拟器负载、GC 分别记录，不能把 47.41% jank 全部归因于 ScrollView。
- [ ] 将现有卡片构建分离为 create 与 bind，尺寸、字体、边距、颜色仍复用原工厂参数；选中/评级只更新受影响卡片、进度和统计，避免 `root.removeAllViews()`。
- [ ] 减少重复设置相同布局属性、重复创建图标 Typeface、无内容变化的 Drawable 和格式化结果；样式缓存必须按主题/字号/强调色失效，不能在不同 View 间共享会串状态的可变 Drawable。
- [ ] 若仍有明显 layout/bind 瓶颈，接入 RecyclerView + stable IDs + 精确 item 更新，继续每页 40 条；控件顺序、分页位置、卡片间距与原先相同。stable IDs 使用无碰撞的持久行 ID/映射，不能直接用 String.hashCode。先核实兼容版本并使用现有阿里云 Maven 源。
- [ ] 笔记列表使用独立可回收滚动宿主，不将 wrap_content RecyclerView 嵌入 ScrollView；MainActivity 只为该页面增加内容宿主，其他页面保持原滚动行为。
- [ ] 回收绑定时更新 ID、listeners、选中态、标签、显示配置、可选择状态；禁用不必要的列表更换动画，避免闪烁和内容位移。
- [ ] 每次内部重构后对照 Task 0 截图，复测真实指针标题点击、长按、横滑、勾选、返回锚点和详情选文；页面偏移或文字换行变化立即修复。

**验收：** 视觉门槛与真实输入用例通过，且 trace/重复帧测量说明收益。若主要瓶颈为模拟器 GPU，保留没有收益的容器改造之前必须提供物理设备或其他渲染后端对照，不能靠重写 UI 宣称修复。

### Task 7：综合验收和交付

**文件：** 新增 `docs/verification/PERFORMANCE-PRESERVE-UI.md`；产物放 `docs/verification/performance-preserve-ui/`；根据完成范围更新 README，不重写历史规模报告的测量值。

- [ ] 运行全部 JVM 测试、Lint、Debug/Release 构建；保留当前 157 项测试并增加实际新覆盖，不能以删测试或放宽断言换通过。
- [ ] 运行当前 25 项设备基线：1000/3000 规模各 11 项、标题真实触摸 1 项、暂停/仅归档入口 2 项；补交互事务失败、数据迁移、恢复回退、并发导出、生命周期恢复和视觉矩阵。
- [ ] 按 Task 0 的相同 Release 方法输出三轮 P50/P95/P99、帧指标、trace、冷启动、内存与截图差异。设备/刷新率/后台负载变化单独记录，不混用 Debug 和 Release 数字。
- [ ] 至少 30 分钟反复搜索、翻页、详情往返、复习及长会话切换；记录 GC 后内存趋势和 bitmap/图片压力。没有物理设备时明确保留该项限制。
- [ ] 验证标准库、共享引用、未知字段、旧备份、8MiB 文本、图片及坏档；新数据库故障不自动覆盖迁移前文件，加密设置逐字节保持。
- [ ] 每个子项目独立记录变更文件、功能通过情况、视觉差异和未达性能目标。未达标时保留基线及 trace，不能因没有 ANR/崩溃而称整体流畅。

## 6. 量化验收目标

以下是实施目标，不是已有结果。先建立同方法基线，再看三轮是否一致达标。

| 指标 | 目标 |
|---|---|
| 评分首次反馈 | P95 ≤100ms，不等同于提交成功 |
| 评分持久化成功并显示下一张 | 3000 条 P95 ≤250ms；历史增加到 1 万事件后不出现整库线性增长 |
| 本地查词内容显示 | P95 ≤150ms；历史记录成功单独测量，目标 P95 ≤250ms |
| 搜索最后输入到正确结果 | 包含合并窗口的 P95 ≤200ms，不丢输入法组合文本 |
| 已有计划切换 | P95 ≤100ms；只读路径不写盘 |
| 首次 12 计划生成 | P95 ≤500ms，计算/落盘均不阻塞 UI |
| 同模拟器纯滚动 | jank 比例 ≤25%、P95 ≤40ms；真机按实际刷新率另设帧预算，不据此宣称 60fps |
| 启动/列表进入 | 同方法重测后中位数相对新基线不退化超过 20% |
| 数据可靠性 | 故障注入无部分提交，重试不重复事件，备份为一致版本，设置/凭据不被覆盖 |
| 视觉 | 第 2 节截图及尺寸/换行约束全部满足 |

## 7. 执行命令与通过依据

在 Java 独立仓库根目录执行，优先离线缓存；需要下载时沿用阿里云镜像。超时/权限同因最多三次，已知原因解决后再继续，不无界重试。

```powershell
.\gradlew.bat --offline testDebugUnitTest lintDebug assembleDebug assembleRelease
.\gradlew.bat --offline assembleDebugAndroidTest '-PdeviceRunner=com.lexinote.nativeapp.ScaleInstrumentation'
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e size 1000 -e mode final com.lexinote.nativeapp.test/com.lexinote.nativeapp.ScaleInstrumentation
adb shell am instrument -w -e size 3000 -e mode final com.lexinote.nativeapp.test/com.lexinote.nativeapp.ScaleInstrumentation
adb shell am instrument -w -e size 3000 -e mode title com.lexinote.nativeapp.test/com.lexinote.nativeapp.ScaleInstrumentation
adb shell am instrument -w -e size 1000 -e mode empty-states com.lexinote.nativeapp.test/com.lexinote.nativeapp.ScaleInstrumentation
```

新增 runner 在各自任务中创建后，按完全限定类名通过 `-PdeviceRunner=...` 构建。实际安装前保存成套 APK 和数据检查点；迁移后不能只回装旧 APK。以上是后续实施的复现命令，本次计划修订不运行它们。

预期：Gradle 退出码 0、JUnit 无失败/错误、Lint 无错误且不新增未解释警告；所有设备用例失败列表为空。性能是否达标和视觉是否一致分别验收，不用测试数量替代。

## 8. 技术依据

以下官方资料用于实施选型，不要求更换 Java 或现有界面风格。

- [AtomicFile 线程保护要求](https://developer.android.com/reference/android/util/AtomicFile#startWrite())：文件原子替换不提供调用方的并发事务。
- [SQLiteOpenHelper](https://developer.android.com/reference/android/database/sqlite/SQLiteOpenHelper)：系统数据库生命周期与升级接口。
- [JsonReader](https://developer.android.com/reference/android/util/JsonReader)、[JsonWriter](https://developer.android.com/reference/android/util/JsonWriter)：分段 JSON 输入输出。
- [View 层级性能](https://developer.android.com/topic/performance/rendering/optimizing-view-hierarchies)：按测量/布局证据优化，避免依靠外观删减。
- [RecyclerView](https://developer.android.com/develop/ui/views/layout/recyclerview)：需要时复用 View，保持原卡片布局及分页语义。
- [保存 UI 状态](https://developer.android.com/topic/libraries/architecture/saving-states)：区分持久业务数据、屏幕状态和进程重建。

## 9. 本计划复核

- [x] 已读取用户指定报告、机器指标和纯滚动统计，并查看 Release 拼图。
- [x] 把已完成的分页/集合/只读保存优化与后续任务区分，未重复安排为待修复问题。
- [x] 视觉基线、功能约束、失败语义、迁移回退及备份容量均有任务和验收条件。
- [x] 执行文件、命令、依赖和阶段检查点明确；未把性能目标描述成既有测量结果。
- [x] 本次仅修订本计划，不修改应用源码、测试源码、设备数据或其他计划的任务完成状态；按工作区准备要求保存本地文档提交，不推送。
- [x] 修订覆盖基线、统一协议、阶段依赖、迁移回退、备份兼容/配额及重复测量/视觉噪声校准；所有优化实现任务继续保持未执行。
