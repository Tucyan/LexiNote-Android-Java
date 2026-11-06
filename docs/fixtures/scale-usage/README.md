# 大量模拟学习数据（2026-11-01）

所有内容均为离线合成测试数据，不是真实用户记录，不含账户、密钥或图片。120条原有日常词汇与中英例句作为种子，补充工作/学习领域的组合短语；组合释义用于性能测试，未经逐条词典审校。

| 数据 | 1000条样本 | 3000条样本 |
|---|---:|---:|
| 独立词条 |1000|3000|
| 笔记（含系统、共享引用、三层树）|36|36|
| 保存会话|88|88|
| 临时会话|5|5|
| 消息总量|970|970|
| 最长单会话|600|600|
| JSON字节|1584548|3860226|

优先体验 [3000条且含12个复习计划的ZIP](lexinote-scale-3000-with-plans.zip)。这是设备测试用真实业务接口创建计划后导出的初始备份，尚未评分。基础样本另有 [1000条ZIP](lexinote-scale-1000.zip)、[3000条ZIP](lexinote-scale-3000.zip) 以及对应JSON；基础样本不含复习计划。

在「我的 → 数据与备份 → 选择 ZIP / JSON 备份恢复」导入。确认页应显示词汇3000、笔记36、图片0。会覆盖学习数据，请先导出当前数据；供应商及加密密钥独立保留。

复现：`python scripts/create-scale-fixture.py` 生成两档基础JSON/ZIP和设备测试assets，并刷新已有的3000条/10000事件JSON及12计划ZIP，无需网络。词库/合成短语字段和用户文本标为非模型生成，模拟助手回答标为模型生成。含计划样本由ScaleInstrumentation通过reviewPlans.create/BackupArchive.encode生成，设备结果目录保留fixture-with-plans.zip。原生解码、CRC校验、全量导出回导和超容量保护均已验证。

混合来源字段示例见[独立小型样本](../field-provenance/lexinote-provenance-sample.json)。

验收过程与剩余问题见 [大数据交互报告](../../verification/SCALE-USAGE.md)。
