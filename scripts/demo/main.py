"""LexiNote demo entry point for the terminal and local console."""
import argparse
import json
import re
import secrets
import sys
import threading
import time
from contextlib import contextmanager
from datetime import datetime
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlsplit

from runner import Device, ROOT, Run, validate_serial
from scenes import Scenes, STEPS

HISTORY = ROOT / "captures/lexinote-demo"
PAGE = Path(__file__).with_name("console.html")
LOGIN_MESSAGE = "离线查询完成。请在手机上进入「我的 → 连接 TokenDance」，手动登录并授权，返回演示版后继续。"


@contextmanager
def device_lease(serial):
    HISTORY.mkdir(parents=True, exist_ok=True)
    with (HISTORY / (serial + ".lock")).open("a+b") as lockfile:
        if lockfile.tell() == 0:
            lockfile.write(b"0")
        lockfile.flush()
        lockfile.seek(0)
        try:
            if sys.platform == "win32":
                import msvcrt
                msvcrt.locking(lockfile.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(lockfile, fcntl.LOCK_EX | fcntl.LOCK_NB)
        except OSError as error:
            raise SystemExit("该设备已有演示执行器，请使用现有调试台或退出后再启动") from error
        yield


class Controller:
    def __init__(self, serial, run_id=None, physical=False, fixed_actions=None, record_actions=None):
        self.device = Device(serial, physical)
        self.fixed_actions = (json.loads(fixed_actions.read_text(encoding="utf-8"))
                              if fixed_actions is not None else None)
        self.record_actions = record_actions
        HISTORY.mkdir(parents=True, exist_ok=True)
        self.run = self.open_run(run_id) if run_id else None
        if self.run and (self.run.data["serial"] != serial
                         or self.run.data.get("physical", False) != physical):
            raise ValueError("运行记录与指定设备不匹配")
        self.lock = threading.Lock()
        self.pause = threading.Event()
        self.worker = None
        self.message = ""

    @staticmethod
    def open_run(run_id):
        if not re.fullmatch(r"[0-9]{8}-[0-9]{6}-[a-f0-9]{6}", run_id):
            raise ValueError("运行编号无效")
        return Run.load(HISTORY / run_id)

    def prepare(self, single_step=False):
        if self.fixed_actions is None:
            self.device.check()
        run_id = datetime.now().strftime("%Y%m%d-%H%M%S-") + secrets.token_hex(3)
        self.run = Run.create(HISTORY / run_id, self.device.serial, self.device.physical)
        self.run.data["next"] = -1
        self.run.save()
        self.execute(self.device.physical and not single_step)

    def pause_for_login(self):
        if self.fixed_actions is not None:
            return False
        data = self.run.data
        if not self.device.physical or data["next"] != 1 or data["offset"] != 0:
            return False
        if self.device.tokendance_connected():
            if data.pop("awaiting_login", False):
                self.run.event("bilingual", "确认手动授权完成", "passed")
            return False
        if not data.get("awaiting_login"):
            data["awaiting_login"] = True
            self.run.event("offline", "等待手动登录 TokenDance", "awaiting_login")
        self.message = LOGIN_MESSAGE
        return True

    def execute(self, auto):
        if self.fixed_actions is None:
            self.device.check()
        else:
            auto = False
        if self.run is None:
            raise RuntimeError("请先准备演示数据")
        if self.run.data.get("ended"):
            raise RuntimeError("该轮演示已结束，请准备新一轮数据")
        scenes = Scenes(self.device, self.run)
        while not self.pause.is_set():
            index = self.run.data["next"]
            if index >= len(STEPS):
                self.message = "演示已完成"
                return
            if self.pause_for_login():
                return
            name, title = ("prepare", "准备固定演示数据") if index == -1 else STEPS[index]
            self.message = title
            if self.fixed_actions is not None:
                operations = [(f"固定动作 {i}", lambda entry=entry: self.play_action(entry))
                              for i, entry in enumerate(self.fixed_actions[name], 1)]
                if name == "prepare" and self.device.physical:
                    operations.insert(0, ("清除演示版登录状态和内嵌网页缓存",
                                          lambda: self.device.command("shell", "pm", "clear",
                                                                      self.device.package)))
            else:
                operations = getattr(scenes, name)()
                if self.record_actions is not None:
                    operations = [(label, lambda action=action, label=label: self.record_action(name, label, action))
                                  for label, action in operations]
            if not self.run.step(name, operations, self.device):
                self.message = "已暂停，请查看失败操作和截图"
                return
            if not auto:
                if self.pause_for_login():
                    return
                self.message = "本步完成，请核对结果和截图"
                return
        self.message = "已在步骤边界暂停"

    def play_action(self, entry):
        self.device.command(*entry["adb"])
        time.sleep(entry["delay"])

    def record_action(self, step, label, action):
        self.device.recorded_actions = []
        started = time.monotonic()
        try:
            action()
            actions = self.device.recorded_actions
        finally:
            self.device.recorded_actions = None
        path = self.record_actions
        recording = json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}
        entries = recording.setdefault(step, [])
        entries.extend(
            {"adb": args, "delay": 0.35 if args[:2] == ["shell", "input"] else 1.5}
            for args in actions)
        if not actions and entries and label.startswith("等待"):
            entries[-1]["delay"] = max(entries[-1]["delay"], round(time.monotonic() - started, 1))
        path.write_text(json.dumps(recording, ensure_ascii=False, indent=2), encoding="utf-8")

    def act(self, action, background=False):
        if action == "pause":
            self.pause.set()
            return
        with self.lock:
            if self.worker and self.worker.is_alive():
                raise RuntimeError("操作正在执行")
            self.pause.clear()
            if action == "end":
                if not self.run:
                    raise RuntimeError("当前没有运行记录")
                self.run.data["ended"] = True
                self.run.event("preview", "结束演示", "ended")
                self.message = "演示已结束，历史已保留"
                return
            if action == "snapshot":
                if not self.run:
                    raise RuntimeError("请先准备演示数据")
                self.device.check()
                index = self.run.data["next"]
                step = STEPS[index][0] if 0 <= index < len(STEPS) else "preview"
                self.run.capture(step, "手动截图", self.device)
                return
            if action in {"skip", "retry"}:
                if not self.run or self.run.data["next"] >= len(STEPS):
                    raise RuntimeError("当前没有待执行步骤")
                data = self.run.data
                index = data["next"]
                if action == "skip" and index < 0:
                    raise RuntimeError("准备步骤不能跳过")
                if action == "skip" and data.get("awaiting_login"):
                    raise RuntimeError("请完成手动授权后再继续")
                data["inflight"] = None
                step = STEPS[index][0] if index >= 0 else "prepare"
                if action == "skip":
                    data.update(next=index+1, offset=0)
                    self.run.event(step, "手动跳过", "skipped")
                    return
                self.run.event(step, "确认重试当前操作", "retry")
            if action not in {"prepare", "prepare_step", "auto", "next", "retry"}:
                raise ValueError("未知操作")
            def task():
                try:
                    if action in {"prepare", "prepare_step"}:
                        self.prepare(single_step=action == "prepare_step")
                    else:
                        self.execute(action == "auto")
                except Exception as error:
                    self.message = str(error)
            if background:
                self.worker = threading.Thread(target=task, daemon=True)
                self.worker.start()
            else:
                task()

    def state(self):
        active = self.run.path.name if self.run else None
        runs = sorted((p.name for p in HISTORY.iterdir() if (p / "run.json").is_file()), reverse=True)
        return {"active": active, "runs": runs, "serial": self.device.serial,
                "physical": self.device.physical, "fixed": self.fixed_actions is not None,
                "awaiting_login": bool(self.run and self.run.data.get("awaiting_login")),
                "message": self.message, "busy": bool(self.worker and self.worker.is_alive()),
                "steps": [{"id": key, "name": name} for key, name in [("prepare", "准备演示数据"), *STEPS]]}


def serve(controller, port):
    class Handler(BaseHTTPRequestHandler):
        def reply(self, payload, kind="application/json; charset=utf-8", status=200):
            if not isinstance(payload, bytes):
                payload = json.dumps(payload, ensure_ascii=False).encode()
            self.send_response(status)
            self.send_header("Content-Type", kind)
            self.send_header("Content-Length", str(len(payload)))
            self.send_header("Cache-Control", "no-store")
            self.send_header("X-Content-Type-Options", "nosniff")
            self.send_header("Content-Security-Policy",
                             "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
            self.end_headers()
            self.wfile.write(payload)

        def local(self):
            return self.headers.get("Host", "") in (f"127.0.0.1:{port}", f"localhost:{port}")

        def do_GET(self):
            if not self.local():
                return self.reply({"error": "Forbidden"}, status=403)
            path = unquote(urlsplit(self.path).path)
            try:
                if path == "/":
                    return self.reply(PAGE.read_bytes(), "text/html; charset=utf-8")
                if path == "/api/state":
                    return self.reply(controller.state())
                if path.startswith("/api/runs/"):
                    _, _, _, run_id, *tail = path.split("/")
                    run = controller.open_run(run_id)
                    if not tail:
                        return self.reply(run.data)
                    if len(tail) == 1 and re.fullmatch(r"[0-9]{4}-[a-z]+\.png", tail[0]):
                        return self.reply((run.path / tail[0]).read_bytes(), "image/png")
            except (ValueError, OSError):
                pass
            self.reply({"error": "记录不存在"}, status=404)

        def do_POST(self):
            if (not self.local() or self.headers.get("X-LexiNote-Demo") != "1"
                    or self.headers.get("Content-Type") != "application/json"):
                return self.reply({"error": "Forbidden"}, status=403)
            try:
                size = int(self.headers.get("Content-Length", 0))
                if self.path != "/api/control" or not 0 < size < 1024:
                    raise ValueError("请求无效")
                payload = json.loads(self.rfile.read(size))
                action = payload["action"]
                if action not in ("prepare", "next", "retry", "snapshot", "end"):
                    raise ValueError("调试台仅支持逐步执行")
                controller.act("prepare_step" if action == "prepare" else action, background=True)
                self.reply(controller.state())
            except (ValueError, KeyError, TypeError, RuntimeError) as error:
                self.reply({"error": str(error)}, status=400)

        def log_message(self, *_args):
            pass

    server = ThreadingHTTPServer(("127.0.0.1", port), Handler)
    print(f"LexiNote 调试台：http://127.0.0.1:{port}/", flush=True)
    server.serve_forever()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--physical", action="store_true", help="在 USB 真机运行独立演示版")
    parser.add_argument("--run", help="继续已有的运行编号")
    parser.add_argument("--port", type=int, default=8765)
    parser.add_argument("--prepare", action="store_true", help="在指定演示应用中覆盖恢复固定备份")
    replay = parser.add_mutually_exclusive_group()
    replay.add_argument("--fixed-actions", type=Path, help="逐步回放已录制的 ADB 动作")
    replay.add_argument("--record-actions", type=Path, help="在调试运行中录制固定 ADB 动作")
    parser.add_argument("mode", choices=("console", "step", "auto"))
    args = parser.parse_args()
    try:
        validate_serial(args.serial, args.physical)
    except ValueError as error:
        parser.error(str(error))
    if args.run and args.prepare:
        parser.error("--run 和 --prepare 不能同时使用")
    if args.mode != "console" and not (args.run or args.prepare):
        parser.error("命令行演示需要 --prepare 或 --run")
    with device_lease(args.serial):
        run_cli(args)


def run_cli(args):
    controller = Controller(args.serial, args.run, args.physical, args.fixed_actions, args.record_actions)
    if args.prepare:
        controller.act("prepare")
        print(controller.message, flush=True)
        if controller.run:
            print(f"运行编号：{controller.run.path.name}", flush=True)
    if args.mode == "console":
        serve(controller, args.port)
        return
    if not controller.run or controller.run.data["next"] < 0:
        sys.exit(1)
    if args.mode == "auto":
        while True:
            if controller.run.data.get("awaiting_login"):
                print(LOGIN_MESSAGE)
                if not sys.stdin.isatty() or input("授权后按 Enter 继续，输入 Q 结束：").strip().lower() == "q":
                    break
            controller.act("auto")
            if not controller.run.data.get("awaiting_login"):
                break
        print(controller.message)
        sys.exit(0 if controller.run.data["next"] == len(STEPS) else 1)
    while controller.run.data["next"] < len(STEPS):
        index = controller.run.data["next"]
        command = input(f"[{index+1}/{len(STEPS)}] {STEPS[index][1]} | Enter 单步 / A 自动 / R 重试 / S 跳过 / Q 退出：").strip().lower()
        if command == "q":
            break
        action = {"": "next", "a": "auto", "r": "retry", "s": "skip"}.get(command)
        if action:
            controller.act(action)
            print(controller.message)


if __name__ == "__main__":
    main()
