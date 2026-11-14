"""ADB device driver and durable checkpoints shared by the demo front ends."""
import base64
import json
import os
import re
import shutil
import sqlite3
import subprocess
import time
import xml.etree.ElementTree as ET
from datetime import datetime
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
PACKAGE = "com.lexinote.nativeapp"
DEMO_PACKAGE = PACKAGE + ".demo"
FIXTURE = ROOT / "docs/fixtures/normal-usage/lexinote-normal-usage-20261028.zip"


def stamp():
    return datetime.now().astimezone().isoformat(timespec="seconds")


def validate_serial(serial, physical=False):
    if physical:
        if (not re.fullmatch(r"[A-Za-z0-9._-]+", serial or "")
                or serial.startswith("emulator-")):
            raise ValueError("真机模式需要显式指定通过 USB 连接的真机序列号")
        return serial
    if not re.fullmatch(r"emulator-[0-9]+", serial or ""):
        raise ValueError("请显式指定已连接的 Android 模拟器，例如 emulator-5554")
    return serial


def adb_path():
    for root in (os.getenv("ANDROID_SDK_ROOT"), os.getenv("ANDROID_HOME"),
                 Path(os.getenv("LOCALAPPDATA", "")) / "Android/Sdk"):
        if root:
            for name in ("adb.exe", "adb"):
                candidate = Path(root) / "platform-tools" / name
                if candidate.is_file():
                    return str(candidate)
    return shutil.which("adb") or "adb"


class Device:
    def __init__(self, serial, physical=False):
        self.serial = validate_serial(serial, physical)
        self.physical = physical
        self.package = DEMO_PACKAGE if physical else PACKAGE
        self.adb = adb_path()
        self.recorded_actions = None

    def command(self, *args, binary=False, timeout=15):
        result = subprocess.run([self.adb, "-s", self.serial, *map(str, args)],
                                capture_output=True, timeout=timeout)
        if result.returncode:
            raise RuntimeError(result.stderr.decode("utf-8", "replace").strip()
                               or result.stdout.decode("utf-8", "replace").strip())
        if self.recorded_actions is not None and (
                args[:1] == ("push",) or
                args[:2] in {("shell", "input"), ("shell", "touch")} or
                args[:3] in {("shell", "am", "start"), ("shell", "am", "force-stop"),
                             ("shell", "am", "broadcast"), ("shell", "pm", "clear")}):
            self.recorded_actions.append([str(arg) for arg in args])
        return result.stdout if binary else result.stdout.decode("utf-8", "replace").strip()

    def check(self):
        if self.command("get-state") != "device":
            raise RuntimeError("指定的 Android 设备尚未连接或授权")
        emulator = self.command("shell", "getprop", "ro.kernel.qemu") == "1"
        if emulator == self.physical:
            raise RuntimeError("设备类型与所选模式不匹配")
        if self.physical and not self.command("shell", "pm", "path", self.package).startswith("package:"):
            raise RuntimeError("请先在指定手机安装独立的 LexiNote 演示版")

    def launch(self):
        self.check()
        self.command("shell", "am", "force-stop", self.package)
        self.command("shell", "am", "start", "-W", "-n", f"{self.package}/{PACKAGE}.MainActivity")

    def reset_physical_demo(self):
        if not self.physical or self.package != DEMO_PACKAGE:
            raise RuntimeError("仅允许重置真机上的独立演示版")
        self.check()
        if self.command("shell", "pm", "clear", DEMO_PACKAGE) != "Success":
            raise RuntimeError("独立演示版未能清除登录状态和网页缓存")

    def tokendance_connected(self):
        result = self.command("shell", "am", "broadcast",
                              "-n", f"{self.package}/{PACKAGE}.DemoInputReceiver",
                              "-a", f"{PACKAGE}.DEMO_TOKENDANCE_STATUS")
        return "result=-1" in result

    def tree(self):
        path = "/data/local/tmp/lexinote-demo-ui.xml"
        outcome = self.command("shell", "uiautomator", "dump", path)
        if "dumped" not in outcome:
            raise TimeoutError("当前界面不可读取")
        return ET.fromstring(self.command("exec-out", "cat", path, binary=True))

    @staticmethod
    def match(node, label):
        values = [node.get(k, "").strip() for k in ("text", "content-desc", "resource-id")]
        if hasattr(label, "search"):
            return any(label.search(value) for value in values)
        return label in values

    @staticmethod
    def bounds(node):
        return list(map(int, re.findall(r"-?\d+", node.get("bounds", ""))))

    def wait(self, predicate, description, timeout=24):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            try:
                found = predicate(self.tree())
                if found is not None and found is not False:
                    return found
            except (ET.ParseError, TimeoutError, subprocess.TimeoutExpired):
                pass
            time.sleep(.3)
        raise TimeoutError(f"等待超时：{description}")

    def find(self, label, index=0, timeout=24, class_name=None):
        def locate(tree):
            nodes = [node for node in tree.iter("node")
                     if (label is None or self.match(node, label))
                     and (node.get("class") == class_name if class_name is not None
                          else node.get("class") not in ("android.widget.EditText",
                                                        "android.widget.AutoCompleteTextView"))]
            return nodes[index] if nodes and -len(nodes) <= index < len(nodes) else None
        return self.wait(locate, str(label or class_name), timeout)

    def tap_node(self, node):
        x1, y1, x2, y2 = self.bounds(node)
        self.command("shell", "input", "tap", (x1+x2)//2, (y1+y2)//2)

    def tap(self, label, index=0, timeout=24):
        self.tap_node(self.find(label, index, timeout))

    def back(self):
        self.command("shell", "input", "keyevent", "KEYCODE_BACK")

    def scroll(self, up=False):
        size = re.findall(r"(\d+)x(\d+)", self.command("shell", "wm", "size"))[-1]
        w, h = map(int, size)
        a, b = (38, 70) if up else (70, 38)
        self.command("shell", "input", "swipe", w//2, h*a//100, w//2, h*b//100, 430)

    def scroll_to(self, label, attempts=5):
        for i in range(attempts):
            try:
                # Read each intermediate viewport once. Give the final
                # viewport time to settle before reporting a missing control.
                return self.find(label, timeout=4 if i + 1 == attempts else 1)
            except TimeoutError:
                if i + 1 < attempts:
                    self.scroll()
        raise TimeoutError(f"滚动后仍找不到：{label}")

    def fill(self, value, label=None, index=0, class_name="android.widget.EditText"):
        node = self.find(label, index, class_name=class_name)
        if re.fullmatch(r"[a-zA-Z0-9 .,-]*", value):
            x1, y1, x2, y2 = self.bounds(node)
            edit = "input text " + value.replace(" ", "%s") if value else "input keyevent KEYCODE_DEL"
            self.command("shell", f"input tap {(x1+x2)//2} {(y1+y2)//2} && "
                         f"input keycombination 113 29 && {edit}")
        else:
            self.tap_node(node)
            self.command("shell", "input", "keycombination", "113", "29")
            payload = base64.b64encode(value.encode()).decode()
            result = self.command("shell", "am", "broadcast", "-n", f"{self.package}/{PACKAGE}.DemoInputReceiver",
                                  "-a", f"{PACKAGE}.DEMO_INPUT", "--es", "text64", payload)
            if "result=-1" not in result:
                raise RuntimeError("无法输入中文，请安装包含演示输入组件的 Debug APK")
            self.command("shell", "input", "keyevent", "KEYCODE_PASTE")
        if not self.physical:
            ime = self.command("shell", "dumpsys", "input_method")
            if re.search(r"(?m)^\s*mInputShown=true\b", ime):
                self.back()

    def screenshot(self, path):
        image = self.command("exec-out", "screencap", "-p", binary=True)
        if not image.startswith(b"\x89PNG\r\n\x1a\n"):
            raise RuntimeError("模拟器截图不是 PNG")
        path.write_bytes(image)

    def session(self, word=None):
        raw = self.command("exec-out", "run-as", self.package, "cat", "databases/library.db", binary=True)
        with sqlite3.connect(":memory:") as db:
            db.deserialize(raw)
            condition = " AND (json_extract(json,'$.input_text')=? OR json_extract(json,'$.query_results.word')=?)" if word else ""
            row = db.execute("SELECT json FROM entities WHERE kind='conversations'"
                             " AND generation=(SELECT max(generation) FROM entities)"
                             + condition + " ORDER BY json_extract(json,'$.updated_at') DESC, ordinal DESC LIMIT 1",
                             (word, word) if word else ()).fetchone()
            return json.loads(row[0]) if row else {}


class Run:
    def __init__(self, path, data):
        self.path, self.data = path, data

    @classmethod
    def create(cls, path, serial, physical=False):
        validate_serial(serial, physical)
        path.mkdir(parents=True)
        run = cls(path, {"serial": serial, "physical": physical, "created": stamp(), "next": 0,
                         "offset": 0, "history": [], "inflight": None})
        run.save()
        return run

    @classmethod
    def load(cls, path):
        return cls(path, json.loads((path / "run.json").read_text(encoding="utf-8")))

    def save(self):
        temp = self.path / "run.tmp"
        temp.write_text(json.dumps(self.data, ensure_ascii=False, indent=2), encoding="utf-8")
        for attempt in range(3):
            try:
                temp.replace(self.path / "run.json")
                return
            except PermissionError as error:
                # On Windows, a console reading the current history can briefly
                # prevent replacing its file. Keep the last complete snapshot.
                if getattr(error, "winerror", None) not in (5, 32) or attempt == 2:
                    raise
                time.sleep(.08)

    def event(self, step, label, status, **details):
        self.data["history"].append(dict(at=stamp(), step=step, operation=label, status=status, **details))
        self.save()

    def capture(self, step, label, device, status="screenshot"):
        name = f"{len(self.data['history']):04d}-{step}.png"
        try:
            device.screenshot(self.path / name)
        except Exception as error:
            self.event(step, label, "capture_failed", error=str(error))
            return
        self.event(step, label, status, image=name)

    def step(self, step, operations, device=None):
        inflight = self.data["inflight"]
        if inflight:
            index = self.data["offset"]
            if (inflight != {"step": step, "index": index} or index >= len(operations)
                    or not operations[index][0].startswith("等待")):
                raise RuntimeError("上次在操作中断开，请确认模拟器状态后选择重试或跳过")
        for index in range(self.data["offset"], len(operations)):
            label, action = operations[index]
            self.data["inflight"] = {"step": step, "index": index}
            self.event(step, label, "running")
            started = time.monotonic()
            try:
                action()
            except Exception as error:
                self.data["inflight"] = None
                # A wait can resume; a failed mutation has an uncertain outcome.
                if not label.startswith("等待"):
                    self.data["inflight"] = {"step": step, "index": index}
                self.event(step, label, "failed", error=str(error),
                           seconds=round(time.monotonic() - started, 1))
                if device:
                    self.capture(step, "失败现场", device)
                return False
            self.data["offset"] = index + 1
            self.data["inflight"] = None
            self.event(step, label, "passed", seconds=round(time.monotonic() - started, 1))
        if device:
            self.capture(step, "步骤完成", device)
        self.data["next"] += 1
        self.data["offset"] = 0
        self.event(step, "步骤完成", "passed")
        return True
