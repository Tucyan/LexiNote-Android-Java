"""Fixed demo inputs, visible UI actions, and completion checks."""
import hashlib
import re
import sqlite3
import time
from pathlib import Path

from runner import FIXTURE

WORD = "onomatopoeia"
RIVER = "riverbank"
CONTEXT = "We sat on the riverbank and watched the boats."
PROMPT = "请用中文解释 riverbank 和 riverbed 的区别，各给一个英文例句，并附上中文翻译。"
TITLE = "河岸与河床辨析"
DISPOSABLE = "agenda：会议日程"
PLAN = "演示复习计划"
FIXTURE_HASH = "4dc81a9d11ad15890a035f420abda461af194fef1cd9934d28dd8eb67f73501b"
SUBMIT = re.compile("智能查询")
COMPLETE = re.compile("今日任务全部完成|今日复习任务已全部搞定|复习完成")
REVIEW_PROGRESS = re.compile(r"^今日第\s+(\d+)\s*/\s*\d+\s+词$")
REVIEW_FLIP = "查看答案 / 翻转卡片"

STEPS = [
    ("offline", "离线查词"),
    ("bilingual", "AI 双语义项整理"),
    ("verify", "联网核对"),
    ("organize", "整理并保存到两本笔记"),
    ("assistant", "AI 助手中文追问"),
    ("sessions", "AI 会话管理"),
    ("notes", "笔记筛选、排序和显示"),
    ("image", "图片查询与目标确认"),
    ("plan", "新建并编辑复习计划"),
    ("review", "今日复习与再练一组"),
]


class Scenes:
    def __init__(self, device, run):
        self.d, self.run = device, run

    def click(self, label, index=0, description=None):
        return description or f"点击 {getattr(label, 'pattern', label)}", lambda: self.d.tap(label, index)

    def find(self, label, timeout=24):
        return f"等待 {getattr(label, 'pattern', label)}", lambda: self.d.find(label, timeout=timeout)

    def fill(self, text, index=0, label=None):
        return f"输入 {text}", lambda: self.d.fill(text, label=label, index=index)

    def back(self):
        return self.click("返回", description="返回上一级")

    def top(self):
        def scroll():
            for _ in range(4):
                self.d.scroll(up=True)
        return "滚动到页面顶部", scroll

    def shot(self, step, label):
        return f"记录截图：{label}", lambda: self.run.capture(step, label, self.d)

    def scroll_click(self, label):
        return f"滚动并打开 {getattr(label, 'pattern', label)}", lambda: self.d.tap_node(self.d.scroll_to(label))

    def toggle(self, label, enabled, index=0, scroll=False, attempts=5):
        def apply():
            node = self.d.scroll_to(label, attempts=attempts) if scroll else self.d.find(label, index)
            checked = node.get("checked")
            if checked not in ("true", "false") or node.get("checkable") != "true":
                raise RuntimeError("目标不是可勾选控件")
            if (checked == "true") != enabled:
                self.d.tap_node(node)
        return f"{'勾选' if enabled else '取消勾选'} {getattr(label, 'pattern', label)}", apply

    def open_settings(self):
        def open_tab():
            for attempt in range(3):
                self.d.tap("我的")
                try:
                    self.d.find("设置与数据管理", timeout=5)
                    return
                except TimeoutError:
                    if attempt == 2:
                        raise
        return "打开并确认进入设置", open_tab

    def auto_supplement(self, enabled):
        return [self.open_settings(),
                self.toggle("查词时自动进行 AI 补充", enabled, scroll=True, attempts=7),
                self.click("查询记录")]

    def query(self, word, context=""):
        return [self.click("文本查询"), self.fill(word), self.fill(context, 1),
                (f"提交查询 {word}", lambda: self.submit_query("文本查询"))]

    def submit_query(self, mode):
        if self.d.physical:
            # Re-render the same query mode to remove focus and release the
            # keyboard without invoking the phone's back navigation.
            self.d.tap(mode)
        self.d.tap(SUBMIT)

    def wait_session(self, word, predicate, description, timeout=110):
        def wait():
            limit = time.monotonic() + timeout
            while time.monotonic() < limit:
                try:
                    session = self.d.session(word)
                except (sqlite3.Error, ValueError):
                    time.sleep(1)
                    continue
                if session.get("last_error"):
                    raise RuntimeError("App 显示请求失败，请在模拟器检查原因")
                if predicate(session):
                    return
                time.sleep(1)
            raise TimeoutError(description)
        return f"等待 {description}", wait

    def pick_file(self, file):
        # DocumentsUI shows file names as text in list view and as
        # "name, size, date" accessibility descriptions in grid view.
        label = re.compile(r"^" + re.escape(file) + r"(?:,|$)")
        search_class = "android.widget.AutoCompleteTextView"
        def select(timeout=24):
            node = self.d.find(label, timeout=timeout)
            self.d.tap_node(node)
            if node.get("package") == "com.android.fileexplorer":
                self.d.tap("确定")

        def search():
            self.d.fill(file, class_name=search_class)
            self.d.command("shell", "input", "keyevent", "KEYCODE_ENTER")
            select()
        try:
            select(timeout=7)
            return
        except TimeoutError:
            pass
        try:
            self.d.find(None, timeout=4, class_name=search_class)
        except TimeoutError:
            pass
        else:
            search()
            return
        downloads = re.compile("^Downloads$|^下载内容$|^下载$")
        try:
            self.d.find(downloads, timeout=4)
        except TimeoutError:
            self.d.tap(re.compile("Show roots|显示根目录|打开方式|显示存储|Open from"), timeout=8)
            self.d.tap(downloads)
        try:
            select(timeout=5)
        except TimeoutError:
            self.d.tap(re.compile("^Search$|^搜索$"))
            search()

    def push_sample(self, path, name):
        remote = "/sdcard/Download/" + name
        self.d.command("push", path, remote)
        self.d.command("shell", "touch", remote)
        self.d.command("shell", "am", "broadcast", "-a",
                       "android.intent.action.MEDIA_SCANNER_SCAN_FILE", "-d", "file://" + remote)

    def prepare(self):
        def push_fixture():
            if hashlib.sha256(FIXTURE.read_bytes()).hexdigest() != FIXTURE_HASH:
                raise RuntimeError("基础备份校验值不匹配")
            self.push_sample(FIXTURE, "lexinote-demo.zip")
        def push_image():
            self.push_sample(Path(__file__).with_name("scene.png"), "lexinote-demo.png")
        reset = [("清除演示版登录状态和内嵌网页缓存", self.d.reset_physical_demo)] if self.d.physical else []
        return [*reset, ("打开 LexiNote", self.d.launch), ("传入固定备份", push_fixture),
                ("传入固定示例图", push_image), self.open_settings(),
                self.scroll_click("选择 ZIP / JSON 备份恢复"),
                ("选择固定 ZIP 文件", lambda: self.pick_file("lexinote-demo.zip")),
                self.find("确认覆盖恢复"), self.click("恢复", index=-1),
                self.find("选择 ZIP / JSON 备份恢复", 50), self.click("查询记录")]

    def offline(self):
        return [*self.auto_supplement(False), *self.query(WORD),
                self.find("内置词库 · ECDICT"), self.find("释义与例句 (2 条)")]

    def bilingual(self):
        def paired(session):
            result = session.get("query_results") or {}
            meanings = result.get("meanings", [])
            return result.get("dictionary_ai_aligned") and any(
                m.get("zh_definition") and m.get("en_definition") for m in meanings)
        def return_to_queries():
            try:
                self.d.tap("查询记录", timeout=5)
            except TimeoutError:
                self.d.tap("返回")
                self.d.find("查询记录")

        navigation = ("返回查询首页", return_to_queries) if self.d.physical else self.back()
        return [navigation, *self.auto_supplement(True), *self.query(WORD),
                self.find("内置词库 · ECDICT"),
                self.wait_session(WORD, paired, "AI 返回带双语义项的卡片"),
                self.find("中文释义"), self.find("英文释义")]

    def verify(self):
        def references():
            status = (self.d.session(RIVER).get("web_verification") or {}).get("status")
            if status != "insufficient":
                self.scroll_click("查看引用")[1]()
        return [self.back(), *self.query(RIVER, CONTEXT),
                self.wait_session(RIVER, lambda s: (s.get("query_results") or {}).get("dictionary_context"),
                                  "语境解读完成"),
                self.click("联网核对", description="提交真实联网核对"),
                self.wait_session(RIVER, lambda s: (s.get("web_verification") or {}).get("status"),
                                  "联网核对返回结论"),
                ("展开可用的引用", references), self.shot("verify", "核对结果")]

    def organize(self):
        return [self.click("整理笔记"), self.top(), self.toggle("收录此释义", True, scroll=True),
                self.scroll_click("添加备注"), self.fill("河流语境下的用法"), self.click("保存", -1),
                self.scroll_click("添加批注"), self.fill("这里指河岸，不是河床。"), self.click("保存", -1),
                self.top(), ("关闭一个例句的笔记显示", self.hide_first_example),
                self.shot("organize", "义项显示、备注和批注"),
                self.click("选择添加笔记"), self.toggle("默认笔记", True), self.toggle("工作沟通", True),
                self.shot("organize", "同时选择两本笔记"), self.click("保存", -1),
                self.wait_session(RIVER, lambda s: (s.get("saved_content") or {}).get("saved"),
                                  "词条保存到所选笔记", 40)]

    def hide_first_example(self):
        label = re.compile("例句.*[显示隐藏]$")
        self.d.scroll_to(label)
        self.toggle(label, False)[1]()

    def assistant(self):
        def answered(session):
            messages = session.get("ai_messages") or []
            for index, message in enumerate(messages):
                if message.get("role") == "user" and message.get("content") == PROMPT:
                    return any(m.get("role") == "assistant" and m.get("content")
                               for m in messages[index+1:])
            return False
        return [self.click("打开 AI 助手继续提问"), self.fill(PROMPT),
                self.click("发送", description="发送中文追问到真实 AI 接口"),
                self.wait_session(RIVER, answered, "中文追问回复完成"), self.find("发送")]

    def sessions(self):
        def remove_disposable():
            self.d.scroll_to(DISPOSABLE)
            parent = next((p for p in reversed(list(self.d.tree().iter("node")))
                           if any(self.d.match(n, DISPOSABLE) for n in p.iter("node"))
                           and sum(self.d.match(n, "删除") for n in p.iter("node")) == 1), None)
            if parent is None:
                raise TimeoutError("找不到演示待删会话的删除按钮")
            self.d.tap_node(next(n for n in parent.iter("node") if self.d.match(n, "删除")))
        def submit_filter():
            self.d.command("shell", "input", "keyevent", "KEYCODE_ENTER")
        return [self.click("保存会话"), self.fill("河岸辨析"), self.click("保存", -1),
                self.click("修改名称"), self.fill(TITLE), self.click("保存", -1),
                self.back(), self.click(re.compile("^临时")), self.shot("sessions", "临时会话列表"),
                self.click(re.compile("^已保存")),
                self.fill(TITLE, label="搜索会话名称"), ("提交会话名称筛选", submit_filter),
                self.scroll_click(TITLE),
                self.find("修改名称"), self.shot("sessions", "重新打开已保存会话"),
                self.back(), self.click(re.compile("^临时")),
                (f"打开固定样例会话 {DISPOSABLE} 的删除确认", remove_disposable),
                self.find(re.compile(r"确定删除「" + re.escape(DISPOSABLE) + "」")),
                self.click("删除", -1)]

    def notes(self):
        def open_work():
            try:
                self.d.find("工作沟通", timeout=5)
            except TimeoutError:
                self.scroll_click("主题学习")[1]()
            self.scroll_click("工作沟通")[1]()
        return [self.click("笔记"), ("从主题学习打开工作沟通", open_work),
                self.click(re.compile("⚡ 筛选")), self.click("短语"),
                self.scroll_click(re.compile("应用筛选")),
                self.click(re.compile("⇅")), self.click(re.compile("按字母升序")),
                self.click(re.compile("平铺详细|紧凑模式")), self.shot("notes", "短语筛选与 A–Z 布局"),
                self.click(re.compile("形态: 短语")), self.click(re.compile("👁")),
                self.click(re.compile("英文释义.*优先")), self.shot("notes", "英文释义显示"),
                self.fill(RIVER, label=re.compile("搜索词汇")),
                self.scroll_click(RIVER), self.scroll_click("配置各笔记显示"),
                self.click("工作沟通"), self.toggle("例句", False),
                self.shot("notes", "工作沟通的独立显示"), self.click("保存", -1),
                self.scroll_click("配置各笔记显示"), self.click("默认笔记"),
                self.shot("notes", "对照默认笔记的显示"), self.click("取消", -1)]

    def image(self):
        def enter_mixed_mode():
            self.d.tap("文本查询")
            self.d.fill("", index=1)
            self.d.tap("图文+提问")

        def result_visible():
            def visible(tree):
                labels = [node.get("text", "").strip() for node in tree.iter("node")]
                if any("会话快照版本已过期" in label for label in labels):
                    raise RuntimeError("词条已显示但历史记录写入冲突。关闭提示后可继续检查词条。")
                return "take over" in labels and any(label.startswith("释义与例句") for label in labels)
            self.d.wait(visible, "图片查询词条已显示", timeout=70)

        def frame():
            node = self.d.find(re.compile("图片编辑器"))
            x, y, right, bottom = self.d.bounds(node)
            w, h = right - x, bottom - y
            self.d.command("shell", "input", "swipe", x+w*11//100, y+h*43//100,
                           x+w*89//100, y+h*58//100, 650)
        def open_editor():
            try:
                self.d.find(re.compile("图片编辑器"), timeout=6)
            except TimeoutError:
                self.scroll_click("裁剪 / 选区")[1]()
        def outcome():
            node = self.d.find(re.compile("确认查询|已整理词条|上次请求失败"), timeout=105)
            if self.d.match(node, re.compile("上次请求失败")):
                raise RuntimeError("视觉模型调用失败")
        def confirm():
            try:
                self.d.tap("确认查询", timeout=8)
            except TimeoutError:
                if not (self.d.session().get("query_results") or {}).get("word"):
                    raise
        return [self.deselect_notebook(), self.click("查询记录"), ("进入图文查询并清除旧语境", enter_mixed_mode),
                self.click("相册选取"), ("选取固定演示图", lambda: self.pick_file("lexinote-demo.png")),
                ("打开图片编辑器", open_editor), self.click("框选"), ("框选包含 take over 的句子", frame),
                self.shot("image", "图片框选"), self.click("完成"),
                self.fill("请识别框选的英文句子，并查短语 take over。"),
                ("提交真实视觉模型请求", lambda: self.submit_query("图文+提问")),
                ("等待视觉模型结果", outcome), ("确认识别出的查询目标", confirm),
                ("等待图片查询词条显示", result_visible)]

    def deselect_notebook(self):
        def leave():
            try:
                self.d.find("查询记录", timeout=4)
            except TimeoutError:
                self.d.tap("返回")
        return "退出词条详情", leave

    def plan(self):
        def spinner():
            self.d.tap_node(self.d.find(None, class_name="android.widget.Spinner"))
        return [self.deselect_notebook(), self.click("复习"), self.scroll_click(re.compile("新建.*计划")),
                self.fill(PLAN), ("展开绑定笔记列表", spinner), self.click("阅读表达"),
                self.fill("3", 1), self.fill("10", 2),
                self.toggle("导入该笔记本中已有的复习进度", False), self.click("立即创建"),
                self.find(PLAN), self.shot("plan", "新建计划：每日 3 个新词，复习上限 10"),
                self.scroll_click("编辑"), self.fill("12", 2), self.click("保存修改"),
                self.find(re.compile("每日新词 3.*复习 12"))]

    def complete_review_round(self):
        rated_position = 0
        flipped_position = 0

        def next_action(tree):
            nodes = list(tree.iter("node"))
            if any(self.d.match(node, COMPLETE) for node in nodes):
                return None, None
            for node in nodes:
                progress = REVIEW_PROGRESS.fullmatch(node.get("text", "").strip())
                if progress:
                    position = int(progress.group(1))
                    break
            else:
                return None
            if position <= rated_position:
                return None
            rating = "困难" if position == 1 else "简单"
            for node in nodes:
                if self.d.match(node, rating) or (position != flipped_position and self.d.match(node, REVIEW_FLIP)):
                    return position, node
            return None

        while True:
            position, node = self.d.wait(next_action, "复习卡片前进或本组完成")
            if node is None:
                return
            self.d.tap_node(node)
            if self.d.match(node, REVIEW_FLIP):
                flipped_position = position
            else:
                rated_position = position

    def review(self):
        return [self.scroll_click(re.compile("开启今日复习")),
                ("等待并完成今日复习剩余卡片", self.complete_review_round),
                self.shot("review", "第 1 组完成"),
                self.scroll_click(re.compile("再来一组")), self.click(re.compile("再来一天的量")),
                self.click("开始加练"), self.find(REVIEW_PROGRESS),
                ("等待并完成加练剩余卡片", self.complete_review_round),
                self.shot("review", "第 2 组完成")]
