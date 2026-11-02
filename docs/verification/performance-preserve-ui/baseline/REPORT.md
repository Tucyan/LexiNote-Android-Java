# LexiNote Performance & Visual Baseline Report (Task 0)

- **Execution Timestamp**: 2026-11-01T12:17:43Z
- **Target Device**: `emulator-5554`
- **Automation Driver**: `scripts/audit-performance-preserve-ui.py`
- **Instrumentation Runner**: `com.lexinote.nativeapp.PerformanceInstrumentation`
- **Rounds**: 3 rounds × 30 samples (warmups discarded: 5)

## 1. Quantitative Latency Baseline vs. Section 6 Targets

| Metric | Scale | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Target P95 | Baseline Status |
|---|---|---:|---:|---:|---:|---:|:---:|
| `rating_input_to_feedback_ms` | 1000 | 430.16 | 570.96 | 657.88 | 910.94 | ≤ 100.0 | BASELINE_OVER_TARGET |
| `rating_input_to_commit_ms` | 1000 | 303.51 | 438.02 | 477.89 | 720.08 | ≤ 250.0 | BASELINE_OVER_TARGET |
| `rating_input_to_idle_ms` | 1000 | 431.91 | 572.52 | 663.07 | 914.34 | - | - |
| `local_query_content_ms` | 1000 | 307.93 | 378.99 | 422.98 | 493.44 | ≤ 150.0 | BASELINE_OVER_TARGET |
| `local_query_commit_ms` | 1000 | 186.07 | 248.74 | 261.76 | 338.77 | ≤ 250.0 | BASELINE_OVER_TARGET |
| `local_query_idle_ms` | 1000 | 333.62 | 429.87 | 451.98 | 546.71 | - | - |
| `search_input_to_feedback_ms` | 1000 | 85.42 | 120.71 | 140.42 | 169.07 | - | - |
| `search_input_to_idle_ms` | 1000 | 104.03 | 136.01 | 157.04 | 201.6 | ≤ 200.0 | PASS |
| `review_plan_switch_ms` | 1000 | 25.06 | 48.79 | 56.4 | 72.39 | ≤ 100.0 | PASS |
| `rating_input_to_feedback_ms` | 3000 | 1052.31 | 1190.41 | 1253.24 | 1424.32 | ≤ 100.0 | BASELINE_OVER_TARGET |
| `rating_input_to_commit_ms` | 3000 | 761.39 | 892.91 | 942.77 | 1016.17 | ≤ 250.0 | BASELINE_OVER_TARGET |
| `rating_input_to_idle_ms` | 3000 | 1055.62 | 1195.86 | 1254.56 | 1427.55 | - | - |
| `local_query_content_ms` | 3000 | 739.25 | 830.86 | 878.08 | 1044.53 | ≤ 150.0 | BASELINE_OVER_TARGET |
| `local_query_commit_ms` | 3000 | 457.98 | 524.45 | 568.09 | 712.18 | ≤ 250.0 | BASELINE_OVER_TARGET |
| `local_query_idle_ms` | 3000 | 778.98 | 893.49 | 946.64 | 1126.26 | - | - |
| `search_input_to_feedback_ms` | 3000 | 241.77 | 289.11 | 313.51 | 448.89 | - | - |
| `search_input_to_idle_ms` | 3000 | 258.12 | 314.89 | 326.4 | 460.27 | ≤ 200.0 | BASELINE_OVER_TARGET |
| `review_plan_switch_ms` | 3000 | 24.2 | 35.28 | 45.6 | 58.4 | ≤ 100.0 | PASS |

## 2. Pure Scrolling Framestats & Jank

| Scale | Total Frames | Janky Frames | Jank % | P50 (ms) | P90 (ms) | P95 (ms) | P99 (ms) | Target Jank % |
|---|---:|---:|---:|---:|---:|---:|---:|:---:|
| 1000 | 101 | 91 | 90.1% | 77 | 121 | 129 | 450 | ≤ 25.0% |
| 3000 | 98 | 89 | 90.82% | 69 | 125 | 150 | 600 | ≤ 25.0% |

## 3. 3,000 Items + 10,000 Events Capacity Rejection Baseline

- **Loaded Legitimate History Events**: 10000
- **Capacity Rejection Expected**: `True`
- **Capacity Rejection Actual**: `True`
- **Rejection Message**: *"笔记文本超过当前设备容量（最高4 MB），本次修改未写入"*
- **Assessment**: Legacy JSON backend enforces the 4MB limit (`MAX_JSON_BYTES`). Successfully establishes capacity rejection baseline for evaluation against Task 3 SQLite incremental transactions.

## 4. Visual Parity & Calibration Artifacts

- **Visual Screenshots**: `visual/light_normal/`, `visual/dark_normal/`, `visual/light_large/`
- **Contact Sheets**: `visual/contact-sheet-*.png`
- **Noise Calibration**: `noise-calibration.json`
- **Storage Restoration**: `original-restoration.json`
