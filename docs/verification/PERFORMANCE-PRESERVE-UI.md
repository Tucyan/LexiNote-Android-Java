# 保持 UI 的架构与性能优化：执行进度

记录日期：2026-11-05。工作分支 `codex/android-native-java`，本轮起点 `7073de93b2aea6e582b39eb1cbf4f6d164c30612`。本文件记录实现和实际证据，**不表示全部计划已验收**。用户要求在 5 小时额度剩余 20% 时停止并保存进度。

## 已实现，正在集成验证

- 默认存储切换为系统 SQLite；串行后台队列负责初始化、提交、恢复和导出。评分、每日队列准备、会话保存使用局部事务。迁移保留旧文件，重开数据库验证后才激活；恢复使用暂存代次并保留可回退代次。
- 评分固定事件参数、阻止重复提交，成功确认后推进；支持生命周期状态和已提交事件恢复。异步失败不返回伪成功。兼容快照修改按确认顺序排队，避免连续设置使用同一 revision。
- SAF 备份接入流式 JSON/ZIP、图片映射、CRC 和暂存导入。压缩输入和展开内容分别限制 16 MiB；不再使用旧 4 MiB 内存接口处理生产大库恢复。
- 搜索复用逐字段规范化索引，75 ms 合并输入、后台匹配并拒绝过期结果；保留输入对象和组合文本。批量勾选局部更新，保持每页 40 条及全筛选结果选择。
- 修复审查发现的扩展字段误删除；`token_count` 和 `usage.total_tokens` 保留。凭据字段匹配在迁移、导入、备份中统一。
- 日常大样本单元测试默认不再重写已跟踪 fixture。两份被测试重生成的文件已核实仅 ID 和派生引用变化，并精确恢复到 HEAD；SHA-256 均为 `3831087f16486a47008f5e0ecff046cc92c18843081a376a2eeaf02e36f45b75`。

## 验证证据

原始结果目录：[20261105](performance-preserve-ui/20261105/)。

- 离线 Gradle `testDebugUnitTest lintDebug assembleDebug assembleRelease assembleDebugAndroidTest` 最终检查成功：198 项 JVM，0 失败/错误/跳过；Lint 0 错误、27 警告。最后一轮日志保存在本地 `.planning/2026-11-04-performance/final-checks.log`；后续新增测试需单独核验。
- `IncrementalReviewInstrumentation` 设备通过：迁移、评分领域一致性、事件重放、重开、回滚，并包含局部队列与故障注入断言。见 [SQLite 原始结果](performance-preserve-ui/20261105/sqlite-regression.txt)。
- 合法 10,139,662 字节业务库完成 SQLite → JSON → ZIP → JSON → SQLite 往返，2400 条词汇及未知字段保留。首次运行发现未缓冲 JSON 小写入；增加固定 32 KiB 缓冲后重跑通过，主机计时整段 instrumentation 13,474 ms（包含启动，不是导入单项耗时）。见 [缓冲后结果](performance-preserve-ui/20261105/large-library-buffered.txt)。
- 流式备份设备通过：超过 8 MiB codec、图片映射、敏感字段过滤、未知字段和导入失败保持原库；附有缓冲写调用次数、不关闭调用方流、错误校验和及缺少图片拒绝断言。见 [最终原始结果](performance-preserve-ui/20261105/streaming-backup-final.txt)。
- 连续设置保存设备通过：阻塞首笔后台保存，主题及强调色两次提交均获得成功确认且最终值均持久化。见 [原始结果](performance-preserve-ui/20261105/sequential-settings.txt)。
- 搜索交互设备通过：输入对象/组合文本、过期结果、数据与页面切换、勾选对象稳定性及跨页全选。见 [原始结果](performance-preserve-ui/20261105/notebook-search.txt)。单次 `search_ms=164` 不是重复性能验收。
- 评分提交期间 Activity 重建设备通过：新实例核对评分汇总，只提交一次、持久化一条事件且不重发。见 [原始结果](performance-preserve-ui/20261105/review-recreation.txt)。这不等同于完整进程死亡矩阵。
- 3000 条词汇加 10000 条历史事件，直接流式导入 SQLite、局部准备任务、评分、重复事件查重与重开设备通过，保留 10001 条事件；无整库评分快照。见 [存储隔离结果](performance-preserve-ui/20261105/history-storage.txt)。
- **历史全界面诊断失败：** 同一历史样本的 Performance harness 在初始化 `SqliteLibraryStore.restore → replace → Library.snapshot/copy` 路径触发 192 MiB 堆 OOM，未取得评分性能样本。见 [原始堆栈](performance-preserve-ui/20261105/release-history-diagnostic.txt)。该失败发生在测试的内存恢复夹具，不是生产流式导入入口；但它暴露了兼容快照复制的内存成本，不能据上条存储通过宣称历史大库 UI 已通过。
- 1000 条和 3000 条隔离规模回归各 11 项通过，评分持久化断言已通过。见 [1000 条](performance-preserve-ui/20261105/scale-1000.txt)、[3000 条](performance-preserve-ui/20261105/scale-3000.txt)。真实标题指针 1 项、暂停/归档空态 2 项通过。
- Release 3000 条一轮诊断（1 热身、5 样本）6 组通过；数据在 [诊断原始结果](performance-preserve-ui/20261105/release-diagnostic-3000.txt)。其中 `review_first_plans_gen_ms=7839` 测量的是旧兼容快照路径，不代表新局部队列命令，已修正 harness，后续需重测；其它数值也不能替代三轮验收。采样末尾做过诊断截图，不能把此轮作为严格无干扰性能基线。
- 一次 Release 纯滚动采样完成：140 帧，janky 32（22.86%），P95 34 ms。仅这一轮，不能与不同设备配置的历史 47.41% 直接作改善对比。12,291,295 字节 Perfetto trace 已保存本地，原始字符串含 `notebook.scroll` / `card.bind` 标记；尚未用 trace processor 分析主线程、GPU、GC 因果。[帧结果](performance-preserve-ui/20261105/release-scroll-3000/runner-results/gfxinfo-scroll.txt)。trace 被仓库现有 `*.perfetto-trace` 规则忽略，继续工作时勿清理。
- [设备记录](performance-preserve-ui/20261105/device-metadata.txt)：API 37，1080×2424，420 dpi，系统字体 1.0，192 MiB heap growth limit，约 2 GiB RAM，系统时区 GMT；计划内按显式业务时区工作。此配置与旧报告的 1344×2992 / 576 MiB 不同。
- 在独立源码副本构建原始 `7073de9` 基线并保留 1000/3000 条截图与单次 Debug 数据；两次均暴露旧评分“确认后未持久化”断言失败。最初冷启动遇到系统低内存杀进程，未作为有效采样。不可将这些单次值当成 P95。
  基线 APK 校验值见 [baseline-apk-hashes.json](performance-preserve-ui/20261105/baseline-apk-hashes.json)，来源为原提交的独立 archive，未把候选生产代码复制进基线。系统 tracing 方法参考 [Android 官方说明](https://developer.android.com/topic/performance/tracing)。

## 尚不能宣称完成的部分

1. UI 仍持有可变 Library，部分非高频操作仍使用兼容整库快照和整代重写；主线程快照复制、过滤/排序和 40 张卡片构建尚未全部移出。因此 Task 1/3 的完整 DTO/全命令增量化边界仍未达到。
2. 旧 Smoke/Feature/Persistence 部分测试夹具直接操作应用 legacy 文件，尚未全部迁移为 SQLite 隔离测试；只通过编译不能作为其运行通过的证据。
3. 完整大库/图片故障矩阵、进程死亡与旋转、初始化失败恢复、低内存堆峰值仍需补充实际测试。SAF 目标文件最终拷贝失败可能留下不完整文件，当前不能保证外部文档提供方原子替换。
4. 尚未完成同一基线状态五次截图噪声校准，以及浅深色、大字号、键盘、紧凑/详细和空态的完整视觉对照；不能宣称像素级一致。已查看的候选首屏存在系统剪贴板提示遮挡，不能扩大遮罩使它通过，需等待提示消失后重新采集。
5. 尚未完成 Release 的 3 轮 × 30 样本 × 5 热身矩阵、全历史样本、纯滚动 Perfetto 归因和帧统计验收。当前不承诺耗时目标或滚动帧率；未引入 RecyclerView 或调整视觉样式。

## 继续顺序

后续阶段已并入 [Java 基准版开发计划](../superpowers/plans/2026-11-05-dictionary-baseline.md)。本报告保留上一轮实测结果。

下一步优先把历史性能夹具改为流式初始化，并逐步消除显示加载/非高频保存的全库多次复制；在 192 MiB 配置上重测历史样本，不能提高堆限制掩盖问题。随后完成同设备视觉校准与对照，再做 Release 重复矩阵和滚动 trace 归因；依据 trace 决定是否需要列表回收。已有独立设备 runner、1000/3000 规模、标题与空态本轮已通过，可作为继续工作的回归集。按证据更新计划勾选，最后整理本地提交；不推送远端。用户已有 `docs/architecture/` 未跟踪文档应继续保留。

### 架构复核后的具体调整

继续保留 Java、原生 View 和单 app 模块，当前证据不要求更换 UI 框架或全面搬迁包结构。SQLite 已把热点写入从整库事务分离；下一阶段的重点是读取与显示边界，而非继续堆叠持久化线程。

- `storage/LibraryStore.java:292` 的加载链经过 `loadSnapshot → getRawDocument → new Library`，每层均可能复制全库；优先改为按屏幕所需的分页/统计/单实体只读结果，历史事件不随普通词库页整批加载。
- `SqliteLibraryStore.java:248` 中除事件查重外的普通查询仍建立整库领域适配器；逐类落实直接 SQL 查询，保持原有排序、共享引用和筛选规则。
- `MainActivity.java:754` 的兼容保存仍在 UI 线程取全库 snapshot；将这些入口迁为携带 ID 与字段的命令，消除快照副本及 `SqliteLibraryStore.java:555` 后的整代重写。
- 恢复/回退的提交结果仍有内存快照兼容接口；生产大库路径继续使用流式导入和轻量确认，分离“数据库提交成功”和“页面已加载”。增加真实低堆启动/恢复/失败回调测试后再删除兼容路径。

以上调整保持既有视觉参数及分页交互。本轮 `app/src/main/res` 没有修改；完整截图验收仍按前述待办执行。

## 当前交接状态

修改保存在工作区，尚未提交或推送。当前工作区不干净，包含本轮生产代码、测试和报告；没有 reset/clean，也没有覆盖用户架构文档。模拟器当前安装本轮 Release 应用和最近一次流式备份测试 runner；所有新增测试使用独立 cache 数据库。最新源码及 APK 校验值在同日目录的 `source-hashes.json` / `apk-hashes.json`，manifest 每次仅声明一个 runner，重新执行其它用例前需使用 `-PdeviceRunner=对应完整类名` 构建测试 APK。
