# LexiNote 功能演示

需要 Python 3.11+（含 SQLite deserialize）、Android SDK 的 ADB，以及已连接的专用模拟器。安装当前仓库的 Debug APK，并提前在 App 里配置有效的模型。联网核对使用 TokenDance；图片场景需要支持图像输入的模型。脚本通过真实界面提交请求，模型响应会变化。登录、授权、充值和配置凭据由演示外完成。

```powershell
.\gradlew.bat assembleDebug
adb -s emulator-5554 install -r app/build/outputs/apk/debug/app-debug.apk
python scripts/demo/main.py --serial emulator-5554 console
```

打开 <http://127.0.0.1:8765/>。点「准备演示数据」会把固定基础备份恢复到指定模拟器，覆盖它的 LexiNote 学习数据。不要对包含个人学习记录的模拟器执行。调试台随后可自动继续或单步执行；暂停在本步结束时生效。

USB 真机使用独立的「LexiNote 演示」应用。先连接、解锁并授权 USB 调试，再把命令中的 `手机序列号` 换成 `adb devices -l` 显示的真机序列号：

```powershell
.\gradlew.bat -PphysicalDemo assembleDebug
adb -s 手机序列号 install -r app/build/outputs/apk/debug/app-debug.apk
python scripts/demo/main.py --serial 手机序列号 --physical --port 8766 console
```

打开 <http://127.0.0.1:8766/>，点击「准备演示数据」。真机模式会重置 `com.lexinote.nativeapp.demo` 的本地数据，清除加密授权、内嵌网页的 Cookie、站点存储和缓存，再自动导入样例、关闭自动 AI 补充并完成离线查询。它会停在查词结果页。此时在手机里返回「我的」，点击「连接 TokenDance」，在内嵌页面手动登录并授权。回到演示版后，在调试台点击「自动继续」，其余步骤自动运行。真机模式只操作独立演示版，样例备份也只导入该应用。

### TokenDance 自动回调失败

点击「连接 TokenDance」时，App 检查系统浏览器能否将回调链接交给当前安装的应用。能接收时默认打开自动回调页，保留「改用一次性授权码」。无法确认时默认打开一次性授权码页，演示版通常走这个分支。

要复现回调失败，在「LexiNote 演示 → 我的 → 连接 TokenDance」进入授权页。如果默认进入一次性授权码页，先点「改用自动回调连接」。停留在授权页，无需先登录。用 ADB 将一个不含真实授权码的失败回调交给当前页面。把命令中的序列号换成 `adb devices -l` 显示的真机序列号。

```powershell
adb -s 手机序列号 shell am start -W -a android.intent.action.VIEW -n com.lexinote.nativeapp.demo/com.lexinote.nativeapp.TokenDanceActivity -d 'https://taskstream.xyz/lexinote/auth/callback?error=access_denied'
```

预期界面提示「自动授权未完成，已切换为一次性授权码方式」，内嵌网页重新打开 TokenDance 授权页，同时显示「粘贴授权码」和「完成连接」。此步骤模拟 App 收到失败回调后的处理。浏览器完成授权后未唤起 App 时，手动返回授权页并点「改用一次性授权码」，也会打开新的无回调授权页。

TokenDance 将手动粘贴一次性授权码的流程称为 Headless。App 发起新的 S256 PKCE 授权请求时需省略 `callback_url`。用户在 TokenDance 登录并确认授权后，网页显示有效期 10 分钟的一次性授权码。用户回到 LexiNote，点「粘贴授权码 → 完成连接」，App 用该授权码和同一会话的 `code_verifier` 换取 API Key。成功时提示「已连接 TokenDance，可以开始查询了」。录屏时隐藏授权码和凭据。[TokenDance 官方授权文档](https://tokendance.space/docs/api-key-oauth#headless)

纯命令行版本：

```powershell
python scripts/demo/main.py --serial emulator-5554 --prepare step
python scripts/demo/main.py --serial emulator-5554 --prepare auto
python scripts/demo/main.py --serial emulator-5554 --run 20261111-120000-ab12cd console
```

使用终端或调试台其中一个。恢复旧运行时，把示例编号换成终端输出或历史下拉菜单中的实际编号，并保留该运行的模拟器现场。退出程序不会清除截图。历史位于 `captures/lexinote-demo/<运行编号>/`，其中 `run.json` 是操作时间线，PNG 是步骤、过程和失败现场截图。历史运行可只读切换。

等待超时后用「执行下一步 / 继续等待」恢复等待；它沿用当前检查点。操作结果不确定时，先检查模拟器是否已点击或提交，再明确决定重试当前操作或跳过。若接口明确失败并需要重新发出请求，可以先在模拟器重试，再让调试台继续等待。截图和操作记录会包含样例文本与模型回复，因此不要在录制期间切换到凭据设置或其他个人界面。

图片确认后若 App 提示「会话快照版本已过期」，先确认这条提示并保留历史截图，再继续等待页面。图片步骤以词条页显示为验收，查询历史是否成功写入由 App 提示。

固定内容在 `scenes.py`。起点是 `lexinote-normal-usage-20261028.zip` 的 SHA-256 校验版本；固定图片是同目录的 `scene.png`。复习计划从当天开始，每天学习，关闭导入旧进度，初始分配 3 个新词。编辑计划展示把复习上限从 10 改到 12，保留当天已经生成的三词队列。流程包括原始离线查词、双语整理、联网核对、整理并保存、中文追问、会话管理、笔记视图、图片查询、新建及编辑计划，以及今日复习与加练。

### 固定动作回放

先在固定分辨率、显示缩放和键盘配置的演示机上，用 `--record-actions <文件>` 启动控制台，从准备数据开始逐步录制。录制时的定位用于取得实际坐标。授权在手机上手动操作，不进入 ADB 动作文件。动作文件可编辑各段延时。

正式回放时改用 `--fixed-actions <文件>` 启动控制台。真机准备步骤先清除独立演示版的应用数据，包括 TokenDance 授权和内嵌网页缓存，然后按文件中的固定动作导入样例。离线查询后由演示者手动登录授权。每段按文件顺序发出固定 ADB 命令并等待预设延时，由演示者查看画面并确认下一段。录制和回放使用同一台演示机及相同界面配置。
