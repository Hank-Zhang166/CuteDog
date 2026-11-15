#!/usr/bin/env python3
"""Configure the disposable CI runner's KVM device and retain real probe evidence."""
import argparse
import errno
import fcntl
import json
import os
from pathlib import Path
import re
import stat
import subprocess
import sys
import time


def snapshot():
    device = Path("/dev/kvm")
    result = {"exists": device.exists(), "readable": os.access(device, os.R_OK),
              "writable": os.access(device, os.W_OK)}
    if device.exists():
        details = device.stat()
        result.update(character_device=stat.S_ISCHR(details.st_mode), mode=oct(stat.S_IMODE(details.st_mode)),
                      owner_uid=details.st_uid, owner_gid=details.st_gid)
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--probe-only", action="store_true", help="Read-only actual KVM probe; do not configure udev or permissions")
    args = parser.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    report_path = args.output / "setup.json"
    if report_path.exists():
        parser.error("Use a new output directory; previous KVM evidence must not be overwritten")
    cpu = Path("/proc/cpuinfo").read_text(errors="replace") if Path("/proc/cpuinfo").exists() else ""
    modules = Path("/proc/modules").read_text(errors="replace") if Path("/proc/modules").exists() else ""
    report = {"sourceCommit": os.getenv("GITHUB_SHA"), "runId": os.getenv("GITHUB_RUN_ID"),
              "runAttempt": os.getenv("GITHUB_RUN_ATTEMPT"), "probeOnly": args.probe_only,
              "uid": os.getuid(), "groups": os.getgroups(), "before": snapshot(),
              "cpuVirtualizationFlags": sorted(set(re.findall(r"\b(?:vmx|svm)\b", cpu))),
              "loadedKvmModules": [line.split()[0] for line in modules.splitlines() if line.startswith("kvm")],
              "startedUtc": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "commands": [], "status": "running"}
    stage = "initialize"
    exit_code = 1

    def save():
        report_path.write_text(json.dumps(report, indent=2) + "\n")

    def command(name, argv, input_text=None):
        nonlocal stage
        stage = name
        result = subprocess.run(argv, input=input_text, text=True, capture_output=True, timeout=30)
        record = {"stage": name, "args": argv, "exitCode": result.returncode,
                  "stdout": result.stdout, "stderr": result.stderr}
        report["commands"].append(record)
        print(json.dumps(record), flush=True)
        save()
        if result.returncode:
            raise subprocess.CalledProcessError(result.returncode, argv)

    try:
        save()
        if not args.probe_only:
            stage = "verify_disposable_ci"
            if os.getenv("GITHUB_ACTIONS") != "true":
                raise RuntimeError("KVM setup is restricted to a GitHub Actions runner; use --probe-only for a read-only local check")
            command("write_udev_rule", ["sudo", "tee", "/etc/udev/rules.d/99-kvm4all.rules"],
                    'KERNEL=="kvm", GROUP="kvm", MODE="0666", OPTIONS+="static_node=kvm"\n')
            command("reload_udev_rules", ["sudo", "udevadm", "control", "--reload-rules"])
            command("trigger_device_permissions", ["sudo", "udevadm", "trigger", "--name-match=kvm"])
            # trigger schedules udev work; wait before observing the resulting permissions.
            command("settle_udev_events", ["sudo", "udevadm", "settle", "--timeout=15"])
            report["afterUdev"] = snapshot()
            if report["afterUdev"].get("character_device") and not (report["afterUdev"]["readable"] and report["afterUdev"]["writable"]):
                command("repair_existing_device_permissions", ["sudo", "chmod", "a+rw", "/dev/kvm"])
        stage = "open_kvm_as_runner"
        if not snapshot().get("character_device"):
            raise FileNotFoundError(errno.ENOENT, "No usable /dev/kvm character device; hardware acceleration was not established", "/dev/kvm")
        device_fd = os.open("/dev/kvm", os.O_RDWR | os.O_CLOEXEC)
        try:
            stage = "get_kvm_api_version"
            version = fcntl.ioctl(device_fd, 0xAE00, 0)  # KVM_GET_API_VERSION
            report["kvmApiVersion"] = version
            if version != 12:
                raise RuntimeError(f"Unsupported KVM API version: {version}")
            stage = "create_kvm_vm_as_runner"
            vm_fd = fcntl.ioctl(device_fd, 0xAE01, 0)  # KVM_CREATE_VM, no vCPUs started
            os.close(vm_fd)
            report["createdAndClosedVm"] = True
        finally:
            os.close(device_fd)
        report["status"] = "passed"
        exit_code = 0
    except Exception as error:
        raw_code = error.returncode if isinstance(error, subprocess.CalledProcessError) else None
        report.update(status="failed", failedStage=stage, error=str(error), errorType=type(error).__name__,
                      errno=getattr(error, "errno", None), commandExitCode=raw_code)
        if raw_code is not None and 1 <= raw_code <= 255:
            exit_code = raw_code
        print(json.dumps({"status": "failed", "stage": stage, "error": str(error), "commandExitCode": raw_code}), file=sys.stderr, flush=True)
    finally:
        report.update(after=snapshot(), exitCode=exit_code,
                      finishedUtc=time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()))
        save()
    return exit_code


if __name__ == "__main__":
    raise SystemExit(main())
