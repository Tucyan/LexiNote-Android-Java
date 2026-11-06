# LexiNote Java 基准版开发计划

> 实施时使用 executing-plans 或 subagent-driven-development 按阶段推进。复杂任务委派给 `gpt-6.1-terra`，简单任务委派给 `gpt-6-luna`，推理强度统一为 `high`。

**目标**　个人词库未收录的查询优先使用 ECDICT，有上下文时由 AI 补充语境解读，用户可主动联网核对。应用按字段记录 AI 生成标记，并完成上次中断的大库与基准验收。

**架构**　沿用 Java、原生 View、现有结果卡和按义项保存。内置词库使用独立的只读 SQLite，个人数据继续走现有 SQLite 存储队列。检索、词典数据与模型生成内容在写入时分别记录来源。

**范围**　联网首版只有「联网核对」。测试数据直接升级到新结构。旧笔记字段兼容与自动迁移退出本轮验收，现版新格式备份恢复仍是交付项。AI 生成标记只写数据，查询页新增的是词库来源和联网核对区。

**当前验证（2026-11-06）**　阶段 0–5 的主要链路已实现。真实 Android 已验证 ECDICT 命中、模型语境补充、TokenDance 联网核对、义项保存、失败恢复与已提交数据跨进程读取。两项查询修复分别覆盖确认语境丢失及首次保存覆盖释义编辑。阶段 6 的完整视觉对照、更多联网样本及计费仍待验证。Release 性能矩阵按用户指示暂停。详见 [实施与验收记录](../../verification/DICTIONARY-BASELINE.md)。

## 阶段 0　接住当前工作区

- [ ] 核对 `codex/android-native-java`、HEAD、未提交改动及未跟踪源码。规划时 HEAD 为 `7073de9`。保持 `docs/architecture/` 用户文件、上一轮原始截图、trace、APK 哈希及 OOM 堆栈可追溯。
- [ ] 从当前源码复验 JVM、Lint、Debug/Release 及测试 APK，跑原有 SQLite 评分、备份、搜索和 Activity 重建用例。198 项 JVM 通过、两组规模各 11 项通过属于上一轮记录，作为回归参照。
- [ ] 将通过复验的已实现代码作为独立的可恢复检查点，记录源码和 APK 哈希。后续分开整理存储修复、词库接入和联网改动，避免把未核验的工作混在一起。

通过条件为当前工作区的变更归属、已通过项目和待修问题可核对，存在可恢复的检查点。见 [上一轮执行报告](../../verification/PERFORMANCE-PRESERVE-UI.md)。

## 阶段 1　解除大库断点

涉及 `app/src/androidTest/java/com/lexinote/nativeapp/PerformanceInstrumentation.java`、`HistoryStorageInstrumentation.java`，以及生产链路的 `storage/LibraryStore.java`、`SqliteLibraryStore.java`、`MainActivity.java`。

- [ ] 先改历史性能夹具。把 3000 条词汇加 10000 条历史事件通过文件流导入隔离 SQLite，样本重置使用同一份种子文件。避免在夹具里把全库读成 JSON 再走内存 `restore`。保留原先 OOM 记录，并分别记录夹具耗时和实际操作耗时。
- [ ] 用 192 MiB 堆分别验证夹具初始化、真实 Activity 冷启动、词条进入、搜索、评分以及导入后重新打开页面。测到哪一段仍有整库副本，再修对应生产入口。优先处理显示加载、查单个词条、历史统计及实际保存链路；让评分事件按需查询，避免随着普通词条页读入全量历史。
- [ ] 核对同一固定数据在重开前后的词条、引用、历史事件计数与评分结果。确认评分成功只追加一次，取消或失败后的界面与已提交数据一致。

通过条件为原历史样本能跑完上述设备场景，结果可复现，堆上限保持 192 MiB。历史存储 runner 已通过，但上轮全界面测试在夹具 `restore → replace → Library.snapshot/copy` 处失败。两条路径分开验收。见 [存储通过记录](../../verification/performance-preserve-ui/20261105/history-storage.txt) 和 [OOM 堆栈](../../verification/performance-preserve-ui/20261105/release-history-diagnostic.txt)。

## 阶段 2　定稿字段契约并升级测试数据

涉及 `app/src/main/java/com/lexinote/nativeapp/AgentProtocol.java`、`QueryWorkflow.java`、`QueryUi.java`、`Library.java`、`BackupStreamCodec.java` 和流式导入校验。测试数据来源在 `scripts/create-scale-fixture.py`、`scripts/create-normal-usage-fixture.cjs`、`app/src/test/java/com/lexinote/nativeapp/GenerateScaleHistoryTest.java`，输出在 `app/src/androidTest/assets/` 与 `docs/fixtures/`。

- [ ] 固定数据契约。查询结果继续使用 `word/type/phonetic/meanings`，笔记词汇使用 `content/type/phonetic/meanings`。在持有内容的对象上加 `field_meta.<字段>.is_ai_generated` 布尔标记。例如 `{"zh_definition":{"is_ai_generated":false},"example":{"is_ai_generated":true}}` 是一条释义的 `field_meta`。来源类型、词库版本、引用链接与 AI 布尔标记分别记录。
- [ ] 列出写入点并由应用打标。覆盖模型返回或修改的词条、识别文字、音标、词性、中英释义、例句、来源文本、备注、补充说明和后续问题，以及会写入会话的 AI 回复、标题、猜词结果与新联网结论。后续问题等列表字段若整批来自一次生成，可整字段标记。混合来源时拆成可分别标记的内容记录。AI 选择一个已有义项，原释义的标记保持其真实来源，生成的语境解释标为 `true`。
- [ ] 测试覆盖三类写入。字典直取和用户原文为 `false`，模型生成或改写为 `true`，应用直接摘录的网页证据为 `false`，模型结合这些证据写出的总结仍为 `true`。局部编辑 AI 内容保留标记，完全用已知人工或词典内容替换时以新来源为准。来源标记由应用依据写入路径设置。
- [ ] 处理保存、合并、备份和恢复。标记随对应内容一起新增、保留或删除，尤其测试新例句合并时原释义标记保持正确。联网核对记录保存在查询会话下，用户保存核对后的义项时只随选中内容带上对应来源。
- [ ] 在生成器中升级正常用例、1000/3000 规模和 3000/10000 历史用例，重建实际使用的 JSON/ZIP，校验词条、事件数量、ID 引用和归档校验和。保留一份明确包含词库内容、模型内容和混合义项的小型测试样本。把旧 runner 改成使用新格式和隔离 cache 数据库，普通单元测试继续只校验 fixture，不重写版本化产物。

通过条件为新 fixture 可导入，读写及备份恢复后字段与标记一致。升级范围是仓库测试数据与隔离测试库，真实用户数据不进入升级脚本。旧格式测量输入留在历史验收目录。

## 阶段 3　接入 ECDICT 离线查询

新增计划文件 `scripts/build-ecdict.py`、`app/src/main/java/com/lexinote/nativeapp/OfflineDictionary.java`，修改 `QueryUi.java` 的文本查询和目标确认入口。

- [ ] 核对选定 ECDICT 数据版本、许可和来源。构建脚本从固定输入生成只读 SQLite，记录源文件 SHA-256、词条数、安装包增量与许可。以常用单词、完整短语、变形词、多义词和空字段的实测结果确定首包范围。见 [ECDICT 数据格式及字段](https://github.com/skywind3000/ECDICT#数据格式)。
- [ ] 实现独立字典查询。先匹配整个词条或短语，再走明确的词形映射。词库数据按条读取，不随个人词库启动加载。中英义项能确定对应时才配对；保留独立释义和原始词形。
- [ ] 接到现有结果卡。个人词库命中走已有笔记结果；未收录时先查 ECDICT，以 ECDICT 内容生成当前查询结果。字典字段标为 `false`，来源显示「内置词库 · ECDICT」。词库未命中后进入原有 AI 查词。图文查询先确认识别目标，再复用同一路由。
- [ ] 验证断网命中、变形归一、多义词、完整短语、词库文件损坏和用户主动保存。确认普通内置词条查询不触发模型调用，个人笔记的 ID 和引用仅在实际保存后创建。

通过条件为离线结果能在现有卡片中正确显示和保存，词库命中与个人词库命中的来源可区分。

## 阶段 4　接入基于词库的 AI 补充

修改 `app/src/main/java/com/lexinote/nativeapp/AiClient.java`、`AgentProtocol.java`、`QueryWorkflow.java`、`QueryUi.java`。

- [ ] ECDICT 命中即显示词条。有原句时后台发送原句和候选义项 ID，回复只需返回选中的 ID、简短依据或“当前词库没有合适义项”。没有原句时，沿用「AI 补充」入口。词库结果可在等待期间查看和保存。
- [ ] 校验候选 ID 属于本次结果。原始词库字段保留，语境解释或新义项单独呈现并标记为 `true`。无合适候选时可以显示独立的「AI 补充义项」。断网、取消、模型不可用或格式错误时继续展示已加载的词典结果。
- [ ] 用固定模型响应测快速切词、旧回复、拒绝伪造候选 ID、无适合义项、取消和部分保存。回调沿用会话 ID、revision 和数据 generation 检查。把“选对义项”和“生成内容来源正确”分开断言。

通过条件为 AI 能选择有依据的现有义项或给出明确区分的补充，过期回复无法改写当前结果，标记可保存和回看。

## 阶段 5　只接入按需联网核对

新增计划文件 `app/src/main/java/com/lexinote/nativeapp/WebVerificationClient.java`，修改 `AiClient.java`、`AgentProtocol.java` 和 `QueryUi.java`。

TokenDance 官方文档确认网关提供独立搜索端点，首版优先验证 DeepSeek 网页搜索。它接受 TokenDance Bearer API Key，结果直接提供 `title`、`url`、`snippet` 和 `summary`。博查、UniFuncs 同属网关备选，当前项目 Key 的访问权限、实际响应及费用需用配置完成的测试账号核对。现有 `/gateway/v1/chat/completions` 继续负责生成结论，联网核对通过独立端点取得来源。见 [官方索引](https://tokendance.space/llms.txt)、[DeepSeek 网页搜索](https://tokendance.space/docs/protocol-deepseek-web-search.md)、[博查搜索](https://tokendance.space/docs/protocol-bocha-web-search.md)、[UniFuncs 搜索](https://tokendance.space/docs/protocol-unifuncs-web-search.md)。

- [ ] 先以固定响应的检索替身走通完整流程。首选请求为 `POST https://tokendance.space/gateway/deepseek/v1/tools/search`，请求体使用 `query`、`count: 3`、`need_content: false`。按官方约束校验查询词 1–128 个 Unicode 字符。用户点「联网核对」后默认只发送词或短语，将返回的相关摘要交给模型结合已有释义与语境判断。
- [ ] 用独立测试配置验证现有加密存储的 TokenDance Key 是否有端点权限，再接一个实际适配器。鉴权、额度或检索失败时给出相应状态，由用户主动重试。若首选端点不可用，按同一验收口径比较博查和 UniFuncs，再确定替代接口。
- [ ] 查询会话持有 `web_verification`。记录核对目标、状态、结论和来源列表，来源由应用分配 ID，保留标题、URL 和实际取得的摘要。模型只回引用 ID。未知 ID 不展示为证据，来源不足时显示“暂无法确认”。供应商摘要记录为检索资料，生成方式未经证实时不推断 AI 标记。LexiNote 模型生成的核对结论写 `is_ai_generated: true`。
- [ ] 在现有词条卡旁提供「联网核对」按钮，在下方显示可展开的核对区。复用查询取消与过期回调保护。测试有证据、结论冲突、无结果、超时、未知引用和切词后响应，并在配置好服务的独立环境完成一次真实检索。

通过条件为一次用户操作能得到可追溯的结论，失败状态明确，核对不会覆盖已有释义。指定网页读取排在首版之后。

**2026-11-05 接口实测。** 使用用户提供的临时 TokenDance Key，以 `bank` 的河岸义项做了一次搜索和一次模型核对。两次请求均返回 HTTP 200。

| 环节 | 请求与观测 |
|---|---|
| 搜索 | `POST /gateway/deepseek/v1/tools/search`，`query: "bank river land beside river site:dictionary.cambridge.org"`、`count: 3`、`need_content: false`。单次约 765 ms，返回 3 条带标题、HTTPS URL 和摘要的结果。 |
| 核对 | `POST /gateway/v1/chat/completions`，模型 `deepseek-v4-flash`。将搜索摘要连同应用分配的 ID 和原句传给模型。单次约 2229 ms，报告使用 777 tokens，返回结构化结论 `supported`、义项「河岸」及有效引用 `S1`。 |

搜索样本含同一词典词条的不同语言站点和带页面杂项的标题。集成时先做标题清理、同源重复结果处理和引用 ID 校验。此次验证已证明临时 Key 可访问两个端点，Android 集成、常见义项覆盖、异常场景和实际计费仍待验收。API Key 未写入计划或测试记录。

## 阶段 6　统一验收与收尾

- [ ] 新格式回归先走 JVM、Lint、Debug/Release、设备测试 APK，再跑 1000/3000 词条、3000/10000 历史、词库与联网交互、保存和备份恢复。保留已有大库备份往返、损坏文件、缺图、校验和、失败回滚、连续保存和 Activity 重建用例。补齐正常启动、恢复失败与进程死亡场景。
- [ ] 用相同设备和相同数据做视觉对照。先校准截图噪声，复测浅深色、大字号、键盘、空态及紧凑/详细列表。把来源标签与核对区列为预期视觉变化。其余现有卡片布局、40 条分页、跨页全选与标题点击按原计划验收。
- [ ] 使用修正后的性能工具跑原计划的 Release 三轮，每轮 5 次热身和 30 个有效样本。分开记录本地词库首屏、AI 补充、联网核对与保存确认。对照旧目标验收搜索、评分和滚动，先用已采集的 Perfetto trace 归因，需要时复采。同一设备上先确认卡顿成因，再决定列表容器调整。
- [ ] 记录实际通过项、失败项、源码与 APK 哈希。将仍在使用的整库复制与非热点整库重写列出具体入口。若影响本版低堆、数据正确性或原性能门槛则留在本版处理，否则作为下一阶段的存储改造任务。

在 Java 仓库根目录执行构建门槛：

```powershell
.\gradlew.bat --offline testDebugUnitTest lintDebug assembleDebug assembleRelease
.\gradlew.bat --offline assembleDebugAndroidTest '-PdeviceRunner=com.lexinote.nativeapp.PerformanceInstrumentation'
```

设备测试按 runner 分别构建和安装测试 APK，使用隔离数据。性能口径、原指标与命令沿用 [原性能计划](2026-11-01-performance-preserve-ui.md)。单次 Debug 值、上一轮五个 Release 样本与 140 帧滚动采样保留为诊断证据。

## 原计划断点对应

| 原计划 | 新安排 |
|---|---|
| Task 0 基线 | 阶段 0 冻结源码与产物，阶段 6 完成同设备截图噪声校准和正式采样 |
| Task 1–3 存储、确认、SQLite | 已验证的评分/队列/会话增量和异步确认进阶段 0 回归。大库生产读写的剩余整库路径进阶段 1 |
| Task 4 流式备份 | 已有大库往返保留。阶段 2 升级 fixture 并验证字段元数据，阶段 6 验收本版格式及故障恢复 |
| Task 5–6 搜索与渲染 | 现有搜索索引、过期保护和局部勾选进入回归；字典查询为独立入口，滚动归因在阶段 6 |
| Task 7 综合验收 | 阶段 6 统一收口 |

执行主线为 **0 → 1 → 2 → 3 → 4 → 5 → 6**。ECDICT 授权核对、词库样本挑选和检索服务接口核对可以在阶段 1 并行准备。共享的查询结果结构、生成器和 `QueryUi` 修改按主线合入。基准版以阶段 6 验收证据为交付依据。
