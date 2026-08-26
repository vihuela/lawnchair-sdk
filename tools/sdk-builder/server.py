#!/usr/bin/env python3
"""Local web builder for package-specific Lawnchair client SDK bridges."""

from __future__ import annotations

import argparse
import hashlib
import html
import json
import os
import re
import shutil
import subprocess
import threading
import time
import uuid
import zipfile
from dataclasses import dataclass, field
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import unquote, urlparse


ROOT = Path(__file__).resolve().parents[2]
STATIC_DIR = Path(__file__).resolve().parent / "static"
OUTPUT_ROOT = Path(__file__).resolve().parent / "output"
MODULE_DIR = ROOT / "launcher-sdk-client"
BUILD_LOCK = threading.Lock()
PACKAGE_PATTERN = re.compile(r"^[a-zA-Z][a-zA-Z0-9_]*(?:\.[a-zA-Z][a-zA-Z0-9_]*)+$")
VERSION_PATTERN = re.compile(r"^[0-9A-Za-z][0-9A-Za-z._-]{0,63}$")


def resolve_current_sdk_version() -> str:
    environment_version = os.environ.get("VERSION", "").strip().removeprefix("v")
    if environment_version:
        version = environment_version
    else:
        build_script = (ROOT / "launcher-core" / "build.gradle").read_text(encoding="utf-8")
        match = re.search(
            r"def sdkPublishVersion\s*=.*?\.orElse\(\"([^\"]+)\"\)",
            build_script,
            re.DOTALL,
        )
        if not match:
            raise RuntimeError("无法从 launcher-core/build.gradle 读取 SDK 发布版本")
        version = match.group(1).removesuffix("-local")
    if not VERSION_PATTERN.fullmatch(version):
        raise RuntimeError(f"当前源码的 SDK 发布版本无效：{version}")
    return version


PUBLIC_FUNCTIONS = [
    ("ClientLauncherSdk", "initialize", "fun initialize(application: Application, entry: ClientLauncherEntry): Unit", "在宿主 Application.onCreate 中注册包专属业务 Entry 并初始化 Launcher SDK。"),
    ("ClientLauncherSdk", "isDefaultLauncher", "fun isDefaultLauncher(context: Context): Boolean", "判断当前应用是否已被系统设置为默认桌面。"),
    ("ClientLauncherSdk", "requestDefaultLauncher", "suspend fun requestDefaultLauncher(): ClientCommandResult", "拉起系统默认桌面选择或默认 Launcher 设置流程。"),
    ("ClientLauncherSdk", "launchDesktop", "suspend fun launchDesktop(): ClientCommandResult", "打开 Launcher 主桌面。"),
    ("ClientLauncherSdk", "openMinusOne", "suspend fun openMinusOne(animated: Boolean = true): ClientCommandResult", "展开负一屏，可选择是否播放展开动画。"),
    ("ClientLauncherSdk", "setApplicationLanguage", "suspend fun setApplicationLanguage(languageTag: String?): ClientCommandResult", "同步修改宿主应用和 Launcher 的语言；传 null 表示跟随系统。"),
    ("ClientLauncherEntry", "minusOneLaunchPolicy", "protected fun minusOneLaunchPolicy(): ClientMinusOneLaunchPolicy", "业务覆写：配置 Launcher 启动后展示桌面还是冷启动自动展开负一屏。"),
    ("ClientLauncherEntry", "renderMinusOne", "@Composable protected fun renderMinusOne(scope: ClientMinusOneScope, modifier: Modifier)", "业务必须覆写：提供负一屏的 Compose 页面内容。"),
    ("ClientMinusOneScope", "getPhase", "val phase: ClientMinusOnePhase", "只读：获取负一屏当前阶段，例如展开中、已展开或收起中。"),
    ("ClientMinusOneScope", "getProgress", "val progress: Float", "只读：获取负一屏展开进度，范围为 0 到 1。"),
    ("ClientLauncherEntry", "loadDesktopItems", "protected suspend fun loadDesktopItems(): List<ClientDesktopItem>?", "业务覆写：返回完整桌面 Item 快照；返回 null 表示数据暂不可用。"),
    ("ClientLauncherEntry", "onDesktopItemAction", "protected suspend fun onDesktopItemAction(itemId: String, actionId: String): Unit", "业务覆写：处理 ProviderAction 类型桌面 Item 的点击事件。"),
    ("ClientLauncherEntry", "iconClickInterceptionEnabled", "protected fun iconClickInterceptionEnabled(): Boolean", "业务覆写：决定是否启用 Launcher 图标点击前置拦截。"),
    ("ClientLauncherEntry", "onLauncherIconClick", "protected suspend fun onLauncherIconClick(request: ClientLauncherIconClickRequest): ClientLauncherIconClickDecision", "业务覆写：在图标执行原点击前处理广告等逻辑，并决定继续或消费点击。"),
    ("ClientLauncherSdk", "currentState", "fun currentState(): ClientLauncherStateSnapshot", "读取 Launcher 当前状态快照，包括运行时、语言和负一屏状态。"),
    ("ClientLauncherSdk", "observeState", "fun observeState(): Flow<ClientLauncherStateSnapshot>", "持续监听 Launcher 状态变化。"),
    ("ClientLauncherSdk", "observeEvents", "fun observeEvents(): Flow<ClientLauncherEvent>", "监听桌面可见、桌面图标点击等 Launcher 事件。"),
    ("ClientLauncherSdk", "closeMinusOne", "suspend fun closeMinusOne(animated: Boolean = true): ClientCommandResult", "收起负一屏并回到桌面。"),
    ("ClientLauncherSdk", "toggleMinusOne", "suspend fun toggleMinusOne(animated: Boolean = true): ClientCommandResult", "根据当前状态切换负一屏的展开或收起。"),
    ("ClientMinusOneScope", "close", "fun close(animated: Boolean = true): ClientCommandResult", "在负一屏内容内部主动收起负一屏。"),
    ("ClientLauncherSdk", "refreshDesktopItems", "suspend fun refreshDesktopItems(): ClientCommandResult", "重新读取业务提供的桌面 Item 快照并同步到 Launcher。"),
    ("ClientLauncherSdk", "revealDesktopItem", "suspend fun revealDesktopItem(itemId: String, animated: Boolean = true): ClientCommandResult", "打开桌面并定位到指定 SDK 桌面 Item，但不触发点击。"),
    ("ClientLauncherSdk", "openDesktopItem", "suspend fun openDesktopItem(itemId: String, animated: Boolean = false): ClientCommandResult", "定位指定 SDK 桌面 Item，并执行该 Item 配置的点击行为。"),
    ("ClientLauncherSdk", "removeDesktopItem", "suspend fun removeDesktopItem(itemId: String, rememberRemoval: Boolean = true): ClientCommandResult", "从桌面移除指定 SDK Item，可记录用户移除状态。"),
    ("ClientLauncherSdk", "restoreDesktopItem", "suspend fun restoreDesktopItem(itemId: String): ClientCommandResult", "恢复之前被移除的 SDK 桌面 Item。"),
    ("ClientLauncherSdk", "openHomeSettings", "suspend fun openHomeSettings(): ClientCommandResult", "打开系统桌面应用/默认桌面的相关设置页面。"),
    ("ClientLauncherSdk", "openLauncherAppInfo", "suspend fun openLauncherAppInfo(): ClientCommandResult", "打开当前 Launcher 应用的系统详情页面。"),
]

PUBLIC_CLASSES = [
    "ClientLauncherSdk",
    "ClientLauncherEntry",
    "ClientCommandResult",
    "ClientCommandResult$Applied",
    "ClientCommandResult$Queued",
    "ClientCommandResult$AlreadyInRequestedState",
    "ClientCommandResult$DesktopItemsReconciled",
    "ClientCommandResult$Rejected",
    "ClientCommandResult$Failed",
    "ClientMinusOneLaunchPolicy",
    "ClientMinusOneScope",
    "ClientMinusOnePhase",
    "ClientDesktopItem",
    "ClientDesktopClickAction",
    "ClientDesktopClickAction$LaunchIntent",
    "ClientDesktopClickAction$ProviderAction",
    "ClientDesktopRow",
    "ClientDesktopRow$FromTop",
    "ClientDesktopRow$FromBottom",
    "ClientDesktopConflictPolicy",
    "ClientLauncherIconClickRequest",
    "ClientLauncherIconClickEvent",
    "ClientLauncherIconClickDecision",
    "ClientLauncherStateSnapshot",
    "ClientLauncherEvent",
    "ClientLauncherEvent$DesktopVisible",
    "ClientLauncherEvent$LauncherIconClicked",
    "ClientDesktopVisibleSource",
]

FUNCTION_GROUPS = {
    "接入与桌面": {
        ("ClientLauncherSdk", "initialize"),
        ("ClientLauncherSdk", "isDefaultLauncher"),
        ("ClientLauncherSdk", "requestDefaultLauncher"),
        ("ClientLauncherSdk", "launchDesktop"),
        ("ClientLauncherSdk", "openHomeSettings"),
        ("ClientLauncherSdk", "openLauncherAppInfo"),
    },
    "负一屏": {
        ("ClientLauncherSdk", "openMinusOne"),
        ("ClientLauncherSdk", "closeMinusOne"),
        ("ClientLauncherSdk", "toggleMinusOne"),
        ("ClientLauncherEntry", "minusOneLaunchPolicy"),
        ("ClientLauncherEntry", "renderMinusOne"),
        ("ClientMinusOneScope", "getPhase"),
        ("ClientMinusOneScope", "getProgress"),
        ("ClientMinusOneScope", "close"),
    },
    "语言": {
        ("ClientLauncherSdk", "setApplicationLanguage"),
    },
    "桌面 Item": {
        ("ClientLauncherEntry", "loadDesktopItems"),
        ("ClientLauncherEntry", "onDesktopItemAction"),
        ("ClientLauncherSdk", "refreshDesktopItems"),
        ("ClientLauncherSdk", "revealDesktopItem"),
        ("ClientLauncherSdk", "openDesktopItem"),
        ("ClientLauncherSdk", "removeDesktopItem"),
        ("ClientLauncherSdk", "restoreDesktopItem"),
    },
    "图标点击拦截": {
        ("ClientLauncherEntry", "iconClickInterceptionEnabled"),
        ("ClientLauncherEntry", "onLauncherIconClick"),
    },
    "状态与事件": {
        ("ClientLauncherSdk", "currentState"),
        ("ClientLauncherSdk", "observeState"),
        ("ClientLauncherSdk", "observeEvents"),
    },
}
FUNCTION_GROUP_BY_KEY = {
    key: group
    for group, keys in FUNCTION_GROUPS.items()
    for key in keys
}

CLASS_GROUPS = {
    "核心入口": {
        "ClientLauncherSdk",
        "ClientLauncherEntry",
    },
    "命令结果": {
        "ClientCommandResult",
        "ClientCommandResult$Applied",
        "ClientCommandResult$Queued",
        "ClientCommandResult$AlreadyInRequestedState",
        "ClientCommandResult$DesktopItemsReconciled",
        "ClientCommandResult$Rejected",
        "ClientCommandResult$Failed",
    },
    "负一屏": {
        "ClientMinusOneLaunchPolicy",
        "ClientMinusOneScope",
        "ClientMinusOnePhase",
    },
    "桌面 Item": {
        "ClientDesktopItem",
        "ClientDesktopClickAction",
        "ClientDesktopClickAction$LaunchIntent",
        "ClientDesktopClickAction$ProviderAction",
        "ClientDesktopRow",
        "ClientDesktopRow$FromTop",
        "ClientDesktopRow$FromBottom",
        "ClientDesktopConflictPolicy",
    },
    "图标点击拦截": {
        "ClientLauncherIconClickRequest",
        "ClientLauncherIconClickEvent",
        "ClientLauncherIconClickDecision",
    },
    "状态与事件": {
        "ClientLauncherStateSnapshot",
        "ClientLauncherEvent",
        "ClientLauncherEvent$DesktopVisible",
        "ClientLauncherEvent$LauncherIconClicked",
        "ClientDesktopVisibleSource",
    },
}
CLASS_GROUP_BY_NAME = {
    name: group
    for group, names in CLASS_GROUPS.items()
    for name in names
}

FORBIDDEN_BUSINESS_TYPE_PREFIXES = (
    "app.lawnchair.sdk.",
)


@dataclass
class Job:
    id: str
    package_name: str
    sdk_version: str
    state: str = "queued"
    progress: int = 0
    message: str = "等待构建"
    log: list[str] = field(default_factory=list)
    result: dict | None = None
    error: str | None = None
    created_at: float = field(default_factory=time.time)

    def public(self) -> dict:
        return {
            "id": self.id,
            "packageName": self.package_name,
            "sdkVersion": self.sdk_version,
            "state": self.state,
            "progress": self.progress,
            "message": self.message,
            "log": self.log[-240:],
            "result": self.result,
            "error": self.error,
        }


JOBS: dict[str, Job] = {}
JOBS_LOCK = threading.Lock()


def update_job(job: Job, *, progress: int | None = None, message: str | None = None) -> None:
    with JOBS_LOCK:
        if progress is not None:
            job.progress = progress
        if message is not None:
            job.message = message


def append_log(job: Job, line: str) -> None:
    clean = re.sub(r"\x1b\[[0-9;]*m", "", line.rstrip())
    if clean:
        with JOBS_LOCK:
            job.log.append(clean)


def run_process(job: Job, args: list[str], cwd: Path) -> None:
    process = subprocess.Popen(
        args,
        cwd=cwd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        bufsize=1,
        env={**os.environ, "TERM": "dumb"},
    )
    assert process.stdout is not None
    for line in process.stdout:
        append_log(job, line)
    code = process.wait()
    if code != 0:
        raise RuntimeError(f"命令执行失败（exit={code}）：{' '.join(args)}")


def extract_classes_jar(aar: Path, target: Path) -> None:
    with zipfile.ZipFile(aar) as archive:
        target.write_bytes(archive.read("classes.jar"))


def parse_mapping(mapping_file: Path) -> tuple[dict[str, str], dict[tuple[str, str], list[dict]]]:
    class_map: dict[str, str] = {}
    methods: dict[tuple[str, str], list[dict]] = {}
    current_class: str | None = None
    class_pattern = re.compile(r"^(\S+) -> (\S+):$")
    member_pattern = re.compile(r"^\s+(?:(?:\d+):(?:\d+):)?(.+?) -> (\S+)$")
    method_pattern = re.compile(r"^(.*?)\s+([^\s(]+)\((.*)\)(?::\d+:\d+)?$")

    for raw_line in mapping_file.read_text(encoding="utf-8").splitlines():
        class_match = class_pattern.match(raw_line)
        if class_match:
            current_class = class_match.group(1)
            class_map[current_class] = class_match.group(2)
            continue
        if current_class is None:
            continue
        member_match = member_pattern.match(raw_line)
        if not member_match:
            continue
        original, obfuscated = member_match.groups()
        method_match = method_pattern.match(original)
        if not method_match:
            continue
        return_type, method_name, arguments = method_match.groups()
        entry = {"returnType": return_type, "name": method_name, "arguments": arguments, "r8Name": obfuscated}
        bucket = methods.setdefault((current_class, method_name), [])
        if entry not in bucket:
            bucket.append(entry)
    return class_map, methods


def javap_lines(jar: Path, class_name: str) -> list[str]:
    completed = subprocess.run(
        ["javap", "-classpath", str(jar), "-protected", class_name],
        cwd=ROOT,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        check=False,
    )
    if completed.returncode != 0:
        return []
    return [line.strip().removesuffix(";") for line in completed.stdout.splitlines() if "(" in line]


def select_javap_methods(lines: list[str], method_name: str) -> list[str]:
    marker = f" {method_name}("
    return [line for line in lines if marker in line]


def split_arguments(arguments: str) -> list[str]:
    if not arguments.strip():
        return []
    result: list[str] = []
    current: list[str] = []
    depth = 0
    for character in arguments:
        if character == "<":
            depth += 1
        elif character == ">":
            depth = max(0, depth - 1)
        if character == "," and depth == 0:
            result.append("".join(current).strip())
            current = []
        else:
            current.append(character)
    result.append("".join(current).strip())
    return result


def erased_type(type_name: str) -> str:
    value = type_name.strip()
    while "<" in value:
        value = re.sub(r"<[^<>]*>", "", value)
    return value.replace("...", "[]")


def javap_arguments(line: str, method_name: str) -> list[str] | None:
    marker = f" {method_name}("
    start = line.find(marker)
    if start < 0:
        return None
    arguments_start = start + len(marker)
    arguments_end = line.find(")", arguments_start)
    if arguments_end < 0:
        return None
    return [erased_type(argument) for argument in split_arguments(line[arguments_start:arguments_end])]


def mapped_arguments(arguments: str, class_map: dict[str, str]) -> list[str]:
    return [class_map.get(argument, argument) for argument in split_arguments(arguments)]


def build_comparison(package_name: str, pre_jar: Path, r8_jar: Path, mapping_file: Path) -> dict:
    namespace = f"{package_name}.launcher.sdk"
    class_map, method_map = parse_mapping(mapping_file)
    javap_cache: dict[tuple[str, str], list[str]] = {}

    def javap_cached(kind: str, jar: Path, class_name: str) -> list[str]:
        key = (kind, class_name)
        if key not in javap_cache:
            javap_cache[key] = javap_lines(jar, class_name)
        return javap_cache[key]

    class_rows = []
    for simple_name in PUBLIC_CLASSES:
        source_name = f"{namespace}.{simple_name}"
        class_rows.append(
            {
                "group": CLASS_GROUP_BY_NAME[simple_name],
                "source": source_name,
                "preR8": source_name,
                "r8": class_map.get(source_name, source_name),
            }
        )

    function_rows = []
    for simple_class, function_name, source_signature, description in PUBLIC_FUNCTIONS:
        source_class = f"{namespace}.{simple_class}"
        r8_class = class_map.get(source_class, source_class)
        mapped = method_map.get((source_class, function_name), [])
        r8_names = sorted({entry["r8Name"] for entry in mapped})
        pre_signatures = select_javap_methods(javap_cached("pre", pre_jar, source_class), function_name)
        r8_signatures: list[str] = []
        r8_lines = javap_cached("r8", r8_jar, r8_class)
        for entry in mapped:
            expected_arguments = mapped_arguments(entry["arguments"], class_map)
            candidates = select_javap_methods(r8_lines, entry["r8Name"])
            exact = [
                line for line in candidates
                if javap_arguments(line, entry["r8Name"]) == expected_arguments
            ]
            r8_signatures.extend(exact or candidates)
        function_rows.append(
            {
                "group": FUNCTION_GROUP_BY_KEY[(simple_class, function_name)],
                "sourceClass": source_class,
                "sourceFunction": source_signature,
                "description": description,
                "preR8": pre_signatures or [f"{source_class}.{function_name}(…)"],
                "r8Class": r8_class,
                "r8Name": ", ".join(r8_names) if r8_names else function_name,
                "r8": sorted(set(r8_signatures)) or [f"{r8_class}.{function_name}(…)"],
            }
        )
    return {"classes": class_rows, "functions": function_rows}


def validate_business_abi(comparison: dict) -> dict:
    """Fail a build when a type visible to business code is stable or leaks core SDK types."""
    violations: list[str] = []
    for row in comparison["classes"]:
        if row["source"] == row["r8"]:
            violations.append(f"业务类未被 R8 随机改名：{row['source']}")

    for row in comparison["functions"]:
        signatures = [row["sourceFunction"], *row["preR8"], *row["r8"]]
        for signature in signatures:
            leaked_prefix = next(
                (prefix for prefix in FORBIDDEN_BUSINESS_TYPE_PREFIXES if prefix in signature),
                None,
            )
            if leaked_prefix:
                violations.append(
                    f"业务函数泄露固定 SDK 类型 {leaked_prefix}："
                    f"{row['sourceClass']} / {row['sourceFunction']} / {signature}"
                )

    if violations:
        raise RuntimeError("业务 ABI 随机化校验失败：\n" + "\n".join(violations))
    return {
        "passed": True,
        "randomizedClassCount": len(comparison["classes"]),
        "checkedFunctionCount": len(comparison["functions"]),
        "forbiddenTypePrefixes": list(FORBIDDEN_BUSINESS_TYPE_PREFIXES),
        "message": "所有业务引用类均已随机改名，业务函数签名未泄露固定 Lawnchair SDK 类型。",
    }


def write_report_html(path: Path, package_name: str, comparison: dict) -> None:
    def grouped_rows(rows: list[dict], column_count: int, render_row) -> str:
        groups: dict[str, list[dict]] = {}
        for row in rows:
            groups.setdefault(row["group"], []).append(row)
        output: list[str] = []
        for group, entries in groups.items():
            output.append(f'<tr class="group"><th colspan="{column_count}">{html.escape(group)}</th></tr>')
            output.extend(render_row(row) for row in entries)
        return "\n".join(output)

    class_rows = grouped_rows(
        comparison["classes"],
        3,
        lambda row: f"<tr><td><code>{html.escape(row['source'])}</code></td><td><code>{html.escape(row['preR8'])}</code></td><td><code>{html.escape(row['r8'])}</code></td></tr>",
    )
    function_rows = grouped_rows(
        comparison["functions"],
        4,
        lambda row: f"<tr><td>{html.escape(row['description'])}</td><td><code>{html.escape(row['sourceFunction'])}</code></td><td><code>{html.escape(chr(10).join(row['preR8']))}</code></td><td><code>{html.escape(chr(10).join(row['r8']))}</code></td></tr>",
    )
    path.write_text(
        f"""<!doctype html><meta charset=\"utf-8\"><title>{html.escape(package_name)} API mapping</title>
<style>body{{font:14px system-ui;margin:32px;color:#17211d}}table{{border-collapse:collapse;width:100%;margin:16px 0 36px}}th,td{{border:1px solid #d9e2dc;padding:10px;text-align:left;vertical-align:top}}th{{background:#eef2ef}}.group th{{background:#dfe9e3;color:#315743;font-size:13px}}code{{white-space:pre-wrap;word-break:break-all}}</style>
<h1>{html.escape(package_name)} Launcher SDK API mapping</h1>
<h2>类映射</h2><table><thead><tr><th>源类</th><th>pre-R8</th><th>R8</th></tr></thead><tbody>{class_rows}</tbody></table>
<h2>函数映射</h2><table><thead><tr><th>中文说明</th><th>Kotlin 源签名</th><th>pre-R8 JVM</th><th>R8 JVM</th></tr></thead><tbody>{function_rows}</tbody></table>""",
        encoding="utf-8",
    )


def write_maven_repository(job_dir: Path, package_name: str, sdk_version: str, r8_aar: Path) -> tuple[Path, str]:
    group_id = "com.lawnchair.generated"
    artifact_id = f"{package_name}-launcher-sdk"
    package_hash = hashlib.sha256(package_name.encode()).hexdigest()[:8]
    version = f"{sdk_version}-{package_hash}"
    artifact_dir = job_dir / "maven" / Path(*group_id.split(".")) / artifact_id / version
    artifact_dir.mkdir(parents=True, exist_ok=True)
    artifact_name = f"{artifact_id}-{version}"
    shutil.copy2(r8_aar, artifact_dir / f"{artifact_name}.aar")
    pom = f"""<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<project xmlns=\"http://maven.apache.org/POM/4.0.0\" xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xsi:schemaLocation=\"http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd\">
  <modelVersion>4.0.0</modelVersion><groupId>{group_id}</groupId><artifactId>{artifact_id}</artifactId><version>{version}</version><packaging>aar</packaging>
  <dependencies><dependency><groupId>com.github.vihuela.lawnchair-sdk</groupId><artifactId>launcher-core</artifactId><version>v{sdk_version}</version></dependency></dependencies>
</project>
"""
    (artifact_dir / f"{artifact_name}.pom").write_text(pom, encoding="utf-8")
    coordinate = f"{group_id}:{artifact_id}:{version}"
    return job_dir / "maven", coordinate


def make_bundle(job_dir: Path, package_name: str, sdk_version: str, comparison: dict, abi_validation: dict) -> dict:
    aar_dir = MODULE_DIR / "build" / "outputs" / "aar"
    mapping_dir = MODULE_DIR / "build" / "outputs" / "mapping" / "release"
    generated_source = MODULE_DIR / "build" / "generated" / "source" / "client" / "main" / "kotlin" / "ClientLauncherSdk.kt"
    pre_aar = aar_dir / "launcher-sdk-client-preR8.aar"
    r8_aar = aar_dir / "launcher-sdk-client-release.aar"
    mapping = mapping_dir / "mapping.txt"
    for required in (pre_aar, r8_aar, mapping, generated_source):
        if not required.is_file():
            raise RuntimeError(f"缺少构建产物：{required}")

    downloads = job_dir / "downloads"
    downloads.mkdir(parents=True, exist_ok=True)
    copied_pre = downloads / f"{package_name}-launcher-sdk-pre-r8.aar"
    copied_r8 = downloads / f"{package_name}-launcher-sdk-r8.aar"
    copied_mapping = downloads / "mapping.txt"
    copied_source = downloads / "ClientLauncherSdk.kt"
    shutil.copy2(pre_aar, copied_pre)
    shutil.copy2(r8_aar, copied_r8)
    shutil.copy2(mapping, copied_mapping)
    shutil.copy2(generated_source, copied_source)
    (downloads / "api-comparison.json").write_text(json.dumps(comparison, ensure_ascii=False, indent=2), encoding="utf-8")
    (downloads / "abi-validation.json").write_text(json.dumps(abi_validation, ensure_ascii=False, indent=2), encoding="utf-8")
    write_report_html(downloads / "api-comparison.html", package_name, comparison)

    maven_root, coordinate = write_maven_repository(job_dir, package_name, sdk_version, copied_r8)
    repository_zip = downloads / f"{package_name}-launcher-maven.zip"
    shutil.make_archive(str(repository_zip.with_suffix("")), "zip", root_dir=maven_root)
    integration = f"""# {package_name} Launcher SDK 接入

```kotlin
repositories {{ maven {{ url = uri(\"/absolute/path/to/extracted/maven\") }}; maven(\"https://jitpack.io\") }}
dependencies {{ implementation(\"{coordinate}\") }}
```

业务 Entry 继承 `api-comparison.html` 中 `ClientLauncherEntry` 对应的 R8 类，并按函数表覆盖
负一屏、桌面 Item 和 Icon 拦截回调。宿主 `Application.onCreate` 调用 R8 后的
`initialize(application, entry)`，将业务 Entry 实例传入。AAR 会自动合并内部 Entry 的
Manifest metadata，业务不再引用 `LawnchairSdkEntry`、`MinusOneExtension` 等稳定内部类型。
"""
    (downloads / "integration.md").write_text(integration, encoding="utf-8")

    bundle_base = job_dir / f"{package_name}-launcher-sdk-bundle"
    bundle_zip = Path(shutil.make_archive(str(bundle_base), "zip", root_dir=downloads))
    final_bundle = downloads / bundle_zip.name
    shutil.move(bundle_zip, final_bundle)
    files = [copied_r8, copied_pre, copied_mapping, copied_source, downloads / "api-comparison.json", downloads / "abi-validation.json", downloads / "api-comparison.html", downloads / "integration.md", repository_zip, final_bundle]
    return {
        "coordinate": coordinate,
        "repositorySnippet": 'maven { url = uri("/absolute/path/to/extracted/maven") }',
        "dependencySnippet": f'implementation("{coordinate}")',
        "comparison": comparison,
        "abiValidation": abi_validation,
        "downloads": [{"name": file.name, "url": f"/api/jobs/{job_dir.name}/download/{file.name}"} for file in files],
    }


def build_job(job: Job) -> None:
    try:
        job.state = "running"
        with BUILD_LOCK:
            update_job(job, progress=8, message="生成包专属源码与混淆字典")
            gradle_args = [
                str(ROOT / "gradlew"),
                ":launcher-sdk-client:clean",
                ":launcher-sdk-client:assemblePreR8",
                ":launcher-sdk-client:assembleRelease",
                f"-PlauncherClientPackage={job.package_name}",
                "--no-configuration-cache",
                "--console=plain",
            ]
            update_job(job, progress=18, message="编译 pre-R8 与 R8 AAR")
            run_process(job, gradle_args, ROOT)
            update_job(job, progress=72, message="解析 mapping 与 JVM 公开签名")
            job_dir = OUTPUT_ROOT / job.id
            job_dir.mkdir(parents=True, exist_ok=True)
            pre_jar = job_dir / "pre-r8-classes.jar"
            r8_jar = job_dir / "r8-classes.jar"
            pre_aar = MODULE_DIR / "build" / "outputs" / "aar" / "launcher-sdk-client-preR8.aar"
            r8_aar = MODULE_DIR / "build" / "outputs" / "aar" / "launcher-sdk-client-release.aar"
            mapping = MODULE_DIR / "build" / "outputs" / "mapping" / "release" / "mapping.txt"
            extract_classes_jar(pre_aar, pre_jar)
            extract_classes_jar(r8_aar, r8_jar)
            comparison = build_comparison(job.package_name, pre_jar, r8_jar, mapping)
            abi_validation = validate_business_abi(comparison)
            update_job(job, progress=88, message="整理 Maven 依赖与下载包")
            result = make_bundle(job_dir, job.package_name, job.sdk_version, comparison, abi_validation)
            with JOBS_LOCK:
                job.result = result
                job.state = "completed"
                job.progress = 100
                job.message = "构建完成"
    except Exception as error:
        with JOBS_LOCK:
            job.state = "failed"
            job.error = str(error)
            job.message = "构建失败"
        append_log(job, f"ERROR: {error}")


class Handler(BaseHTTPRequestHandler):
    server_version = "LawnchairSdkBuilder/1.0"

    def log_message(self, format_string: str, *args: object) -> None:
        print(f"[{self.log_date_time_string()}] {format_string % args}")

    def send_json(self, payload: dict, status: HTTPStatus = HTTPStatus.OK) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.end_headers()
        self.wfile.write(body)

    def serve_file(self, path: Path, content_type: str, download_name: str | None = None) -> None:
        if not path.is_file():
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        data = path.read_bytes()
        self.send_response(HTTPStatus.OK)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Access-Control-Allow-Origin", "*")
        if download_name:
            self.send_header("Content-Disposition", f'attachment; filename="{download_name}"')
        self.end_headers()
        self.wfile.write(data)

    def do_OPTIONS(self) -> None:
        self.send_response(HTTPStatus.NO_CONTENT)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "Content-Type")
        self.send_header("Access-Control-Max-Age", "600")
        self.end_headers()

    def do_GET(self) -> None:
        parsed = urlparse(self.path)
        if parsed.path in ("/", "/index.html"):
            self.serve_file(STATIC_DIR / "index.html", "text/html; charset=utf-8")
            return
        if parsed.path == "/api/config":
            sdk_version = resolve_current_sdk_version()
            self.send_json(
                {
                    "sdkVersion": sdk_version,
                    "versionSource": "launcher-core/build.gradle",
                }
            )
            return
        match = re.fullmatch(r"/api/jobs/([0-9a-f]+)/?", parsed.path)
        if match:
            with JOBS_LOCK:
                job = JOBS.get(match.group(1))
                payload = job.public() if job else None
            self.send_json(payload if payload else {"error": "任务不存在"}, HTTPStatus.OK if payload else HTTPStatus.NOT_FOUND)
            return
        download = re.fullmatch(r"/api/jobs/([0-9a-f]+)/download/([^/]+)", parsed.path)
        if download:
            job_id, raw_name = download.groups()
            name = Path(unquote(raw_name)).name
            with JOBS_LOCK:
                exists = job_id in JOBS
            if not exists:
                self.send_error(HTTPStatus.NOT_FOUND)
                return
            self.serve_file(OUTPUT_ROOT / job_id / "downloads" / name, "application/octet-stream", name)
            return
        self.send_error(HTTPStatus.NOT_FOUND)

    def do_POST(self) -> None:
        if urlparse(self.path).path != "/api/build":
            self.send_error(HTTPStatus.NOT_FOUND)
            return
        try:
            length = int(self.headers.get("Content-Length", "0"))
            payload = json.loads(self.rfile.read(length).decode("utf-8"))
        except (ValueError, json.JSONDecodeError):
            self.send_json({"error": "请求 JSON 无效"}, HTTPStatus.BAD_REQUEST)
            return
        package_name = str(payload.get("packageName", "")).strip()
        if not PACKAGE_PATTERN.fullmatch(package_name):
            self.send_json(
                {"error": "Android 包名格式无效：至少两段，每段以字母开头，且只能包含字母、数字和下划线"},
                HTTPStatus.BAD_REQUEST,
            )
            return
        job = Job(
            id=uuid.uuid4().hex[:12],
            package_name=package_name,
            sdk_version=resolve_current_sdk_version(),
        )
        with JOBS_LOCK:
            JOBS[job.id] = job
        threading.Thread(target=build_job, args=(job,), daemon=True).start()
        self.send_json(job.public(), HTTPStatus.ACCEPTED)


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--host", default="127.0.0.1")
    parser.add_argument("--port", default=8765, type=int)
    args = parser.parse_args()
    OUTPUT_ROOT.mkdir(parents=True, exist_ok=True)
    server = ThreadingHTTPServer((args.host, args.port), Handler)
    print(f"Lawnchair SDK Builder: http://{args.host}:{args.port}")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
