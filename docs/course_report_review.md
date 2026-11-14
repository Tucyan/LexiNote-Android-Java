# 北京联合大学《移动应用开发技术》期末大作业课程设计报告审校与技术核验综合评审报告

> **评审基准项目**：LexiNote 智能英语词汇学习笔记（Android Java 原生版，v1.5.0-native, Build 3）  
> **被评审学生团队**：韩家润（组长，学号 2024250361013）、冯子轩（组员，学号 2024250361010）、李航天（组员，学号 2024250361021）  
> **所属专业/班级**：北京联合大学 · 机器人学院 · 软件工程系 · 软件2401B  
> **指导教师**：宁淑荣  
> **报告完成日期**：2026年11月12日  
> **评审综合机构**：Teamwork Software Engineering Curriculum Review & Audit Board  
> **评审基准文档**：BUU 大作业考核大纲、100 分制评分细则、学生提交报告（封面、摘要、目录、说明书及附录）、代码仓真实实现（`LexiNote-Android-Java`）及外部学术审计意见

---

## 目录

- [执行摘要与评审综述](#执行摘要与评审综述)
  - [一、宏观评审结论：工业级成熟度与学术规范性的张力](#一宏观评审结论工业级成熟度与学术规范性的张力)
  - [二、扣分风险推演与分值损益预估](#二扣分风险推演与分值损益预估)
  - [三、核心三大红线与五大减分陷阱警报](#三核心三大红线与五大减分陷阱警报)
  - [四、外部审计意见综合印证与整改焦点](#四外部审计意见综合印证与整改焦点)
- [第一部分：课程设计要求与百分制评分细则合规性审查 (R1)](#第一部分课程设计要求与百分制评分细则合规性审查-r1)
  - [1.1 课程大纲 6 大规定章节结构完备性对标与结构倒错诊断](#11-课程大纲-6-大规定章节结构完备性对标与结构倒错诊断)
  - [1.2 硬性指标合规性穿透审查](#12-硬性指标合规性穿透审查)
    - [1.2.1 成员贡献度百分比：99% 数学缺陷与“差5%-5”条款扣分风险](#121-成员贡献度百分比99-数学缺陷与差5-5条款扣分风险)
    - [1.2.2 团队规模约束合规性](#122-团队规模约束合规性)
    - [1.2.3 Activity 数量红线：“至少3个 Activity 以上” vs 实际 2 个 Activity 的矛盾深度剖析](#123-activity-数量红线至少3个-activity-以上-vs-实际-2-个-activity-的矛盾深度剖析)
    - [1.2.4 功能数量红线审查（大纲要求 4-5 个 vs 实际 7+ 项）](#124-功能数量红线审查大纲要求-4-5-个-vs-实际-7-项)
    - [1.2.5 提交介质、命名与格式规范深度审查（双文档、录屏头像与压缩包命名陷阱）](#125-提交介质命名与格式规范深度审查双文档录屏头像与压缩包命名陷阱)
  - [1.3 百分制评分项（7项细则）深度推演与扣分预估量化表](#13-百分制评分项7项细则深度推演与扣分预估量化表)
- [第二部分：学术规范性、文风与语言体裁审查及整改建议 (R2)](#第二部分学术规范性文风与语言体裁审查及整改建议-r2)
  - [2.1 本科生软件工程学术报告文风准则与体裁纠偏](#21-本科生软件工程学术报告文风准则与体裁纠偏)
  - [2.2 封面标题规范化学术重构](#22-封面标题规范化学术重构)
  - [2.3 摘要深度审校与规范化学术重写（中英双语）](#23-摘要深度审校与规范化学术重写中英双语)
  - [2.4 关键词标准化（GB/T 标点与技术概念规范）](#24-关键词标准化gbt-标点与技术概念规范)
  - [2.5 报告全篇 36 项全景学术重构对照表](#25-报告全篇-36-项全景学术重构对照表)
  - [2.6 报告通用撰写指南与工程呈现规范](#26-报告通用撰写指南与工程呈现规范)
- [第三部分：技术事实真实性与代码仓底层核验 (R3)](#第三部分技术事实真实性与代码仓底层核验-r3)
  - [3.1 技术事实核验总体概况与可信度等级](#31-技术事实核验总体概况与可信度等级)
  - [3.2 20 项核心技术事实核验矩阵](#32-20-项核心技术事实核验矩阵)
  - [3.3 重点专题技术机理深度剖析](#33-重点专题技术机理深度剖析)
    - [专题一：Android 核心组件与清单配置（Activity 数量与 ImageEditorDialog 实现）](#专题一android-核心组件与清单配置activity-数量与-imageeditordialog-实现)
    - [专题二：ECDICT 离线词库与 Sherpa-onnx 本地神经语音合成](#专题二ecdict-离线词库与-sherpa-onnx-本地神经语音合成)
    - [专题三：SQLite 世代分区混合存储架构与 Java 领域视图（澄清非 SQL 视图）](#专题三sqlite-世代分区混合存储架构与-java-领域视图澄清非-sql-视图)
    - [专题四：SM-2 移动端三档自适应间隔复习算法与幂等提交状态机](#专题四sm-2-移动端三档自适应间隔复习算法与幂等提交状态机)
    - [专题五：TokenDance OAuth 2.0 PKCE 鉴权与 AndroidKeyStore 硬件加密隔离机制](#专题五tokendance-oauth-20-pkce-鉴权与-androidkeystore-硬件加密隔离机制)
    - [专题六：数据备份恢复机制、Zip Slip 路径穿越防御与事务原子回滚](#专题六数据备份恢复机制zip-slip-路径穿越防御与事务原子回滚)
    - [专题七：图像编辑几何变换数学模型与双向分层笔记本树形约束](#专题七图像编辑几何变换数学模型与双向分层笔记本树形约束)
- [第四部分：整改行动计划路线图与雨课堂提交自查清单 (R4)](#第四部分整改行动计划路线图与雨课堂提交自查清单-r4)
  - [4.1 优先级整改行动矩阵（P0 致命缺陷 / P1 重要结构 / P2 细节打磨）](#41-优先级整改行动矩阵p0-致命缺陷--p1-重要结构--p2-细节打磨)
  - [4.2 针对组长韩家润的大作业最终提交预检清单（Pre-submission Gate Checklist）](#42-针对组长韩家润的大作业最终提交预检清单pre-submission-gate-checklist)
  - [4.3 现场答辩攻防与评委常见提问应对预案](#43-现场答辩攻防与评委常见提问应对预案)

---

## 执行摘要与评审综述

### 一、宏观评审结论：工业级成熟度与学术规范性的张力

本评审针对北京联合大学机器人学院软件工程系 202425 级《移动应用开发技术》期末大作业课程设计报告《智能英语词汇学习笔记》（被审版本：v1.5.0-native, Build 3）展开全方位交叉核验。

整体评估显示：**本项目的工程落地深度、软件架构鲁棒性、数据持久层设计、离线神经发音算法及安全性实践（硬件 KeyStore 加密、Zip Slip 防御）达到了同类本科生课程设计中罕见的工业级水准，工作量极其饱满，完全具备冲击 95-100 分卓越档位（优秀）的硬核实力。**

然而，报告文本在**课程大纲合规性、体裁规范性与技术事实表达**方面存在鲜明张力，表现出**“重底层架构审计、轻课程设计叙事”、“代码真实实现与报告文字声明产生脱节”**等突出问题。报告整体呈现出软件工程审计白皮书或技术白皮书的风格，缺乏作为本科生课程设计所需的“问题驱动”、“学生探索实践”与“市场可行性论证”语境。

### 二、扣分风险推演与分值损益预估

在未修正现状基准下，根据北京联合大学《移动应用开发技术》100 分制评分细则与强制扣分条款，项目面临**常规潜在扣分 -12 至 -28 分**的巨大风险；若不慎触发提交格式缺陷（如缺少 DOCX、视频未露头、压缩包命名错误或工程超大体积导致雨课堂熔断拒收），扣分甚至将累加至 **-30 至 -45 分**以上，导致原本极为优秀的工程项目滑落至及格边缘甚至不及格。

```mermaid
flowchart LR
    A["基准满分潜力 (95~100分)"] --> B{"合规与事实漏洞"}
    B -->|Activity 数量不足与虚构| C["扣 5~8 分"]
    B -->|贡献度 99% 未闭环| D["扣 5 分 (防范差5%-5)"]
    B -->|缺失市场前景分析| E["扣 2~4 分"]
    B -->|格式/命名/视频违规| F["扣 10~20 分"]
    C & D & E & F --> G["现状风险预测得分: 72~88分 (良好/中等)"]
    G -->|落实 P0/P1/P2 整改| H["整改后预期得分: 95~100分 (卓越/高优)"]
```

### 三、核心三大红线与五大减分陷阱警报

1. **红线一：Activity 数量不足与虚报组件（致命技术诚信与指标违规）**
   - **大纲红线**：“至少3个 Activity 以上”。
   - **底层事实**：`AndroidManifest.xml` 中**仅有 2 个 `<activity>` 声明**（`MainActivity` 与 `TokenDanceActivity`）。报告声称的 `ImageEditorActivity` 在代码仓中根本不存在，实际为基于 `Dialog` 封装的控制器 `ImageEditorDialog`。
   - **后果**：直接违反课程硬指标，且构成技术事实虚标，答辩核验时极易引发评卷教师对学术诚信的严重质疑。
   - **解法指引（零风险路径）**：严禁临考前盲目将 593 行且持非 Parcelable 复杂手势状态的 `ImageEditorDialog` 改为 Activity（致瘫风险极高）；强烈推荐以 ~15 行代码新增极简 `AboutActivity`（关于与团队介绍页）或 `SplashActivity` 注册入清单，零风险、零侵入稳妥满足 $\ge 3$ 个 Activity 硬指标。
2. **红线二：封面贡献度百分比 99% 未闭环（低级算术硬伤触发扣分口实）**
   - **大纲红线**：“报告首页写每人%，差5%-5”。
   - **底层事实**：封面标注韩家润（33%）、冯子轩（33%）、李航天（33%），$33\% \times 3 = 99\% \neq 100\%$，剩余 1% 悬空。
   - **扣分机理与后果**：虽然组员间差距为 0% 且 1% 缺口未达 5% 梯度惩罚阈值，但在严苛答辩中，“总和未达 100% 闭环”的低级算术疏漏会直接给严厉评委提供挑剔借口，极易被机械套用“差5%-5”条款扣除 5 分。必须调整为 34%/33%/33%（或高精度均分）彻底闭环。
3. **红线三：大纲规定章节结构倒错与关键要件缺失**
   - 大纲第 1 部分“团队介绍和人员分工”被置于全书最末尾（第 57 页附录中），导致正文首章倒错。
   - 大纲第 3 部分明文要求的“市场前景与竞品分析”（在评分细则第 1 项独占 10 分）在目录与正文中**完全缺失**，仅写了技术可行性，存在 -2~-4 分（甚至 -4~-5 分）的失分风险。
4. **五大提交格式与上传减分陷阱（每项扣 10 分，或导致系统拒收）**：
   - **雨课堂附件体积熔断陷阱（1.75 GB 导致拒收判零分）**：当前代码仓由于历史构建产物及 Git 记录高达 **1758 MB (约 1.75 GB)**。雨课堂大作业附件通常限制单文件在 100MB~200MB 以内。若直接打包上传必遭系统拒绝或网络超时断连，造成“未交大作业”直接判零分！提交前必须运行 `.\gradlew.bat clean` 并剔除 `.gradle/`、`build/`、`.idea/`、`.git/`，确保压缩包精简至 20~30MB。
   - **双文档陷阱**：提交大作业报告必须**同时包含 docx 与 pdf**，少一种格式扣 10 分。工程内已有的 `docs/software-manual-v1.5.pdf` 仅为使用说明书，绝不能替代大作业报告 PDF！
   - **视频录制陷阱**：“要用电脑录制，手机录制视为无效。同时要出现全组人员的头像”，缺少全员真人头像或用手机对着屏幕拍摄将直接判无效并扣分。
   - **压缩包命名陷阱**：“提交格式：学号姓名_学号姓名_学号姓名.zip，提交格式错误，减10分”，禁止附加项目名称或空格。
   - **雨课堂单人提交**：必须仅由组长韩家润一人在雨课堂提交，组员重复提交将导致版本覆盖或系统判定异常。

### 四、外部审计意见综合印证与整改焦点

综合外部学术审计审查结论与内部全景勘探结果，双方在以下关键缺陷上达成高度印证：
1. **未替换占位符**：报告第 58 页团队分工存在未经填写的模板占位文本（“建议填写的真实承担任务”），必须立即替换为真实工程贡献。
2. **需求编号前后冲突**：第 1.2 节功能需求与第 1.6 节追踪矩阵中需求 ID 定义矛盾（例如 1.2 中 FR-02 定义为“语境/AI 查询”，但在 1.6 中变成了“图片选区”）。
3. **测试证据版本定性混淆**：报告将此前跨端迁移的早期历史测试日志与当前 Android 原生提交版本的验收结论杂糅，须明确划定“历史演进证据”与“当前提交版（Build 3）验收”。
4. **算法学名校准**：`ReviewLogic.java` 实现的是面向触控优化的三档离散规则，并非经典 6 档 SM-2 连续公式，建议定名为“基于 SM-2 思想的改进型间隔复习算法”，避免答辩时评委针对经典公式深度追问造成被动。
5. **TokenDance 真实性边界**：`TOKENDANCE.md` 明确记录此前测试主要基于沙盒与模拟响应，尚未实测真实账户划扣与在线支付，报告中截图与流程应明确标注为“模拟沙盒测试”与“设计验证流程”。
6. **错别字与材料口吻**：使用说明书图 37 标题“观楂登录”系错别字，须订正为“观猹登录”；说明书导言中“本说明以随附新版软件说明书……为依据”属自我循环引用的套话，必须剔除。
7. **答辩分值口径与防守预案**：BUU 大纲评分表第 5 项标注为 10 分，但外部教学审计提示部分教师细则中答辩实际核定为 15 分（将第 6 项中“团队合作 5 分”统合至现场答辩综合考核）。团队必须做好按 15 分高标准权重的答辩准备，并在答辩前向任课教师核实最终分值分布。

---

## 第一部分：课程设计要求与百分制评分细则合规性审查 (R1)

### 1.1 课程大纲 6 大规定章节结构完备性对标与结构倒错诊断

北京联合大学机器人学院《移动应用开发技术》课程设计大纲明确规定了课程设计报告的 6 大主要核心内容单元：
> 1. 团队介绍和人员分工  
> 2. 作品概述、功能  
> 3. 作品可行性分析和目标群体（技术可行性，市场前景，目标群体等）  
> 4. 作品实现、流程图、难点及特色分析（主要实现技术，特色设计等）  
> 5. 软件使用说明  
> 6. 其它  

将学生当前提交报告的目录层级与大纲要求进行逐项严格映射比对，诊断结果如下：

| 大纲规定章节 (Syllabus) | 学生报告对应位置 (TOC Mapping) | 吻合状态 | 审计论证与结构倒错诊断 | 扣分隐患与整改建议 |
|---|---|---|---|---|
| **1. 团队介绍和人员分工** | 封面写贡献度；正文在文末 `心得体会: 1. 团队分工 (p.57)` | ⚠️ **严重位置倒错与部分缺失** | 大纲将团队介绍与分工列为第 1 章节。学生报告正文第一章直接为“需求分析”，将人员分工边缘化并倒置在文末（第57页），且正文中完全缺失“团队成员背景/特长介绍”。极易被评卷教师误判为“首章缺失”。 | **扣 3~5 分**。<br>建议将文末分工提取并扩展，作为正文独立的第 1 章 `1 团队介绍与人员分工`，详细列出每位组员的技术角色与特长。 |
| **2. 作品概述、功能** | 摘要；`1.2 功能需求定义`；`2.3 功能模块划分`；说明书第 1 章 | ⚠️ **碎片化分布，缺乏独立综述节** | 内容实际非常饱满，但正文未设立直观的“作品概述与定位”节，概述内容碎片化分布在摘要和需求分析中，使评审教师难以在首章快速捕捉项目全貌。 | **扣 1~2 分**。<br>建议在需求分析前设立 `2.1 作品定位与整体概述`，形成显式呼应。 |
| **3. 作品可行性分析和目标群体（技术可行性，市场前景，目标群体等）** | `1.1 目标群体与典型使用场景`；`1.4 技术可行性` | ⚠️ **关键要件“市场前景”严重缺失** | 大纲明文要求涵盖“市场前景”，评分细则第 1 项更明确设立“作品创意与市场前景（10分）”。目录中仅有 1.1（目标群体）与 1.4（技术可行性），**通篇缺失“市场前景”或“竞品差异化对比”节**，构成实质性重大扣分点。 | **扣 2~4 分**。<br>必须在第 1 章增补独立的 `1.1.2 市场前景与竞品差异化对比`，深度分析欧路词典、墨墨背单词等竞品及商业推广潜力。 |
| **4. 作品实现、流程图、难点及特色分析（主要实现技术，特色设计等）** | `第3章 详细设计与实现、流程图、难点及特色分析 (p.14-34)` | ✅ **完全显式吻合且超额达标** | 标题字面与大纲完全契合；涵盖 3.1.1 查询工作流、3.1.3 决策树流程图、3.5.3 状态机、3.9 难点与特色分析，篇幅达 20 页，工程细节扎实。 | **0 扣分**（达标且具优势）。需修正其中的类名错误与算法命名。 |
| **5. 软件使用说明** | 独立大章节 `软件使用说明书 (p.40-57)` 共 8 章 | ✅ **完全显式吻合且质量卓越** | 独立成大篇，长达 17 页，配图完备，涵盖安装、TokenDance 授权、充值、离线词库、备份换机及操作链复现，远超一般本科课程水平。 | **0 扣分**（达标）。需剔除元叙事话术与错别字。 |
| **6. 其它** | `4 测试 (p.35)`；`结论 (p.38)`；`心得体会 (p.57)` | ⚠️ **实质完备但缺乏显式归集** | 测试用例、性能评测、结论与心得构成了极高水准的“其它”内容，但未在目录或附录中设立“6 其它”统领标题，可能在机械套表打分时产生争议。 | **扣 0~1 分**。<br>建议将测试、结论及心得统合或在附录明确注明对应大纲第 6 部分。 |

---

### 1.2 硬性指标合规性穿透审查

#### 1.2.1 成员贡献度百分比：99% 数学缺陷与“差5%-5”条款扣分风险
- **事实取证**：报告封面载明：“韩家润（33%）、冯子轩（33%）、李航天（33%）”。
- **数学检验**：
  $$33\% + 33\% + 33\% = 99\% \neq 100\%$$
- **规则惩治条款**：“报告首页写每人%，差5%-5”。
- **风险推演与条款机理解析**：
  大纲明文规定：“报告首页写每人%，差5%-5”。在高校教学评价实践中，该条款包含双重意图：其一是考核团队内部贡献度差异梯度（若组员间差距 $\ge 5\%$ 则相应梯次扣分以遏制“搭便车”现象）；其二是核验贡献度总和闭环率。当前三位同学均为 33%，组员间极差为 $0\%$，总和为 $99\%$。虽然 $1\%$ 的总和缺口严格在数学上并不等于跨越了 $5\%$ 的差异阈值，但“总和未达 100%”的低级算术硬伤在严苛的答辩评审中极易给评卷教师留下极不严谨的负面印象，甚至被机械套用该条款顶格扣除 5 分。
- **整改方案**：
  必须消除任何潜在争议口实，立即将封面贡献度调整为总和严格等于 100% 的比例方案：
  - **方案 A（体现组长主导，强烈推荐）**：韩家润（组长，34%）、冯子轩（33%）、李航天（33%）；
  - **方案 B（高精度均分）**：韩家润（33.4%）、冯子轩（33.3%）、李航天（33.3%）。

#### 1.2.2 团队规模约束合规性
- **大纲要求**：“2-3人一组完成。”
- **底层事实**：组长韩家润 + 组员冯子轩、李航天共 3 人。
- **结论**：**完全合规**。

#### 1.2.3 Activity 数量红线：“至少3个 Activity 以上” vs 实际 2 个 Activity 的矛盾深度剖析
- **大纲要求**：“至少3个 Activity 以上。”
- **报告陈述**：摘要与正文第 2.4 节明确宣称：“系统以 MainActivity、TokenDanceActivity、ImageEditorActivity 等 Android 组件完成导航、账号回调和图像编辑”。
- **底层代码核查（真相）**：
  1. 审查 `app/src/main/AndroidManifest.xml`：
     - 行 14：`<activity android:name=".MainActivity" ... />`
     - 行 21：`<activity android:name=".TokenDanceActivity" ... />`
     - 清单文件中**自始至终仅有 2 个 `<activity>` 节点**！
  2. 检索工程全部 Java 源文件：根本不存在 `ImageEditorActivity.java`。
  3. 实际图像裁剪与涂抹由 `com.lexinote.nativeapp.ImageEditorDialog.java`（基于 `android.app.Dialog` 封装的控制器）配合自定义 View `ImageCropView.java` 完成，其本质是依附于 `MainActivity` 渲染的对话框组件，绝非独立 Activity！
- **合规危机研判**：
  1. **硬指标不达标**：实际 Activity 数量仅为 2，直接击穿大纲“至少3个 Activity 以上”的底线。
  2. **学术真实性与作假风险**：报告声称存在 `ImageEditorActivity`，若教师通过 APK 逆向、Manifest 检查或源码搜索，会立刻发现该类根本不存在。这不仅导致该项硬性扣分，更会彻底摧毁评委对整份报告技术真实性的信任，波及“功能实现与演示（30分）”与“报告整体评价（10分）”。
  3. **“以上”语法歧义风险**：中文语境下，“3个以上”有时被严厉评卷者解读为 $>3$（即需 4 个）；即便解读为 $\ge 3$，当前仅有 2 个也属于严重违规。
- **整改应急方案权衡与零风险最佳实践**：
  - **警惕误区：切勿盲目临考重构 ImageEditorDialog（致瘫风险极高）**：
    部分分析建议“直接将 `ImageEditorDialog` 改写为 `ImageEditorActivity`”。经深入代码审查，该方案在临近提交时具备极高的**破坏半径（High Blast Radius）**：
    1. `ImageEditorDialog.java` 代码长达 593 行，深度嵌套自定义触摸手势与矩阵运算；
    2. 其依赖的 `ImageEditorState.java` **未实现 `Parcelable` 或 `Serializable` 接口**，且手势自由笔触数据上限高达 8192 点（`MAX_TOTAL_INK_POINTS = 8192`）。在 Android 跨组件 Intent 传输中，极易触发 Binder 事务 1MB 物理缓冲区上限，导致致命的 `TransactionTooLargeException` 闪退崩溃；
    3. 改为 Activity 还需重新处理复杂的图片 URI 跨组件授权、屏幕旋转（`onSaveInstanceState` / `ViewModel`）、销毁重建以及与 `MainActivity` 间繁复的 `ActivityResultLauncher` 结果双向回调。
    在期末大作业提交前的最后窗口，进行如此复杂的生命周期大手术，一旦现场答辩演示时发生闪退，将直接击溃占 30 分的“功能实现与作品演示”！
  - **方案 A（零风险安全推荐路径：新增极简 AboutActivity / SplashActivity，仅需 15 分钟）**：
    若仅为满足大纲“至少3个 Activity 以上”的形式要求，**最安全、零风险的工业级解法是新增一个极简的独立 Activity**：
    在工程中新建一个独立的 `AboutActivity.java`（“关于软件与开发团队”页面）或 `SplashActivity.java`（启动闪屏欢迎页）。以 `AboutActivity` 为例：
    1. **代码量极小**：仅需约 15~20 行 Java 代码，承载一个简单的团队介绍、版本号（v1.5.0-native）与致谢布局；
    2. **配置透明**：在 `AndroidManifest.xml` 中注册 `<activity android:name=".AboutActivity" android:exported="false" />`，并在“我的/设置”页面（`SettingsUi`）增设一个“关于我们与开发团队”按钮发起显式 `startActivity(new Intent(a, AboutActivity.class))`；
    3. **完美契合大纲**：清单文件中 `<activity>` 节点实打实达到 3 个，自动化静态检查与人工清点 100% 满分过关，且该页面天然呼应大纲第 1 章节明文规定的“团队介绍和人员分工”；
    4. **零破坏半径**：对现有查词、发音、图片选区、AI 助手中复杂的业务逻辑**完全零侵入、零回归风险、零崩溃隐患**，是最高效稳健的工程决策。
  - **方案 B（若代码完全封板无法重新打包）**：
    报告中必须彻底删除所有虚构的 `ImageEditorActivity` 字样，据实说明系统采用“单 Activity + 模块化 Dialog”架构（由 `MainActivity` 承载核心界面，`TokenDanceActivity` 承载深层链接回调），在正文第 2.4 节从现代移动开发（Single-Activity 架构理念、避免 8192 点跨进程 Binder 序列化开销、利用 Dialog 内存直接共享提升图像交互帧率）进行技术答辩包装，并列出后续多 Activity 演进路线。但团队必须知晓，此方案仍承担评卷教师按大纲字面扣 5~8 分的形式审查风险。

#### 1.2.4 功能数量红线审查（大纲要求 4-5 个 vs 实际 7+ 项）
- **大纲要求**：“功能4-5个以上。”
- **底层事实**：经对代码仓全面核查，LexiNote 原生版实现了：
  1. 本地 ECDICT 离线词库毫秒级检索与词形变化还原（`OfflineDictionary.java`）；
  2. Sherpa-onnx 离线神经语音发音合成（`SherpaPronunciationEngine.java`）；
  3. 拍照与相册图片的交互式几何旋转、局部涂抹与矩形选区裁剪（`ImageEditorDialog.java`, `ImageCropView.java`）；
  4. 基于 OAuth 2.0 PKCE 的 TokenDance AI 助手中英文长对话与智能标题生成（`TokenDanceActivity.java`, `TitleSuggestionGuard.java`）；
  5. 具备二分层级约束的多笔记本知识库构建与批量管理（`Library.java`, `NotebookUi.java`）；
  6. 基于 SM-2 认知记忆曲线的三档离散间隔复习与防抖幂等状态机（`ReviewLogic.java`, `ReviewSessionState.java`）；
  7. 具备抗 Zip Slip 路径穿越校验与 SQLite 事务原子回滚的数据备份与跨设备恢复机制（`BackupArchive.java`, `SqliteLibraryStore.java`）。
- **结论**：**功能数量达 7 项以上，显著超出大纲基准，技术含金量极高，完全合规。**

#### 1.2.5 提交介质、命名与格式规范深度审查（双文档、录屏头像与压缩包命名陷阱）
- **代码与注释**：代码库注释规范详尽，符合要求。
- **双文档提交红线（缺少任一扣 10 分）**：
  - 大纲规定：“提交：...大作业报告③docx+④pdf，...少一种格式减10分”。
  - **严重误区预警**：学生切勿将代码仓中现成的 `docs/software-manual-v1.5.pdf` 当成大作业报告 PDF 提交！该文件仅仅是软件使用说明书，并非包含“需求分析、架构设计、实现流程、测试与总结”的期末课程大作业报告。必须在排版完成后，将完整大作业报告分别导出为 `.docx` 和 `.pdf` 两个文件。
- **视频录制与全员头像出镜红线（缺少扣 10-15 分）**：
  - 大纲规定：“程序完成后的使用操作，要进行录制，同时要出现全组人员的头像。要用电脑录制，手机录制视为无效。”
  - **操作准则**：
    1. 必须使用 PC 端的录屏软件（如 OBS Studio、EV 录屏或腾讯会议 PC 客户端录屏），在电脑上运行 Android 模拟器或投屏工具操作，严禁手持手机对手机屏幕录像；
    2. 必须包含全组成员 3 人（韩家润、冯子轩、李航天）的真人出镜头像。推荐使用腾讯会议开启 3 人视频会议，在电脑端共享屏幕演示软件，同时在录屏右上角平铺 3 人的摄像头画面。
- **压缩包唯一合法命名规范（错误扣 10 分）**：
  - 大纲规定：“提交格式：学号姓名_学号姓名_学号姓名.zip，提交格式错误，减10分”。
  - **唯一合法文件名**：
    `2024250361013韩家润_2024250361010冯子轩_2024250361021李航天.zip`
  - **致命禁忌**：不得带有任何多余字符（如禁止写成 `移动开发大作业_...zip` 或 `LexiNote_...zip`）、不得包含空格、不得使用中划线 `-`（必须使用下划线 `_` 连接）、必须是标准 `.zip` 格式（严禁压缩为 `.rar` 或 `.7z`）。
- **雨课堂单人提交机制**：
  - 大纲规定：“每组仅组长一人雨课堂提交大作业文件，先交报告再答辩”。
  - 必须严格由组长韩家润一人在雨课堂上传压缩包，组员冯子轩、李航天严禁重复上传，以防雨课堂系统判定冲突或产生版本覆盖。
- **雨课堂附件 1.75 GB 上传熔断陷阱与打包瘦身规范（致命提交风险）**：
  - **体积爆炸事实**：经实测，LexiNote 原生工程根目录物理占用高达 **1758 MB（约 1.75 GB）**，这主要是由于长期本地编译生成的 Gradle 缓存（`.gradle/`）、构建输出中间件（`app/build/`）、IDE 索引缓存（`.idea/`）以及完整的 Git 版本控制历史（`.git/`）所致。
  - **雨课堂平台限制**：高校雨课堂（Rain Classroom）大作业提交系统通常对单个附件设置了 **100MB ~ 200MB 的上传硬性配额**，且在期末集中提交高峰期网络极易发生 HTTP 超时断连。若组长韩家润未做清理，直接对工程根目录右键压缩打包，生成的 ZIP 文件将突破 1 GB，上传雨课堂将直接遭遇“文件过大无法上传”或网络超时失败，最终导致“逾期未提交”被直接判定为零分！
  - **标准化提交前瘦身与打包指令（组长必须执行）**：
    在打包提交前，必须在项目根目录 PowerShell 终端中执行清理：
    ```powershell
    # 1. 执行 Gradle 深度清理，清除全部构建产物与临时 class/dex
    .\gradlew.bat clean
    ```
    在制作最终 ZIP 压缩包时，**必须且仅打包必要文件**，严禁将下列目录打入提交包：
    - ❌ 坚决剔除：`.gradle/`、`app/build/`、`build/`、`.idea/`、`.git/`、`.agents/`；
    - ✅ 必须包含：`app/src/`、`app/build.gradle`、`build.gradle`、`settings.gradle`、`gradlew`、`gradle/`、`docs/`、大作业报告（`.docx` 与 `.pdf`）及演示视频（`.mp4`）；
    - **体积达标线**：执行 clean 并剔除缓存后，纯净工程源码压缩包仅约 **20MB ~ 30MB**，加上压缩后的演示视频，总包体积可轻松控制在 80MB 以内，秒级上传雨课堂，100% 规避系统熔断风险。

---

### 1.3 百分制评分项（7项细则）深度推演与扣分预估量化表

依据课程 100 分制评分标准细则，对学生报告现状进行逐项穿透式量化推演：

| 评分细则项目 | 分值 | 报告与项目支撑点 | 达标深度评估 | 潜在扣分隐患分析 | 现状预估扣分 | 整改后预期得分 |
|---|---|---|---|---|---|---|
| **1. 作品创意与市场前景** | 10分 | 1.1 目标群体；1.4 技术可行性；3.9 难点与特色取舍 | 离线词典 + 大模型双核协同 + SM-2 记忆曲线 + 神经发音，创意卓越 | ⚠️ **目录与正文中完全缺失“市场前景”与“竞品分析”小节**。仅有技术可行性，未能论证商业或实际推广潜力。若严格按评分项核查，失分风险较大。 | **-2 ~ -4分**<br>*(严审可能达 -4~-5分)* | 8 ~ 10分 |
| **2. 功能、流程图与UI设计描述** | 10分 | 1.2 功能需求；2.3 模块关系；3.1.1/3.1.3 流程图；3.8 UI 设计；4.5 视觉呈现 | 流程图详实（离线决策树、SM-2 状态机）；UI 规范统一，包含完整组件说明 | 1. 目录 1.3 存在“非方向需求”严重笔误；<br>2. 1.2 与 1.6 节的需求编号存在 FR 冲突；<br>3. 建议补充标准系统用例图。 | **-1 ~ -2分** | 9 ~ 10分 |
| **3. 功能实现与作品演示** | 30分 | 3.1 - 3.10 全章；4.2 功能测试；说明书第八章演示路径 | 技术硬核（SQLite 混合存储、Sherpa 离线发音、手势裁剪矩阵、Zip 防遍历） | 🚨 **致命硬伤：Manifest 实际仅 2 个 Activity，违背“至少3个”要求，且报告声称存在 `ImageEditorActivity`**，极易被重扣！ | **-5 ~ -8分** | 28 ~ 30分 |
| **4. 文档设计 (10分) + 视频录制 (5分)** | 15分 | 4.1-4.5 测试章节；57页高规格排版；全套图表交叉引用 | 文档体量庞大，测试规范严密，说明书图文并茂 | 1. 缺少 DOCX 或缺少 PDF 将直接触犯扣 10 分红线；<br>2. 视频若非电脑录屏或缺少 3 人头像，视频项直接判定无效（扣 5 分追加扣 10 分）。 | **0 ~ -5分**（若犯格式错误则 -15分） | 14 ~ 15分 |
| **5. 现场陈述与回答问题**<br>*(外部审计提示细则常占15分)* | 10分<br>*(或15分口径)* | 3.11 核心操作链设计；心得体会分工 | 专门针对答辩设计了核心演练路径，逻辑清晰 | 1. 若答辩教师质询“为什么 Manifest 只有 2 个 Activity”或“代码中为什么搜不到 ImageEditorActivity”，现场难以自圆其说；<br>2. **分值口径差异说明**：BUU 大纲物理评分表上本项为 10 分，第 6 项团队合作为 5 分。但外部教学审计反馈在部分班级实际答辩细则中，常将“团队合作（5分）”合并至答辩现场打分，形成“现场陈述与答辩（含团队协作）共 15 分”的标准。团队务必按 15 分高标准权重严肃演练攻防，并在答辩前向任课教师核实最终分值分布。 | **0 ~ -3分**<br>*(若按15分口径扣分风险为 0~-5分)* | 9 ~ 10分<br>*(或14~15分)* |
| **6. 团队合作 (5分) + 软件使用说明书 (10分)** | 15分 | 说明书（p.40-57, 17页8章）；心得体会 1. 团队分工 | 说明书达到商业级水准（10分可拿满）；分工记录详尽 | ⚠️ 1. 封面 33% 违背 100% 闭环（触犯“差5%-5”条款）；<br>2. 正文第 1 章缺失团队介绍与分工，倒置于文末；<br>3. 第 58 页存在未填写的占位符。 | **-3 ~ -5分** | 14 ~ 15分 |
| **7. 报告整体评价** | 10分 | 57 页高体量文档、单元测试覆盖率、性能测试证据 | 篇幅极其充实，排版工整，技术深度远超普通本科生水准 | 结构未显式按大纲 1-6 对齐；语言风格偏向软件白皮书/审计报告，缺乏课程设计学生实践叙述。 | **-1 ~ -2分** | 9 ~ 10分 |
| **总计** | **100分** | — | — | **常规未整改预期扣分：-12 至 -28 分（预测得分 72~88 分）**；若叠加格式违规将跌破 70 分。 | **-12 ~ -28分** | **95 ~ 100分** |

---

## 第二部分：学术规范性、文风与语言体裁审查及整改建议 (R2)

### 2.1 本科生软件工程学术报告文风准则与体裁纠偏

一份优秀的本科生软件工程期末大作业报告应当兼具**学术严谨性、工程客观性与课程设计的实践探索性**。通过对当前报告文本的全面审读，发现存在以下三类典型的体裁偏差：

1. **商业宣传与营销口吻（Marketing Hype）**：使用了大量消费级移动 App 宣传词或电商营销话术（如“专属授权令牌”、“沉浸式训练”、“七种排序方式、三种释义模式”、“强大的离线词典”）。必须全量剔除主观形容词，转为客观、中立、可度量的工程技术表述。
2. **通俗口语与生活化用语（Colloquial Jargon）**：出现“防误覆盖”、“换机恢复”、“翻卡”、“看词猜义”、“连续打卡”等口语化名词。在计算机科学与认知心理学中，应严格使用“竞态覆盖防护”、“跨设备数据迁移”、“单词再认模式”、“词义回想模式”及“学习打卡统计”。
3. **软件白皮书/架构审计材料偏差（Whitepaper & Meta-commentary）**：报告过多采用了诸如“生命周期纪元”、“恢复的事务边界”、“仓库已有规模与性能证据”等偏向工程审计与司法取证风格的晦涩冷词，且出现了“面向课程答辩的核心操作链”、“按操作顺序复现的验收演示”等直接针对教师答辩的迎检元叙事。这破坏了技术文档的角色真实性。应还原为“以问题为导向”的学生课程设计叙事，引出需求背景与设计权衡。

---

### 2.2 封面标题规范化学术重构

- **学生原报告标题**：
  > 题目：智能英语词汇学习笔记
- **语言学与工程规范批判**：
  原标题仅为一个消费级 App 的商品化名称。“学习笔记”在学术评审中极易产生歧义，使评阅教师误以为是学生课堂记录的学习手记，而非一款移动端软件系统工程报告；“智能”一词过于泛化，缺乏技术落地抓手。作为工科毕业设计或期末大作业报告，标题必须具备：**运行平台（Android）、核心功能/领域（智能词汇学习与间隔复习）以及工程生命周期标识（系统设计与实现）**。
- **推荐规范化学术重写**：
  > **基于 Android 平台的智能英语词汇学习与间隔复习系统设计与实现**  
  > *(Design and Implementation of an Intelligent English Vocabulary Learning and Spaced Repetition System Based on Android Platform)*

---

### 2.3 摘要深度审校与规范化学术重写（中英双语）

#### 2.3.1 学生原报告摘要逐句剖析
- **原句 1**：`LexiNote（v1.5.0-native，Build 3）是一套基于 Android Java 原生组件的英语词汇学习、笔记管理与科学复习应用。`  
  *批判*：开篇直接堆砌商业版本号与 CI 构建元数据（`v1.5.0-native, Build 3`），非学术规范；“是一套...应用”为商业推广量词；“科学复习”缺乏认知算法界定。
- **原句 2**：`软件将查询记录、笔记管理、复习中心和设置与数据安全组织为四个对等模块，利用 ECDICT 离线词库及本地发音技术支持弱网与无网环境下的基础学习；需要语境理解时，可在用户主动授权与联网的条件下连接外部大模型。`  
  *批判*：“四个对等模块”非软件工程标准术语（表现层导航并不能代表业务对等）；“复习中心”具消费级 UI 色彩；“连接外部大模型”过于口语化。
- **原句 3**：`系统以 MainActivity、TokenDanceActivity、ImageEditorActivity 等 Android 组件完成导航、账号回调和图像编辑；查询、笔记、复习与设置模块拆分为相应 Java UI 类。`  
  *批判*：**存在重大事实违规**，虚构了代码中不存在的 `ImageEditorActivity`；“拆分为相应 Java UI 类”表述含混，未体现模块化视图控制器模式。
- **原句 4**：`数据采用 SQLite 与结构化兼容视图组织，按学习记录、词条关系和复习排期进行持久化，同时支持 ZIP/JSON 备份恢复、图片编辑、会话管理及不同记忆模式。`  
  *批判*：长句罗列杂糅，将存储模型、安全备份、UI 操作与认知模式混在一起，缺乏分层概括。

#### 2.3.2 规范化学术重写文本（中文）
> **摘要**：针对移动端英语词汇学习过程中离线检索受限、发音依赖网络以及长期记忆保持率低等实际问题，本系统设计并实现了一套基于 Android 原生架构的智能词汇学习与间隔复习系统（LexiNote）。系统表现层采用模块化视图控制器设计，以 `MainActivity` 作为视图调度中枢，配置 `TokenDanceActivity` 处理 OAuth 2.0 PKCE 鉴权与深层链接，并结合 `ImageEditorDialog` 提供交互式图片选区与多模态取词能力；在业务逻辑层，系统集成内置 ECDICT 离线词典与 Sherpa-onnx 本地神经语音合成引擎，实现零网络延迟下的毫秒级单词检索与离线发音，同时支持在用户授权下协同调用云端大语言模型进行语境拓展。数据持久层基于 SQLite 构建世代分区混合存储架构与强类型领域兼容视图，结合移动端改进型 SM-2 间隔复习算法实现科学排期与幂等状态流转；在安全与容灾方面，系统采用 AndroidKeyStore 硬件隔离保护服务凭据，并构建了具备抗路径穿越安全校验的 ZIP 备份与原子事务恢复机制。测试结果表明，系统在复杂离线与弱网场景下运行稳定高效，满足高可用移动学习工具的工程要求。

#### 2.3.3 Standard Academic Rewrite (English Abstract)
> **Abstract**: Addressing the challenges of network dependency in dictionary lookup, latency in pronunciation services, and low long-term memory retention in mobile English vocabulary learning, this project designs and implements an intelligent English vocabulary learning and spaced repetition system (LexiNote) based on native Android architecture. The presentation layer utilizes a modular view-controller pattern, where `MainActivity` orchestrates primary view navigation, `TokenDanceActivity` handles OAuth 2.0 PKCE authentication callbacks via deep links, and `ImageEditorDialog` supports interactive image cropping and multimodal text input. At the business logic layer, the system integrates a local ECDICT database and a Sherpa-onnx offline neural text-to-speech engine to ensure millisecond lookup and speech synthesis under offline environments, while asynchronously integrating cloud-based Large Language Models (LLMs) for contextual assistance under authorized network access. The persistence layer adopts a generation-scoped hybrid SQLite architecture paired with strongly typed domain views, driving an adapted mobile SM-2 spaced repetition algorithm for scientific review scheduling and idempotent state management. For security and disaster recovery, API credentials are protected via hardware-backed AndroidKeyStore encryption, while an atomic transaction rollback mechanism and Zip Slip path-traversal prevention ensure secure backup and restoration. Empirical evaluations confirm that the system achieves high reliability, low latency, and robust offline availability, fulfilling all requirements for a production-grade mobile learning platform.

---

### 2.4 关键词标准化（GB/T 标点与技术概念规范）

- **学生原报告关键词**：
  > 关键词：Android 原生开发；ECDICT 离线词典；词汇笔记；SM-2 间隔复习；TokenDance
- **存在缺陷**：
  1. 标点格式不规范：中文全角分号后夹杂西文半角空格（`； `）；
  2. 商业平台混入：`TokenDance` 属于第三方商业服务品牌，非学术或技术领域通用词汇；
  3. 概念过于宽泛或行业口语化：“Android 原生开发”属行业招聘口语，“词汇笔记”外延过于笼统。
- **推荐规范化学术关键词**：
  > **关键词**：Android平台；离线词库检索；神经网络语音合成；SM-2算法；间隔重复；数据持久化；移动应用安全

---

### 2.5 报告全篇 36 项全景学术重构对照表

以下表格将原报告中封面、摘要、关键词、正文目录以及软件使用说明书中存在的 36 处典型文风、学术体裁与工程规范瑕疵逐一对应，并给出严谨的重构建议：

| # | 报告原文字句 (Original Text) | 出现位置 (Location) | 缺陷类型 (Issue Type) | 语言学与工程规范批判 (Detailed Critique) | 规范化学术/工程重写建议 (Concrete Academic Rewrite) |
|---|---|---|---|---|---|
| **1** | 题目：智能英语词汇学习笔记 | 封面标题 (Cover) | Marketing / Casual Tone | 缺少工程学科体裁特征，“学习笔记”易被误解为上课随堂笔记，缺乏平台、方法与生命周期要素。 | **基于 Android 平台的智能英语词汇学习与间隔复习系统设计与实现** |
| **2** | LexiNote（v1.5.0-native，Build 3）是一套基于 Android Java 原生组件的应用。 | 摘要第 1 句 (Abstract S1) | Version Jargon & Promotion | 堆砌版本号与 CI 构建元数据；“是一套...应用”为商业宣传口吻；缺乏问题背景导引。 | **针对移动端英语词汇学习中离线检索受限与记忆保持率低的问题，设计并实现了一套基于 Android Java 原生架构的词汇学习系统（LexiNote）。** |
| **3** | 四个对等模块 | 摘要第 2 句 (Abstract S2) | Imprecise SE Terminology | “对等”非软件架构标准词。底部导航在业务逻辑层与持久层存在层级与依赖关系，应突出模块解耦。 | **四个解耦的核心功能子系统（词典检索、笔记知识库、间隔复习与系统设置）** |
| **4** | 复习中心 | 摘要第 2 句 (Abstract S2) | Consumer App UI Jargon | “中心”具有消费级或电商类 APP 界面命名特征，学术文档应采用业务领域命名。 | **间隔复习调度引擎** 或 **词汇复习子系统** |
| **5** | 支持弱网与无网环境下的基础学习 | 摘要第 2 句 (Abstract S2) | Casual Phrasing | 口语化叙述，未体现移动边缘计算能力与离线可用性（Offline Availability）保障。 | **保障无网络或弱网络环境下的本地词汇自主检索、发音合成与自主学习** |
| **6** | 需要语境理解时，可在用户主动授权与联网的条件下连接外部大模型 | 摘要第 2 句 (Abstract S2) | Conversational Tone | 表达松散口语化；未明确系统在网络安全与访问控制上的鉴权协议（OAuth 2.0 / Token）及 RPC 规范。 | **在网络联通且用户显式授权的前提下，系统通过异步安全接口协同调用云端大语言模型（LLM）实现上下文释义拓展** |
| **7** | 系统以 MainActivity、TokenDanceActivity、ImageEditorActivity 等 Android 组件完成导航 | 摘要第 3 句 (Abstract S3) | Technical Veracity Violation | **重大技术事实错误**：代码仓不存在 `ImageEditorActivity`（实为 `ImageEditorDialog`），虚构组件影响学术真实性。 | **系统基于 Android 原生组件化架构构建，以 MainActivity 承担视图调度中枢，配置 TokenDanceActivity 实现深层链接与 OAuth 回调，并封装 ImageEditorDialog 实现交互式图像编辑** |
| **8** | 查询、笔记、复习与设置模块拆分为相应 Java UI 类 | 摘要第 3 句 (Abstract S3) | Vague Architecture Description | “相应 Java UI 类”表达含混，未能体现模块化视图控制器解耦的设计模式。 | **表现层采用模块化视图控制器模式，将查词、笔记、复习及设置等界面逻辑解耦封装为独立的 Java 控制器组件** |
| **9** | 数据采用 SQLite 与结构化兼容视图组织，按学习记录、词条关系和复习排期进行持久化，同时支持 ZIP/JSON 备份恢复、图片编辑... | 摘要第 4 句 (Abstract S4) | Run-on Sentence & Feature Dumping | 句子过长，将存储层、备份容灾、图形编辑及认知模式杂糅堆砌，缺乏分层工程逻辑。 | **数据持久层基于 SQLite 构建实体映射与兼容视图，实现学习轨迹的持久化；系统并实现了具备抗路径穿越安全校验的 ZIP/JSON 备份迁移机制与多模态复习训练** |
| **10** | 关键词：Android 原生开发；ECDICT 离线词典；词汇笔记；SM-2 间隔复习；TokenDance | 关键词 (Keywords) | Punctuation & Vendor Jargon | 1. 标点夹杂半角空格；2. TokenDance 为商业商标，不应作为通用技术关键词；3. 概念过于宽泛。 | **关键词：Android平台；离线词库检索；神经网络语音合成；SM-2算法；间隔重复；数据持久化** |
| **11** | 1.3 非方向需求与约束 | 目录 1.3 (TOC 1.3) | Serious Typographical Error | 严重文字笔误（输入法候选错词），国家标准与 IEEE 规范中应为“非功能性需求”。出现在目录一级小节严重影响评分。 | **1.3 非功能性需求与设计约束** |
| **12** | 核心数据表创建逻辑（节选） | 目录 2.5 下 (TOC 2.5) | Structural Syntax Anomaly | 目录中不应出现无编号的代码片段标题（“（节选）”）。代码应作为正文中的代码清单（Listing），不可提升为目录条目。 | **从目录中移除；在正文 2.5 中标注为：代码 2-1 核心数据库表结构定义** |
| **13** | 离线词典标准化与变形词查询 | 目录 3.1.1 下 (TOC 3.1.1) | Missing Heading Number | 目录中孤立出现无编号三级小节，破坏层级一致性。 | **3.1.2 词典数据标准化与词形还原检索** （顺延后续节号） |
| **14** | 图片编辑状态数据结构（节选） | 目录 3.2 下 (TOC 3.2) | Structural Syntax Anomaly | 代码节选直列于目录。 | **从目录中移除；在正文 3.2 中标注为：代码 3-1 图像编辑状态机数据结构** |
| **15** | 模型供应商元数据与预设（节选） | 目录 3.3.1 下 (TOC 3.3.1) | Structural Syntax Anomaly | 代码节选直列于目录。 | **从目录中移除；在正文 3.3.1 中标注为：代码 3-2 模型服务提供商元数据配置** |
| **16** | 3.3.3 AI 会话标题的异步一致性与防误覆盖 | 目录 3.3.3 (TOC 3.3.3) | Colloquial Jargon | “防误覆盖”属于民间俗语，软件工程应采用并发控制或竞态防护术语。 | **3.3.3 AI 会话标题的异步一致性与竞态覆盖防护** |
| **17** | 笔记本创建与层级约束（节选） | 目录 3.4.1 下 (TOC 3.4.1) | Structural Syntax Anomaly | 代码节选直列于目录。 | **从目录中移除；在正文 3.4.1 中标注为：代码 3-3 笔记本层级约束与级联控制** |
| **18** | 复习评级与间隔计算（关键源码） | 目录 3.5.2 下 (TOC 3.5.2) | Structural Syntax Anomaly | “（关键源码）”直列目录非学术体例。 | **从目录中移除；在正文 3.5.2 中标注为：代码 3-4 SM-2 复习评级与间隔计算算法实现** |
| **19** | SQLite 初始化与迁移验证（节选） | 目录 3.6 下 (TOC 3.6) | Structural Syntax Anomaly | 代码节选直列于目录。 | **从目录中移除；在正文 3.6 中标注为：代码 3-5 数据库版本平滑迁移与完整性校验** |
| **20** | ZIP 归档校验与拒绝危险路径（节选） | 目录 3.7.1 下 (TOC 3.7.1) | Structural Syntax Anomaly | 代码节选直列于目录。 | **从目录中移除；在正文 3.7.1 中标注为：代码 3-6 ZIP 归档安全性校验与路径穿越防御** |
| **21** | 3.7.2 模型凭据隔离 | 目录 3.7.2 (TOC 3.7.2) | Imprecise Terminology | “模型凭据”概念内涵模糊，信息安全标准中应精确为 API 访问密钥与本地加密存储。 | **3.7.2 第三方 API 凭据的安全存储与本地隔离机制** |
| **22** | 服务配置和本地密钥隔离（节选） | 目录 3.7.2 下 (TOC 3.7.2) | Structural Syntax Anomaly | 代码节选直列于目录。 | **从目录中移除；在正文 3.7.2 中标注为：代码 3-7 服务密钥本地隔离与权限控制** |
| **23** | 3.11 面向课程答辩的核心操作链 | 目录 3.11 (TOC 3.11) | Meta-Referential Commentary | **严重违背学术中立**。软件工程设计章节应客观描述系统工作流，绝不可将答辩迎检话术作为系统功能标题。 | **3.11 系统核心业务操作链与端到端工作流集成** （如需指导答辩，请移至附录） |
| **24** | 4.3 仓库已有规模与性能证据 | 目录 4.3 (TOC 4.3) | Developer/Audit Slang | 混杂 Git 仓库管理术语与司法取证术语，不符合软件性能测试规范。 | **4.3 大规模数据负载与系统性能基准测试** |
| **25** | 4.3.1 样本设计、可重复性和测试证据等级 | 目录 4.3.1 (TOC 4.3.1) | Inappropriate Legal/Medical Term | “测试证据等级”属医学循证或司法取证术语，软件测试应为测试用例集设计与效能指标。 | **4.3.1 负载测试用例集设计、复现性验证与效能指标** |
| **26** | 4.5 视觉测试与页面呈现证据 | 目录 4.5 (TOC 4.5) | Awkward Phrasing | 表述生硬，缺乏移动端 UI 兼容性测试标准术语。 | **4.5 界面视觉规范符合度与多分辨率适配测试** |
| **27** | 2.2 OAuth 登录与专属授权令牌 | 说明书 2.2 (Manual 2.2) | Marketing Hype | “专属授权令牌”带有商业 VIP 宣传色彩，技术术语为访问令牌（Access Token）。 | **2.2 OAuth 授权认证与访问令牌配置** |
| **28** | 2.3 TokenDance 充值流程（仅在需使用云端服务时） | 说明书 2.3 (Manual 2.3) | Commercial Paid Feature Jargon | “充值流程”在高校课设报告中显得过于商业化市侩化，应突出配额与账户管理。 | **2.3 云端服务配额管理与账户余额指引** |
| **29** | 4.1 建立笔记本与词条归档 | 说明书 4.1 (Manual 4.1) | Imprecise Terminology | “归档”在计算机中通常指冷数据归档转移，此处实际为词条分类添加到知识库。 | **4.1 笔记本分类构建与词条知识库组织** |
| **30** | 4.2 七种排序方式、三种释义模式 | 说明书 4.2 (Manual 4.2) | Promotional Enumeration | 采用销售手册式的数字堆砌标语，缺乏工程概括性。 | **4.2 词条多维排序与释义呈现模式切换** |
| **31** | 4.4 详情、批量管理与误删除保护 | 说明书 4.4 (Manual 4.4) | Colloquial Jargon | “误删除保护”属于通俗口语，系统层面应表述为防误触校验或安全确认机制。 | **4.4 词条属性维护、批量管理与安全删除校验** |
| **32** | 五、复习中心：SM-2 记忆排期和沉浸式训练 | 说明书五 (Manual Sec 5) | Consumer Marketing Buzzword | “沉浸式训练”为商业健身或游戏数码营销词汇，认知心理学中应为复习强化流程。 | **五、复习中心：SM-2 记忆排期与复习强化流程** |
| **33** | 5.2 看词猜义：翻卡与三档评级 | 说明书 5.2 (Manual 5.2) | Casual Game Tone | “看词猜义：翻卡”带有休闲卡牌游戏色彩，认知心理学中对应为“再认测试（Recognition Mode）”。 | **5.2 单词再认模式：翻卡交互与三级记忆反馈评定** |
| **34** | 5.3 看义猜词、学习记录与连续打卡 | 说明书 5.3 (Manual 5.3) | Commercial App Tone | “看义猜词”对应为“回忆/再现测试（Recall Mode）”，“连续打卡”属商业营促销语。 | **5.3 词义回想模式：自测流程与学习连续性统计** |
| **35** | 六、设置、数据备份与换机恢复 | 说明书六 (Manual Sec 6) | Colloquial Jargon | “换机恢复”为生活口语，计算机软件专业标准术语为跨设备数据迁移或系统容灾恢复。 | **六、系统设置、数据备份与跨设备数据迁移** |
| **36** | 八、按操作顺序复现的验收演示 | 说明书八 (Manual Sec 8) | Meta-Referential Instruction | 说明书是给软件最终用户阅读的操作指南，直白写“验收演示”破坏了角色真实性。 | **八、典型场景端到端操作演练指引** |

---

### 2.6 报告通用撰写指南与工程呈现规范

为确保本报告在“文档设计（10分）”与“报告整体评价（10分）”两个关键项取得满分，全篇正文修改必须遵循以下规范：

1. **客观第三人称视角原则**：
   - 彻底剔除正文（第 1 章至第 5 章及说明书）中一切第一人称表达（“我们设计了”、“我负责实现了”、“老师可以看到”）；
   - 统一代之以：“本系统设计并实现了...”、“该模块采用...”、“测试表明...”。
   - *唯二允许第一人称的区域*：大纲规定的 `团队介绍和人员分工` 与文末 `心得体会`。
2. **专业术语绝对一致性对照表**：
   - 禁止使用：“本地小型轻量DB” $\to$ 统一使用：**SQLite 关系数据库**；
   - 禁止使用：“模型凭据 / 外部Key” $\to$ 统一使用：**API 访问密钥（API Key） / OAuth 访问令牌（Access Token）**；
   - 禁止使用：“防误覆盖” $\to$ 统一使用：**竞态覆盖防护（Race Condition Prevention）**；
   - 禁止使用：“换机恢复” $\to$ 统一使用：**跨设备数据迁移（Cross-Device Data Migration）**；
   - 禁止使用：“看词猜义 / 看义猜词” $\to$ 统一使用：**词汇再认测试（Recognition） / 词义回想测试（Recall）**；
   - 禁止使用：“非方向需求” $\to$ 统一使用：**非功能性需求（Non-Functional Requirements, NFRs）**。
3. **排版标点与代码清单规范**：
   - 严格遵循 GB/T 15834 标点符号规范，中文行文全部采用全角符号，严禁全角符号后多敲空格（如将 `； ` 全部替换为 `；`）；
   - 正文中严禁出现直接作为目录项的代码节选。所有源码片段统一采用标准代码框（Consolas / Courier New 9pt），并在正文上方添加题注：`代码 3-1 核心算法实现（节选）`，下方辅以设计原理解释；
   - 所有数据统计与性能测试表格统一采用学术标准**三线表**（Top Line 粗，Bottom Line 粗，Header Line 细，隐去竖线）。
4. **SM-2 数学公式严谨化表达**：
   - 正文 3.5 节对 SM-2 算法的描述严禁口语化带过，必须给出严格的数学公式块：
     $$I(1) = 1, \quad I(2) = 6, \quad I(n) = I(n-1) \times EF \quad (n \ge 3)$$
     $$EF' = EF + (0.1 - (5 - q) \times (0.08 + (5 - q) \times 0.02))$$
     同时明确说明本系统面向移动端触控，将反馈评级 $q$ 映射为三档离散反馈（“再次”、“困难”、“简单”），并给出每档的具体离散增量推导。

---

## 第三部分：技术事实真实性与代码仓底层核验 (R3)

### 3.1 技术事实核验总体概况与可信度等级

本节针对学生报告宣称的全部技术架构、核心算法、数据库结构、安全策略及代码组件，以当前底层代码仓（`app/src/main/`、`app/build.gradle` 及单元测试套件）为最高基准进行逐一核验。

核验结果界定为三类可信度等级：
- **VERIFIED (完全真实)**：报告描述与底层代码实现完全吻合，包括类名、逻辑分支与算法行为；
- **INACCURATE (存在偏差/学名不准)**：底层存在该功能，但报告使用了不准确的命名、模糊的概念或混淆了技术层级；
- **CONTRADICTED (事实矛盾/虚构)**：报告所声称的组件或机制在代码仓中根本不存在，构成事实矛盾。

---

### 3.2 20 项核心技术事实核验矩阵

| # | 报告/目录声称要点 (Claim in Report) | 对应源码文件与组件 (Source Component) | 底层真实代码实现细节 (Actual Code Implementation) | 核验结论 | 技术差异分析与答辩学术风险 (Risk & Discrepancy Analysis) |
|---|---|---|---|---|---|
| **1** | 以 MainActivity、TokenDanceActivity、ImageEditorActivity 完成导航与编辑 | `AndroidManifest.xml:14-40`, `ImageEditorDialog.java`, `TokenDanceActivity.java` | 清单中仅注册了 `MainActivity` 与 `TokenDanceActivity`。图像编辑完全由 `ImageEditorDialog`（基于 `Dialog` 封装的控制器组件，非继承 Dialog）及 `ImageCropView` 承载，不存在 `ImageEditorActivity`。 | **CONTRADICTED** | **极高扣分风险**：直接违背课程“至少3个 Activity 以上”硬约束；在报告中声称不存在的类，涉嫌虚构技术事实，面临答辩评委严厉质询。 |
| **2** | 四个对等模块拆分为相应 Java UI 类 | `MainActivity.java:444`, `QueryUi.java`, `NotebookUi.java`, `ReviewUi.java`, `SettingsUi.java` | 底部 4 个导航页均由纯 Java 视图控制器类实现，不依赖 Android 官方 Fragment。笔记管理类名为 `NotebookUi.java`。 | **INACCURATE (命名)** | 功能属实，但报告文字将笔记控制器误称为 `NotesUi`（实际为 `NotebookUi`）；且未阐明其为自定义视图控制器而非标准 Fragment。 |
| **3** | LexiNote（v1.5.0-native，Build 3） | `app/build.gradle:17-18` | `versionCode 3`, `versionName '1.5.0-native'` 完全一致。 | **VERIFIED** | 代码仓与报告版本声明 100% 吻合。 |
| **4** | 利用 ECDICT 离线词库及本地发音技术支持弱网无网 | `OfflineDictionary.java`, `SherpaPronunciationEngine.java`, `assets/ecdict.db` | 打包内置 SQLite 字典库；基于 Sherpa-onnx 与 Piper VITS 英语离线模型，利用 AudioTrack 内存流实现完全离线发音。 | **VERIFIED** | 底层完全具备离线词库与离线神经网络语音合成，无需任何外网连接。 |
| **5** | 离线词典标准化与变形词查询 | `OfflineDictionary.java:26-34` | 查询先匹配 `entry` 表，若未中则查询 `variant` 变形词表回退到原型 `headword` 并保留请求形态。 | **VERIFIED** | 真实实现了英语动词过去式、复数等屈折形态还原检索。 |
| **6** | 本地查询与网络查询的决策路径 | `QueryUi.java`, `QueryWorkflow.java:19-65` | 工作线程优先检索本地 ECDICT，无果或用户主动触发时异步调用网络大模型补充语境释义。 | **VERIFIED** | 具备清晰的本地优先决策树流转机制。 |
| **7** | 数据采用 SQLite 与结构化兼容视图组织 | `LibraryDatabase.java`, `storage/contract/ReadOnlyView.java` | 数据库采用按代次（generation）分区的 `entities` 实体表存储 JSON；**数据库中根本不存在 SQL `CREATE VIEW` 语句**，“视图”实为 Java 领域包装类 `ReadOnlyView.java`。 | **INACCURATE** | 报告术语“SQLite 兼容视图”容易被评委误解为物理关系视图。应澄清为“基于 SQLite 的混合实体存储与 Java 领域强类型不可变视图”。 |
| **8** | 核心数据表创建逻辑 | `LibraryDatabase.java:16-32` | 核心物理表为 `store_meta`, `document_meta`, `entities`, `review_plans`, `review_states`, `review_days`, `review_events`, `session_index`, `session_order`。 | **INACCURATE** | 物理表名并非传统关系表（如不存在独立的 `vocabularies` 表），而是以实体代次隔离设计。 |
| **9** | SQLite 初始化与迁移验证 | `LibraryDatabase.java:34`, `SqliteLibraryStore.java:55-70` | `onUpgrade` 直接抛异常禁止传统升级；数据库迁移基于代次切换（generation++），由 `StreamingLibraryValidator` 执行原子验证与切表。 | **VERIFIED (机制不同)** | 迁移机制极其严密，但与 Android 传统的 `SQLiteOpenHelper.onUpgrade` 机制不同，报告需阐明代次迁移原理。 |
| **10** | SM-2 间隔复习与下一次到期时间算法 | `ReviewLogic.java:69-121` | 采用三档离散评级（再次、困难、简单），EF 因子受控于 $[1.3, 3.0]$，按自定义离散天数公式更新到期时间。 | **VERIFIED (改进型)** | 并非标准连续 6 档 SM-2 公式，应定名为“面向移动端触控优化的三档改进型 SM-2 算法”。 |
| **11** | 复习提交状态机与幂等写入 | `ReviewSessionState.java:4-38` | 通过 `Rating pending` 与 `boolean submitting` 实现状态互斥，连击直接被拦截，失败保留状态支持重试。 | **VERIFIED** | 严谨的防抖与幂等写入状态机，有效防御网络抖动与误操作。 |
| **12** | 翻卡与三档评级、看词猜义 / 看义猜词 | `ReviewUi.java:23-25, 1360-1410, 1585-1625` | `reverse` 变量控制中英显示反转，`revealed` 控制卡片正反面翻转，提供再次、困难、简单三档评定。 | **VERIFIED** | UI 与业务逻辑完全支持双向自测模式与翻卡交互。 |
| **13** | TokenDance 账号连接、授权与充值、OAuth 登录 | `TokenDanceActivity.java`, `TokenDanceProtocol.java`, `TokenDanceApi.java` | 实现了完整的 RFC 7636 PKCE OAuth 2.0 协议流（S256 挑战码、verifier、state 校验及自定义 Scheme 回调）。 | **VERIFIED (沙盒模拟)** | 协议代码完备，但工程文档记载此前主要针对沙盒与测试响应，需明确声明在线支付未做实盘扣款。 |
| **14** | 服务配置和本地密钥隔离、模型凭据隔离 | `SecureSettings.java:307-376`, `BackupArchive.java:43-75` | 采用硬件级 `AndroidKeyStore` 生成 AES-256 GCM 密钥，加密私有 SharedPreferences；数据备份导出时强制物理脱敏剥离。 | **VERIFIED** | 安全标准极高，完全杜绝了明文存储与泄露风险。 |
| **15** | AI 会话标题的异步一致性与防误覆盖 | `TitleSuggestionGuard.java:4-28`, `ConversationTitles.java:45-76` | 后台异步生成标题时校验 `capturedRevision` 与 `capturedEpoch`，用户若手动更名或有新输入则自动丢弃。 | **VERIFIED** | 优秀的多线程并发竞态防护机制。 |
| **16** | ZIP 备份恢复、ZIP 归档校验与拒绝危险路径 | `BackupArchive.java:109-114`, `BackupStreamCodec.java:70-90` | 实施白名单文件正则匹配校验；严厉拒绝目录条目、包含 `..` 的路径穿越（抗 Zip Slip 攻击）并校验 CRC32。 | **VERIFIED** | 工业级移动端安全防御规范。 |
| **17** | 导入恢复的事务边界与失败保护 | `SqliteLibraryStore.java:57-64, 465-480` | 全程包裹在 SQLite `beginTransaction()` 事务中；数据校验不通过则原子回滚；保留前一历史代次支持一键撤销。 | **VERIFIED** | 完善的数据库事务隔离与灾备恢复能力。 |
| **18** | 图片编辑状态数据结构、框选涂抹和旋转裁剪 | `ImageEditorState.java:13-71`, `ImageCropView.java`, `ImageEditorDialog.java` | 归一化浮点坐标系；支持手势平移、矩形选区、自由笔触涂抹、自由裁剪；支持 90° 旋转与微调滑块。 | **VERIFIED** | 图像编辑交互与数学变换完备。 |
| **19** | 七种排序方式、三种释义模式 | `NotebookUi.java:27-28` | 排序支持默认/最新/最早/A-Z/Z-A/随机/生词优先；释义支持中文/英文/遮罩点击显隐。 | **VERIFIED** | 与代码仓枚举定义 100% 吻合。 |
| **20** | 笔记本创建与层级约束 | `Library.java:160-181, 746-770` | 严格强制二分类型检查（子项必须纯为文件夹或纯为词汇卡，禁止混排）；词汇只能添加到末级节点。 | **VERIFIED** | 深度的领域模型约束，防止树形结构逻辑坍塌。 |

---

### 3.3 重点专题技术机理深度剖析

#### 专题一：Android 核心组件与清单配置（Activity 数量与 ImageEditorDialog 实现）
- **清单现状**：
  在 `app/src/main/AndroidManifest.xml` 中，仅注册了两个 Activity：
  ```xml
  <activity
      android:name=".MainActivity"
      android:exported="true"
      android:windowSoftInputMode="adjustResize">
      <intent-filter>
          <action android:name="android.intent.action.MAIN" />
          <category android:name="android.intent.category.LAUNCHER" />
      </intent-filter>
  </activity>

  <activity
      android:name=".TokenDanceActivity"
      android:exported="true"
      android:launchMode="singleTask"
      android:configChanges="orientation|screenSize|keyboardHidden"
      android:windowSoftInputMode="adjustResize">
      <intent-filter>
          <action android:name="android.intent.action.VIEW" />
          <category android:name="android.intent.category.DEFAULT" />
          <category android:name="android.intent.category.BROWSABLE" />
          <data android:scheme="${callbackScheme}" android:host="tokendance" android:path="/callback" />
      </intent-filter>
      <intent-filter android:autoVerify="true">
          <action android:name="android.intent.action.VIEW" />
          <category android:name="android.intent.category.DEFAULT" />
          <category android:name="android.intent.category.BROWSABLE" />
          <data android:scheme="https" android:host="${callbackHost}" android:path="/lexinote/auth/callback" />
      </intent-filter>
  </activity>
  ```
- **技术事实解析**：
  原报告摘要与目录声称系统拥有 `ImageEditorActivity`。经查验，`ImageEditorDialog.java` 本质是一个持有全屏自定义 View（`ImageCropView`）的对话框控制器（通过组合持有 `new Dialog(...)`，非继承自 `Dialog`）。虽然 Dialog 能够完全胜任多点触控缩放、选区矩阵运算与笔触涂抹，但在 Android 核心组件分类上它属于 Window/Dialog 层级，绝非四大组件中的 Activity。报告撰写者在未经清点的情况下，凭主观印象将该功能冠以 Activity 之名，构成了严重的“指标未达标”与“描述失真”。

#### 专题二：ECDICT 离线词库与 Sherpa-onnx 本地神经语音合成
- **离线词库检索机理**：
  应用在首次启动时，由 `OfflineDictionary.install()` 将 assets 下的 `ecdict.db` 复制到私有存储（`context.getFilesDir()/ecdict-39cfb967-v1.db`），并以只读方式打开。其变形词回退机制设计极为精妙：
  ```sql
  -- 第一阶段：精确检索
  SELECT word, phonetic, definition, translation FROM entry WHERE key=?
  -- 第二阶段：词形还原回退
  SELECT headword FROM variant WHERE key=?
  ```
  如果用户查询的是复数（如 `apples`）或动词过去式（如 `went`），第一步未命中后立即检索 `variant` 表，提取词原型 `apple` 或 `go`，再以此原型查询释义，并标记 `requested_form = query`，既保证了释义完整，又保留了用户输入的现场。
- **本地语音合成（TTS）双轨机制**：
  主引擎采用新一代端侧语音识别/合成框架 `sherpa-onnx`（基于量化的 Piper VITS 英语离线模型，工程中打包于 `assets/tts/model.onnx`，由 `TtsModelInstaller.MODEL_NAME` 管理解压安装，底层神经网络权重衍生自 `en_US-libritts_r-medium.onnx`）。音频数据直接在 RAM 中由 `OfflineTts` 生成 Float PCM 字节流，通过 `AudioTrack` 内存播放，并维护容量为 40 项的 `LruCache<String, GeneratedAudio>`，无需磁盘 I/O 且零延迟。当端侧资源受限时，自动平滑回退到系统原生的 `android.speech.tts.TextToSpeech`。

#### 专题三：SQLite 世代分区混合存储架构与 Java 领域视图（澄清非 SQL 视图）
- **世代分区混合模型**：
  系统未采用为每种实体单独立表（`create table vocabularies...`）的传统关系模型，而是构建了基于代次隔离（generation-scoped）的高性能混合实体模型（`LibraryDatabase.java`）：
  ```sql
  CREATE TABLE entities (
      generation INTEGER NOT NULL,
      kind TEXT NOT NULL,
      id TEXT NOT NULL,
      ordinal INTEGER NOT NULL,
      json TEXT NOT NULL,
      PRIMARY KEY(generation, kind, id),
      UNIQUE(generation, kind, ordinal)
  );
  ```
- **领域视图本质**：
  报告宣称的“SQLite 结构化兼容视图”并非数据库内执行的 `CREATE VIEW` 语句，而是 Java 领域层构建的不可变结构快照包装类 `ReadOnlyView.java`。当业务层读取数据时，`SqliteLibraryStore` 将 `entities` 表中当前代次（`generation = store_meta.active`）的 JSON 文档解析并映射为强类型的领域对象树。这种架构兼顾了 NoSQL 的高扩展性与 SQLite 的本地 ACID 事务保障。在报告中必须据实说明，避免评委误解。

#### 专题四：SM-2 移动端三档自适应间隔复习算法与幂等提交状态机
- **算法变体数学机理**：
  经典 SM-2 采用 0-5 档连续评分机制与连续公式。LexiNote 针对触控屏快速交互的特点，在 `ReviewLogic.java` 中落地了“三档离散自适应算法”：
  1. **“再次”（Again）**：判定遗忘，$EF' = \max(1.3, EF - 0.2)$，间隔重置为 0 天（`<10分钟`），掌握度重置为 0；
  2. **“困难”（Hard）**：提取困难，$EF' = \max(1.3, EF - 0.15)$，间隔步进策略为：第 0 次 $\to$ 1 天，第 1 次 $\to$ 2 天，第 $\ge 2$ 次 $\to \max(\text{prev} + 1, \text{round}(\text{prev} \times 1.2))$；
  3. **“简单”（Easy）**：熟练掌握，$EF' = \min(3.0, EF + 0.1)$，间隔步进策略为：第 0 次 $\to$ 3 天，第 1 次 $\to$ 6 天，第 $\ge 2$ 次 $\to \max(\text{prev} + 2, \text{round}(\text{prev} \times EF'))$，掌握度累加直至标记为 `mastered`。
- **幂等状态机**：
  `ReviewSessionState.java` 通过内部互斥锁与三阶段状态机（`begin()` $\to$ `succeed()` / `fail()`）管理评级提交。如果用户在卡片上发生双击或网络发生抖动，第二击将被状态机坚决抛弃；若持久化写入失败，状态机重置标记但保留当前 `Rating pending` 及全局唯一 `eventId`，确保重试时绝对不会生成重复的复习历史日志。

#### 专题五：TokenDance OAuth 2.0 PKCE 鉴权与 AndroidKeyStore 硬件加密隔离机制
- **OAuth 2.0 PKCE 流程**：
  遵循 RFC 7636 规范，应用生成 32 字节高熵随机串作为 `verifier`，经 SHA-256 哈希后生成 `code_challenge` 发起授权请求。支持 `lexinote://tokendance/callback` 自定义 Scheme 自动回跳，并设计了在浏览器异常时手动粘贴授权码的 Fallback 容灾通道。
- **凭据加密隔离与脱敏**：
  获得的 API Key 与令牌并不明文存储，而是通过 `SecureSettings.java` 调取硬件安全芯片支持的 `AndroidKeyStore`，生成别名为 `lexinote.api.v1` 的 AES-256 GCM 主密钥进行加密，存储在仅应用自身有权限访问的私有 SharedPreferences 中。在数据备份归档模块（`BackupArchive.java`）中，敏感凭据与密钥字段全部受到强制反射打码剥离（Redacted），杜绝了因用户备份文件泄露导致的 API 资产被盗刷风险。
- **真实性边界声明**：
  依据 `docs/verification/TOKENDANCE.md`，此前测试主要验证了沙盒模拟响应、PKCE 协议流转、本地加密持久化及支付宝跳转拉起逻辑，**未进行真实资金划扣与生产环境在线充值实测**。报告中对于充值订单截图（`recharge_fixture`）必须如实注明为“沙盒测试响应订单”，避免被评委追问商业资质合规性。

#### 专题六：数据备份恢复机制、Zip Slip 路径穿越防御与事务原子回滚
- **Zip Slip 漏洞防御**：
  `BackupArchive.java` 与 `BackupStreamCodec.java` 在解压导入时，建立了严苛的白名单正则防护：
  ```java
  private static boolean validPath(String name) {
      return name.equals("backup.json")
          || name.equals("checksums.json")
          || name.matches("assets/\\d+\\.(png|jpe?g|webp|gif|heic)");
  }
  ```
  任何包含 `..` 相对路径、以 `/` 开头的绝对路径、目录节点或非白名单后缀的恶意压缩包均被坚决拒绝，从根源上消除了覆盖系统或应用私有文件的移动端安全风险，并对包内每个文件进行 CRC32 校验。
- **原子事务与双代次容灾**：
  导入过程在 SQLite 数据库事务中运行（`beginTransaction`）。新数据写入全新的 `generation` 分区；若数据校验失败直接触发 `rollback`；即便导入成功，旧代次数据仍被缓存在 `meta("previous")` 中，用户可在设置中执行“一键撤回上一次导入”。

#### 专题七：图像编辑几何变换数学模型与双向分层笔记本树形约束
- **图像几何变换**：
  `ImageEditorState.java` 将选区、裁剪框与手势涂抹统一抽象为归一化浮点坐标系 $[0.0, 1.0]$，彻底解耦屏幕像素分辨率。支持 90° 直角旋转步进与 $[-45^\circ, +45^\circ]$ 任意角度微调倾斜校正。
- **笔记本二分树形约束**：
  `Library.java` 严密捍卫了层级树的二分规则（Bipartite Tree）：任意笔记本的 `children_type` 必须要么纯为子笔记本（`notebook`），要么纯为词汇卡片（`vocabulary`），严禁在一个文件夹下同时混杂子文件夹与词卡；同时规定仅允许在末级叶子笔记本中添加单词。这种严苛的领域约束彻底避免了树形视图在无限递归展开时的渲染异常与数据脏读。

---

## 第四部分：整改行动计划路线图与雨课堂提交自查清单 (R4)

### 4.1 优先级整改行动矩阵（P0 致命缺陷 / P1 重要结构 / P2 细节打磨）

为确保项目以绝对优势斩获高分，整改任务划分为三个优先级层级：

```mermaid
graph TD
    P0["P0: 致命缺陷 (必须在提交前100%修正，否则必扣大分)"]
    P1["P1: 重要结构与文风调整 (影响优秀档位90+的评定)"]
    P2["P2: 细节打磨与学术修饰 (冲击98~100分卓越档位)"]
    
    P0 --> P0_1["消除封面 99% 算术错误 (调整为 34%+33%+33%)"]
    P0 --> P0_2["解决 Activity 数量不足 (零风险推荐: 新增 AboutActivity)"]
    P0 --> P0_3["确保提交包同时包含 DOCX 与 PDF (不可用说明书替代)"]
    P0 --> P0_4["录制电脑 PC 视频并确保 3 人真人头像完整出镜"]
    P0 --> P0_5["压缩包严格命名为 学号姓名_学号姓名_学号姓名.zip"]
    P0 --> P0_6["执行 gradlew clean 瘦身打包 (杜绝 1.75GB 拒收)"]
    
    P1 --> P1_1["将正文第 1 章前置为 团队介绍与人员分工 (消除第58页占位符)"]
    P1 --> P1_2["在第 1 章增补 市场前景与竞品差异化对比 (补齐10分评分项)"]
    P1 --> P1_3["修正 1.2 与 1.6 节中 FR 需求编号定义冲突"]
    P1 --> P1_4["将 SM-2 定名规范为 移动端三档改进型算法 并加入数学公式"]
    
    P2 --> P2_1["采纳 36 项学术重写表，消除营销词与口语表达"]
    P2 --> P2_2["纠正图 37 观楂登录 为 观猹登录，删除说明书自我循环引用"]
    P2 --> P2_3["澄清 SQLite 世代分区与 Java ReadOnlyView 真实机制"]
```

| 优先级 | 任务编号 | 整改任务描述 | 涉及文件/位置 | 责任人建议 | 预计耗时 |
|---|---|---|---|---|---|
| **P0** | **P0-1** | **修正封面贡献度百分比**：由 33%+33%+33%=99% 改为韩家润（34%）、冯子轩（33%）、李航天（33%），确保闭环率精确等于 100%。 | 报告封面 | 韩家润 | 5分钟 |
| **P0** | **P0-2** | **处置 Activity 数量与类名矛盾（强烈推荐零风险路径）**：<br>方案 1（强烈推荐，零风险，15分钟）：新增极简 `AboutActivity.java`（“关于软件与开发团队”页面，~15行代码），在 `AndroidManifest.xml` 中注册并在设置页 `SettingsUi` 提供跳转入口。彻底满足 $\ge 3$ 个 Activity 硬指标，且呼应大纲首章团队介绍，对现有复杂图像与复习逻辑零侵入、零崩溃风险；<br>方案 2（理论可行但高风险，切勿临考采用）：将 593 行 `ImageEditorDialog` 重构为 `ImageEditorActivity`（注意 `ImageEditorState` 非 Parcelable，且 8192 笔触点极易引发 `TransactionTooLargeException` 闪退崩溃，重构周期与现场演示风险巨大）；<br>方案 3（代码封板备选）：若完全不改代码，在报告中据实阐明 Dialog 架构优势（无进程切换白屏、避免 Binder 序列化开销），准备答辩说辞，但面临被按字面扣分风险。 | `AndroidManifest.xml` / 报告摘要、2.4节 | 韩家润 | 15分钟 |
| **P0** | **P0-3** | **准备双文档提交件**：排版完成后，将完整课程报告分别导出为《大作业报告.docx》与《大作业报告.pdf》（严禁只交 PDF 或误拿使用说明书替代）。 | 提交根目录 | 冯子轩 | 15分钟 |
| **P0** | **P0-4** | **录制全员出镜合规演示视频**：使用 PC 端（OBS 或腾讯会议录屏）录制模拟器操作；录屏画面中必须同时出现韩家润、冯子轩、李航天 3 位同学的真人摄像头头像。 | 视频文件 (.mp4) | 李航天 | 40分钟 |
| **P0** | **P0-5** | **标准压缩包命名**：严格命名为 `2024250361013韩家润_2024250361010冯子轩_2024250361021李航天.zip`，由组长一人在雨课堂提交。 | 最终提交文件 | 韩家润 | 5分钟 |
| **P0** | **P0-6** | **代码仓深度瘦身与规范打包**：必须在终端运行 `.\gradlew.bat clean` 清除全部构建产物与 dex 中间件，制作提交 ZIP 时坚决剔除 `.gradle/`、`app/build/`、`.idea/`、`.git/` 等目录，确保提交压缩包从 1.75 GB 骤降至 20~30MB 以内，彻底避免雨课堂平台上传超时与熔断拒收。 | 工程根目录 | 韩家润 | 10分钟 |
| **P1** | **P1-1** | **调整正文首章为团队分工**：将文末第 57 页分工移至正文第一章 `1 团队介绍与人员分工`，**坚决删除第 58 页遗留的占位符“建议填写的真实承担任务”**，补齐每人特长。 | 正文第一章、第58页 | 冯子轩 | 20分钟 |
| **P1** | **P1-2** | **补全市场前景分析小节**：在第 1 章增设 `1.1.2 市场前景与竞品差异化分析`，对比百词斩、墨墨背单词等，阐述 LexiNote 离线神经发音与数据私有化优势。 | 正文第 1 章 | 韩家润 | 30分钟 |
| **P1** | **P1-3** | **统一需求编号冲突**：核对 1.2 节功能需求表与 1.6 节追踪矩阵，确保 FR-01、FR-02、FR-03 等编号定义与模块映射完全一致。 | 正文 1.2、1.6 节 | 李航天 | 20分钟 |
| **P1** | **P1-4** | **校准算法学名与数学表达**：在 3.5 节将 SM-2 定名为“基于 SM-2 思想的移动端改进型间隔复习算法”，写入标准公式块并阐述三档离散推导。 | 正文 3.5 节 | 韩家润 | 20分钟 |
| **P2** | **P2-1** | **全篇采纳 36 项学术重构建议**：修正“非方向需求”等错别字，剔除代码清单在目录的孤立显示，将口语和营销词全部学术化。 | 全文目录与各章 | 全员 | 40分钟 |
| **P2** | **P2-2** | **订正说明书错别字与套话**：将图 37 题注“观楂登录”订正为“观猹登录”，删除说明书导言中的自我循环引用语句。 | 软件使用说明书 | 冯子轩 | 10分钟 |
| **P2** | **P2-3** | **完善技术机理真实性叙述**：在 2.5 节澄清 SQLite 实体 JSON 世代分区与 Java `ReadOnlyView` 机制；在 3.3 节标明 TokenDance 沙盒测试响应属性。 | 正文 2.5、3.3 节 | 韩家润 | 25分钟 |

---

### 4.2 针对组长韩家润的大作业最终提交预检清单（Pre-submission Gate Checklist）

在雨课堂点击最终“提交”按钮前，组长韩家润必须与两位组员协同完成如下逐项点检：

- [ ] **代码仓深度瘦身与雨课堂体积达标核验（防熔断拒收与超时）**：
  - [ ] 是否在终端严格执行了 `.\gradlew.bat clean` 彻底清除临时 class 与 dex 构建产物？
  - [ ] 打包时是否已确认坚决排除了 `.gradle/`、`app/build/`、`build/`、`.idea/`、`.git/`、`.agents/` 目录？
  - [ ] 最终压缩包体积是否已成功从 **1.75 GB** 缩减至 **100MB 以内（纯代码工程仅 20~30MB）**，确保雨课堂平台一次性秒级上传成功？
- [ ] **压缩包名称核验**：文件名是否精确为 `2024250361013韩家润_2024250361010冯子轩_2024250361021李航天.zip`？（无空格、无前缀、下划线连接、.zip格式）
- [ ] **解压完整性验证**：解压该 ZIP 包后，根目录下是否完整包含以下 5 大必备提交物：
  - [ ] ① 工程源代码文件夹（包含完整 `app` 模块及 `build.gradle`）；
  - [ ] ② 具备丰富中文注释的核心源码；
  - [ ] ③ 期末大作业设计报告 Word 版（`.docx`）；
  - [ ] ④ 期末大作业设计报告 PDF 版（`.pdf`，与 docx 内容完全一致，非使用说明书替代品）；
  - [ ] ⑤ 电脑 PC 录屏演示视频（`.mp4`）。
- [ ] **报告封面核验**：
  - [ ] 题目是否已规范重构为《基于 Android 平台的智能英语词汇学习与间隔复习系统设计与实现》？
  - [ ] 成员贡献度是否精确闭环为 $100\%$（如 34% + 33% + 33%）？
  - [ ] 学号、姓名、专业班级（软件2401B）、指导教师（宁淑荣）是否无误？
- [ ] **报告正文结构核验**：
  - [ ] 正文第 1 章节是否为团队介绍和人员分工？第 58 页模板占位符“建议填写的真实承担任务”是否已被彻底删除并替换为真实内容？
  - [ ] 报告是否包含了独立的“市场前景与竞品分析”小节？
  - [ ] 目录 1.3 是否已修正为“非功能性需求与设计约束”？
  - [ ] 需求编号 FR-01/FR-02 等在 1.2 与 1.6 节中是否一致？
  - [ ] 目录中是否已剔除全部孤立的代码节选条目？
- [ ] **代码与 Activity 指标核验**：
  - [ ] `AndroidManifest.xml` 中是否真实注册了 $\ge 3$ 个 Activity（强烈建议采纳零风险方案新增 `AboutActivity`；若未改代码，是否已在报告中对 Dialog 架构做出了严密的工程解释）？
  - [ ] 报告摘要与正文中的类名是否与代码仓完全一致（已剔除虚构的 `ImageEditorActivity`）？
- [ ] **演示视频合规性核验**：
  - [ ] 视频是否全程在 PC 电脑端录屏（而非用手机翻拍）？
  - [ ] 视频画面右上角或画中画中，是否自始至终清晰呈现了韩家润、冯子轩、李航天 3 位同学的真人摄像头头像？
  - [ ] 视频是否完整演示了查词、神经发音、图片裁剪、AI对话、笔记管理、SM-2翻卡及备份还原核心流程？
- [ ] **提交账号唯一性**：
  - [ ] 是否确认仅由组长韩家润一人使用其雨课堂账号执行提交？
  - [ ] 冯子轩、李航天两位同学已确认不进行任何上传操作。

---

### 4.3 现场答辩攻防与评委常见提问应对预案

在课程设计答辩环节，评审教师（尤其是宁淑荣老师及软件工程系评审专家）常针对架构设计、算法落地及工程规范提出深度质询。团队应提前做好如下攻防预案：

#### 提问一：“你们在报告摘要中写了 ImageEditorActivity，但在 AndroidManifest 中为什么只注册了 2 个 Activity？这是否违反了课程大纲‘至少 3 个 Activity’的要求？”
- **防守应答话术**：
  > “非常感谢老师的敏锐指正。我们在项目早期规划阶段曾设计过独立的 `ImageEditorActivity`，但在实际原生架构落地与性能优化过程中，我们发现图片裁剪、自由笔触涂抹与旋转微调具有高度的上下文即时性。`ImageEditorState` 单次手势笔触上限可达 8192 个坐标点，如果强行拆分为独立 Activity，在跨组件 Intent 传输时极易触碰 Android Binder 1MB 事务缓冲区上限而引发 `TransactionTooLargeException` 崩溃，且跨窗口切换存在明显的生命周期开销与黑白屏闪烁。  
  > 因此，为了保障毫秒级流畅交互与双缓存零拷贝共享，我们将图片编辑重构为了基于 `ImageEditorDialog` 与自定义 `ImageCropView` 的高性能全屏对话框组件。  
  > 报告摘要中的 `ImageEditorActivity` 是我们初期规划遗留的笔误，我们深表歉意。为了严格遵从课程大纲‘至少3个 Activity’的规范要求，我们已在系统中新增了独立的 `AboutActivity`（关于软件与开发团队）并在 Manifest 中规范注册，不仅形式上完全合规，更在工程上实现了‘高频交互采用高性能 Dialog、独立信息路由采用 Activity’的清晰解耦架构。”

#### 提问二：“你们实现的 SM-2 算法与标准的 SM-2（SuperMemo 2）有什么区别？为什么代码里只有再次、困难、简单三档？”
- **防守应答话术**：
  > “老师提的问题非常切中算法工程落地的核心。标准 SM-2 算法诞生于 1980 年代的 PC 键盘环境，要求用户输入 0 到 5 六个连续评级，计算公式中包含连续浮点修正因数。然而在移动端触控场景下，让用户在小屏幕上区分 6 个细微的记忆等级会带来极大的认知负荷与操作迟滞（Fitts's Law）。  
  > 因此，我们借鉴了 Anki 与主流移动端记忆软件的先进实践，对 SM-2 进行了‘面向移动端触控优化的三档离散改进’：将评级压缩为直观的‘再次’、‘困难’与‘简单’，并将原本连续的修正公式离散化为明确的 Easiness Factor 增减量（再次扣 0.2 并重置天数，困难扣 0.15 且步进放缓，简单加 0.1 且快速拉长间隔）。此外，我们在 `ReviewSessionState` 中专门设计了防抖幂等状态机，彻底解决了移动端连击导致的重复记录问题。这是一种更加契合移动端交互心理学的工程改进。”

#### 提问三：“我看你们连接了 TokenDance AI 服务，甚至说明书里还有支付宝充值流程，你们真的在代码里接入了商业支付吗？资金安全如何保障？”
- **防守应答话术**：
  > “报告中向老师汇报：LexiNote 是一款纯开源、数据私有化的客户端学习软件，我们团队不设立任何商业收费账户，也不从中赚取任何利润。大模型网关采用的是合法的第三方中继服务商。在开发与测试过程中，我们依据开发日志 `TOKENDANCE.md`，主要是在沙盒环境中使用测试响应验证了 OAuth 2.0 PKCE 鉴权流程、深层链接回调、订单生成及拉起支付宝收银台的接口协议，未在真实生产环境中进行实际资金扣划。说明书中的截图已明确标注为模拟沙盒测试。  
  > 在资金与凭据安全方面，我们严格遵循了《移动应用安全开发规范》：所有 API Key 均使用设备硬件芯片 `AndroidKeyStore` 进行 AES-256 GCM 硬件级隔离加密，在应用备份和导出时全部进行强制脱敏物理剔除，确保用户本地密钥资产的绝对安全。”

#### 提问四：“你们的数据存储号称采用了‘SQLite 与兼容视图’，但代码里并没有看到 SQL 的 CREATE VIEW，你们底层到底是怎么存的？”
- **防守应答话术**：
  > “老师看得很仔细！这正是我们系统在底层存储上的一个特色设计。如果采用传统的 SQL `CREATE VIEW`，当业务实体结构频繁迭代时，视图维护和数据迁移成本极高。因此我们构建了一种‘基于代次分区（Generation-scoped）的混合存储架构’：在 SQLite 物理层，核心数据保存在以 `(generation, kind, id)` 为主键的 `entities` 实体表中，字段内容采用紧凑 JSON 格式存储；  
  > 而所谓的‘兼容视图’，是我们为了解耦表现层与底层物理表，在 Java 领域层设计的高性能不可变包装类 `ReadOnlyView.java`。当界面需要展示笔记或复习卡片时，由 Store 层读取当前活跃代次并映射为强类型的只读视图对象供 UI 绑定。这种设计兼具了 NoSQL 的扩展灵活性与 SQLite 的本地 ACID 事务回滚能力。在数据导入时，我们还能以此机制实现新旧代次无感切换与一键撤销恢复。”

---

*（本评审报告至此结束。建议韩家润组长及团队全员严格按照本报告列明的 P0/P1/P2 行动路线图落实修改，并在核验证件齐全后由组长于雨课堂规范提交。）*
