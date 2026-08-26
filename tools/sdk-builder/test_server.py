from __future__ import annotations

import importlib.util
import io
import os
import sys
import tarfile
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
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


class MappingParserTest(unittest.TestCase):
    def test_parses_class_method_and_field_descriptors(self) -> None:
        mapping_text = """\
source.Entry -> r8.A:
    source.Value INSTANCE -> INSTANCE
    java.lang.Object execute(java.lang.String,boolean,kotlin.coroutines.Continuation) -> a
"""
        with tempfile.TemporaryDirectory() as temp:
            mapping = Path(temp) / "mapping.txt"
            mapping.write_text(mapping_text, encoding="utf-8")
            classes, methods, fields = SERVER.parse_mapping(mapping)

        self.assertEqual("r8.A", classes["source.Entry"])
        self.assertEqual(
            "(Ljava/lang/String;ZLkotlin/coroutines/Continuation;)Ljava/lang/Object;",
            methods[("source.Entry", "execute")][0]["descriptor"],
        )
        self.assertEqual(
            "Lsource/Value;",
            fields[("source.Entry", "INSTANCE")][0]["descriptor"],
        )


class JavapPublicAbiParserTest(unittest.TestCase):
    def test_distinguishes_constructor_from_method_named_like_obfuscated_class(self) -> None:
        output = """\
public final class r8.A {
  public r8.A();
    descriptor: ()V
  public final int A();
    descriptor: ()I
}
"""
        completed = SimpleNamespace(returncode=0, stdout=output, stderr="")
        with patch.object(SERVER.subprocess, "run", return_value=completed):
            abi = SERVER.javap_public_abi(Path("classes.jar"), "r8.A")

        self.assertTrue(abi["exists"])
        self.assertEqual(
            [("constructor", "<init>", "()V"), ("method", "A", "()I")],
            [(row["kind"], row["name"], row["descriptor"]) for row in abi["members"]],
        )


class PublicAbiMemberComparisonTest(unittest.TestCase):
    def member_abis(self) -> tuple[dict, dict]:
        return (
            {
                "members": [
                    {
                        "kind": "method",
                        "name": "execute",
                        "displayName": "execute",
                        "descriptor": "(Ljava/lang/String;)V",
                        "declaration": "public final void execute(java.lang.String)",
                    },
                ],
            },
            {
                "members": [
                    {
                        "kind": "method",
                        "name": "a",
                        "displayName": "a",
                        "descriptor": "(Ljava/lang/String;)V",
                        "declaration": "public final void a(java.lang.String)",
                    },
                ],
            },
        )

    def test_matches_member_by_mapping_name_and_descriptor(self) -> None:
        pre_abi, r8_abi = self.member_abis()
        method_map = {
            ("source.Entry", "execute"): [
                {"descriptor": "(Ljava/lang/String;)V", "r8Name": "a"},
            ],
        }
        rows = SERVER.public_abi_member_rows(
            "source.Entry",
            "r8.A",
            pre_abi,
            r8_abi,
            {"source.Entry": "r8.A"},
            method_map,
            {},
        )
        self.assertEqual("", rows[0]["violation"])
        self.assertEqual("a", rows[0]["r8Name"])

    def test_marks_public_member_without_mapping(self) -> None:
        pre_abi, r8_abi = self.member_abis()
        rows = SERVER.public_abi_member_rows(
            "source.Entry",
            "r8.A",
            pre_abi,
            r8_abi,
            {"source.Entry": "r8.A"},
            {},
            {},
        )
        self.assertEqual("mapping 中缺少公开成员", rows[0]["violation"])

    def test_marks_r8_descriptor_drift(self) -> None:
        pre_abi, r8_abi = self.member_abis()
        r8_abi["members"][0]["descriptor"] = "(I)V"
        method_map = {
            ("source.Entry", "execute"): [
                {"descriptor": "(Ljava/lang/String;)V", "r8Name": "a"},
            ],
        }
        rows = SERVER.public_abi_member_rows(
            "source.Entry",
            "r8.A",
            pre_abi,
            r8_abi,
            {"source.Entry": "r8.A"},
            method_map,
            {},
        )
        self.assertIn("descriptor 不一致", rows[0]["violation"])

    def test_marks_ambiguous_mapping_for_same_jvm_signature(self) -> None:
        pre_abi, r8_abi = self.member_abis()
        method_map = {
            ("source.Entry", "execute"): [
                {"descriptor": "(Ljava/lang/String;)V", "r8Name": "a"},
                {"descriptor": "(Ljava/lang/String;)V", "r8Name": "b"},
            ],
        }
        rows = SERVER.public_abi_member_rows(
            "source.Entry",
            "r8.A",
            pre_abi,
            r8_abi,
            {"source.Entry": "r8.A"},
            method_map,
            {},
        )
        self.assertIn("多个 R8 成员映射", rows[0]["violation"])


class PublicR8AbiValidationTest(unittest.TestCase):
    def valid_comparison(self) -> dict:
        return {
            "classes": [
                {
                    "source": "source.Entry",
                    "preR8": "source.Entry",
                    "r8": "r8.A",
                    "violation": "",
                },
            ],
            "publicAbiMembers": [
                {
                    "sourceClass": "source.Entry",
                    "sourceName": "execute",
                    "r8Name": "a",
                    "kind": "method",
                    "preDescriptor": "(Ljava/lang/String;)V",
                    "preDeclaration": "public final void execute(java.lang.String)",
                    "r8Declaration": "public final void a(java.lang.String)",
                    "violation": "",
                },
                {
                    "sourceClass": "source.Entry",
                    "sourceName": "<init>",
                    "r8Name": "<init>",
                    "kind": "constructor",
                    "preDescriptor": "()V",
                    "preDeclaration": "public source.Entry()",
                    "r8Declaration": "public r8.A()",
                    "violation": "",
                },
            ],
            "untrackedPublicClasses": [],
        }

    def test_accepts_complete_randomized_public_abi(self) -> None:
        result = SERVER.validate_public_r8_abi(self.valid_comparison())
        self.assertEqual(1, result["checkedPublicClassCount"])
        self.assertEqual(2, result["checkedPublicMemberCount"])
        self.assertEqual(1, result["randomizedPublicMemberCount"])
        self.assertEqual({"method": 1, "constructor": 1}, result["publicMemberKindCounts"])

    def test_rejects_missing_or_changed_public_member(self) -> None:
        comparison = self.valid_comparison()
        comparison["publicAbiMembers"][0]["violation"] = "R8 产物缺少映射后的公开成员"
        with self.assertRaisesRegex(RuntimeError, "缺少映射后的公开成员"):
            SERVER.validate_public_r8_abi(comparison)

    def test_rejects_class_signature_drift(self) -> None:
        comparison = self.valid_comparison()
        comparison["classes"][0]["violation"] = "R8 类声明的继承关系发生变化"
        with self.assertRaisesRegex(RuntimeError, "继承关系发生变化"):
            SERVER.validate_public_r8_abi(comparison)

    def test_rejects_untracked_public_class(self) -> None:
        comparison = self.valid_comparison()
        comparison["untrackedPublicClasses"] = ["source.NewPublicType"]
        with self.assertRaisesRegex(RuntimeError, "公开类未登记"):
            SERVER.validate_public_r8_abi(comparison)

    def test_rejects_fixed_sdk_type_leak_from_any_public_member(self) -> None:
        comparison = self.valid_comparison()
        comparison["publicAbiMembers"][0]["preDeclaration"] = (
            "public final app.lawnchair.sdk.api.Result execute(java.lang.String)"
        )
        with self.assertRaisesRegex(RuntimeError, "公开 ABI 泄露固定 SDK 类型"):
            SERVER.validate_public_r8_abi(comparison)

    def test_allows_r8_to_widen_protected_member_to_public(self) -> None:
        self.assertTrue(
            SERVER.public_declaration_compatible(
                "protected abstract void execute()",
                "public abstract void execute()",
            ),
        )
        self.assertFalse(
            SERVER.public_declaration_compatible(
                "public abstract void execute()",
                "protected abstract void execute()",
            ),
        )


class KotlinDefaultBridgeNameValidationTest(unittest.TestCase):
    def test_accepts_bridge_linked_to_preserved_copy_name(self) -> None:
        comparison = {
            "publicAbiMembers": [
                {
                    "sourceClass": "source.Model",
                    "sourceName": "copy",
                    "r8Name": "copy",
                    "kind": "method",
                },
                {
                    "sourceClass": "source.Model",
                    "sourceName": "copy$default",
                    "r8Name": "copy$default",
                    "kind": "method",
                },
            ],
        }
        self.assertEqual(1, SERVER.validate_kotlin_default_bridge_names(comparison))

    def test_rejects_bridge_detached_from_randomized_base_name(self) -> None:
        comparison = {
            "publicAbiMembers": [
                {
                    "sourceClass": "source.Facade",
                    "sourceName": "command",
                    "r8Name": "a",
                    "kind": "method",
                },
                {
                    "sourceClass": "source.Facade",
                    "sourceName": "command$default",
                    "r8Name": "b",
                    "kind": "method",
                },
            ],
        }
        with self.assertRaisesRegex(RuntimeError, "默认参数 ABI 命名"):
            SERVER.validate_kotlin_default_bridge_names(comparison)


class BuilderPageAiContextTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls) -> None:
        cls.page = (SERVER.STATIC_DIR / "index.html").read_text(encoding="utf-8")

    def test_exposes_separate_business_and_full_abi_copies(self) -> None:
        self.assertIn('id="copyBusinessContext"', self.page)
        self.assertIn('id="copyFullAbiContext"', self.page)
        self.assertIn('data-tab="publicAbi"', self.page)
        self.assertIn('id="publicAbiTable"', self.page)
        self.assertIn("comparison?.publicAbiMembers", self.page)

    def test_ai_context_uses_local_properties_credentials(self) -> None:
        self.assertIn('localProperties.getProperty("github.packages.username")', self.page)
        self.assertIn('localProperties.getProperty("github.packages.token")', self.page)
        self.assertIn('github.packages.username=', self.page)
        self.assertIn('github.packages.token=', self.page)
        self.assertNotIn('providers.gradleProperty("gpr.user")', self.page)
        self.assertNotIn('providers.gradleProperty("gpr.key")', self.page)

    def test_ai_context_documents_explicit_arguments_and_preserved_members(self) -> None:
        self.assertIn("所有调用参数（包括 Boolean）", self.page)
        self.assertIn("`copy`、`copy$default`、`$stable`、`INSTANCE`", self.page)
        self.assertIn("checkedPublicMemberCount", self.page)
        self.assertIn("publicMemberKindCounts", self.page)


if __name__ == "__main__":
    unittest.main()
