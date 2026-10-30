# 复习计划数据层验收（2026-10-30）

实现和验收检出：`C:\Users\ALmerb\Desktop\MyProgram\work\Android\LexiNote-review-plans`。
交付目标：`C:\Users\ALmerb\Desktop\MyProgram\LexiNote-Android-Java`。

## 自动验收

使用完整缓存中的 Gradle 8.13，未下载依赖，未修改项目构建版本：

```powershell
& 'C:\Users\ALmerb\.gradle\wrapper\dists\gradle-8.13-bin\5xuhj0ry160q40clulazy9h7d\gradle-8.13\bin\gradle.bat' testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest '-PdeviceRunner=com.lexinote.nativeapp.ReviewPlansInstrumentation' --offline --no-daemon
```

- `BUILD SUCCESSFUL`。
- 全量29个测试套件，150项测试，0失败、0错误、0跳过。
- 其中 `ReviewPlansTest` 新增15项，覆盖创建/配置不可变约束、独立进度、每日配额与重启、再次评分、幂等与冲突重放、计划时区和非计划日、新词/复习任务区分、显式迁移、父子笔记范围、删除保护、历史保留、空日期补齐、JSON/ZIP、当天稍晚到期、极端整数、恢复一致性。
- Lint：0 errors, 16 warnings，告警位置均在本次未修改的UI/配置代码。
- 调试APK及设备测试APK构建成功。

首次测试在新增API缺失时编译失败；实现后首批12项通过。复核发现的3个边界行为分别补充测试并确认失败，再完成修复，最终15项全部通过。

## 设备持久化验收

设备：已运行的 `emulator-5554`，Android x86_64模拟器。专用 runner 不启动界面，在目标应用缓存中创建唯一临时目录，通过包装后的 Context 将 `Repository` 的文件写入该目录，避免触碰用户当前 `library.json`。

```powershell
$adb = 'C:\Users\ALmerb\AppData\Local\Android\Sdk\platform-tools\adb.exe'
& $adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
& $adb -s emulator-5554 install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
& $adb -s emulator-5554 shell am instrument -w com.lexinote.nativeapp.test/com.lexinote.nativeapp.ReviewPlansInstrumentation
```

实际输出：

```text
PASS: review plan Android JSON, AtomicFile reload, retry, statistics, ZIP restore and rollback
```

首轮runner使用测试包的 Context 创建目录失败；改为目标应用 Context 的独立缓存后，重新构建设备测试APK并通过上述验证。应用生产源码未因此更改。

## 审查与范围

两轮代码复核已处理当日稍晚到期遗漏、调度整数溢出、恢复状态与事件不一致三个问题；复核后无遗留的必须修复项。`git diff --check` 通过。

未改动 `ReviewUi`、其他客户端页面、应用凭证、真实用户笔记或构建配置；未推送GitHub。客户端接入顺序、行为口径与容量边界详见 [交付说明](../REVIEW_PLANS_HANDOFF.md)。当前未测试提醒通知/闹钟注册，因为本次没有实现该功能。
