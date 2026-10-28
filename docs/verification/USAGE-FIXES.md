# 正常使用问题修复与验收

2026-10-28。基线 b8bc2b6；对应 [上一轮9组问题](USAGE-EXPERIENCE.md)。9组均已修复，并使用同一份120词汇样本完成设备回归及实际输入复核。没有调用在线模型、登录或支付服务。

## 修复结果

| 问题 | 当前行为 | 验收 |
| --- | --- | --- |
| UX01 普通查词挤占AI额度 | query、temporary、saved分别管理；普通本地查词不进入临时列表。空白助手草稿保持内存，首次发送才落盘；已有查询显式进入助手时提升为temporary。 | budget本地查询后仍为5临时、8保存、54消息；[实际查询](usage-fixes/manual/screens/08-local-budget.png)、[数据](usage-fixes/manual/after-local.json)。 |
| UX02 导入排序和淘汰错对象 | 最近查询按创建时间，会话按最近使用时间排序；时间相同时按ID稳定排序。最近查询保留10条，AI临时保留5条；saved不参与限额。 | 倒序样本新增budget后删除最旧deadline、保留最新agenda；第六条AI淘汰最旧used to（同时间用ID决胜）；已有查询进入助手后清空历史不会删除该AI会话。 |
| UX03 忘记的词计入熟练 | review_count继续记录尝试次数；独立review_mastery_level记录掌握等级。简单+1、再次归零、困难不升级，三级熟练。本组总数固定，完成与跳过分别计数。 | agenda简单、budget再次后，[进度保持1/10](usage-fixes/manual/screens/09-fixed-review-progress.png)，[熟练数保持16](usage-fixes/manual/screens/10-mastery-recomputed-16.png)。 |
| UX04 搜索JSON字段名 | 仅搜索词条、标签及释义/例句/来源/备注/批注的文字值，支持NFKC、大小写及空白归一化；显示匹配数。 | source匹配4条、批量全选4条。[实际画面](usage-fixes/manual/screens/01-live-source-four.png)。 |
| UX05 切类型丢失搜索文字 | 输入时同步filter并仅刷新结果区域，保留输入控件和光标。切类型、笔记、标签保留文字。 | 未按回车输入meeting，点击短语仍保留meeting，结果为1条。[实际画面](usage-fixes/manual/screens/02-meeting-preserved.png)。 |
| UX06 日期时区错误 | 时间戳转为本地时区后显示及按本地日期筛选；包含结束日全天，支持偏移时间、夏令时和无效值回退。 | Asia/Singapore的10月28日匹配8条保存会话；[首屏](usage-fixes/manual/screens/06-date-local-first.png)、[列表底部](usage-fixes/manual/screens/07-date-local-bottom.png)。 |
| UX07 删除后弹窗陈旧 | 删除和清空后立即重建当前弹窗行，更新存储和首页计数；普通query快照随其历史裁剪，saved/temporary保留。 | context删除后当前弹窗立即消失；继续清空后显示“暂无最近查询”。[设备画面](usage-fixes/screens/usage-fixes/UX07.png)。 |
| UX08 空态误导 | 区分无匹配、父分类和真实空笔记，提供清除筛选入口；只有父分类数量注明“直接词条”，叶笔记保留紧凑标签。 | [无匹配](usage-fixes/manual/screens/03-no-matches.png)、[父分类](usage-fixes/manual/screens/04-parent-guidance.png)。 |
| UX09 标题吞掉点击 | 标题及类型区域显式响应详情/选择，保留标签筛选和标题横向滚动。 | 设备真实触摸和ADB实际点击均打开详情；[标准字号](usage-fixes/manual/screens/05-title-opens-detail.png)、[大字号](usage-fixes/manual/screens/12-title-large-font-detail.png)，[大字号搜索叠加标签](usage-fixes/manual/screens/11-tag-and-search-large-font.png)正常。 |

## 熟练数为何从48变为16

旧统计只检查review_count>=3。样本48个满足这一条件的词中，16个最后评级为简单、16个为困难、16个为再次。新规则对无等级字段的旧数据采用保守兼容：无旧评级或最后为简单且次数>=3，沿用熟练；明确困难、再次或未知评级不推断为熟练。因此本样本重算为16，实际操作后仍为16。

全部120个词条、旧次数和评级原样保留。新产生的掌握等级单独保存；选择再次不会因尝试次数达到3而增加熟练数。[操作后的数据](usage-fixes/manual/after-review.json)包含budget的次数3、评级再次、掌握等级0。审查脚本新增可选-CurrentMastery，旧报告默认仍可使用legacy_count，新报告使用graded_level，避免将旧诊断指标误当新界面统计。

## 旧格式与备份兼容

- 规范化保留已有元数据及未知字段，补齐缺失的标题、来源和时间信息。旧saved=true、自定义顶层标题、无metadata的会话保留名称并标为用户命名，避免被AI标题覆盖。
- 明确retention=query仍按查询历史裁剪，即使其中包含初始用户消息。原版本地查询也可能包含这条消息，不能仅凭非空消息就占用AI临时额度。
- 缺元数据且属于最近查询的旧记录按query迁移；独立于查询索引、类型缺失且有消息的旧会话保守保留为temporary。
- 旧原生记录temporary+origin=query且零消息视为误标query；新显式开启的AI会话记录ai_session_entered，区分已进入的查询上下文。旧格式无法识别曾打开但未发言且没有任何内容的会话，此时按query处理。
- query快照仅保留在最近查询范围内，避免无界增长；保存会话、临时会话和词条不因清空查询历史被删除。

## 验证结果

- 新增15项JVM测试，总计 **132项、0失败、0错误**。覆盖保留/排序/旧数据、等级/进度、文字检索/空态、日期偏移/夏令时/无效日期。
- 离线执行 testDebugUnitTest lintDebug assembleDebug assembleDebugAndroidTest，成功；lint **0错误、13条现有警告**。[完整构建日志](usage-fixes/integrated-build.txt)。
- 新的UsageExperienceInstrumentation **10组通过、0失败**，覆盖9个问题组和额外的查询转助手保留边界。使用打包在测试APK中的同一份公开样本JSON，每组独立重置；[日志](usage-fixes/device-usage-green.txt)。
- 原有 **10组Smoke + 7组Feature + 5组ModelConfiguration通过**，与新增合计32组。[Smoke](usage-fixes/device-smoke.txt)、[Feature](usage-fixes/device-features.txt)、[配置](usage-fixes/device-configuration.txt)。
- 原有图片测试夹具现在显式进入temporary，原图/选区与保存会话断言继续保留；没有通过删除原有断言掩盖保留策略变化。
- 额外用ADB实际输入与点击保存12组PNG/XML，核对实时检索、未提交文字、标签组合、空态、标题、日期、查词和评级。没有用内部performClick代替标题的真实触摸验证。
- 独立代码复核发现的旧标题、空白草稿和查询上下文保留问题已关闭；最后复核无剩余阻塞。

红灯证据：日期实际仅2条及删除后陈旧行见[设备红灯](usage-fixes/device-root-red.txt)；[空白草稿](usage-fixes/device-empty-red.txt)、[上下文提升](usage-fixes/device-context-red.txt)、[3项旧格式失败](usage-fixes/legacy-compat-red.txt)。会话域另有[分类红灯](usage-fixes/session-red.txt)及[规范化红灯](usage-fixes/session-normalization-red.txt)。新纯helper的最初红灯为缺类编译失败，不能将其表述为所有原始行为都已有失败的JVM用例；原始界面行为证据保留在上一轮报告。

设备脚本调试时修正了两处测试设施问题：截图不能从应用主线程调用；窗口切换时反复按Back可能结束Activity。最终改为测试线程截图、通过弹窗自身按钮关闭，并重新执行全部10组取证。早期诊断截图不作为本次通过证据。

## 恢复及交付边界

测试设备Pixel_9a / API37 / 1080×2424。结束时词库、加密服务偏好、恢复前快照逐字节还原，时区恢复GMT，既有before-normal-usage回退文件保留。[还原检查](usage-fixes/restoration.json)、[还原后数据](usage-fixes/restored-state.json)。仍为120词汇、10最近、5临时、8保存、54消息；16熟练为新算法对旧记录的派生结果，不是删除了32个词条。

本轮生成并实际安装运行的APK位于app/build/outputs/apk/debug/app-debug.apk。[文件校验信息](usage-fixes/apk.json)。这是调试签名APK，可覆盖安装相同签名版本。未重新验收真实相机、实体设备、在线模型或真实TokenDance支付；这轮相关网络行为由离线HTTPS夹具覆盖。
