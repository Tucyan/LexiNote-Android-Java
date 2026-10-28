# LexiNote Android Java

LexiNote 的独立 Android 原生迁移首版。应用界面、业务规则和服务由 Java 编写，采用原生 Android View；无需 Node、Expo、React Native 或 Kotlin 应用源码。

本仓库位于 `../LexiNote-Android-Java`，分支为 `codex/android-native-java`，拥有独立 `.git`，公开仓库为 [Tucyan/LexiNote-Android-Java](https://github.com/Tucyan/LexiNote-Android-Java)。来源是 [Tucyan/LexiNote](https://github.com/Tucyan/LexiNote) 的 `d465b969b76f9baaba3d098fb89342916e7efce9`。原项目现有更改已先提交，本仓库创建后不再修改原项目。

这是已可编译、安装和离线使用的迁移首版，尚未达到全部 Expo 功能和交互的逐项一致。详细覆盖范围与差异见 [迁移说明](docs/MIGRATION.md)。

已完成一次同模拟器界面对比与主要布局对齐，保留原版 / 调整前 / 调整后截图及可重复的离线数据。见 [界面对比报告](docs/ui-parity/REPORT.md) 和 [五页面对照图](docs/ui-parity/after-comparison.png)。

## 使用

Android Studio 打开本目录，使用 Java 17。项目配置：Gradle 8.13 / Android Gradle Plugin 8.13.2 / compileSdk 36 / targetSdk 36 / minSdk 26（Android 8.0）。依赖优先使用阿里云 Maven 镜像，Gradle Wrapper 使用官方发行地址；现有本机缓存可离线构建。

配置 `ANDROID_HOME`，或在不提交的 `local.properties` 指定 SDK，例如：

```properties
sdk.dir=C\:/Users/ALmerb/AppData/Local/Android/Sdk
```

Windows PowerShell：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
# 完成依赖下载后可加 --offline
adb install -r .\app\build\outputs\apk\debug\app-debug.apk
```

macOS/Linux：`bash ./gradlew testDebugUnitTest lintDebug assembleDebug`。

应用包名 `com.lexinote.nativeapp`，与原版 `com.lexinote.app` 可同时安装。Debug APK 由 Android 默认调试密钥签名，仅用于体验和开发；商店发布前请配置正式签名。没有在仓库中存放密钥、API Key 或本机 SDK 路径。

启动后可在「笔记 → 管理」手动新增词汇，并在「查询记录」离线查找；本地命中无需配置 AI。在「我的」可直接连接 TokenDance，登录授权后自动保存密钥；系统浏览器使用一次性授权码完成连接。在「高级设置」选择其他预设供应商或新增兼容服务，填写 HTTPS 地址和 API Key，可获取/搜索模型列表、手动填写模型 ID，配置视觉输入和思考强度。未保存的当前输入可直接进行文本/图片测试；保存后才用于查询。每个供应商密钥独立加密保存，留空保留，另有清除入口；更换地址须重新填写已有密钥或明确清除。网络请求可取消，助手支持 SSE 和普通 JSON 回退，无自动重试。连接测试可能产生少量费用。

## 已实现

- 查询：文本 / 图片 / 图文、本地词库优先、澄清候选、目标确认、独立词条页和助手页、最近 10 条快照；自动记录开关及默认字段/笔记、AI 补充、就地编辑和按释义整理（中英二选一、例句显示）。
- 图片：图库及系统相机完整文件输出，原生框选/圈选/画笔、旋转、双指缩放移动、多步撤销重做；原图、派生选区、编辑状态分别保存并参与 ZIP 备份。
- 会话：5 条临时会话上限、保存会话不受此限制、AI 异步命名及手动命名保护、搜索/日期筛选/删除、动态追问只填入不自动发送、SSE 安全预览、最近 200 条消息的回收列表与阅读位置恢复、可横滑的 Markdown 表格和代码块。
- 笔记：树形笔记、同级子项类型一致、默认笔记与全局索引、共享引用及每笔记独立显示、释义编辑/批注/备注/标签、标签快捷筛选、批量移动/复制引用/移除引用/全局删除、字段内划词批注、筛选 JSON/CSV/Markdown 导出。
- 猜词：四阶段 AI 引导、图文语境、最终结果转入查询草稿后显式提交。
- 复习：词卡/看义猜词、笔记范围、10/20/50组量、答案揭示、再次入队/困难/简单评分和本机保存。
- 设置和数据：五种预设/自定义供应商、独立密钥及旧配置迁移、模型发现/搜索选择、文本/视觉连接测试、按模型声明显示思考档位、视觉开关、主题/字号/强调色、Keystore 加密凭据、原子保存、备份恢复/回退与损坏恢复入口。

## 从 Expo 版迁移数据

1. 在旧版导出完整 ZIP 备份。
2. 在本应用「设置 → 选择 ZIP / JSON 备份恢复」选择文件。
3. 校验词汇、笔记、会话、图片数量后，确认覆盖恢复。
4. 在设置重新填写模型连接；原供应商密钥不会自动迁移。

支持旧版 2.0–2.2 ZIP 与 1.0 / 1.1 JSON，内置一个由原仓库实际编码器生成的 2.2 ZIP 测试样本。ZIP 校验 CRC32、拒绝异常路径并重映射图片；历史 JSON 仅包含图片旧路径时，跨设备图片可能不可读。新备份请选择「保存完整 ZIP 备份（含图片）」。备份不是加密文件。

为控制移动设备内存，本版压缩文件及解压总数据上限为 `min(16 MB, 应用最大堆 / 16)`，JSON 文本上限为 `min(4 MB, 上述上限 / 2)`；与旧版 50 MB 上限不同。超过容量会拒绝导入/写入并保留已有数据。外部网页图片需先保存为本机图片；导出不会静默跳过无法读取的图片。

## 验证与代码入口

- 101 项 JVM 测试：协议、模拟 HTTP/SSE、供应商元数据/草稿、目录能力与推理选项、探测内容验证/取消保护、共享引用、查询整理、图像编辑历史、批量管理/批注/Markdown、标题保护、树结构、会话隔离、旧数据与 ZIP 互通。
- `lintDebug`、Java 编译、Debug APK 构建通过。部分 Lint 警告是固定工具版本与单语言 UI 提示；没有屏蔽构建错误。
- Pixel 9a / Android API 37 的10组设备检查涵盖五入口、查询区尺寸、离线查词、图片隔离、猜词状态、复习、Keystore、裁剪、持久化/回退与损坏文件恢复。见 [设备日志](docs/verification/ui-device-smoke.txt)。最低 Android 8.0 尚未进行真机验收。
- 2026-10-27 在用户授权下使用临时凭据，完成模拟器内 DeepSeek `deepseek-flash` 真实模型发现、文本/盲测图片探测、查词和助手追问；测试后恢复原设置并移除临时凭据。另有2组模型配置离线设备检查。见 [模型配置验收及截图](docs/verification/MODEL-CONFIGURATION.md)。
- 另有 7 组新增设备检查：完整相机输出/空 Intent 回传、原图与选区 ZIP、就地整理、批量引用、200 条消息阅读锚点、真实界面接入的模拟 SSE、异步命名竞争保护。见 [本轮功能验收与截图](docs/verification/FEATURES.md)。相机检查通过模拟相机文件回传验证，尚未进行物理设备拍照验收。

复跑设备检查：

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.lexinote.nativeapp.test/com.lexinote.nativeapp.SmokeInstrumentation
# 新增功能套件（PowerShell 中需保留参数引号）
.\gradlew.bat assembleDebugAndroidTest '-PdeviceRunner=com.lexinote.nativeapp.FeatureInstrumentation'
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.lexinote.nativeapp.test/com.lexinote.nativeapp.FeatureInstrumentation
```

代码入口 `app/src/main/java/com/lexinote/nativeapp/`：`MainActivity` 管理生命周期和导航；`QueryUi` / `NotebookUi` / `ReviewUi` / `SettingsUi` 分离各界面；`Library` 承担可独立测试的领域规则；`Repository` 用 AtomicFile 保存和回退；`AiClient` / `AgentProtocol` 管理网络与结构化回复；`SecureSettings` 封装 Keystore；`BackupArchive` 处理备份；`ImageTools` / `ImageCropView` 处理图片。

构建版本依据 [AGP 8.13 官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)。文档和图片通过 [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) 选择，不要求全盘存储权限；凭据使用 [Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

MIT License，保留原项目许可证。独立公开仓库：[Tucyan/LexiNote-Android-Java](https://github.com/Tucyan/LexiNote-Android-Java)。

2026-10-28：新增 TokenDance 账户入口、PKCE 授权/一次性码、内置离线模型列表、原生充值状态及余额/授权恢复。117 项 JVM 测试和 22 组离线设备检查通过；真实登录与支付尚未实测。见 [TokenDance 验收](docs/verification/TOKENDANCE.md)。

正常使用测试数据：80 单词、40 短语、10 条最近查询、5 条临时和 8 条保存会话。已通过真实文件选择器导入和回导校验，见 [模拟备份](docs/fixtures/normal-usage/README.md) 与 [测试报告](docs/verification/NORMAL-USAGE.md)。

实际使用续查：确认 9 组问题，含本地查询占用临时会话额度、导入顺序引发错误淘汰、复习统计与搜索/筛选交互。实测证据、恢复检查和建议修复顺序见 [使用体验审查](docs/verification/USAGE-EXPERIENCE.md)。本轮仅记录问题，测试数据及设置已恢复。

已修复上述9组实际使用问题：会话保留与时间淘汰、复习熟练度、搜索/日期筛选、历史删除刷新、空态和标题点击。132项JVM测试及32组离线设备检查通过，含同样本实际输入与大字号复核。[修复验收与APK信息](docs/verification/USAGE-FIXES.md)。旧样本熟练数按评级由48重算为16，词条与记录全部保留。
