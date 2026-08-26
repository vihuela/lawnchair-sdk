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


if __name__ == "__main__":
    unittest.main()
