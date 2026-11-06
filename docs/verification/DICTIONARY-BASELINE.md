# Java 基准版实施与验收记录

2026-11-05，`codex/android-native-java`，起点 `7073de9`。实施范围和待办见 [基准版开发计划](../superpowers/plans/2026-11-05-dictionary-baseline.md)。

## 已实现

- 个人词库优先。未收录的文本和已确认的图文目标先读内置 ECDICT。原形与变形结果按条读取，词库内容和个人库分开存储。无上下文的词库命中可直接离线显示，默认不创建个人笔记。词库资源和重建方法见 [ECDICT 说明](../ECDICT-NOTICE.md)。
- ECDICT 命中后，有上下文且配置了模型时后台补充语境。候选义项 ID 由应用验证。词典原文保留，生成的新义项与解读单独记录。
- 「联网核对」使用 TokenDance 独立搜索和当前 TokenDance 模型。搜索结果只提供来源 ID、链接和摘要，模型引用的 ID 由应用校验。成功的核对结论可按用户操作加入可保存释义，引用随该释义保存。
- 写入点为已知来源字段记录 `field_meta.<字段>.is_ai_generated`，模型不能自报来源标记。元数据随保存、合并和备份恢复处理，界面不显示布尔属性。
- 正常、1000/3000 词条与 3000/10000 事件测试数据完成更新。既有文本、ID 和引用数量核对通过；增加独立的混合来源小样本。

## 本轮验证

模拟器前两次从窗口模式启动失败，第三次以无窗口 SwiftShader 启动并连上 ADB。历史库功能复测沿用 192 MiB 堆上限。该渲染环境的单次结果保留为诊断。

| 验证 | 结果 |
|---|---|
| Gradle | 209 项 JVM 测试通过。Lint 0 错误、27 警告。Debug、Release 与设备测试 APK 构建成功。 |
| 历史库 Activity | 流式导入 3000 条词和 10000 条事件，真实页面完成启动、搜索、详情、本地查询、评分和重开。重开保留 3000 条词、10001 条评分事件及 9780 个笔记引用。 |
| 历史性能 runner | 原夹具改走流式导入和隔离重置。3000/10000 的 Debug 与 Release 单样本诊断各 6 组通过，夹具重置时间单独记录。 |
| 离线查询与引用区 | 真实首页 `went → go` 命中，检查来源标签、历史持久化、个人笔记优先和内置结果无自动存入。用固定核对响应验证引用可展开，并把带来源与 AI 标记的义项写入查询历史。 |
| 保存与备份 | 字段来源混合样本经过 SQLite 导入、流式导出和恢复，布尔标记保留。超过 8 MiB、图片映射、校验和、凭据过滤、失败回滚通过。 |
| 原有规模回归 | 1000 和 3000 条各 11 项通过。评分 Activity 重建、SQLite 迁移/评分一致性/重放和回滚通过。 |

阶段 5 的真实 TokenDance 接口单次核对在上一次可行性验证通过。本轮 Android 引用区使用固定响应。生产移动端检索调用、真实多义词覆盖率与实际计费另行验收。

原始证据包括 [历史库设备日志](dictionary-baseline/20261105/history-ui.txt)、[Release 历史诊断](dictionary-baseline/20261105/release-history-smoke.txt)、[离线查询和引用区](dictionary-baseline/20261105/dictionary-query-ui.txt) 和 [字段标记备份往返](dictionary-baseline/20261105/streaming-backup.txt)。已目视检查 [个人查询结果截图](dictionary-baseline/20261105/personal-query-debug-sample.png) 的布局和按钮。该截图属于现版诊断样本。

## 2026-11-05 续做顺序

下列是 11 月 5 日的原始交接。11 月 6 日起先验收功能，性能测试暂停。

1. 完成三轮 Release 采样。历史夹具目前每次隔离导入并重建 Activity，计时与实际操作分离。持续运行前要核对 cache 空间。存储关闭完成信号和已关闭 SQLite 种子复制可用于后续降低准备成本。保持历史场景的 192 MiB 验收条件。
2. 用标准状态补齐截图噪声校准、原界面视觉对照和联网新增区域检查。对已有滚动 trace 做主线程、GC 与渲染归因。
3. 补真实 Android TokenDance 账号权限、检索质量、取消与失败、计费的验收记录。字段来源的出库接口与可点击证据已经具备，逐条记录样本差异。

源码和测试产物保留在当前工作区。开发前检查点和本轮命令原始日志位于 `.planning/2026-11-05-dictionary-baseline/`。
## 停止检查点

停止检查点：5 小时额度最后查询已用 93%，剩余约 7%。1000 条 Release 首轮在停止前完成，5 次热身、30 个有效样本，6 组通过、0 组失败。续做从第 2 轮及其余规模开始，视觉全矩阵和真实 Android 联网验证仍待完成。 代码及文档已保存在当前分支的工作区，尚未提交或推送。所有子代理已结束。

## 2026-11-06 功能优先验收

本轮按用户要求暂停性能测试，重点验证真实 Android 查询、保存和恢复。

| 场景 | 结果 |
|---|---|
| 图文确认后的语境 | 设备用例先复现原句与要求未保留。修复后在分流到个人词库、ECDICT 或模型之前写入会话 `source_context`，后续联网核对读取同一上下文。 [设备结果](dictionary-baseline/20261106/confirmed-context.txt) |
| 首次保存期间编辑释义 | 暂停首次会话写入后立即编辑，修复前界面与 SQLite 都丢失编辑。修复后界面立即保留编辑，首次写入确认后保存最新版，再用新版结果进行语境补充。 [修复前](dictionary-baseline/20261106/query-save-race-red.txt) · [修复后](dictionary-baseline/20261106/query-save-race-green.txt) |
| Android 真联网 | 用 App 当前默认模型 `deepseek-v4.1-flash` 查询 `riverbank`，ECDICT 命中后成功保存实际模型的语境补充和 AI 字段标记。点击「联网核对」后得到 3 条来源，校验了 2 个引用 ID。测试配置最初选了不在 App 允许范围内的旧 `deepseek-v4-flash`，该失败在本地模型校验阶段定位。 [实测结果](dictionary-baseline/20261106/android-tokendance-live.txt) |
| 结果保存 | 在真实查询卡展开引用，添加核对义项并通过「整理笔记」保存到默认笔记。隔离库保留义项 AI 标记、来源 ID 和笔记引用。该保存场景使用固定响应。 [设备结果](dictionary-baseline/20261106/dictionary-save-and-citations.txt) |
| 启动和恢复 | 损坏测试库触发启动失败，失败恢复回调送达且原快照保留，有效恢复及 SQLite 关闭后重开通过。 [设备结果](dictionary-baseline/20261106/startup-restore-recovery.txt) |
| 已提交数据跨进程 | 在一个 PID 中通过 Activity 和存储队列保存，结束进程后在新 PID 用首页查询重新读取，核对笔记引用和 AI 标记。此用例覆盖提交完成后的重启。 [写入进程](dictionary-baseline/20261106/process-reopen-seed.txt) · [读取进程](dictionary-baseline/20261106/process-reopen-read.txt) |

真实联网用例使用隔离缓存库和一次性本地凭据通道。测试配置由 Android Keystore 加密，结束后确认独立配置和 ADB 通道已清理。当前 210 项 JVM 测试通过，Lint 0 错误和 27 警告，Debug、Release 及测试 APK 均构建通过。

真实联网证据来自一次英语词条场景，更多语境、账号计费和事务执行中进程中止仍属于单独验证场景。性能矩阵继续暂停。

## 2026-11-06 功能边界与多义词复测

使用无窗口 Pixel_9a（API 37，1080 × 2424，420 dpi）及隔离 SQLite 词库。本轮未执行性能测试。

| 场景 | 验收结果 |
|---|---|
| 断网、无 API Key | 模拟器无默认网络。查询带语境的 `by and large` 后，仍能通过 ECDICT 显示和保存到默认笔记。SQLite 中的词条、笔记引用和非 AI 来源标记均保留。测试后恢复设备网络设置。 [设备记录](dictionary-baseline/20261106/offline-boundary.txt) |
| 词库未命中与异常重试 | 从首页确认后进入实际 `AiClient.lookup` 并保存生成结果。联网核对用受控 HTTP 响应验证取消、无有效来源、搜索超时和 503，原始释义在页面与 SQLite 中均保留。重试得到有效来源后，核对结论和引用成功写入。 [Debug](dictionary-baseline/20261106/context-search-green.txt) · [Release](dictionary-baseline/20261106/context-search-release.txt) |
| 多义词真实检索 | `book a flight` 的语境解读选对了「预订」义项。原词检索得到 3 条图书相关结果，应用显示「暂无法确认」。对照请求加入已选的 ECDICT 义项后，3 条摘要均支持该动词用法。修复后 Android 真实核对取得 3 条来源并校验 3 个引用，抽查摘要均支持「预订」。 [首次运行](dictionary-baseline/20261106/android-live-book-before.txt) · [检索对照](dictionary-baseline/20261106/live-book-search-diagnostic.json) · [修复后实测](dictionary-baseline/20261106/android-live-book-after.txt) |
| 短语真实复测 | `by and large` 在语境中选中「大体上，基本上」，联网得到 3 条来源并校验 2 个引用。两条被引用的摘要支持该解释。 [实测结果](dictionary-baseline/20261106/android-live-idiom-after.txt) |
| 查询页视觉 | 与旧结果页和整理模式截图对照了共用的标题区、释义卡及固定底部操作区。新来源标签和展开的引用可读，整理入口及双列保存按钮可见。旧截图使用不同词条，本次对照覆盖共用布局及新增区域。 [旧结果页](normal-usage/screens/09-query-result.png) · [旧整理模式](features-screens/query-organization.png) · [展开引用](dictionary-baseline/20261106/dictionary-expanded-evidence.png) · [离线整理](dictionary-baseline/20261106/dictionary-offline-organize.png) |

修复只调整发给搜索端点的检索词。ECDICT 结果有有效的已选义项时，检索词加入该释义和 `dictionary`，长度限制为 128 个 Unicode 码点，完整原句继续只用于模型侧语境分析。无可用义项时保持原词检索。设备回归捕获实际 POST 请求，修复前只发送原词，修复后断言已包含所选词典释义。 [修复前回归](dictionary-baseline/20261106/context-search-red.txt) · [修复后回归](dictionary-baseline/20261106/context-search-green.txt)

最终构建通过 210 项 JVM 测试，Lint 0 错误、27 警告，Debug、Release 和 AndroidTest APK 均构建成功。Release APK 用调试证书签名供本地试用，SHA-256 为 `3FC90C9564FD021342713A63455AB794C3D3D2127555EC59ABA453DECE25DCEB`。真实样本复测结束后，已确认 ADB 临时通道和隔离凭据配置清理完成。计费、更多真实网络场景和同数据的全量视觉矩阵尚未覆盖。

## 2026-11-06 TokenDance 账单核对

使用与 App 相同的网关路径、模型和应用归因头，在同一个测试账号中分开调用搜索和模型。用请求 trace 匹配用量明细，每次查账前后对照已使用额度。金额字段以微元记录，1 元等于 1,000,000 微元。 [TokenDance 开放 API](https://tokendance.space/docs/open-api.md)

| 实际调用 | HTTP | 账单条目 | 用量 | 实扣 |
|---|---:|---|---|---:|
| DeepSeek 搜索 | 200 | `deepseek-search` | 3 条来源 | ¥0.005 |
| 首次模型探针 | 400 | `deepseek-v4.1-flash` | 0 tokens | ¥0 |
| 按 App 请求格式复测 | 200 | `deepseek-v4.1-flash` | 输入 16、输出 5 tokens | ¥0.000058 |

三笔均匹配到独立用量行，成功两笔的合计实扣为 ¥0.005058，分别与余额接口返回的已用额度增量一致。首次失败探针的参数与 App 不同，复测使用 App 的关闭思考参数。金额仅对应本次样本。 [搜索和失败探针](dictionary-baseline/20261106/billing-control.json) · [成功模型账单](dictionary-baseline/20261106/billing-chat-success.json)

## 2026-11-06 同数据视觉对照检查点

以 `7073de9` 为基准，候选和基准使用同一份 1000 词条夹具和同一 Pixel_9a 模拟器。浅色和深色分别在默认字号和大字号下采集，每种 12 个页面状态。对照只排除设备系统状态栏的 142 像素区域，以 RGB 通道差大于 8、面积比例不超过 0.5% 为既定门槛。

48 对截图已采集。46 对的应用区域像素差为 0，涵盖详细列表、搜索结果、词汇详情、复习正反面及评分后、空计划、长会话与设置等。浅色默认字号的候选书架和紧凑首页被 Pixel Launcher 无响应弹窗覆盖，已标记无效。候选五次校准也受弹窗干扰，需重采。全量验收还需补齐键盘显示、暂停与仅归档管理状态。 [差异数据](dictionary-baseline/20261106/clean-visual-comparison.json) · [浅色默认](dictionary-baseline/20261106/clean-visual-light-normal.png) · [深色默认](dictionary-baseline/20261106/clean-visual-dark-normal.png) · [浅色大字](dictionary-baseline/20261106/clean-visual-light-large.png) · [深色大字](dictionary-baseline/20261106/clean-visual-dark-large.png)

5 小时额度最后检查剩余 7%，在交接时停止采集。原始 PNG 和 runner 日志在 `.planning/2026-11-05-dictionary-baseline/clean-visual-*`。候选 APK 已重新安装，临时 Android 用户已清理。模拟器主用户旧版 `files/library.json` 的前后哈希不同，已保留前后哈希，尚未核对文件内容差异。所检查的其他文件哈希一致。性能测试继续暂停。

## 2026-11-06 首页单词直达

首页纯文本单词优先查个人笔记和 ECDICT。需要模型判断时，留在首页显示可取消的查询进度。模型认可原词并返回有效释义时直接使用该结果。模型只确认原词时，自动查询并保留用户原语境。建议换词或需要澄清时进入助手处理。短语和图片继续走现有路径。

设备用例先复现等待模型时提前进入助手。修复后以受控模型响应验证等待期间留在首页、原词确认自动查询、现成释义直接使用、改词须确认和短语确认，且结果写入 SQLite。图文确认和离线词库回归通过。210 项 JVM 测试通过，Lint 0 错误、27 警告，Debug 和 Release APK 构建成功。 [查询边界](dictionary-baseline/20261106/direct-word-stable.txt) · [图文确认](dictionary-baseline/20261106/direct-word-image-regression.txt) · [离线词库](dictionary-baseline/20261106/direct-word-dictionary-regression.txt)
