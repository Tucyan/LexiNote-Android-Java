# 正常使用数据导入测试

日期：2026-10-28。应用代码基线：`e05c186`。本轮提供模拟备份并实际导入测试，没有修改应用功能。

## 数据与操作

[数据包及说明](../fixtures/normal-usage/README.md)：80 单词、40 短语、128 释义、12 批注、8 笔记、10 最近查询、5 临时会话、8 保存会话、54 条会话消息。模拟用户记录，不含凭据和图片。

先用原版 Expo 的 `encodeBackup` / `decodeBackup` 校验并生成 ZIP，再以原生 `BackupArchive.decode` 和 `Library.validate` 及引用核对检查 ZIP、JSON。通过模拟器的应用设置→恢复→Android 文件选择器选择下载目录中的 ZIP，在确认页核对 120 词汇、8 笔记、0 图片，再执行恢复；没有用替换私有 library.json 的方式冒充界面导入。

测试设备：Pixel_9a / Android API 37 / 1080×2424；浅色、标准字号、绿色强调色。导入前额外保留私有词库快照，应用自身也保留恢复前快照。测试后模拟数据继续留在模拟器中；可以通过「我的→回到上次恢复前」回退。服务配置文件在导入前后逐字节比较一致，没有调用模型 API。

## 已核对

- [恢复确认页](normal-usage/screens/02-restore-confirmation.png)显示 120 词汇、8 笔记、0 图片。
- [全部词汇](normal-usage/screens/03-notebooks-all.png)显示 120 条；仅在批量管理中全选进行计数，[单词 80](normal-usage/screens/04-words-80.png)、[短语 40](normal-usage/screens/05-phrases-40.png)；没有执行移动或删除。
- [主题笔记树](normal-usage/screens/06-notebook-tree.png)含四个子笔记，各 30 条；默认笔记 20 条，短语精练 40 条为共享引用。
- [最近查询](normal-usage/screens/08-recent-queries.png)包含全部 10 条；可打开 [agenda 的释义、例句和批注](normal-usage/screens/09-query-result.png)及 [character 的两个义项](normal-usage/screens/10-query-two-meanings.png)。
- [保存会话搜索](normal-usage/screens/11-saved-search.png)可检索 evidence；[保存的助手会话](normal-usage/screens/12-saved-assistant-table.png)保留问答和实际渲染的 Markdown 表格。
- [临时会话列表](normal-usage/screens/13-temporary-list.png)显示 5/5；[used to 多轮问答](normal-usage/screens/14-temporary-assistant.png)可正常打开。
- [复习统计](normal-usage/screens/15-review-statistics.png)显示总数 120、熟练 48、待复习 72；没有点击评级改变原样本的复习数据。
- 使用应用的「保存完整 ZIP 备份」和系统保存文件界面实际回导。设备文件异步写入结束后读取 [roundtrip-export.zip](normal-usage/roundtrip-export.zip)，再次由原生和原版 Expo 两套解码器验证。回导仍为 80/40 词条、128 释义、12 批注、8 笔记、10 查询、5 临时与 8 保存会话、54 条消息。
- [逐项比较结果](normal-usage/roundtrip-comparison.json)：词条实体、笔记、全部消息正文、会话名称、会话及历史 ID 一致。打开会话会按正常使用逻辑更新最近使用时间和数组位置，因此不声称整个 ZIP 字节相同。

- [重启后保留](normal-usage/screens/17-after-restart.png)：强制停止并重新启动应用后，最近查询仍为 10 条、全部词汇仍为 120 条。
- 项目要求的离线 Gradle 单测、lint、APK 构建检查成功；本轮应用源码没有变化，Gradle 复用了相应检查缓存。

## 本轮发现的界面问题

1. **导入后的查询/会话排序不统一。** 原版备份的集合按新到旧排列；原生页面反向遍历数组，首次导入后的最近查询先显示较旧的 deadline，而最新 agenda 在末尾。会话列表也存在相同的初始顺序问题。数据完整，问题在顺序约定；后续应按明确时间排序并核对第六条临时会话的淘汰规则，而不是调整样本来掩盖问题。
2. **父分类空态不准确。** 主题学习已显示四个子笔记各 30 条，下方仍显示“此分类暂无词汇记录”。父分类的直接词条数为 0 是结构设计，提示应改为引导进入子笔记。截图可见，词汇没有丢失。

这两项留作下一轮应用修复，本轮没有将数据校验通过表述为所有界面行为完全一致。

## 自动化边界

格式验证通过原版源码和原生实际解码类执行；`scripts/VerifyUsageFixture.java` 可复用检查数量、时间、引用和消息。模拟器文件选择器切换时出现过暂时的空可访问性树，等待窗口稳定后继续；未发现应用崩溃。首次拉取回导文件早于异步写入完成而得到空文件，确认设备文件已写成 26,150 字节后重新拉取，校验成功。本次没有测试真实相机、在线模型和支付，也不包含图片数据。
