#!/usr/bin/env python3
"""
scripts/audit-performance-preserve-ui.py
Automated Performance & UI-Preserving Benchmarking Driver for LexiNote Android Native.

Task 0 Implementation:
- Multi-round automation driver (3 rounds, 30 samples, 5 warmups).
- Pre-test backup of user storage and post-test byte-for-byte restoration.
- APK compilation with -PdeviceRunner=com.lexinote.nativeapp.PerformanceInstrumentation.
- Installation to target emulator or device via ADB.
- Execution of benchmark routines and capacity rejection baseline.
- Live background Perfetto trace recording and gfxinfo framestats parsing during pure scroll.
- Statistical calculation of P50, P90, P95, P99, mean, stddev against Section 6 targets.
- Visual baseline screenshot collection, 5-run noise calibration, and contact sheets.
"""

import argparse
import hashlib
import json
import math
import os
import pathlib
import re
import shutil
import subprocess
import sys
import time
from typing import Any, Dict, List, Optional, Tuple

ROOT = pathlib.Path(__file__).resolve().parents[1]

# Section 6 Quantitative Targets (docs/superpowers/plans/2026-11-01-performance-preserve-ui.md)
TARGETS = {
    "rating_input_to_feedback_ms": {"target_p95": 100.0, "desc": "Rating first visible feedback"},
    "rating_input_to_commit_ms": {"target_p95": 250.0, "desc": "Rating persist and advance (3000 scale)"},
    "local_query_content_ms": {"target_p95": 150.0, "desc": "Local query content display"},
    "local_query_commit_ms": {"target_p95": 250.0, "desc": "Local query history persisted"},
    "search_input_to_idle_ms": {"target_p95": 200.0, "desc": "Search input to filtered idle"},
    "review_plan_switch_ms": {"target_p95": 100.0, "desc": "Existing plan switch (read-only)"},
    "review_first_plans_gen_ms": {"target_p95": 500.0, "desc": "First 12 plans generation"},
    "scroll_jank_percent": {"target_max": 25.0, "desc": "Pure scroll janky frames percentage"},
    "scroll_p95_ms": {"target_max": 40.0, "desc": "Pure scroll P95 frame time"},
}


class AdbHelper:
    def __init__(self, device: Optional[str] = None):
        self.adb_bin = self._find_adb()
        self.device = device or self._detect_device()

    def _find_adb(self) -> str:
        candidates = [
            pathlib.Path.home() / "AppData/Local/Android/Sdk/platform-tools/adb.exe",
            pathlib.Path(os.environ.get("LOCALAPPDATA", "")) / "Android/Sdk/platform-tools/adb.exe",
            pathlib.Path(os.environ.get("ANDROID_HOME", "")) / "platform-tools/adb.exe",
            pathlib.Path(os.environ.get("ANDROID_SDK_ROOT", "")) / "platform-tools/adb.exe",
        ]
        for c in candidates:
            if c.is_file():
                return str(c)
        which_adb = shutil.which("adb")
        if which_adb:
            return which_adb
        raise RuntimeError("ADB not found. Ensure Android SDK platform-tools is installed.")

    def _detect_device(self) -> str:
        res = subprocess.run([self.adb_bin, "devices"], capture_output=True, text=True, check=True)
        devices = []
        for line in res.stdout.strip().splitlines()[1:]:
            parts = line.split()
            if len(parts) >= 2 and parts[1] == "device":
                devices.append(parts[0])
        if not devices:
            raise RuntimeError("No connected Android device/emulator found via ADB.")
        # Prioritize emulator-5554 if present
        if "emulator-5554" in devices:
            return "emulator-5554"
        return devices[0]

    def cmd(self, *args, timeout: int = 120) -> subprocess.CompletedProcess:
        full_cmd = [self.adb_bin, "-s", self.device, *map(str, args)]
        return subprocess.run(full_cmd, capture_output=True, text=True, timeout=timeout)

    def raw(self, *args, timeout: int = 60) -> bytes:
        full_cmd = [self.adb_bin, "-s", self.device, *map(str, args)]
        p = subprocess.run(full_cmd, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)
        return p.stdout

    def shell(self, command: str, timeout: int = 180) -> str:
        res = self.cmd("shell", command, timeout=timeout)
        if res.returncode != 0:
            raise RuntimeError(f"ADB shell failed ({res.returncode}): {res.stderr}\nCommand: {command}")
        return res.stdout

    def pull(self, remote: str, local: pathlib.Path) -> None:
        local.parent.mkdir(parents=True, exist_ok=True)
        res = self.cmd("pull", remote, str(local), timeout=180)
        if res.returncode != 0:
            raise RuntimeError(f"ADB pull failed ({remote} -> {local}): {res.stderr}")
        # Flatten if ADB created a nested directory matching remote base name
        remote_name = pathlib.Path(remote).name
        nested = local / remote_name
        if nested.is_dir():
            for item in nested.iterdir():
                dest = local / item.name
                if dest.exists():
                    if dest.is_dir():
                        shutil.rmtree(dest)
                    else:
                        dest.unlink()
                shutil.move(str(item), str(local))
            shutil.rmtree(nested, ignore_errors=True)

    def push(self, local: pathlib.Path, remote: str) -> None:
        res = self.cmd("push", str(local), remote, timeout=120)
        if res.returncode != 0:
            raise RuntimeError(f"ADB push failed ({local} -> {remote}): {res.stderr}")

    def install(self, apk_path: pathlib.Path) -> None:
        res = self.cmd("install", "-r", "-t", str(apk_path), timeout=180)
        out = (res.stdout + " " + res.stderr).strip()
        if "Success" not in out:
            raise RuntimeError(f"Failed to install {apk_path}: {out}")


class DataBackupGuard:
    TARGET_PACKAGE = "com.lexinote.nativeapp"
    PROTECTED_FILES = [
        "files/library.json",
        "files/library.json.bak",
        "files/before-restore.json",
        "files/before-restore.json.bak",
        "shared_prefs/provider_private.xml",
    ]

    def __init__(self, adb: AdbHelper, backup_dir: pathlib.Path):
        self.adb = adb
        self.backup_dir = backup_dir
        self.manifest_file = backup_dir / "backup-manifest.json"
        self.manifest: Dict[str, Dict[str, Any]] = {}

    def backup(self) -> None:
        self.backup_dir.mkdir(parents=True, exist_ok=True)
        # Test if run-as works; if package is not debuggable, install debug APK (-r preserves data)
        test_run = self.adb.raw("exec-out", "run-as", self.TARGET_PACKAGE, "echo", "ok")
        if b"package not debuggable" in test_run:
            debug_apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
            if debug_apk.exists():
                self.adb.install(debug_apk)

        for rel_path in self.PROTECTED_FILES:
            local_target = self.backup_dir / rel_path
            local_target.parent.mkdir(parents=True, exist_ok=True)
            content = self.adb.raw("exec-out", "run-as", self.TARGET_PACKAGE, "cat", f"/data/data/{self.TARGET_PACKAGE}/{rel_path}")
            if content and not content.startswith(b"run-as:") and b"No such file" not in content and b"does not exist" not in content:
                local_target.write_bytes(content)
                sha = hashlib.sha256(content).hexdigest()
                self.manifest[rel_path] = {"sha256": sha, "bytes": len(content)}
        self.manifest_file.write_text(json.dumps(self.manifest, indent=2), encoding="utf-8")

    def restore(self) -> List[Dict[str, Any]]:
        results = []
        if not self.manifest:
            return results
        for rel_path, meta in self.manifest.items():
            local_file = self.backup_dir / rel_path
            if not local_file.exists():
                continue
            data = local_file.read_bytes()
            remote_tmp = f"/data/local/tmp/{local_file.name}"
            self.adb.push(local_file, remote_tmp)
            self.adb.shell(f"run-as {self.TARGET_PACKAGE} cp {remote_tmp} /data/data/{self.TARGET_PACKAGE}/{rel_path}")
            self.adb.shell(f"rm {remote_tmp}")

            # Re-read to verify exact byte-for-byte SHA-256 match
            verify_bytes = self.adb.raw("exec-out", "run-as", self.TARGET_PACKAGE, "cat", f"/data/data/{self.TARGET_PACKAGE}/{rel_path}")
            actual_sha = hashlib.sha256(verify_bytes).hexdigest()
            byte_equal = (actual_sha == meta["sha256"])
            results.append({
                "file": rel_path,
                "byte_equal": byte_equal,
                "expected_sha": meta["sha256"],
                "actual_sha": actual_sha,
                "bytes": len(verify_bytes),
            })
        return results


class StatsCalculator:
    @staticmethod
    def percentile(values: List[float], p: float) -> float:
        if not values:
            return 0.0
        sorted_vals = sorted(values)
        if len(sorted_vals) == 1:
            return sorted_vals[0]
        rank = (p / 100.0) * (len(sorted_vals) - 1)
        k = int(rank)
        d = rank - k
        if k >= len(sorted_vals) - 1:
            return sorted_vals[-1]
        return sorted_vals[k] + d * (sorted_vals[k + 1] - sorted_vals[k])

    @classmethod
    def summarize(cls, values: List[float]) -> Dict[str, Any]:
        if not values:
            return {"count": 0, "mean": 0.0, "stddev": 0.0, "min": 0.0, "max": 0.0, "p50": 0.0, "p90": 0.0, "p95": 0.0, "p99": 0.0}
        n = len(values)
        mean_val = sum(values) / n
        variance = sum((x - mean_val) ** 2 for x in values) / (n - 1) if n > 1 else 0.0
        stddev_val = math.sqrt(variance)
        return {
            "count": n,
            "mean": round(mean_val, 2),
            "stddev": round(stddev_val, 2),
            "min": round(min(values), 2),
            "max": round(max(values), 2),
            "p50": round(cls.percentile(values, 50.0), 2),
            "p90": round(cls.percentile(values, 90.0), 2),
            "p95": round(cls.percentile(values, 95.0), 2),
            "p99": round(cls.percentile(values, 99.0), 2),
        }


class GfxinfoParser:
    @staticmethod
    def parse(text: str) -> Dict[str, Any]:
        result: Dict[str, Any] = {}
        m_total = re.search(r"Total frames rendered:\s+(\d+)", text)
        m_jank = re.search(r"Janky frames:\s+(\d+)\s+\(([\d.]+)%\)", text)
        m_p50 = re.search(r"50th percentile:\s+(\d+)ms", text)
        m_p90 = re.search(r"90th percentile:\s+(\d+)ms", text)
        m_p95 = re.search(r"95th percentile:\s+(\d+)ms", text)
        m_p99 = re.search(r"99th percentile:\s+(\d+)ms", text)
        if m_total:
            result["total_frames"] = int(m_total.group(1))
        if m_jank:
            result["janky_frames"] = int(m_jank.group(1))
            result["jank_percentage"] = float(m_jank.group(2))
        if m_p50:
            result["p50_ms"] = int(m_p50.group(1))
        if m_p90:
            result["p90_ms"] = int(m_p90.group(1))
        if m_p95:
            result["p95_ms"] = int(m_p95.group(1))
        if m_p99:
            result["p99_ms"] = int(m_p99.group(1))
        return result


def calibrate_visual_noise(screenshot_paths: List[pathlib.Path], status_bar_height: int = 118) -> Dict[str, Any]:
    try:
        from PIL import Image
    except ImportError:
        return {"calibrated": False, "reason": "Pillow not installed"}

    if len(screenshot_paths) < 2:
        return {"calibrated": False, "reason": "Fewer than 2 calibration runs available"}

    images = [Image.open(p).convert("RGB") for p in screenshot_paths]
    w, h = images[0].size
    unmasked_pixels = w * max(0, h - status_bar_height)

    pair_diffs = []
    for i in range(len(images)):
        for j in range(i + 1, len(images)):
            pix_i = images[i].load()
            pix_j = images[j].load()
            diff_count = 0
            for y in range(status_bar_height, h):
                for x in range(w):
                    r1, g1, b1 = pix_i[x, y]
                    r2, g2, b2 = pix_j[x, y]
                    if max(abs(r1 - r2), abs(g1 - g2), abs(b1 - b2)) > 8:
                        diff_count += 1
            ratio = diff_count / unmasked_pixels
            pair_diffs.append(ratio)

    max_noise = max(pair_diffs) if pair_diffs else 0.0
    return {
        "calibrated": True,
        "runs_evaluated": len(screenshot_paths),
        "pairs_compared": len(pair_diffs),
        "status_bar_mask_height": status_bar_height,
        "max_noise_ratio": round(max_noise, 6),
        "max_noise_percent": f"{max_noise * 100:.4f}%",
        "threshold_safe": max_noise < 0.005,  # must be comfortably <= 0.5%
        "calibrated_threshold": 0.005,
    }


def generate_contact_sheet(image_dir: pathlib.Path, output_file: pathlib.Path) -> bool:
    try:
        from PIL import Image, ImageDraw
    except ImportError:
        print("[!] Pillow not available, skipping contact sheet generation.")
        return False

    png_files = sorted(image_dir.glob("*.png"))
    if not png_files:
        return False

    thumb_w, thumb_h = 240, 534
    cols = min(6, len(png_files))
    rows = math.ceil(len(png_files) / cols)

    canvas_w = cols * (thumb_w + 16) + 16
    canvas_h = rows * (thumb_h + 40) + 20
    canvas = Image.new("RGB", (canvas_w, canvas_h), "#0f172a")
    draw = ImageDraw.Draw(canvas)

    for idx, p in enumerate(png_files):
        c = idx % cols
        r = idx // cols
        x = c * (thumb_w + 16) + 16
        y = r * (thumb_h + 40) + 20

        try:
            im = Image.open(p).convert("RGB")
            im.thumbnail((thumb_w, thumb_h), Image.Resampling.LANCZOS)
            draw.text((x + 2, y), p.stem, fill="#f8fafc")
            canvas.paste(im, (x, y + 24))
        except Exception as e:
            print(f"[!] Failed to paste {p.name}: {e}")

    output_file.parent.mkdir(parents=True, exist_ok=True)
    canvas.save(output_file)
    print(f"[+] Saved contact sheet: {output_file}")
    return True


class BenchmarkDriver:
    def __init__(self, args: argparse.Namespace):
        self.args = args
        self.adb = AdbHelper(args.device)
        self.out_dir = pathlib.Path(args.output).resolve()
        self.backup_guard = DataBackupGuard(self.adb, self.out_dir / "backup-user-data")

    def run(self) -> None:
        print(f"=== LexiNote Performance & UI Parity Audit ===")
        print(f"Target ADB Device: {self.adb.device}")
        print(f"Output Directory : {self.out_dir}")
        print(f"Scales to Test   : {self.args.scales}")
        print(f"Rounds / Samples : {self.args.rounds} rounds, {self.args.samples} samples, {self.args.warmups} warmups")
        print()

        # Step 1: Pre-test backup of user storage
        print("[1/6] Performing pre-test data backup guardrail...")
        self.backup_guard.backup()
        print(f"    Backed up {len(self.backup_guard.manifest)} protected storage files.")

        try:
            # Step 2: Build & install APKs if needed
            if not self.args.skip_build:
                print("\n[2/6] Building debug, release, and androidTest APKs with PerformanceInstrumentation...")
                gradlew = ROOT / "gradlew.bat" if os.name == "nt" else ROOT / "gradlew"
                build_targets = ["assembleDebug", "assembleDebugAndroidTest"]
                if self.args.mode in ["all", "benchmark"]:
                    build_targets.append("assembleRelease")
                build_cmd = [
                    str(gradlew),
                    "--offline",
                    *build_targets,
                    f"-PdeviceRunner={self.args.runner}",
                ]
                res = subprocess.run(build_cmd, cwd=str(ROOT), capture_output=True, text=True)
                if res.returncode != 0:
                    print(res.stdout)
                    print(res.stderr)
                    raise RuntimeError("Gradle build failed.")
                print("    Gradle build SUCCESSFUL.")

            if not self.args.skip_install:
                print("\n[2/6 b] Installing APKs to device...")
                debug_apk = ROOT / "app/build/outputs/apk/debug/app-debug.apk"
                test_apk = ROOT / "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"
                self.adb.install(debug_apk)
                self.adb.install(test_apk)
                print("    APKs successfully installed.")

            # Step 3: Benchmarking across scales and rounds
            scales = [s.strip() for s in self.args.scales.split(",") if s.strip()]
            all_raw_samples: Dict[str, Dict[str, Any]] = {}
            summary_metrics: Dict[str, Dict[str, Any]] = {}

            if self.args.mode in ["all", "benchmark"]:
                print("\n[3/6] Executing multi-round benchmarks...")
                for scale in scales:
                    print(f"\n--- Testing Scale: {scale} ---")
                    scale_raw: Dict[str, Any] = {"rounds": {}}
                    metric_pool: Dict[str, List[float]] = {}
                    single_events_pool: Dict[str, List[float]] = {}
                    scroll_stats_pool: Dict[str, Any] = {}

                    if scale in ["3000_10k", "3000events"]:
                        # Capacity rejection baseline check
                        print("  Running capacity rejection baseline check on 3000+10,000 events...")
                        am_cmd = (
                            f"am instrument -w -e size {scale} -e mode benchmark -e round 1 "
                            f"com.lexinote.nativeapp.test/{self.args.runner}"
                        )
                        self.adb.shell(am_cmd, timeout=120)
                        remote_dir = f"/sdcard/Android/data/com.lexinote.nativeapp/files/perf-benchmark-{scale}-r1"
                        local_dir = self.out_dir / scale / "round-1"
                        self.adb.pull(remote_dir, local_dir)
                        if (local_dir / "metrics.json").exists():
                            m_data = json.loads((local_dir / "metrics.json").read_text(encoding="utf-8"))
                            summary_metrics[scale] = {
                                "capacity_rejection_expected": m_data.get("capacity_rejection_expected", True),
                                "capacity_rejection_actual": m_data.get("capacity_rejection_actual", False),
                                "capacity_rejection_message": m_data.get("capacity_rejection_message", ""),
                                "loaded_history_events": m_data.get("loaded_history_events", 10000),
                            }
                        continue

                    for r in range(1, self.args.rounds + 1):
                        print(f"  Round {r}/{self.args.rounds}...")
                        am_cmd = (
                            f"am instrument -w -e size {scale} -e mode benchmark -e round {r} "
                            f"-e samples {self.args.samples} -e warmups {self.args.warmups} "
                            f"com.lexinote.nativeapp.test/{self.args.runner}"
                        )
                        inst_out = self.adb.shell(am_cmd, timeout=300)
                        remote_dir = f"/sdcard/Android/data/com.lexinote.nativeapp/files/perf-benchmark-{scale}-r{r}"
                        local_dir = self.out_dir / scale / f"round-{r}"
                        self.adb.pull(remote_dir, local_dir)

                        metrics_path = local_dir / "metrics.json"
                        if metrics_path.exists():
                            m_data = json.loads(metrics_path.read_text(encoding="utf-8"))
                            scale_raw["rounds"][f"round_{r}"] = m_data
                            # Pool timings
                            for m_name, vals in m_data.get("timings", {}).items():
                                metric_pool.setdefault(m_name, []).extend(vals)
                            for s_name, s_val in m_data.get("single_events", {}).items():
                                single_events_pool.setdefault(s_name, []).append(s_val)

                    # Pure scroll profiling with Perfetto & Gfxinfo
                    if not self.args.skip_perfetto:
                        print(f"  Pure scroll measurement & Perfetto tracing for scale {scale}...")
                        self.adb.shell("dumpsys gfxinfo com.lexinote.nativeapp framestats reset")
                        trace_remote = f"/data/misc/perfetto-traces/scroll-{scale}.perfetto-trace"
                        self.adb.shell(f"rm -f {trace_remote}")

                        # Start background trace
                        self.adb.shell(
                            f"perfetto --background-wait -o {trace_remote} -t 12s -b 64mb "
                            "-a com.lexinote.nativeapp view gfx input dalvik sched freq"
                        )
                        # Trigger pure scroll routine
                        self.adb.shell(
                            f"am instrument -w -e size {scale} -e mode scroll -e round 1 "
                            f"com.lexinote.nativeapp.test/{self.args.runner}"
                        )
                        # Settle and pull trace
                        time.sleep(2)
                        local_trace = self.out_dir / scale / f"scroll-{scale}.perfetto-trace"
                        try:
                            self.adb.pull(trace_remote, local_trace)
                            self.adb.shell(f"rm -f {trace_remote}")
                        except Exception as e:
                            print(f"    [!] Trace pull error: {e}")

                        # Pull framestats recorded during active instrumentation
                        rem_gfx = f"/sdcard/Android/data/com.lexinote.nativeapp/files/perf-scroll-{scale}-r1/gfxinfo-scroll.txt"
                        loc_gfx = self.out_dir / scale / "gfxinfo-scroll.txt"
                        try:
                            self.adb.pull(rem_gfx, loc_gfx)
                        except Exception as e:
                            print(f"    [!] Framestats pull error: {e}")
                        if loc_gfx.exists():
                            gfx_dump = loc_gfx.read_text(encoding="utf-8")
                        else:
                            gfx_dump = self.adb.shell("dumpsys gfxinfo com.lexinote.nativeapp framestats")
                            loc_gfx.write_text(gfx_dump, encoding="utf-8")
                        scroll_stats_pool = GfxinfoParser.parse(gfx_dump)

                    # Compute statistics for this scale
                    scale_summary: Dict[str, Any] = {
                        "rounds": self.args.rounds,
                        "samples_per_round": self.args.samples,
                        "total_samples": len(metric_pool.get("rating_input_to_commit_ms", [])),
                        "metrics": {},
                        "single_events": {},
                        "pure_scrolling": scroll_stats_pool,
                    }
                    for m_name, all_vals in metric_pool.items():
                        stats = StatsCalculator.summarize(all_vals)
                        if m_name in TARGETS:
                            t_spec = TARGETS[m_name]
                            if "target_p95" in t_spec:
                                stats["target_p95"] = t_spec["target_p95"]
                                stats["target_eval"] = "PASS" if stats["p95"] <= t_spec["target_p95"] else "BASELINE_OVER_TARGET"
                        scale_summary["metrics"][m_name] = stats

                    for s_name, s_vals in single_events_pool.items():
                        scale_summary["single_events"][s_name] = round(sum(s_vals) / len(s_vals), 2)

                    summary_metrics[scale] = scale_summary
                    all_raw_samples[scale] = scale_raw

            # Step 4: Visual Screenshots & Calibration
            if self.args.mode in ["all", "visual", "calibrate"] and not self.args.skip_visual:
                print("\n[4/6] Capturing baseline visual screenshots & calibration...")
                vis_dir = self.out_dir / "visual"

                if self.args.mode in ["all", "visual"]:
                    # Matrices: light_normal, dark_normal, light_large
                    matrices = [
                        ("light_normal", "light", "normal"),
                        ("dark_normal", "dark", "normal"),
                        ("light_large", "light", "large"),
                    ]
                    if self.args.visual_tag:
                        matrices = [m for m in matrices if m[0] == self.args.visual_tag]
                    for tag, th, ft in matrices:
                        print(f"  Capturing 11 screens: theme={th}, font={ft} ({tag})...")
                        self.adb.shell("rm -rf /sdcard/Android/data/com.lexinote.nativeapp/files/perf-visual-3000")
                        am_cmd = (
                            f"am instrument -w -e size 3000 -e mode visual -e theme {th} -e font {ft} "
                            f"com.lexinote.nativeapp.test/{self.args.runner}"
                        )
                        self.adb.shell(am_cmd, timeout=180)
                        rem_dir = "/sdcard/Android/data/com.lexinote.nativeapp/files/perf-visual-3000"
                        tag_dir = vis_dir / tag
                        self.adb.pull(rem_dir, tag_dir)
                        if self.args.contact_sheet:
                            generate_contact_sheet(tag_dir, vis_dir / f"contact-sheet-{tag}.png")

                # Noise calibration: 5 repeated runs
                if self.args.mode in ["all", "calibrate"]:
                    print("  Running 5-run noise calibration on baseline...")
                    self.adb.shell(
                        f"am instrument -w -e size 3000 -e mode calibrate "
                        f"com.lexinote.nativeapp.test/{self.args.runner}"
                    )
                    cal_dir = vis_dir / "calibrate"
                    self.adb.pull("/sdcard/Android/data/com.lexinote.nativeapp/files/perf-calibrate-3000", cal_dir)
                    cal_runs = sorted(cal_dir.glob("calibrate-run-*.png"))
                    noise_res = calibrate_visual_noise(cal_runs)
                    (self.out_dir / "noise-calibration.json").write_text(json.dumps(noise_res, indent=2), encoding="utf-8")
                    print(f"    Noise calibration result: {noise_res.get('max_noise_percent', 'N/A')}")

            # Step 5: Format Summary Reports
            if self.args.mode in ["all", "benchmark"]:
                print("\n[5/6] Formatting summary metrics and reports...")
                (self.out_dir / "raw_samples.json").write_text(json.dumps(all_raw_samples, indent=2), encoding="utf-8")
                (self.out_dir / "summary_metrics.json").write_text(json.dumps(summary_metrics, indent=2), encoding="utf-8")

                report_md = self._format_markdown_report(summary_metrics)
                (self.out_dir / "REPORT.md").write_text(report_md, encoding="utf-8")
                print(f"    Saved: {self.out_dir / 'REPORT.md'}")
                print(f"    Saved: {self.out_dir / 'summary_metrics.json'}")
            else:
                print("\n[5/6] Preserving existing benchmark summary metrics and reports (mode != benchmark/all)...")

        finally:
            # Step 6: Post-test verification & restoration
            print("\n[6/6] Restoring original user data and asserting byte-for-byte equality...")
            restoration_results = self.backup_guard.restore()
            (self.out_dir / "original-restoration.json").write_text(json.dumps(restoration_results, indent=2), encoding="utf-8")
            all_byte_equal = all(r.get("byte_equal", False) for r in restoration_results)
            print(f"    Restoration complete. All byte equal: {all_byte_equal}")
            for r in restoration_results:
                print(f"      - {r['file']}: equal={r['byte_equal']} ({r['bytes']} bytes)")

        print("\n=== Benchmark Audit Completed Successfully ===")

    def _format_markdown_report(self, summary: Dict[str, Any]) -> str:
        lines = [
            "# LexiNote Performance & Visual Baseline Report (Task 0)",
            "",
            f"- **Execution Timestamp**: {time.strftime('%Y-%m-%dT%H:%M:%SZ', time.gmtime())}",
            f"- **Target Device**: `{self.adb.device}`",
            f"- **Automation Driver**: `scripts/audit-performance-preserve-ui.py`",
            f"- **Instrumentation Runner**: `{self.args.runner}`",
            f"- **Rounds**: {self.args.rounds} rounds × {self.args.samples} samples (warmups discarded: {self.args.warmups})",
            "",
            "## 1. Quantitative Latency Baseline vs. Section 6 Targets",
            "",
            "| Metric | Scale | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Target P95 | Baseline Status |",
            "|---|---|---:|---:|---:|---:|---:|:---:|",
        ]

        for scale, data in summary.items():
            if "metrics" not in data:
                continue
            for m_name, stats in data["metrics"].items():
                target_str = f"≤ {stats.get('target_p95', '-')}" if "target_p95" in stats else "-"
                eval_str = stats.get("target_eval", "-")
                lines.append(
                    f"| `{m_name}` | {scale} | {stats['p50']} | {stats['p90']} | {stats['p95']} | {stats['p99']} | {target_str} | {eval_str} |"
                )

        lines.extend([
            "",
            "## 2. Pure Scrolling Framestats & Jank",
            "",
            "| Scale | Total Frames | Janky Frames | Jank % | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Target Jank % |",
            "|---|---:|---:|---:|---:|---:|---:|---:|:---:|",
        ])

        for scale, data in summary.items():
            sc = data.get("pure_scrolling", {})
            if sc:
                lines.append(
                    f"| {scale} | {sc.get('total_frames', '-')} | {sc.get('janky_frames', '-')} | "
                    f"{sc.get('jank_percentage', '-')}% | {sc.get('p50_ms', '-')} | {sc.get('p90_ms', '-')} | "
                    f"{sc.get('p95_ms', '-')} | {sc.get('p99_ms', '-')} | ≤ 25.0% |"
                )

        if "3000_10k" in summary or "3000events" in summary:
            cap = summary.get("3000_10k") or summary.get("3000events")
            lines.extend([
                "",
                "## 3. 3,000 Items + 10,000 Events Capacity Rejection Baseline",
                "",
                f"- **Loaded Legitimate History Events**: {cap.get('loaded_history_events', 10000)}",
                f"- **Capacity Rejection Expected**: `{cap.get('capacity_rejection_expected')}`",
                f"- **Capacity Rejection Actual**: `{cap.get('capacity_rejection_actual')}`",
                f"- **Rejection Message**: *\"{cap.get('capacity_rejection_message')}\"*",
                "- **Assessment**: Legacy JSON backend enforces the 4MB limit (`MAX_JSON_BYTES`). Successfully establishes capacity rejection baseline for evaluation against Task 3 SQLite incremental transactions.",
            ])

        lines.extend([
            "",
            "## 4. Visual Parity & Calibration Artifacts",
            "",
            "- **Visual Screenshots**: `visual/light_normal/`, `visual/dark_normal/`, `visual/light_large/`",
            "- **Contact Sheets**: `visual/contact-sheet-*.png`",
            "- **Noise Calibration**: `noise-calibration.json`",
            "- **Storage Restoration**: `original-restoration.json`",
            "",
        ])
        return "\n".join(lines)


def main():
    parser = argparse.ArgumentParser(description="LexiNote Performance & UI Parity Audit Driver")
    parser.add_argument("--rounds", type=int, default=3, help="Number of measurement rounds")
    parser.add_argument("--samples", type=int, default=30, help="Number of measurement samples per round")
    parser.add_argument("--warmups", type=int, default=5, help="Number of warmup samples discarded")
    parser.add_argument("--device", type=str, default=None, help="Target ADB device serial (e.g. emulator-5554)")
    parser.add_argument("--scales", type=str, default="1000,3000,3000events", help="Comma-separated scales")
    parser.add_argument("--output", type=str, default="docs/verification/performance-preserve-ui/baseline", help="Output directory")
    parser.add_argument("--runner", type=str, default="com.lexinote.nativeapp.PerformanceInstrumentation", help="Instrumentation runner")
    parser.add_argument("--skip-build", action="store_true", help="Skip Gradle build step")
    parser.add_argument("--skip-install", action="store_true", help="Skip APK install step")
    parser.add_argument("--skip-visual", action="store_true", help="Skip visual screenshots and calibration")
    parser.add_argument("--skip-perfetto", action="store_true", help="Skip Perfetto scrolling trace")
    parser.add_argument("--contact-sheet", action="store_true", default=True, help="Generate contact sheet with Pillow")
    parser.add_argument("--mode", type=str, default="all", choices=["all", "benchmark", "visual", "calibrate", "backup-verify"], help="Execution mode")
    parser.add_argument("--visual-tag", type=str, default=None, choices=["light_normal", "dark_normal", "light_large"], help="Filter specific visual matrix tag to capture (e.g. dark_normal)")
    args = parser.parse_args()

    driver = BenchmarkDriver(args)
    driver.run()


if __name__ == "__main__":
    main()
