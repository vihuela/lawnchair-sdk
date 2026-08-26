#!/usr/bin/env python3
"""Run one package-specific Launcher SDK build inside GitHub Actions."""

from __future__ import annotations

import argparse
import importlib.util
import json
import os
import re
import shutil
import subprocess
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[2]
SERVER_PATH = Path(__file__).with_name("server.py")
JOB_ID_PATTERN = re.compile(r"^[0-9a-f]{12}$")
MIN_FREE_BYTES = 10 * 1024 * 1024 * 1024


def load_server_module():
    spec = importlib.util.spec_from_file_location("launcher_sdk_builder_ci_server", SERVER_PATH)
    if spec is None or spec.loader is None:
        raise RuntimeError("无法加载 SDK 构建模块")
    module = importlib.util.module_from_spec(spec)
    sys.modules[spec.name] = module
    spec.loader.exec_module(module)
    return module


def command_output(command: list[str]) -> str:
    completed = subprocess.run(
        command,
        cwd=ROOT,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        check=False,
    )
    if completed.returncode != 0:
        raise RuntimeError(f"环境检查命令失败：{' '.join(command)}")
    return completed.stdout.strip()


def validate_environment(server, package_name: str, job_id: str) -> None:
    errors: list[str] = []
    if not server.PACKAGE_PATTERN.fullmatch(package_name):
        errors.append("Android 包名格式无效")
    if not JOB_ID_PATTERN.fullmatch(job_id):
        errors.append("构建任务 ID 格式无效")
    if sys.version_info < (3, 10):
        errors.append(f"Python 版本过低：{sys.version.split()[0]}，需要 3.10+")

    required_paths = [ROOT / relative for relative in server.SOURCE_REQUIRED_PATHS]
    missing_paths = [str(path.relative_to(ROOT)) for path in required_paths if not path.exists()]
    if missing_paths:
        errors.append(f"源码缺少必要文件：{', '.join(missing_paths)}")

    java_version = command_output(["java", "-version"])
    match = re.search(r'version "(\d+)', java_version)
    if not match or int(match.group(1)) < 17:
        errors.append("Java 版本无效，需要 JDK 17+")

    android_home = Path(os.environ.get("ANDROID_SDK_ROOT") or os.environ.get("ANDROID_HOME", ""))
    if not android_home.is_dir():
        errors.append("ANDROID_SDK_ROOT/ANDROID_HOME 未指向有效目录")
    else:
        for relative in ("platforms/android-36.1", "build-tools/36.1.0"):
            if not (android_home / relative).is_dir():
                errors.append(f"Android SDK 缺少 {relative}")

    free_bytes = shutil.disk_usage(ROOT).free
    if free_bytes < MIN_FREE_BYTES:
        errors.append(f"可用磁盘空间不足：{free_bytes / 1024**3:.1f} GiB，需要至少 10 GiB")

    packages = server.github_packages_config()
    if not packages["configured"]:
        errors.append("GitHub Packages 发布凭证未注入 CI 环境")

    if errors:
        raise RuntimeError("；".join(errors))

    revision = command_output(["git", "rev-parse", "--short=12", "HEAD"])
    print("环境检查通过")
    print(f"- 源码版本：{revision}")
    print(f"- Python：{sys.version.split()[0]}")
    print(f"- Java：{match.group(1) if match else 'unknown'}")
    print("- Android SDK：platform 36.1 / build-tools 36.1.0")
    print(f"- 可用磁盘：{free_bytes / 1024**3:.1f} GiB")
    print(f"- 发布目标：{packages['owner']}/{packages['repository']}")


def write_job_payload(server, job) -> Path:
    downloads = server.OUTPUT_ROOT / job.id / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    payload_path = downloads / "job.json"
    payload_path.write_text(
        json.dumps(job.public(), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    return downloads


def write_failure_payload(server, package_name: str, job_id: str, error: Exception) -> Path:
    downloads = server.OUTPUT_ROOT / job_id / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    (downloads / "job.json").write_text(
        json.dumps(
            {
                "id": job_id,
                "packageName": package_name,
                "state": "failed",
                "progress": 100,
                "message": "CI 环境检查或构建失败",
                "error": str(error),
                "log": [f"ERROR: {error}"],
            },
            ensure_ascii=False,
            indent=2,
        ),
        encoding="utf-8",
    )
    return downloads


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--package-name", required=True)
    parser.add_argument("--job-id", required=True)
    parser.add_argument("--initialize-only", action="store_true")
    parser.add_argument("--preflight-only", action="store_true")
    args = parser.parse_args()

    server = load_server_module()
    if args.initialize_only:
        write_failure_payload(
            server,
            args.package_name,
            args.job_id,
            RuntimeError("GitHub Actions 环境准备未完成，请查看 CI 步骤状态"),
        )
        return 0
    try:
        validate_environment(server, args.package_name, args.job_id)
        if args.preflight_only:
            return 0

        packages = server.github_packages_config()
        job = server.Job(
            id=args.job_id,
            package_name=args.package_name,
            sdk_version=server.resolve_current_sdk_version(ROOT),
            github_username=packages["username"],
            github_token=packages["token"],
        )
        server.build_job(job)
        downloads = write_job_payload(server, job)
        print(f"构建状态：{job.state}")
        print(f"CI 结果目录：{downloads.relative_to(ROOT)}")
        if job.state == "completed":
            return 0
        print(job.error or "构建失败", file=sys.stderr)
        return 1
    except Exception as error:
        downloads = write_failure_payload(server, args.package_name, args.job_id, error)
        print(str(error), file=sys.stderr)
        print(f"CI 失败结果：{downloads.relative_to(ROOT)}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
