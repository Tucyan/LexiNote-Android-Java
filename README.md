# LexiNote Android Java

LexiNote 的独立 Android 原生迁移首版。应用界面、业务规则和服务由 Java 编写，采用原生 Android View；无需 Node、Expo、React Native 或 Kotlin 应用源码。

本仓库位于 `../LexiNote-Android-Java`，分支为 `codex/android-native-java`，拥有独立 `.git`，没有继承原项目远端。来源是 [Tucyan/LexiNote](https://github.com/Tucyan/LexiNote) 的 `d465b969b76f9baaba3d098fb89342916e7efce9`。原项目现有更改已先提交，本仓库创建后不再修改原项目。

这是已可编译、安装和离线使用的迁移首版，尚未达到全部 Expo 功能和交互的逐项一致。详细覆盖范围与差异见 [迁移说明](docs/MIGRATION.md)。

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

启动后可在「笔记」手动新增词汇，并在「查询」离线查找；本地命中无需配置 AI。调用 AI 前，在「设置」填写 HTTPS OpenAI 兼容接口、模型 ID 和 API Key；图片请求还须开启视觉支持。API Key 留空表示保留，另有明确的清除按钮。网络请求可取消，使用同步 HTTP JSON 响应，由后台线程执行；没有自动重试或收费模型测试。

## 已实现

- 查询：文本 / 图片 / 图文、图库和系统相机缩略图、原生裁剪/旋转/双指缩放、本地词库优先、澄清候选、目标确认、独立词条页和助手页、最近 10 条快照。
- 会话：5 条临时会话上限、保存会话不受此限制、手动命名/按名称搜索/删除、动态追问只填入不自动发送、基本 Markdown 文本显示。
- 笔记：树形笔记、同级子项类型一致、默认笔记与全局索引、词汇多笔记共享引用、完整保存及每笔记独立显示、释义编辑/批注/备注/标签、手动新增、筛选 JSON/CSV 导出。
- 猜词：四阶段 AI 引导、图文语境、最终结果转入查询草稿后显式提交。
- 复习：词卡/看义猜词、笔记范围、答案揭示、再次入队/困难/简单评分和本机保存。
- 设置和数据：自定义供应商、视觉开关、深浅色/系统主题/字号、Keystore 加密凭据、原子文件保存、备份导入前确认和恢复前回退、损坏文件恢复入口。

## 从 Expo 版迁移数据

1. 在旧版导出完整 ZIP 备份。
2. 在本应用「设置 → 选择 ZIP / JSON 备份恢复」选择文件。
3. 校验词汇、笔记、会话、图片数量后，确认覆盖恢复。
4. 在设置重新填写模型连接；原供应商密钥不会自动迁移。

支持旧版 2.0–2.2 ZIP 与 1.0 / 1.1 JSON，内置一个由原仓库实际编码器生成的 2.2 ZIP 测试样本。ZIP 校验 CRC32、拒绝异常路径并重映射图片；历史 JSON 仅包含图片旧路径时，跨设备图片可能不可读。新备份请选择「保存完整 ZIP 备份（含图片）」。备份不是加密文件。

为控制移动设备内存，本版压缩文件及解压总数据上限为 `min(16 MB, 应用最大堆 / 16)`，JSON 文本上限为 `min(4 MB, 上述上限 / 2)`；与旧版 50 MB 上限不同。超过容量会拒绝导入/写入并保留已有数据。外部网页图片需先保存为本机图片；导出不会静默跳过无法读取的图片。

## 验证与代码入口

- 44 项 JVM 测试：协议、模拟 HTTP、共享引用、树结构、会话隔离、系统根重建、旧部分释义兼容、ZIP 与原编码器互通、损坏拒绝。
- `lintDebug`、Java 编译、Debug APK 构建通过。部分 Lint 警告是固定工具版本与单语言 UI 提示；没有屏蔽构建错误。
- Pixel 9a / Android API 37 的设备检查涵盖五入口、离线查词、复习、Keystore、裁剪、持久化/回退与损坏文件恢复。见 [设备日志](docs/verification/device-smoke.txt)。最低 Android 8.0 尚未进行真机验收。
- 模型的网络协议使用无网络模拟测试验证，未使用用户真实凭据调用远端 AI。

复跑设备检查：

```powershell
.\gradlew.bat assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w com.lexinote.nativeapp.test/com.lexinote.nativeapp.SmokeInstrumentation
```

代码入口 `app/src/main/java/com/lexinote/nativeapp/`：`MainActivity` 管理生命周期和导航；`QueryUi` / `NotebookUi` / `ReviewUi` / `SettingsUi` 分离各界面；`Library` 承担可独立测试的领域规则；`Repository` 用 AtomicFile 保存和回退；`AiClient` / `AgentProtocol` 管理网络与结构化回复；`SecureSettings` 封装 Keystore；`BackupArchive` 处理备份；`ImageTools` / `ImageCropView` 处理图片。

构建版本依据 [AGP 8.13 官方兼容表](https://developer.android.com/build/releases/agp-8-13-0-release-notes)。文档和图片通过 [Android Storage Access Framework](https://developer.android.com/training/data-storage/shared/documents-files) 选择，不要求全盘存储权限；凭据使用 [Android Keystore](https://developer.android.com/privacy-and-security/keystore)。

MIT License，保留原项目许可证。远端仓库尚未创建或推送。
