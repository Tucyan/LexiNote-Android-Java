import tempfile
import unittest
import xml.etree.ElementTree as ET
from pathlib import Path
from unittest.mock import patch
from main import Controller

from runner import Device, Run, validate_serial
from scenes import Scenes


class RunnerTest(unittest.TestCase):
    def test_review_finishes_remaining_cards_from_current_position(self):
        class ReviewDevice(Device):
            position = 4
            revealed = True
            ratings = []

            def tree(self):
                if self.position == 7:
                    return ET.fromstring('<root><node text="太棒了！今日任务全部完成"/></root>')
                button = "简单" if self.revealed else "查看答案 / 翻转卡片"
                return ET.fromstring(f'<root><node text="今日第 {self.position} / 6 词"/>'
                                     f'<node text="{button}"/></root>')

            def tap_node(self, node):
                if node.get("text") == "查看答案 / 翻转卡片":
                    self.revealed = True
                else:
                    self.ratings.append((self.position, node.get("text")))
                    self.position += 1
                    self.revealed = False

        device = ReviewDevice("emulator-5554")
        Scenes(device, None).complete_review_round()
        self.assertEqual(device.ratings, [(4, "简单"), (5, "简单"), (6, "简单")])

    def test_physical_demo_waits_for_manual_login_before_any_ai_step(self):
        with tempfile.TemporaryDirectory() as tmp:
            controller = Controller("f0a9da3d", physical=True)
            controller.run = Run.create(Path(tmp) / "run", controller.device.serial, physical=True)
            controller.run.data["next"] = 1
            controller.device.check = lambda: None
            controller.device.tokendance_connected = lambda: False
            with patch("main.Scenes.bilingual", side_effect=AssertionError("AI step must not start")):
                controller.execute(auto=True)
            self.assertEqual(controller.run.data["next"], 1)
            self.assertTrue(controller.state()["awaiting_login"])

    def test_rejects_physical_or_unspecified_device(self):
        for value in ("", "f0a9da3d", "192.0.2.1:5555", "emulator-5554; whoami"):
            with self.assertRaises(ValueError):
                validate_serial(value)
        self.assertEqual(validate_serial("emulator-5554"), "emulator-5554")

    def test_physical_mode_targets_isolated_package_and_rejects_emulator(self):
        device = Device("f0a9da3d", physical=True)
        self.assertEqual(device.package, "com.lexinote.nativeapp.demo")
        with self.assertRaises(ValueError):
            Device("emulator-5554", physical=True)
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "run"
            Run.create(path, device.serial, physical=True)
            self.assertTrue(Run.load(path).data["physical"])

    def test_history_save_retries_a_transient_windows_share_error(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "run"
            run = Run.create(path, "emulator-5554")
            original = Path.replace
            locked = PermissionError("file is being read")
            locked.winerror = 5
            attempts = 0

            def replace(source, target):
                nonlocal attempts
                attempts += 1
                if attempts == 1:
                    raise locked
                return original(source, target)

            with patch.object(Path, "replace", replace):
                run.event("prepare", "完成", "passed")
            self.assertEqual(attempts, 2)
            self.assertEqual(Run.load(path).data["history"][-1]["status"], "passed")

    def test_wait_retry_does_not_resubmit(self):
        actions = []
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "run"
            run = Run.create(path, "emulator-5554")
            operations = [("提交请求", lambda: actions.append("submit")),
                          ("等待结果", lambda: (_ for _ in ()).throw(TimeoutError("waiting")))]
            self.assertFalse(run.step("ai", operations))
            run = Run.load(path)
            operations[1] = ("等待结果", lambda: actions.append("finish"))
            self.assertTrue(run.step("ai", operations))
            self.assertEqual(actions, ["submit", "finish"])
            self.assertEqual(run.data["history"][-1]["status"], "passed")

    def test_interrupted_inflight_action_requires_explicit_retry(self):
        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "run"
            run = Run.create(path, "emulator-5554")
            run.data["inflight"] = {"step": "ai", "index": 0}
            run.save()
            run = Run.load(path)
            with self.assertRaises(RuntimeError):
                run.step("ai", [("提交请求", lambda: self.fail("must not resubmit"))])

    def test_reopen_interrupted_wait_and_keep_historical_screenshot(self):
        class DemoDevice:
            def screenshot(self, path):
                path.write_bytes(b"demo-png")

        with tempfile.TemporaryDirectory() as tmp:
            path = Path(tmp) / "run"
            run = Run.create(path, "emulator-5554")
            run.data["inflight"] = {"step": "ai", "index": 1}
            run.data["offset"] = 1
            run.save()
            run = Run.load(path)
            operations = [("提交请求", lambda: self.fail("must not resubmit")), ("等待结果", lambda: None)]
            self.assertTrue(run.step("ai", operations, DemoDevice()))
            history = Run.load(path).data["history"]
            capture = next(event for event in history if "image" in event)
            self.assertEqual((path / capture["image"]).read_bytes(), b"demo-png")
            self.assertEqual([e["operation"] for e in history if e["status"] == "running"], ["等待结果"])

    def test_ui_label_does_not_match_editable_search_text(self):
        device = Device("emulator-5554")
        device.tree = lambda: ET.fromstring(
            '<root><node text="sample" class="android.widget.AutoCompleteTextView"/>'
            '<node text="sample" class="android.widget.Button"/></root>')
        self.assertEqual(device.find("sample").get("class"), "android.widget.Button")


if __name__ == "__main__":
    unittest.main()
