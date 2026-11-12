import os
import re
import base64
import subprocess
import markdown

WORKSPACE_DIR = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
MD_PATH = os.path.join(WORKSPACE_DIR, "docs", "software-manual-v1.5.md")
HTML_PATH = os.path.join(WORKSPACE_DIR, "docs", "software-manual-v1.5.html")
PDF_PATH = os.path.join(WORKSPACE_DIR, "docs", "software-manual-v1.5.pdf")

def load_file_as_base64(file_path):
    clean_path = file_path.replace("file:///", "").replace("file://", "")
    clean_path = os.path.normpath(clean_path)
    if not os.path.exists(clean_path):
        print(f"Warning: file not found: {clean_path}")
        return ""
    with open(clean_path, "rb") as f:
        data = f.read()
    ext = os.path.splitext(clean_path)[1].lower()
    mime = "image/png"
    if ext in [".jpg", ".jpeg"]:
        mime = "image/jpeg"
    elif ext == ".svg":
        mime = "image/svg+xml"
    elif ext == ".webp":
        mime = "image/webp"
    return f"data:{mime};base64,{base64.b64encode(data).decode('utf-8')}"

def build_pdf_document():
    with open(MD_PATH, "r", encoding="utf-8") as f:
        text = f.read()

    # Data structures for placeholder replacements
    placeholders = {}
    p_counter = 0

    def add_placeholder(html_content):
        nonlocal p_counter
        p_counter += 1
        key = f"PHOLDERBLOCK{p_counter}KEY"
        placeholders[f"<p>{key}</p>"] = html_content
        placeholders[key] = html_content
        return f"\n\n{key}\n\n"

    # 1. Clean up <hr> right before major section headers (## )
    text = re.sub(r'\n---\s*\n+(\s*##\s+)', r'\n\1', text)
    # Remove standalone hr
    text = re.sub(r'\n---\s*\n', '\n\n', text)

    # 2. Add clean page-break markers before specific subsections
    page_break_targets = [
        "### 2.2 TokenDance 账号授权登录图文流程",
        "### 2.3 TokenDance 账户充值图文流程",
        "### 3.2 笔记管理与智能索引（Tab 2）",
        "#### 1. 笔记本组织架构",
        "### 3.3 复习中心与科学记忆体系（Tab 3）",
        "### 3.4 设置与数据安全（Tab 4）"
    ]
    for target in page_break_targets:
        text = text.replace(target, f'<div class="page-break"></div>\n\n{target}')

    # 3. Callout blocks replacement
    callout_note_html = """
<div class="callout callout-note">
    <div class="callout-header">
        <span class="callout-icon">ℹ️</span>
        <span class="callout-title">版本发布与核心架构说明 (NOTE)</span>
    </div>
    <div class="callout-body">
        <div class="callout-item"><strong>版本号</strong>：v1.5.0-native (Build 3)</div>
        <div class="callout-item"><strong>核心定位</strong>：离线优先、安全隐私保护、深度融合大模型语境分析与艾宾浩斯科学记忆体系的高性能原生 Android 智能词汇笔记与复习中心。</div>
        <div class="callout-item"><strong>技术架构</strong>：纯 Java 原生开发，无 WebView/Hybrid 混合容器开销；内置 ECDICT 万词离线词库与 Sherpa-ONNX 离线发音引擎；数据采用 SQLite 与结构化 JSON 双轨本地存储；支持 TokenDance (词元跳动) 大模型云端无感鉴权驱动。</div>
    </div>
</div>
"""
    callout_important_html = """
<div class="callout callout-important">
    <div class="callout-header">
        <span class="callout-icon">🛡️</span>
        <span class="callout-title">充值资金去向与计费模式明确说明 (IMPORTANT)</span>
    </div>
    <div class="callout-body">
        <ol class="callout-list">
            <li><strong>零加价、零抽成、非盈利客户端</strong>：LexiNote 是一款完全开源、离线优先的本地独立应用程序，软件本身<strong>不收取任何应用购买费、月度订阅费、功能解锁费，更不对 API 调用收取任何中间差价或通道佣金</strong>。</li>
            <li><strong>资金直接进入第三方服务商对公账户</strong>：用户在充值流程中看到的收款方——“<strong>杭州似一科技有限公司</strong>”，为 Watcha 观楂平台及 TokenDance (词元跳动) AI API 网关的合法商业运营实体。支付通道经由浦发银行商户收银平台清算，直接进入杭州似一科技有限公司企业账户。</li>
            <li><strong>100% 转化为个人 AI 调用额度</strong>：您充值支付的每一分钱，均 1:1 全额充入您在 TokenDance 的个人账户余额中。</li>
            <li><strong>按实际 Token 消耗计量扣费</strong>：当您在 LexiNote 中使用 AI 语境解析、语法答疑或图像文字提炼时，TokenDance 平台根据底层真实大语言模型（如 DeepSeek-V3、DeepSeek-R1、Kimi 等）公布的官方 Token 单价精准扣减，账单明细在 TokenDance 官网清晰可查。</li>
        </ol>
    </div>
</div>
"""

    # Replace NOTE callout
    text = re.sub(r'>\s*\[!NOTE\][\s\S]*?(?=\n\n|$)', add_placeholder(callout_note_html), text, count=1)
    # Replace IMPORTANT callout
    text = re.sub(r'>\s*\[!IMPORTANT\][\s\S]*?(?=\n\n|$)', add_placeholder(callout_important_html), text, count=1)

    # 4. Architecture component replacement for Mermaid flowchart
    arch_html = """
<div class="arch-overview">
    <div class="arch-root">
        <span class="arch-root-pill">📱 LexiNote 客户端 (v1.5 Android 原生版架构蓝图)</span>
    </div>
    <div class="arch-grid-2x2">
        <div class="arch-module-card mod-query">
            <div class="mod-header">
                <span class="mod-tab-badge">Tab 1 · 25% 屏幕宽</span>
                <h4>🔍 查询记录与 AI 助手</h4>
            </div>
            <ul class="mod-items">
                <li><strong>本地离线毫秒检索</strong>：ECDICT 万词词典、国际音标、高频释义与考纲标签</li>
                <li><strong>多模态拍照识词</strong>：全屏裁剪旋转与生词目标区域画框涂抹精准提取</li>
                <li><strong>AI 深度语境解读</strong>：贴合上下文给出精准语义剖析与地道仿写例句</li>
                <li><strong>AI 英语助手长对话</strong>：多轮连贯语法长难句探讨，支持智能命名与抽屉</li>
                <li><strong>词汇入库与历史追溯</strong>：一键分类存入笔记本与最近查询历史快速温故</li>
            </ul>
        </div>

        <div class="arch-module-card mod-notebooks">
            <div class="mod-header">
                <span class="mod-tab-badge">Tab 2 · 25% 屏幕宽</span>
                <h4>📖 笔记管理与智能索引</h4>
            </div>
            <ul class="mod-items">
                <li><strong>主题笔记本书架视图</strong>：按学科/阅读分类建立多本笔记并独立统计词数</li>
                <li><strong>120+ 真实测试词库</strong>：无限滚动瀑布流高性能平滑加载</li>
                <li><strong>微信风格字母滑动栏</strong>：磨砂银灰气泡与绿色触碰触觉反馈高亮 (A–Z)</li>
                <li><strong>相册风格时间穿梭轴</strong>：精确到日年月日浮动卡片与年份胶囊徽章</li>
                <li><strong>独立锚定下拉菜单</strong>：7 种排序模式 (最新/生词等) & 3 种释义视图</li>
                <li><strong>词条详情与自定义标签</strong>：完整例句展开、多重义项批注与自定义标签</li>
                <li><strong>批量管理模式</strong>：全选/反选、批量迁移归属、批量打标签与批量删除</li>
            </ul>
        </div>

        <div class="arch-module-card mod-review">
            <div class="mod-header">
                <span class="mod-tab-badge">Tab 3 · 25% 屏幕宽</span>
                <h4>🔁 复习中心与记忆体系</h4>
            </div>
            <ul class="mod-items">
                <li><strong>SM-2 艾宾浩斯算法</strong>：自适应间隔排期与 0~5 级记忆掌握度跃迁</li>
                <li><strong>跟手滑动多计划切换</strong>：绝对物理坐标跟手位移与橡皮筋阻尼拉伸</li>
                <li><strong>整合型计划中枢卡</strong>：待学/新词/复习核心指标与进度百分比监控</li>
                <li><strong>【看词猜义】翻卡考核</strong>：双面平滑翻转与艾宾浩斯三档评级 (再次/困难/简单)</li>
                <li><strong>【看义猜词】拼写盲测</strong>：回忆输入与正误实时自动比对判定</li>
                <li><strong>7 天学习动态监控</strong>：连续打卡天数、新词量、复习通过数直观统计</li>
            </ul>
        </div>

        <div class="arch-module-card mod-settings">
            <div class="mod-header">
                <span class="mod-tab-badge">Tab 4 · 25% 屏幕宽</span>
                <h4>⚙️ 设置与数据安全</h4>
            </div>
            <ul class="mod-items">
                <li><strong>TokenDance 免密连接</strong>：OAuth 2.0 专属密钥与自主设定安全限额</li>
                <li><strong>第三方合规充值通道</strong>：资金 1:1 直达服务商似一科技并按 Token 计费</li>
                <li><strong>AI 推理档位调节</strong>：极速响应 (毫秒直接输出) vs 深度思考 (完整思维链)</li>
                <li><strong>语音引擎个性配置</strong>：Sherpa-ONNX 离线神经网络语音包 / 系统 TTS</li>
                <li><strong>数据安全与全量备份</strong>：ZIP 2.2 / JSON 1.1 导出与秒级还原 (Keystore 防泄漏)</li>
            </ul>
        </div>
    </div>
</div>
"""
    text = re.sub(r'```mermaid\s+flowchart[\s\S]*?```', add_placeholder(arch_html), text)

    # 5. Transform ````carousel ... ```` into custom HTML galleries
    carousel_pattern = re.compile(r'````carousel([\s\S]*?)````', re.MULTILINE)
    carousel_counter = 0

    def replace_carousel(match):
        nonlocal carousel_counter
        carousel_counter += 1
        raw_content = match.group(1)
        slides = [s.strip() for s in raw_content.split("<!-- slide -->") if s.strip()]

        count = len(slides)
        if count == 1:
            col_class = "gallery-cols-1"
        elif count in [2, 4]:
            col_class = "gallery-cols-2"
        else:
            col_class = "gallery-cols-3"

        cards_html = []
        for idx, slide in enumerate(slides):
            m = re.match(r'!\[(.*?)\]\(([^\)\s]+)\)', slide)
            if not m:
                continue
            alt_text = m.group(1).strip()
            img_url = m.group(2).strip()
            data_uri = load_file_as_base64(img_url)

            step_match = re.match(r'^(步骤\s*\d+)[:：]\s*(.*)$', alt_text)
            if step_match:
                badge_text = step_match.group(1)
                desc_text = step_match.group(2)
            else:
                badge_text = f"图 {carousel_counter}-{idx+1}"
                desc_text = alt_text

            card = f"""
            <div class="gallery-card">
                <div class="gallery-img-wrapper">
                    <img src="{data_uri}" alt="{alt_text}" loading="eager" />
                </div>
                <div class="gallery-caption">
                    <span class="gallery-badge">{badge_text}</span>
                    <span class="gallery-desc">{desc_text}</span>
                </div>
            </div>
            """
            cards_html.append(card)

        gallery_full = f'<div class="gallery-grid {col_class}">{"".join(cards_html)}</div>'
        return add_placeholder(gallery_full)

    text = carousel_pattern.sub(replace_carousel, text)

    # 6. Convert Markdown to HTML
    body_html = markdown.markdown(
        text,
        extensions=['tables', 'fenced_code', 'nl2br', 'sane_lists', 'attr_list']
    )

    # 7. Replace placeholders
    for k, v in placeholders.items():
        body_html = body_html.replace(k, v)

    # 8. Post-process Table Badges
    body_html = body_html.replace(
        "🟢 100% 离线可用",
        '<span class="matrix-pill pill-offline">🟢 100% 离线可用</span>'
    )
    body_html = body_html.replace(
        "🌐 需网络连接",
        '<span class="matrix-pill pill-online">🌐 需网络连接</span>'
    )

    # 9. Post-process FAQ into clean cards
    faq_pattern = re.compile(
        r'<h3>(Q\d+[:：]\s*.*?)</h3>\s*<p><strong>答</strong>[：:]\s*([\s\S]*?)(?=(?:<h3>|<h2|$))',
        re.MULTILINE
    )
    def replace_faq(match):
        q_text = match.group(1).strip()
        a_text = match.group(2).strip()
        return f"""
        <div class="faq-card">
            <div class="faq-question">
                <span class="faq-q-icon">💬</span>
                <span class="faq-q-text">{q_text}</span>
            </div>
            <div class="faq-answer">
                <span class="faq-a-badge">权威解答</span>
                <div class="faq-a-text">{a_text}</div>
            </div>
        </div>
        """
    body_html = faq_pattern.sub(replace_faq, body_html)

    # 10. Assemble Full HTML
    full_html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1.0">
<title>LexiNote (Android Java 原生版) 官方使用指南与软件说明书</title>
<style>
/* ==================== GLOBAL STYLES ==================== */
@page {{
    size: A4 portrait;
    margin: 14mm 14mm 14mm 14mm;
}}

* {{
    box-sizing: border-box;
    -webkit-print-color-adjust: exact !important;
    print-color-adjust: exact !important;
}}

body {{
    font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "WenQuanYi Micro Hei", sans-serif;
    color: #1E293B;
    background-color: #FFFFFF;
    line-height: 1.65;
    font-size: 13px;
    margin: 0;
    padding: 0;
}}

/* ==================== TYPOGRAPHY ==================== */
h1 {{
    font-size: 18px;
    font-weight: 800;
    color: #0F172A;
    margin-top: 0;
    margin-bottom: 10px;
    line-height: 1.3;
    letter-spacing: -0.02em;
    border-bottom: 2px solid #059669;
    padding-bottom: 8px;
}}

h2 {{
    font-size: 15.5px;
    font-weight: 700;
    color: #0F172A;
    margin-top: 20px;
    margin-bottom: 10px;
    padding-left: 9px;
    border-left: 4.5px solid #059669;
    break-after: avoid;
    letter-spacing: -0.01em;
}}

h2:not(:first-of-type) {{
    break-before: page;
    margin-top: 0;
    padding-top: 4px;
}}

h3 {{
    font-size: 14px;
    font-weight: 600;
    color: #1E293B;
    margin-top: 16px;
    margin-bottom: 8px;
    break-after: avoid;
}}

h4 {{
    font-size: 13px;
    font-weight: 600;
    color: #334155;
    margin-top: 10px;
    margin-bottom: 5px;
    break-after: avoid;
}}

p {{
    margin-top: 0;
    margin-bottom: 8px;
}}

strong {{
    color: #0F172A;
    font-weight: 650;
}}

code {{
    font-family: "Cascadia Code", "Fira Code", "JetBrains Mono", Consolas, monospace;
    font-size: 11.5px;
    background: #F1F5F9;
    color: #0F766E;
    padding: 2px 5px;
    border-radius: 4px;
    border: 1px solid #E2E8F0;
}}

pre {{
    font-family: "Cascadia Code", "Fira Code", "JetBrains Mono", Consolas, monospace;
    font-size: 11.5px;
    background: #F8FAFC;
    border: 1px solid #E2E8F0;
    border-radius: 6px;
    padding: 10px;
    overflow-x: auto;
    break-inside: avoid;
}}

ul, ol {{
    margin-top: 0;
    margin-bottom: 10px;
    padding-left: 20px;
}}

li {{
    margin-bottom: 5px;
}}

/* ==================== DOCUMENT COVER / HEADER ==================== */
.doc-header-banner {{
    background: linear-gradient(135deg, #064E3B 0%, #065F46 45%, #047857 100%);
    color: #FFFFFF;
    border-radius: 8px;
    padding: 14px 20px;
    margin-bottom: 12px;
    box-shadow: 0 3px 8px rgba(6, 78, 59, 0.12);
}}

.doc-header-top {{
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 6px;
}}

.doc-logo-title {{
    font-size: 20px;
    font-weight: 800;
    letter-spacing: -0.02em;
    display: flex;
    align-items: center;
    gap: 8px;
}}

.doc-version-pill {{
    background: rgba(255, 255, 255, 0.2);
    border: 1px solid rgba(255, 255, 255, 0.35);
    padding: 2px 8px;
    border-radius: 9999px;
    font-size: 10.5px;
    font-weight: 600;
}}

.doc-header-sub {{
    font-size: 12px;
    opacity: 0.94;
    line-height: 1.4;
    margin-bottom: 10px;
}}

.doc-tag-bar {{
    display: flex;
    flex-wrap: wrap;
    gap: 5px;
}}

.doc-tag {{
    background: rgba(255, 255, 255, 0.15);
    border: 1px solid rgba(255, 255, 255, 0.25);
    border-radius: 4px;
    padding: 2px 7px;
    font-size: 10.5px;
    font-weight: 500;
}}

/* ==================== CALLOUTS ==================== */
.callout {{
    border-radius: 8px;
    margin: 10px 0 12px 0;
    padding: 10px 12px;
    break-inside: avoid;
    box-shadow: 0 1px 3px rgba(0,0,0,0.03);
}}

.callout-header {{
    font-weight: 700;
    font-size: 12.5px;
    display: flex;
    align-items: center;
    gap: 6px;
    margin-bottom: 6px;
    padding-bottom: 4px;
    border-bottom: 1px dashed rgba(0,0,0,0.1);
}}

.callout-icon {{
    font-size: 13px;
}}

.callout-body {{
    font-size: 11.5px;
    line-height: 1.5;
}}

.callout-item {{
    margin-bottom: 4px;
}}
.callout-item:last-child {{
    margin-bottom: 0;
}}

.callout-list {{
    margin: 0;
    padding-left: 16px;
}}
.callout-list li {{
    margin-bottom: 4px;
}}
.callout-list li:last-child {{
    margin-bottom: 0;
}}

.callout-note {{
    background-color: #F0F9FF;
    border: 1px solid #BAE6FD;
    border-left: 4.5px solid #0284C7;
    color: #0369A1;
}}
.callout-note .callout-title {{
    color: #0284C7;
}}
.callout-note strong {{
    color: #0C4A6E;
}}

.callout-important {{
    background-color: #FEFCE8;
    border: 1px solid #FEF08A;
    border-left: 4.5px solid #EAB308;
    color: #854D0E;
}}
.callout-important .callout-title {{
    color: #A16207;
}}
.callout-important strong {{
    color: #713F12;
}}

/* ==================== ARCHITECTURE COMPONENT ==================== */
.arch-overview {{
    margin: 10px 0 6px 0;
    break-inside: avoid;
}}
.arch-root {{
    text-align: center;
    margin-bottom: 8px;
}}
.arch-root-pill {{
    background: #064E3B;
    color: #FFFFFF;
    font-weight: 700;
    font-size: 11.5px;
    padding: 4px 14px;
    border-radius: 9999px;
    box-shadow: 0 2px 4px rgba(6, 78, 59, 0.2);
    display: inline-block;
}}
.arch-grid-2x2 {{
    display: grid;
    grid-template-columns: 1fr 1fr;
    gap: 8px;
}}
.arch-module-card {{
    background: #FFFFFF;
    border-radius: 7px;
    border: 1px solid #E2E8F0;
    padding: 8px 10px;
    box-shadow: 0 1px 3px rgba(0,0,0,0.03);
}}
.mod-query {{ border-top: 3.5px solid #059669; }}
.mod-notebooks {{ border-top: 3.5px solid #0284C7; }}
.mod-review {{ border-top: 3.5px solid #7C3AED; }}
.mod-settings {{ border-top: 3.5px solid #475569; }}

.mod-header {{
    display: flex;
    justify-content: space-between;
    align-items: center;
    margin-bottom: 5px;
    padding-bottom: 4px;
    border-bottom: 1px solid #F1F5F9;
}}
.mod-header h4 {{
    margin: 0;
    font-size: 11.5px;
    font-weight: 700;
    color: #0F172A;
}}
.mod-tab-badge {{
    font-size: 9px;
    font-weight: 700;
    padding: 1px 4px;
    border-radius: 3px;
}}
.mod-query .mod-tab-badge {{ background: #ECFDF5; color: #065F46; border: 1px solid #A7F3D0; }}
.mod-notebooks .mod-tab-badge {{ background: #F0F9FF; color: #0369A1; border: 1px solid #BAE6FD; }}
.mod-review .mod-tab-badge {{ background: #F5F3FF; color: #5B21B6; border: 1px solid #DDD6FE; }}
.mod-settings .mod-tab-badge {{ background: #F8FAFC; color: #334155; border: 1px solid #CBD5E1; }}

.mod-items {{
    margin: 0;
    padding-left: 14px;
    font-size: 10px;
    line-height: 1.4;
    color: #334155;
}}
.mod-items li {{
    margin-bottom: 2.5px;
}}
.mod-items li strong {{
    color: #0F172A;
}}

/* ==================== SCREENSHOT GALLERY ==================== */
.gallery-grid {{
    display: grid;
    gap: 10px;
    margin: 12px 0 16px 0;
    break-inside: auto;
}}

.gallery-cols-3 {{
    grid-template-columns: repeat(3, 1fr);
}}

.gallery-cols-2 {{
    grid-template-columns: repeat(2, 1fr);
}}

.gallery-cols-1 {{
    grid-template-columns: 1fr;
    max-width: 300px;
    margin-left: auto;
    margin-right: auto;
}}

.gallery-card {{
    background: #FFFFFF;
    border: 1px solid #E2E8F0;
    border-radius: 8px;
    overflow: hidden;
    display: flex;
    flex-direction: column;
    box-shadow: 0 1px 4px rgba(0,0,0,0.04);
    break-inside: avoid;
}}

.gallery-img-wrapper {{
    background: #F1F5F9;
    padding: 6px 4px;
    display: flex;
    justify-content: center;
    align-items: center;
}}

.gallery-img-wrapper img {{
    height: 185px;
    width: auto;
    max-width: 100%;
    object-fit: contain;
    border-radius: 6px;
    border: 1px solid #CBD5E1;
    box-shadow: 0 1px 4px rgba(0,0,0,0.08);
}}

.gallery-cols-1 .gallery-img-wrapper img {{
    height: 260px;
}}

.gallery-cols-2 .gallery-img-wrapper img {{
    height: 210px;
}}

.gallery-caption {{
    padding: 5px 8px;
    background: #FFFFFF;
    border-top: 1px solid #F1F5F9;
    display: flex;
    flex-direction: column;
    gap: 2px;
}}

.gallery-badge {{
    font-size: 10px;
    font-weight: 700;
    color: #059669;
    background: #ECFDF5;
    border: 1px solid #A7F3D0;
    border-radius: 4px;
    padding: 1px 5px;
    align-self: flex-start;
}}

.gallery-desc {{
    font-size: 10.5px;
    color: #334155;
    line-height: 1.3;
    font-weight: 500;
}}

/* ==================== FEATURE MATRIX TABLE ==================== */
table {{
    width: 100%;
    border-collapse: collapse;
    margin: 14px 0;
    font-size: 11.5px;
    break-inside: auto;
}}

thead {{
    display: table-header-group;
}}

th {{
    background: #0F172A;
    color: #FFFFFF;
    font-weight: 600;
    text-align: left;
    padding: 8px 10px;
    border: 1px solid #0F172A;
}}

td {{
    padding: 6px 9px;
    border: 1px solid #E2E8F0;
    vertical-align: middle;
}}

tr:nth-child(even) {{
    background-color: #F8FAFC;
}}

tr {{
    break-inside: avoid;
}}

.matrix-pill {{
    display: inline-block;
    padding: 2px 7px;
    border-radius: 9999px;
    font-size: 10.5px;
    font-weight: 600;
    white-space: nowrap;
}}

.pill-offline {{
    background: #ECFDF5;
    color: #065F46;
    border: 1px solid #A7F3D0;
}}

.pill-online {{
    background: #EFF6FF;
    color: #1E40AF;
    border: 1px solid #BFDBFE;
}}

/* ==================== FAQ CARDS ==================== */
.faq-card {{
    background: #F8FAFC;
    border: 1px solid #E2E8F0;
    border-radius: 8px;
    margin: 12px 0;
    padding: 12px 14px;
    break-inside: avoid;
    box-shadow: 0 1px 3px rgba(0,0,0,0.02);
}}

.faq-question {{
    font-size: 13.5px;
    font-weight: 700;
    color: #0F172A;
    display: flex;
    align-items: center;
    gap: 8px;
    margin-bottom: 7px;
}}

.faq-q-icon {{
    font-size: 14px;
}}

.faq-answer {{
    display: flex;
    gap: 10px;
    align-items: flex-start;
    font-size: 12.5px;
    line-height: 1.55;
    color: #334155;
}}

.faq-a-badge {{
    background: #ECFDF5;
    color: #065F46;
    border: 1px solid #A7F3D0;
    font-size: 10px;
    font-weight: 700;
    padding: 2px 6px;
    border-radius: 4px;
    white-space: nowrap;
    margin-top: 2px;
}}

.faq-a-text {{
    flex: 1;
}}

/* ==================== PAGE BREAKS ==================== */
.page-break {{
    break-before: page;
}}

h1, h2, h3, h4, h5, h6 {{
    break-after: avoid;
}}
</style>
</head>
<body>

<div class="doc-header-banner">
    <div class="doc-header-top">
        <div class="doc-logo-title">
            <span>📚 LexiNote</span>
        </div>
        <span class="doc-version-pill">v1.5.0-native (Build 3)</span>
    </div>
    <div class="doc-header-sub">
        <strong>Android Java 原生版官方使用指南与软件说明书</strong> · 离线优先 · 安全隐私保护 · 艾宾浩斯科学记忆体系 · TokenDance 智能大模型赋能
    </div>
    <div class="doc-tag-bar">
        <span class="doc-tag">⚡ 纯原生 Java (无 Hybrid/WebView 开销)</span>
        <span class="doc-tag">📖 ECDICT 万词离线秒查</span>
        <span class="doc-tag">🎙️ Sherpa-ONNX 离线神经网络发音</span>
        <span class="doc-tag">🧠 SM-2 艾宾浩斯自适应排期</span>
        <span class="doc-tag">🔐 Android Keystore 硬件加密防泄漏</span>
    </div>
</div>

{body_html}

</body>
</html>"""

    with open(HTML_PATH, "w", encoding="utf-8") as f:
        f.write(full_html)
    print(f"HTML successfully generated at: {HTML_PATH}")

def convert_to_pdf():
    chrome_path = r"C:\Program Files\Google\Chrome\Application\chrome.exe"
    edge_path = r"C:\Program Files (x86)\Microsoft\Edge\Application\msedge.exe"
    browser = chrome_path if os.path.exists(chrome_path) else edge_path

    cmd = [
        browser,
        "--headless=new",
        "--disable-gpu",
        "--no-sandbox",
        "--run-all-compositor-stages-before-draw",
        "--no-pdf-header-footer",
        f"--print-to-pdf={PDF_PATH}",
        HTML_PATH
    ]

    print("Running browser to render PDF...")
    result = subprocess.run(cmd, capture_output=True, text=True)
    if os.path.exists(PDF_PATH):
        size_kb = os.path.getsize(PDF_PATH) / 1024
        print(f"PDF successfully exported to: {PDF_PATH} ({size_kb:.1f} KB)")
    else:
        print("Failed to export PDF.")
        print("Stdout:", result.stdout)
        print("Stderr:", result.stderr)

if __name__ == "__main__":
    build_pdf_document()
    convert_to_pdf()
