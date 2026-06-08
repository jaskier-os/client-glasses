"""Run OWW tests on the phone via ADB test_oww command.

Usage:
    python test_on_phone.py                    # 10 pos + 10 neg
    python test_on_phone.py --count 20         # 20 pos + 20 neg
    python test_on_phone.py --positive-only
"""

import argparse
import json
import subprocess
import time
from pathlib import Path

BASE_DIR = Path(__file__).parent
FILESDIR = "/data/user/0/com.repository.listener/files"


def adb(cmd):
    r = subprocess.run(["adb"] + cmd.split(), capture_output=True, text=True, timeout=10)
    return r.stdout.strip()


def adb_shell(cmd):
    r = subprocess.run(["adb", "shell", cmd], capture_output=True, text=True, timeout=10)
    return r.stdout.strip()


def test_wav(wav_path, test_id):
    bn = f"oww_{test_id}.wav"

    # Push file
    subprocess.run(["adb", "push", str(wav_path), f"/data/local/tmp/{bn}"],
                   capture_output=True, timeout=10)
    adb_shell(f"run-as com.repository.listener cp /data/local/tmp/{bn} files/{bn}")

    # Clean stale result
    adb_shell(f"run-as com.repository.listener rm -f files/adb_results/{test_id}.json")

    # Run test
    adb_shell(
        f"am broadcast -n com.repository.listener/.adb.AdbCommandReceiver "
        f"-a com.repository.listener.ADB_COMMAND "
        f"--es type test_oww --es command_id {test_id} "
        f"""--es params '{{"wav_file":"{FILESDIR}/{bn}"}}'"""
    )

    # Wait for result
    for _ in range(20):
        time.sleep(1)
        raw = adb_shell(f"run-as com.repository.listener cat files/adb_results/{test_id}.json")
        if not raw:
            continue
        try:
            result = json.loads(raw)
        except json.JSONDecodeError:
            continue
        if result.get("status") == "success":
            # Cleanup
            adb_shell(f"run-as com.repository.listener rm -f files/{bn}")
            return result["data"]
        if result.get("status") == "error":
            return {"error": result.get("error", "unknown"), "detected": False, "max_score": "0", "frames_above_threshold": 0}

    return {"error": "timeout", "detected": False, "max_score": "0", "frames_above_threshold": 0}


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--count", type=int, default=10)
    parser.add_argument("--positive-only", action="store_true")
    parser.add_argument("--negative-only", action="store_true")
    args = parser.parse_args()

    if not args.negative_only:
        pos_dir = BASE_DIR / "data" / "recorded_positive"
        pos_files = sorted(pos_dir.glob("*.wav"))[:args.count]
        print(f"\n=== POSITIVE ({len(pos_files)} files) ===")
        detected = 0
        for i, f in enumerate(pos_files):
            r = test_wav(f, f"tp{i:03d}")
            ms = r.get("max_score", "?")
            fr = r.get("frames_above_threshold", "?")
            det = r.get("detected", False)
            err = r.get("error", "")
            status = "OK" if det else ("ERR:" + err if err else "MISS")
            print(f"  [{status:4s}] {f.name}: max={ms} frames={fr}")
            if det:
                detected += 1
        print(f"  RATE: {detected}/{len(pos_files)}")

    if not args.positive_only:
        neg_dir = BASE_DIR / "data" / "recorded_negative"
        neg_files = sorted(neg_dir.glob("*.wav"))[:args.count]
        print(f"\n=== NEGATIVE ({len(neg_files)} files) ===")
        fps = 0
        for i, f in enumerate(neg_files):
            r = test_wav(f, f"tn{i:03d}")
            ms = r.get("max_score", "?")
            fr = r.get("frames_above_threshold", "?")
            det = r.get("detected", False)
            err = r.get("error", "")
            status = "FP" if det else ("ERR:" + err if err else "OK")
            print(f"  [{status:4s}] {f.name}: max={ms} frames={fr}")
            if det:
                fps += 1
        print(f"  FP RATE: {fps}/{len(neg_files)}")


if __name__ == "__main__":
    main()
