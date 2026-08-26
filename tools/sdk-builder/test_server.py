from __future__ import annotations

import importlib.util
import io
import os
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch


SERVER_PATH = Path(__file__).with_name("server.py")
SPEC = importlib.util.spec_from_file_location("launcher_sdk_builder_server", SERVER_PATH)
SERVER = importlib.util.module_from_spec(SPEC)
sys.modules[SPEC.name] = SERVER
SPEC.loader.exec_module(SERVER)


class SourceWorkspaceTest(unittest.TestCase):
    def create_source_archive(self, directory: Path) -> Path:
        source = directory / "source" / "repo-123456789abc"
        for relative_path in SERVER.SOURCE_REQUIRED_PATHS:
            path = source / relative_path
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text("#!/bin/sh\n" if relative_path == "gradlew" else "", encoding="utf-8")
        (source / "prebuilts").mkdir()
        (source / "sdk-local" / "prebuilts").symlink_to("../prebuilts")
        archive_path = directory / "source.tar.gz"
        with tarfile.open(archive_path, "w:gz") as archive:
            archive.add(source, arcname=source.name)
        return archive_path

    def test_prepare_downloaded_workspace_and_cleanup(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            archive_path = self.create_source_archive(Path(temp))
            environment = {
                "SDK_SOURCE_MODE": "github",
                "GITHUB_SOURCE_ARCHIVE_URL": archive_path.as_uri(),
                "GITHUB_SOURCE_REF": "feature/sdk-15",
            }
            job = SERVER.Job("test", "com.example.game", "0.1", "user", "token")
            with patch.dict(os.environ, environment, clear=False):
                workspace = SERVER.prepare_source_workspace(job)
            temporary_root = workspace.temporary_root
            self.assertTrue((workspace.root / "launcher-sdk-client" / "build.gradle").is_file())
            self.assertEqual("123456789abc", workspace.revision)
            self.assertEqual("vihuela/lawnchair-sdk@feature/sdk-15", workspace.source_display)
            workspace.cleanup()
            self.assertFalse(temporary_root.exists())

    def test_rejects_archive_path_traversal(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            archive_path = Path(temp) / "invalid.tar.gz"
            with tarfile.open(archive_path, "w:gz") as archive:
                content = b"escape"
                entry = tarfile.TarInfo("../outside.txt")
                entry.size = len(content)
                archive.addfile(entry, io.BytesIO(content))
            with self.assertRaisesRegex(RuntimeError, "越界路径"):
                SERVER.safe_extract_source_archive(archive_path, Path(temp) / "output")

    def test_build_uses_downloaded_root_and_cleans_it_after_failure(self) -> None:
        temporary_root = Path(tempfile.mkdtemp(prefix="launcher-sdk-builder-test-"))
        source_root = temporary_root / "source"
        (source_root / "launcher-core").mkdir(parents=True)
        (source_root / "launcher-sdk-client").mkdir()
        (source_root / "launcher-core" / "build.gradle").write_text(
            'def sdkPublishVersion = providers.environmentVariable("VERSION").orElse("0.1-local")\n',
            encoding="utf-8",
        )
        workspace = SERVER.SourceWorkspace(
            source_root,
            source_root / "launcher-sdk-client",
            "test/repository@main",
            "123456789abc",
            temporary_root,
        )
        job = SERVER.Job("failure", "com.example.game", "0.1", "user", "token-value")
        captured = {}

        def fail_process(_job, args, cwd, _env_overrides=None):
            captured["args"] = args
            captured["cwd"] = cwd
            raise RuntimeError("expected build failure")

        with (
            patch.object(SERVER, "prepare_source_workspace", return_value=workspace),
            patch.object(
                SERVER,
                "resolve_package_version",
                return_value={"nextVersion": "0.1"},
            ),
            patch.object(SERVER, "run_process", side_effect=fail_process),
        ):
            SERVER.build_job(job)

        self.assertEqual(source_root, captured["cwd"])
        self.assertEqual(str(source_root / "gradlew"), captured["args"][0])
        self.assertEqual("failed", job.state)
        self.assertFalse(temporary_root.exists())
        self.assertEqual("", job.github_token)


class ComposeStabilityValidationTest(unittest.TestCase):
    def test_accepts_preserved_stable_field_name(self) -> None:
        comparison = {
            "classes": [{"source": "source.Entry", "preR8": "source.Entry", "r8": "r8.Entry"}],
        }
        declarations = {
            "source.Entry": ["public static final int $stable"],
            "r8.Entry": ["public static final int $stable"],
        }
        with patch.object(
            SERVER,
            "javap_declarations",
            side_effect=lambda _jar, class_name: declarations[class_name],
        ):
            checked = SERVER.validate_compose_stability_fields(
                comparison,
                Path("pre.jar"),
                Path("r8.jar"),
            )
        self.assertEqual(1, checked)

    def test_rejects_obfuscated_stable_field_name(self) -> None:
        comparison = {
            "classes": [{"source": "source.Entry", "preR8": "source.Entry", "r8": "r8.Entry"}],
        }
        declarations = {
            "source.Entry": ["public static final int $stable"],
            "r8.Entry": ["public static final int randomized"],
        }
        with (
            patch.object(
                SERVER,
                "javap_declarations",
                side_effect=lambda _jar, class_name: declarations[class_name],
            ),
            self.assertRaisesRegex(RuntimeError, r"Compose \$stable 字段未保留原名"),
        ):
            SERVER.validate_compose_stability_fields(
                comparison,
                Path("pre.jar"),
                Path("r8.jar"),
            )


class KotlinObjectInstanceValidationTest(unittest.TestCase):
    def test_accepts_preserved_instance_field_name(self) -> None:
        comparison = {
            "classes": [{"source": "source.Facade", "preR8": "source.Facade", "r8": "r8.Facade"}],
        }
        declarations = {
            "source.Facade": ["public static final source.Facade INSTANCE"],
            "r8.Facade": ["public static final r8.Facade INSTANCE"],
        }
        with patch.object(
            SERVER,
            "javap_declarations",
            side_effect=lambda _jar, class_name: declarations[class_name],
        ):
            checked = SERVER.validate_kotlin_object_instance_fields(
                comparison,
                Path("pre.jar"),
                Path("r8.jar"),
            )
        self.assertEqual(1, checked)

    def test_rejects_obfuscated_instance_field_name(self) -> None:
        comparison = {
            "classes": [{"source": "source.Facade", "preR8": "source.Facade", "r8": "r8.Facade"}],
        }
        declarations = {
            "source.Facade": ["public static final source.Facade INSTANCE"],
            "r8.Facade": ["public static final r8.Facade randomized"],
        }
        with (
            patch.object(
                SERVER,
                "javap_declarations",
                side_effect=lambda _jar, class_name: declarations[class_name],
            ),
            self.assertRaisesRegex(RuntimeError, "Kotlin object INSTANCE 字段未保留原名"),
        ):
            SERVER.validate_kotlin_object_instance_fields(
                comparison,
                Path("pre.jar"),
                Path("r8.jar"),
            )


class PublicDefaultArgumentBridgeValidationTest(unittest.TestCase):
    def test_accepts_public_api_without_default_bridges(self) -> None:
        comparison = {
            "functions": [{"sourceClass": "source.Facade"}],
        }
        with patch.object(
            SERVER,
            "javap_declarations",
            return_value=["public static final java.lang.Object command(boolean, kotlin.coroutines.Continuation)"],
        ):
            checked = SERVER.validate_no_public_default_argument_bridges(
                comparison,
                Path("pre.jar"),
            )
        self.assertEqual(1, checked)

    def test_rejects_public_default_bridge(self) -> None:
        comparison = {
            "functions": [{"sourceClass": "source.Facade"}],
        }
        declarations = [
            "public static java.lang.Object command$default(boolean, kotlin.coroutines.Continuation, int, java.lang.Object)",
        ]
        with (
            patch.object(SERVER, "javap_declarations", return_value=declarations),
            self.assertRaisesRegex(RuntimeError, "Kotlin 默认参数桥接"),
        ):
            SERVER.validate_no_public_default_argument_bridges(
                comparison,
                Path("pre.jar"),
            )


if __name__ == "__main__":
    unittest.main()
