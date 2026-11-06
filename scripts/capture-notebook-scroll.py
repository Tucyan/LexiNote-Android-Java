#!/usr/bin/env python3
"""Capture a scroll trace and gfxinfo from an already installed isolated test runner.

The trace includes setup; verify application trace markers are present before analysis.
No APK install, device-data backup, cleanup, or retries are performed.
"""
import argparse
from pathlib import Path
import subprocess
import sys


def run(args, timeout=60):
    return subprocess.run(args, check=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, timeout=timeout)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--serial', required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--size', choices=['1000', '3000'], default='3000')
    parser.add_argument('--adb', default='adb')
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=False)
    adb = [args.adb, '-s', args.serial]
    package = 'com.lexinote.nativeapp'
    trace = '/data/misc/perfetto-traces/lexinote-scroll.perfetto-trace'
    run(adb + ['shell', 'dumpsys', 'gfxinfo', package, 'reset'])
    run(adb + ['shell', 'perfetto', '--background-wait', '-o', trace, '-t', '60s',
               '-b', '64mb', '-a', package, 'sched', 'freq', 'view', 'gfx', 'wm', 'am', 'dalvik'])
    try:
        result = run(adb + ['shell', 'am', 'instrument', '-w', '-e', 'size', args.size,
                     '-e', 'mode', 'scroll', package + '.test/' + package + '.PerformanceInstrumentation'], timeout=120)
        (args.output / 'instrumentation.txt').write_bytes(result.stdout + result.stderr)
    finally:
        data = run(adb + ['shell', 'dumpsys', 'gfxinfo', package, 'framestats'])
        (args.output / 'gfxinfo.txt').write_bytes(data.stdout)
    print('Trace capture started for 60 seconds. Pull it after recording completes:')
    print(f'{args.adb} -s {args.serial} pull {trace} {args.output / "scroll.perfetto-trace"}')
    print('Verify that the trace contains the complete gesture and record device/refresh settings.')


if __name__ == '__main__':
    try:
        main()
    except (subprocess.SubprocessError, OSError) as error:
        print(error, file=sys.stderr)
        sys.exit(1)
