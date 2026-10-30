# 复习计划：数据与业务层交付说明

本次只实现 Java 原生主线的数据与业务层，未修改任何客户端页面。旧 `ReviewUi` 暂时仍运行旧的全局词汇复习流程；以下接入完成后，页面才会使用新计划。项目没有 HTTP 后端，本文“接口”均为本地 Java API。

## 1. 入口与客户端流程

每次从当前 `Library` 获取 `library.reviewPlans()`。持久化失败、备份恢复和撤销可能替换整个 `Library`，不要长期缓存服务实例或其中返回的 JSON。

```java
ReviewPlans reviews = library.reviewPlans();
JSONArray plans = reviews.list(false); // false 仅有效计划；true 包含归档
JSONObject plan = reviews.get(planId);
```

- 进入复习页先展示计划列表；无计划时展示新建入口，不自动创建“全部词汇”计划。
- 选择计划后调用 `dailyQueue(planId, nowMillis)`；它首次调用会生成并保存当天任务，因此调用后需要持久化。
- 每次评分调用 `record` 并持久化，保存成功后才推进卡片。
- 展示词汇内容仍用 `library.find("vocabularies", vocabId)`；调度预览与掌握程度必须读取 `reviews.state(planId, vocabId)`，不能再用词汇实体上的旧字段。
- 本轮结束以返回的 `queue` 是否为空判断。`new_target/review_target` 是当天原始任务数，不是剩余数。

## 2. 计划创建、编辑与归档

```java
String planId = library.reviewPlans().create(
    "工作日英语", notebookId, 20, 50,
    new int[] {1, 2, 3, 4, 5}, "Asia/Shanghai",
    false, System.currentTimeMillis());
// importLegacy=false：从全新进度开始；true：复制该范围内词汇的旧进度。

JSONObject patch = new JSONObject();
patch.put("daily_new_limit", 15);
patch.put("daily_review_limit", 60);
patch.put("weekdays", new JSONArray(new int[] {1, 3, 5}));
library.reviewPlans().update(planId, patch, System.currentTimeMillis());
library.reviewPlans().archive(planId, System.currentTimeMillis());
```

| 字段 | 类型 | 编辑规则 |
|---|---|---|
| `id` | string | 系统生成，不可变 |
| `notebook_id` | string | 必须绑定已有笔记本，创建后不可变 |
| `name` | string | 可编辑，非空，最多120字符 |
| `daily_new_limit` | integer | 可编辑，0～10000 |
| `daily_review_limit` | integer | 可编辑，0～10000，两个配额不能同时为0 |
| `weekdays` | integer[] | 可编辑，ISO星期一=1，星期日=7；不允许空、重复或越界 |
| `zone_id` | string | 有效 Java/IANA 时区，创建后固定，避免历史日期归属发生漂移 |
| `created_at` / `updated_at` | UTC ISO timestamp | 系统维护 |
| `archived` | boolean | 仅通过 `archive` 设置；不提供物理删除或恢复归档接口 |
| `legacy_imported` | boolean | 系统记录创建时是否导入旧进度 |

频率预设映射：每天 `[1,2,3,4,5,6,7]`；仅工作日 `[1,2,3,4,5]`；自选星期传选中的集合。“工作日”当前指周一至周五，不含法定节假日与调休规则。

绑定范围包含笔记本当前所有子笔记中的词汇并去重，允许绑定默认笔记、全部词汇视图或父笔记。词汇后来加入/移出时，后续每日任务使用最新范围。已生成任务不自动补充；移出或删除的词不会继续出题，也不能提交新的评分，历史统计仍保留。

同一笔记本允许多个计划，彼此独立。删除笔记本前会先检查整棵子树，若仍有有效计划绑定则拒绝删除且不会部分删除；先归档计划后可删除笔记本，归档计划与历史仍可查询。

## 3. 每日任务、频率与配额规则

```java
long nowMillis = System.currentTimeMillis();
JSONObject day = library.reviewPlans().dailyQueue(planId, nowMillis);
JSONArray queue = day.getJSONArray("queue");
// {date, zone_id, scheduled, daily_new_limit, daily_review_limit,
//  new_target, review_target, created_at, queue:[{vocab_id,kind,completed,attempts}]}
```

- 按计划 `zone_id` 的本地日期分天，跨日与夏令时通过 `java.time` 处理。
- 复习任务包含**截至该本地日结束之前**到期的已学词（含逾期）；可以当天提前学习，避免早晨进入时漏掉晚上到期的词。
- 新词指本计划尚未成功完成过学习的词。即使在其他计划或旧流程学过，未显式导入时仍是本计划新词。
- 优先复习，再学新词；复习按到期时间、ID排序，新词按ID稳定排序。
- 实际目标为 `min(配置配额, 可用词数)`，不会为凑数量选未到期复习词或重复选词。
- 每日任务首次生成后固定，重启仍使用同一任务；同日多轮不会重新领一份配额。
- 修改配额与频率只影响之后尚未生成的每日任务，通常从下一天生效。连非计划日的空任务也固定；页面查询当天任务前应让用户完成计划编辑。
- 非计划日返回 `scheduled=false` 和空队列，不累积“欠下的新词配额”。到期词留到下一计划日优先处理。
- “再次”写入一次真实答题事件并放到队尾，**不计完成**；“困难”或“简单”才完成该词当日任务，并分别占用一份新学/复习配额。当天新词的再次练习不会额外占用复习配额。
- 若新词当天仅答“再次”，次日仍按新词处理；未完成任务不会原样跨日续传，下个计划日按最新进度重新组题。
- 间隔算法沿用 `ReviewLogic`，含“再次”在内每次评分都会更新调度次数；本次仅增加整数饱和保护。星期频率约束出题日期，不强制改写算法产生的 `review_due`。

## 4. 评分、幂等与持久化

```java
String eventId = Library.id(); // 为一次点击生成，重试时复用
long occurredAt = System.currentTimeMillis(); // 同一点击重试时保持原值
JSONObject event = library.reviewPlans().record(
    planId, vocabId, "困难", eventId, 2500L, occurredAt);
// 随后 repository.save(library)，成功后再次 dailyQueue 获取剩余任务。
```

评分只能是现有中文枚举 `再次` / `困难` / `简单`。`durationMillis` 取值0～86400000；事件ID非空，最多128字符，全库唯一。该词必须仍属于计划范围、位于对应当地日已初始化的任务中且尚未完成。

同一事件ID及完全相同的 `planId/vocabId/grade/durationMillis/occurredAt` 重放返回原事件，不增加次数或完成数。相同ID配不同参数会报错；不同ID再次提交已完成任务也报错。客户端应防止连点，并保留待重试点击的ID、时间和全部参数。跨当地日未落盘的旧点击应重新获取当天任务，不可伪造旧日期补录。

`create/update/archive/dailyQueue/record` 是内存命令，不自动写磁盘。沿用现有 `MainActivity.change(...)` / `persist()` 的保存与回滚机制：

```java
boolean saved = activity.change(() -> activity.library.reviewPlans().record(
    planId, vocabId, grade, eventId, durationMillis, occurredAt));
// saved 为 false 时不要显示成功或提前移除卡片；从 activity.library 重新取服务。
```

同一个事件的状态、当日任务和历史事件均位于一个 `Library` 快照，由现有 `Repository` 的 `AtomicFile` 原子保存。服务方法在同一实例内串行，但不能跨多个 `Library` 副本并发写同一仓库；客户端应在现有主线程/单写入队列执行命令。若自行使用 `Repository`，保存失败时须恢复调用前快照，避免显示未落盘结果。

无效参数、非法配置、缺失实体、归档计划执行命令、不可修改字段、无效备份均抛 `IllegalArgumentException`（非法日期也可能为 `DateTimeException`）；持久化错误沿用仓库异常处理。`get/list/state/statistics` 返回脱离内部数据的副本，修改返回值不会更新计划。

## 5. 数据结构与旧字段迁移

现有 `library.json` 根对象新增 `review`，旧 JSON/ZIP 外层版本保持不变：

```json
{
  "review": {
    "schema_version": 1,
    "plans": [],
    "states": [],
    "days": [],
    "events": []
  }
}
```

| 集合 | 唯一键 | 主要字段与含义 |
|---|---|---|
| `plans` | `id` | 见计划字段表 |
| `states` | `(plan_id,vocab_id)` | `review_count`、`review_mastery_level`、`review_grade`、`review_due`、`review_efactor`、`review_interval_days`、`reviewed_at`、`learned_at`，可选 `imported_at` |
| `days` | `(plan_id,date)` | 固定的时区、频率结果、配额、实际目标、`created_at`、`tasks` |
| `events` | `id` | `plan_id`、`vocab_id`、`date`、`occurred_at`、`grade`、`kind`、`is_retry`、`completed`、`duration_ms` |

`review_due` 为 UTC epoch 毫秒；`*_at` 为 UTC ISO 字符串；`date` 是计划时区的 `YYYY-MM-DD`。`kind` 为 `new/review`；`learned_at=null` 表示本计划尚未成功学习。`reviewed_at` 是最近评分时间。新数据的 `learned_at` 是首次成功评分时间；迁移数据则是迁移基准时间，可通过 `imported_at` 区分。

旧词汇上的 `review_*`、`reviewed_at` 保持原名与原值，以兼容尚未接入的旧页面；新计划永不向这些字段写入。无 `review` 的旧备份可直接加载，初始化空集合，不强制把全局进度归入某个笔记本。

创建时 `importLegacy=true` 才复制绑定范围内 `review_count>0` 的词汇旧调度数据到该计划。缺失掌握等级沿用现有推断逻辑，非法难度因子、负日期/间隔与非法评分做规范化。缺失的历史答题日期、每天完成量、答题时长都不会伪造；因此迁移后可能已有已学词，但热力图没有迁移前历史。

JSON备份、ZIP备份、恢复及恢复前回滚均包含 `review`。加载与保存会验证子版本、字段类型、计划引用、日期/时区、任务数、事件幂等ID以及任务/事件/进度的一致性。已删除词汇和归档后删除的笔记本允许保留历史引用。未知 `schema_version` 会拒绝加载，避免把未来格式静默清空。

## 6. 统计与后续图表

```java
JSONObject statistics = library.reviewPlans().statistics(
    planId, "2026-10-01", "2026-10-31"); // 起止日期都包含
```

返回 `plan_id/zone_id/from/through/active_days/totals/days`。范围最多3660天。`days` 按日期顺序补齐无答题日，可直接作为热力图输入；未曾初始化任务的日期 `scheduled=null`，避免用现有频率推断历史频率。

`totals` 和每日行的统计字段：

| 字段 | 口径 |
|---|---|
| `attempts` | 所有有效评分次数，包含“再次” |
| `new_completed` | 成功完成的新词数，重复评分不累加 |
| `review_completed` | 成功完成的复习词数，同词不同日分别计数 |
| `again/hard/easy` | 三类评分次数 |
| `duration_ms` | 已提交答题时长之和 |
| `distinct_words` | 每日/查询区间中至少评分一次的不同词汇数 |
| `active_days` | 查询区间内至少评分一次的天数，仅顶层返回 |

每日行另含 `date/scheduled/new_target/review_target`。热力图强度可选 `attempts` 或 `new_completed + review_completed`，客户端应明确标注。当前不提供图表页面、提醒通知、闹钟系统注册或跨计划总统计接口；频率用于任务调度。

事件与每日任务目前完整保留，沿用仓库单快照文本4MB的既有容量上限；达到上限时原子保存会失败并回滚，不会静默删除历史。长周期大量学习的下一步应迁移到分页事件存储并设计统计归档，不能只提高上限绕开 Android 内存预算。

## 7. 客户端接入验收清单

- 无计划时要求先新建；计划列表可选择、编辑及归档。
- 新建时选择笔记本与是否导入旧进度；编辑页禁止更换笔记本或时区。
- 每天/工作日/自选星期映射正确；非计划日、空笔记本、当天已完成有明确状态。
- 评分通过服务提交，不再使用 `ReviewUi` 中直接写词汇 `review_*` 的处理；进度/预览读取计划状态。
- 当天重进、重启、多轮都不超配额；“再次”、跳过/退出均不误计完成。当前没有跳过持久化命令，客户端可仅将本轮跳过词后移，不提交评分。
- 保存失败回滚、连点与重放、词汇移出/删除、笔记本删除保护有反馈。
- JSON/ZIP恢复、跨日、计划独立、统计空日期补零符合上述口径。

后端测试位置：`ReviewPlansTest.java`；设备持久化测试：`ReviewPlansInstrumentation.java`（通过独立测试缓存目录运行，不更改应用中的现有笔记）。验收命令与结果见 `docs/verification/review-plans.md`。
