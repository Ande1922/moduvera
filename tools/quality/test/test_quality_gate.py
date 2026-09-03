from __future__ import annotations

import datetime as dt
import importlib.util
import os
from pathlib import Path
import shlex
import shutil
import signal
import stat
import subprocess
import sys
import tempfile
import time
import unittest
from unittest import mock


CORE_PATH = Path(__file__).resolve().parents[1] / "quality_gate.py"
SPEC = importlib.util.spec_from_file_location("quality_gate", CORE_PATH)
assert SPEC and SPEC.loader
quality_gate = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(quality_gate)


class RepositoryFixture:
    def __init__(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.git("init", "-q")
        self.git("config", "user.email", "quality-gate@example.invalid")
        self.git("config", "user.name", "Quality Gate Fixture")

    def close(self) -> None:
        self.temporary.cleanup()

    def git(self, *arguments: str) -> str:
        return subprocess.run(
            ["git", *arguments], cwd=self.root, check=True, text=True, capture_output=True
        ).stdout.strip()

    def write(self, path: str, content: str, mode: int = 0o644) -> None:
        target = self.root / path
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text(content, encoding="utf-8")
        target.chmod(mode)

    def commit(self, message: str) -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")


class QualityGatePolicyTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = RepositoryFixture()
        self.addCleanup(self.fixture.close)
        self.fixture.write("README.md", "# Fixture\n")
        self.base = self.fixture.commit("base")

    def classify_after(self, path: str, content: str, mode: int = 0o644):
        self.fixture.write(path, content, mode)
        head = self.fixture.commit("change")
        return quality_gate.classify(self.fixture.root, self.base, head)

    def test_regular_markdown_is_docs_only(self) -> None:
        profile, reason, _ = self.classify_after("docs/guide.md", "# Guide\n")
        self.assertEqual("docs-only", profile)
        self.assertIn("regular Markdown", reason)

    def test_mixed_change_is_normal(self) -> None:
        self.fixture.write("docs/guide.md", "# Guide\n")
        self.fixture.write("pom.xml", "<project/>\n")
        head = self.fixture.commit("mixed")
        profile, reason, _ = quality_gate.classify(self.fixture.root, self.base, head)
        self.assertEqual("normal", profile)
        self.assertIn("non-Markdown", reason)

    def test_unknown_extension_is_normal(self) -> None:
        profile, _, _ = self.classify_after("docs/guide.unknown", "content\n")
        self.assertEqual("normal", profile)

    def test_deleted_renamed_and_symlink_markdown_are_normal(self) -> None:
        self.fixture.write("old.md", "old\n")
        prior = self.fixture.commit("old")
        (self.fixture.root / "old.md").unlink()
        deleted = self.fixture.commit("delete")
        self.assertEqual("normal", quality_gate.classify(self.fixture.root, prior, deleted)[0])

        self.fixture.git("mv", "README.md", "renamed.md")
        renamed = self.fixture.commit("rename")
        self.assertEqual("normal", quality_gate.classify(self.fixture.root, deleted, renamed)[0])

        os.symlink("renamed.md", self.fixture.root / "linked.md")
        linked = self.fixture.commit("link")
        self.assertEqual("normal", quality_gate.classify(self.fixture.root, renamed, linked)[0])

    def test_missing_local_markdown_link_fails(self) -> None:
        self.fixture.write("docs/guide.md", "[missing](./absent.md)\n")
        head = self.fixture.commit("broken link")
        entries = quality_gate.changed_entries(self.fixture.root, self.base, head)
        with self.assertRaises(quality_gate.GateError):
            quality_gate.check_markdown_links(
                self.fixture.root, head, quality_gate.changed_markdown_paths(entries)
            )

    def test_renamed_and_copied_markdown_destinations_recheck_relative_links(self) -> None:
        self.fixture.write("docs/target.md", "# Target\n")
        self.fixture.write("docs/guide.md", "[target](./target.md)\n")
        prior = self.fixture.commit("linked guide")
        (self.fixture.root / "docs/moved").mkdir()
        self.fixture.git("mv", "docs/guide.md", "docs/moved/guide.md")
        renamed = self.fixture.commit("move guide")
        renamed_entries = quality_gate.changed_entries(self.fixture.root, prior, renamed)
        self.assertIn("docs/moved/guide.md", quality_gate.changed_markdown_paths(renamed_entries))
        with self.assertRaises(quality_gate.GateError):
            quality_gate.check_markdown_links(
                self.fixture.root,
                renamed,
                quality_gate.changed_markdown_paths(renamed_entries),
            )

        self.fixture.write("docs/copy.md", "[target](./target.md)\n")
        source = self.fixture.commit("copy source")
        shutil.copyfile(
            self.fixture.root / "docs/copy.md",
            self.fixture.root / "docs/moved/copy.md",
        )
        copied = self.fixture.commit("copy guide")
        copied_entries = quality_gate.changed_entries(self.fixture.root, source, copied)
        self.assertIn("docs/moved/copy.md", quality_gate.changed_markdown_paths(copied_entries))
        with self.assertRaises(quality_gate.GateError):
            quality_gate.check_markdown_links(
                self.fixture.root,
                copied,
                quality_gate.changed_markdown_paths(copied_entries),
            )

    def test_project_skill_requires_structured_skill_file(self) -> None:
        self.fixture.write(".agents/skills/example/notes.md", "missing SKILL.md\n")
        head = self.fixture.commit("invalid skill")
        with self.assertRaises(quality_gate.GateError):
            quality_gate.check_skill_structure(self.fixture.root, head)

        self.fixture.write(
            ".agents/skills/example/SKILL.md",
            "---\nname: example\ndescription: Fixture Skill\n---\n\n# Example\n",
        )
        valid = self.fixture.commit("valid skill")
        quality_gate.check_skill_structure(self.fixture.root, valid)

    def test_sensitive_added_content_fails_without_echoing_secret(self) -> None:
        exposed_value = "ghp_" + "123456789012345678901234567890"
        credential_key = "to" + "ken"
        self.fixture.write("notes.md", f"{credential_key}={exposed_value}\n")
        head = self.fixture.commit("credential")
        with self.assertRaises(quality_gate.GateError) as caught:
            quality_gate.check_sensitive_content(self.fixture.root, self.base, head)
        self.assertNotIn(exposed_value, str(caught.exception))

    def test_common_credential_key_forms_are_scanned_and_redacted(self) -> None:
        value = "credential-" + "v@lue:1234567890!"
        keys = (
            "client_secret",
            "client-secret",
            "access_token",
            "access-token",
            "refresh_token",
            "refresh-token",
        )
        double_quote = '"'
        single_quote = "'"
        assignments = (
            f"{double_quote}{keys[0]}{double_quote}: {double_quote}{value}{double_quote}",
            f"{single_quote}{keys[1]}{single_quote}={single_quote}{value}{single_quote}",
            f"{double_quote}{keys[2]}{double_quote}:{double_quote}{value}{double_quote}",
            f"{single_quote}{keys[3]}{single_quote}: {single_quote}{value}{single_quote}",
            f"{double_quote}{keys[4]}{double_quote}: {double_quote}{value}{double_quote}",
            f"{keys[5]}={value}",
        )
        for assignment in assignments:
            self.assertIsNotNone(quality_gate.CREDENTIAL_ASSIGNMENT.search(assignment))
            self.assertNotIn(value, quality_gate.redact(assignment))
        self.fixture.write("notes.md", "\n".join(assignments) + "\n")
        head = self.fixture.commit("credentials")
        with self.assertRaises(quality_gate.GateError) as caught:
            quality_gate.check_sensitive_content(self.fixture.root, self.base, head)
        self.assertNotIn(value, str(caught.exception))
        redacted = quality_gate.redact("\n".join(assignments) + "\n")
        self.assertNotIn(value, redacted)
        self.assertEqual(len(assignments), redacted.count("[REDACTED]"))
        self.assertIn('"client_secret": "[REDACTED]"', redacted)
        self.assertIn("'client-secret'='[REDACTED]'", redacted)
        self.assertIn('"access_token":"[REDACTED]"', redacted)

    def test_private_key_redaction_removes_entire_multiline_block(self) -> None:
        begin = "-----BEGIN " + "PRIVATE KEY-----"
        end = "-----END " + "PRIVATE KEY-----"
        payload = "c2Vuc2l0aXZlLXByaXZhdGUta2V5"
        result = quality_gate.redact(f"before\n{begin}\n{payload}\n{end}\nafter\n")
        self.assertEqual("before\n[REDACTED_PRIVATE_KEY_BLOCK]\nafter\n", result)
        self.assertNotIn(begin, result)
        self.assertNotIn(payload, result)
        self.assertNotIn(end, result)

    def test_escaped_multiline_json_credential_is_detected_and_redacted(self) -> None:
        key = "client_" + "secret"
        exposed_value = "live-escaped-" + r'quote\"-value-123456'
        content = f'"{key}":\n  "{exposed_value}"'
        match = quality_gate.CREDENTIAL_ASSIGNMENT.search(content)
        self.assertIsNotNone(match)
        assert match is not None
        self.assertTrue(quality_gate.is_sensitive_credential_assignment(match))
        self.assertNotIn(exposed_value, quality_gate.redact(content))
        self.fixture.write("config.json", "{\n" + content + "\n}\n")
        head = self.fixture.commit("multiline escaped credential")
        with self.assertRaises(quality_gate.GateError):
            quality_gate.check_sensitive_content(self.fixture.root, self.base, head)

    def test_placeholders_and_code_expressions_avoid_false_positives(self) -> None:
        secret_key = "client_" + "secret"
        access_key = "access_" + "token"
        refresh_key = "refresh_" + "token"
        safe_content = "\n".join(
            (
                f"{secret_key} = request.token()",
                f"{access_key} = Optional.empty()",
                f"{refresh_key} = null",
                f"{secret_key} = ${{DB_PASSWORD}}",
            )
        )
        self.assertFalse(
            quality_gate._hunk_has_sensitive_content(safe_content, "TestConfig.java")
        )
        self.fixture.write(
            "src/TestConfig.java",
            "class TestConfig {\n"
            f"  Object a = {safe_content.splitlines()[0].split(' = ', 1)[1]};\n"
            f"  Object b = {safe_content.splitlines()[1].split(' = ', 1)[1]};\n"
            f"  Object c = {safe_content.splitlines()[2].split(' = ', 1)[1]};\n"
            "}\n",
        )
        self.fixture.write("config/application.yml", safe_content.splitlines()[3] + "\n")
        safe_head = self.fixture.commit("safe code and environment placeholder")
        quality_gate.check_sensitive_content(self.fixture.root, self.base, safe_head)
        unsafe_values = (
            "test-prod-secret",
            "example-live-value",
            "${DB_PASSWORD:-hardcoded-value}",
        )
        for exposed_value in unsafe_values:
            assignment = f"{secret_key}={exposed_value}"
            self.assertTrue(quality_gate._hunk_has_sensitive_content(assignment))
            self.assertNotIn(exposed_value, quality_gate.redact(assignment))
        self.fixture.write(
            "config/application.yml",
            "\n".join(f"{secret_key}={value}" for value in unsafe_values) + "\n",
        )
        unsafe_head = self.fixture.commit("unsafe config values")
        with self.assertRaises(quality_gate.GateError):
            quality_gate.check_sensitive_content(self.fixture.root, safe_head, unsafe_head)

    def test_changed_multiline_json_value_uses_unchanged_key_context(self) -> None:
        credential_key = "client_" + "secret"
        self.fixture.write(
            "config.json",
            "{\n" f'  "{credential_key}":\n' '    "${DB_PASSWORD}"\n' "}\n",
        )
        before = self.fixture.commit("placeholder config")
        exposed_value = "replacement-live-" + "value-1234567890"
        self.fixture.write(
            "config.json",
            "{\n" f'  "{credential_key}":\n' f'    "{exposed_value}"\n' "}\n",
        )
        after = self.fixture.commit("replace only credential value")
        with self.assertRaises(quality_gate.GateError) as caught:
            quality_gate.check_sensitive_content(self.fixture.root, before, after)
        self.assertIn("config.json: added line 3", str(caught.exception))
        self.assertNotIn(exposed_value, str(caught.exception))

    def test_java_identifier_assignment_is_exempt_but_config_literal_is_strict(self) -> None:
        java_key = "client" + "Secret"
        self.fixture.write(
            "src/TestConfig.java",
            "class TestConfig {\n"
            f"  String {java_key};\n"
            f"  void set(String {java_key}) {{\n"
            f"    this.{java_key} = {java_key};\n"
            "  }\n"
            "}\n",
        )
        java_head = self.fixture.commit("Java identifier assignment")
        quality_gate.check_sensitive_content(self.fixture.root, self.base, java_head)
        config_value = "client" + "Secret"
        self.fixture.write(
            "config/application.properties", f"{java_key}={config_value}\n"
        )
        config_head = self.fixture.commit("matching config literal")
        with self.assertRaises(quality_gate.GateError) as caught:
            quality_gate.check_sensitive_content(self.fixture.root, java_head, config_head)
        self.assertIn("config/application.properties", str(caught.exception))

    def test_yaml_credential_block_scalars_are_scanned_without_echoing_payload(self) -> None:
        credential_key = "pass" + "word"
        payload = "spring-live-" + "credential-1234567890"
        for index, indicator in enumerate(("|", ">-", "|2+")):
            self.fixture.write(
                f"config/application-{index}.yml",
                "spring:\n"
                f"  {credential_key}: {indicator}\n"
                f"    {payload}\n"
                "    second-secret-line\n"
                "  profiles: test\n",
            )
        head = self.fixture.commit("YAML credential blocks")
        with self.assertRaises(quality_gate.GateError) as caught:
            quality_gate.check_sensitive_content(self.fixture.root, self.base, head)
        self.assertNotIn(payload, str(caught.exception))
        self.assertIn("config/application-", str(caught.exception))

    def test_changed_compact_sequence_yaml_payload_uses_unchanged_header(self) -> None:
        credential_key = "pass" + "word"
        self.fixture.write(
            "config/application.yml",
            "spring:\n"
            "  credentials:\n"
            f"    - {credential_key}: >2-\n"
            "        ${DB_PASSWORD}\n"
            "      enabled: true\n",
        )
        before = self.fixture.commit("compact YAML placeholder")
        exposed = "compact-sequence-live-" + "credential-1234567890"
        self.fixture.write(
            "config/application.yml",
            "spring:\n"
            "  credentials:\n"
            f"    - {credential_key}: >2-\n"
            f"        {exposed}\n"
            "      enabled: true\n",
        )
        after = self.fixture.commit("replace compact YAML payload only")
        with self.assertRaises(quality_gate.GateError) as caught:
            quality_gate.check_sensitive_content(self.fixture.root, before, after)
        self.assertIn("config/application.yml: added line 4", str(caught.exception))
        self.assertNotIn(exposed, str(caught.exception))

    def test_unresolvable_base_fails_closed(self) -> None:
        with self.assertRaises(quality_gate.GateError):
            quality_gate.resolve_commit(self.fixture.root, "missing-revision", "base")


class QualityGateEvidenceTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = RepositoryFixture()
        self.addCleanup(self.fixture.close)
        self.fixture.write("README.md", "# Fixture\n")
        self.base = self.fixture.commit("base")

    def install_gate(self) -> None:
        source = CORE_PATH.parent
        target = self.fixture.root / "tools/quality"
        shutil.copytree(source, target, ignore=shutil.ignore_patterns("__pycache__", "*.pyc"))
        runner = target / "test/run-tests.sh"
        runner.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        runner.chmod(0o755)
        gate = target / "quality-gate.sh"
        gate.chmod(0o755)
        # Core gate fixtures exercise gate orchestration, not the repository-owned
        # delivery workflow. Install an explicit fixture dependency so the real
        # common extension can remain fail-closed when its validator is absent.
        delivery_validator = self.fixture.root / "tools/agent-delivery/validate.py"
        delivery_validator.parent.mkdir(parents=True, exist_ok=True)
        delivery_validator.write_text(
            "#!/usr/bin/env python3\n"
            "import os\n"
            "from pathlib import Path\n"
            "Path(os.environ['QUALITY_GATE_RUN_DIR'], 'delivery-validator-ran').write_text('yes\\n')\n"
            "print('fixture agent delivery validation: PASS')\n",
            encoding="utf-8",
        )
        delivery_validator.chmod(0o755)
        delivery_tests = self.fixture.root / "tools/agent-delivery/test/run-tests.sh"
        delivery_tests.parent.mkdir(parents=True, exist_ok=True)
        delivery_tests.write_text(
            "#!/usr/bin/env bash\n"
            "set -euo pipefail\n"
            "printf 'yes\\n' > \"$QUALITY_GATE_RUN_DIR/delivery-tests-ran\"\n",
            encoding="utf-8",
        )
        delivery_tests.chmod(0o755)
        self.fixture.write(".gitignore", ".quality-gate/\n.maven-args\n")

    def latest_run(self) -> Path:
        run_id = (self.fixture.root / ".quality-gate/latest").read_text().strip()
        return self.fixture.root / ".quality-gate/runs" / run_id

    def run_gate(self, *arguments: str) -> subprocess.CompletedProcess[str]:
        return subprocess.run(
            [str(self.fixture.root / "tools/quality/quality-gate.sh"), *arguments],
            cwd=self.fixture.root,
            text=True,
            capture_output=True,
            check=False,
        )

    def test_docs_only_gate_has_private_evidence_and_redacted_bounded_failure(self) -> None:
        self.install_gate()
        extension = self.fixture.root / "tools/quality/checks.d/common/10-fail.sh"
        extension.parent.mkdir(parents=True, exist_ok=True)
        exposed = "Bear" + "er very-secret-token-1234567890"
        pem_begin = "-----BEGIN " + "PRIVATE KEY-----"
        pem_end = "-----END " + "PRIVATE KEY-----"
        pem_payload = "cHJpdmF0ZS1rZXktcGF5bG9hZA=="
        client_value = "client-value-1234567890"
        credential_keys = (
            "client_secret",
            "client-secret",
            "access_token",
            "access-token",
            "refresh_token",
            "refresh-token",
        )
        credential_assignments = tuple(
            f'"{key}": "{client_value}"' for key in credential_keys
        )
        escaped_terminal_value = "escaped-" + r'quote\"-live-value-123456'
        double_multiline_value = (
            "double-secret-one\nescaped-\\\"-middle\ndouble-secret-three"
        )
        single_multiline_value = (
            "single-secret-one\nescaped-\\'-middle\nsingle-secret-three"
        )
        yaml_key = "pass" + "word"
        yaml_payload = "yaml-secret-one\n  yaml-secret-two"
        credential_assignments += (
            f'"{credential_keys[0]}":\n  "{escaped_terminal_value}"',
            f'"{credential_keys[0]}": "{double_multiline_value}"',
            f"{credential_keys[2]}='{single_multiline_value}'",
            f"{yaml_key}: |-\n  {yaml_payload}\nafter-yaml: visible",
        )
        credential_arguments = " ".join(
            shlex.quote(assignment) for assignment in credential_assignments
        )
        extension.write_text(
            "#!/usr/bin/env bash\n"
            f"printf '%s\\n' '{exposed}' '{pem_begin}' '{pem_payload}' '{pem_end}' "
            f"{credential_arguments}\n"
            "exit 7\n",
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("install gate and extension")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        result = self.run_gate("auto", "--base", self.base, "--head", head, "--ref", "fixture")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("profile: docs-only", result.stdout)
        self.assertNotIn("very-secret-token", result.stdout)
        self.assertIn("Bearer [REDACTED]", result.stdout)
        self.assertNotIn(pem_begin, result.stdout)
        self.assertNotIn(pem_payload, result.stdout)
        self.assertNotIn(pem_end, result.stdout)
        self.assertIn("[REDACTED_PRIVATE_KEY_BLOCK]", result.stdout)
        self.assertNotIn(client_value, result.stdout)
        self.assertNotIn(escaped_terminal_value, result.stdout)
        self.assertNotIn("double-secret", result.stdout)
        self.assertNotIn("single-secret", result.stdout)
        self.assertNotIn("yaml-secret", result.stdout)
        self.assertIn('"client_secret": "[REDACTED]"', result.stdout)
        self.assertIn('"access-token": "[REDACTED]"', result.stdout)
        self.assertIn('"refresh-token": "[REDACTED]"', result.stdout)
        self.assertGreaterEqual(result.stdout.count("[REDACTED]"), len(credential_assignments))
        latest = self.latest_run()
        self.assertEqual(
            0o700, stat.S_IMODE((self.fixture.root / ".quality-gate").stat().st_mode)
        )
        self.assertEqual(
            0o700, stat.S_IMODE((self.fixture.root / ".quality-gate/runs").stat().st_mode)
        )
        self.assertEqual(0o700, stat.S_IMODE(latest.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE((latest / "full.log").stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE((latest / "summary.txt").stat().st_mode))
        self.assertIn("very-secret-token", (latest / "full.log").read_text())
        self.assertLessEqual((latest / "summary.txt").stat().st_size, quality_gate.MAX_SUMMARY_BYTES)

    def test_docs_only_gate_passes_without_maven(self) -> None:
        self.install_gate()
        self.base = self.fixture.commit("install gate")
        self.fixture.write("docs/target.md", "# Target\n")
        self.fixture.write("docs/guide.md", "[target](./target.md)\n")
        head = self.fixture.commit("docs")
        result = self.run_gate("auto", "--base", self.base, "--head", head)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("profile: docs-only", result.stdout)
        self.assertNotIn("maven-clean-verify", result.stdout)

    def test_normal_gate_runs_exact_clean_verify_and_post_maven_extension(self) -> None:
        self.install_gate()
        mvnw = self.fixture.root / "mvnw"
        mvnw.write_text(
            "#!/usr/bin/env bash\n"
            "printf '%s\\n' \"$*\" > \"$QUALITY_GATE_RUN_DIR/maven-args\"\n"
            "chmod 600 \"$QUALITY_GATE_RUN_DIR/maven-args\"\n",
            encoding="utf-8",
        )
        mvnw.chmod(0o755)
        extension = self.fixture.root / "tools/quality/checks.d/normal/40-changed-code"
        extension.parent.mkdir(parents=True, exist_ok=True)
        extension.write_text(
            "#!/usr/bin/env bash\ntest -f \"$QUALITY_GATE_RUN_DIR/maven-args\"\n",
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("install gate and normal extension")
        self.fixture.write("src.txt", "code\n")
        head = self.fixture.commit("code")
        result = self.run_gate("normal", "--base", self.base, "--head", head)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual(
            "-B -ntp clean verify\n", (self.latest_run() / "maven-args").read_text()
        )
        self.assertEqual(
            "yes\n", (self.latest_run() / "delivery-validator-ran").read_text()
        )
        self.assertEqual(
            "yes\n", (self.latest_run() / "delivery-tests-ran").read_text()
        )
        self.assertIn("extension:normal/40-changed-code: PASS", result.stdout)

    def test_public_entry_rejects_docs_only_mode(self) -> None:
        self.install_gate()
        result = self.run_gate("docs-only", "--base", self.base)
        self.assertEqual(2, result.returncode)
        self.assertIn("auto or normal", result.stderr)

    def test_dirty_tracked_checkout_is_rejected_before_steps(self) -> None:
        self.install_gate()
        self.base = self.fixture.commit("install gate")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        self.fixture.write("README.md", "# Dirty fixture\n")
        result = self.run_gate("auto", "--base", self.base, "--head", head)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checkout must be clean", result.stdout)
        self.assertNotIn("gate-self-tests: PASS", result.stdout)

    def test_untracked_extension_is_rejected_instead_of_executed(self) -> None:
        self.install_gate()
        self.base = self.fixture.commit("install gate")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        marker = self.fixture.root / "untracked-extension-ran"
        extension = self.fixture.root / "tools/quality/checks.d/common/10-untracked"
        extension.write_text(f"#!/usr/bin/env bash\ntouch '{marker}'\n", encoding="utf-8")
        extension.chmod(0o755)
        result = self.run_gate("auto", "--base", self.base, "--head", head)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("checkout must be clean", result.stdout)
        self.assertFalse(marker.exists())

    def test_head_must_match_checked_out_commit(self) -> None:
        self.install_gate()
        self.base = self.fixture.commit("install gate")
        self.fixture.write("guide.md", "# Guide\n")
        self.fixture.commit("docs")
        result = self.run_gate("auto", "--base", self.base, "--head", self.base)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("does not match checked-out HEAD", result.stdout)

    def test_committed_symlink_extension_outside_repository_is_rejected(self) -> None:
        self.install_gate()
        with tempfile.TemporaryDirectory() as outside:
            target = Path(outside) / "external-extension"
            target.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
            extension = self.fixture.root / "tools/quality/checks.d/common/10-linked"
            os.symlink(target, extension)
            self.base = self.fixture.commit("install symlink extension")
            result = self.run_gate("normal", "--base", self.base, "--head", self.base)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("committed extension must be a regular 100755 file", result.stdout)

    def test_committed_non_executable_extension_is_rejected(self) -> None:
        self.install_gate()
        extension = self.fixture.root / "tools/quality/checks.d/common/10-not-executable"
        extension.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        extension.chmod(0o644)
        self.base = self.fixture.commit("install non-executable extension")
        result = self.run_gate("normal", "--base", self.base, "--head", self.base)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("committed extension must be a regular 100755 file", result.stdout)

    def test_assume_unchanged_modified_maven_wrapper_is_rejected_before_execution(self) -> None:
        self.install_gate()
        marker = self.fixture.root / "hidden-maven-wrapper-ran"
        mvnw = self.fixture.root / "mvnw"
        mvnw.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        mvnw.chmod(0o755)
        self.base = self.fixture.commit("install gate and Maven wrapper")
        self.fixture.git("update-index", "--assume-unchanged", "mvnw")
        mvnw.write_text(f"#!/usr/bin/env bash\ntouch '{marker}'\n", encoding="utf-8")
        mvnw.chmod(0o755)
        self.addCleanup(
            self.fixture.git, "update-index", "--no-assume-unchanged", "mvnw"
        )
        result = self.run_gate("normal", "--base", self.base, "--head", self.base)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("assume-unchanged or skip-worktree", result.stdout)
        self.assertFalse(marker.exists())

    def test_skip_worktree_modified_extension_is_rejected_before_execution(self) -> None:
        self.install_gate()
        marker = self.fixture.root / "hidden-extension-ran"
        extension = self.fixture.root / "tools/quality/checks.d/common/10-hidden"
        extension.write_text("#!/usr/bin/env bash\nexit 0\n", encoding="utf-8")
        extension.chmod(0o755)
        self.base = self.fixture.commit("install gate and extension")
        relative = extension.relative_to(self.fixture.root).as_posix()
        self.fixture.git("update-index", "--skip-worktree", relative)
        extension.write_text(f"#!/usr/bin/env bash\ntouch '{marker}'\n", encoding="utf-8")
        extension.chmod(0o755)
        self.addCleanup(self.fixture.git, "update-index", "--no-skip-worktree", relative)
        result = self.run_gate("normal", "--base", self.base, "--head", self.base)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("assume-unchanged or skip-worktree", result.stdout)
        self.assertFalse(marker.exists())

    def test_only_twenty_completed_runs_are_retained(self) -> None:
        root = self.fixture.root / ".quality-gate/runs"
        root.mkdir(parents=True)
        for index in range(25):
            run = root / f"20000101T000000.{index:06d}Z-1"
            run.mkdir()
            (run / "completed").write_text("complete\n")
        current = quality_gate.Evidence(self.fixture.root)
        current.complete("status: PASS\n")
        retained = [path for path in root.iterdir() if (path / "completed").is_file()]
        self.assertEqual(quality_gate.MAX_COMPLETED_RUNS, len(retained))
        self.assertTrue(current.run_dir.exists())

    def test_concurrent_completions_share_one_retention_transaction(self) -> None:
        root = self.fixture.root / ".quality-gate/runs"
        root.mkdir(parents=True)
        for index in range(quality_gate.MAX_COMPLETED_RUNS - 1):
            run = root / f"20000101T000000.{index:06d}Z-1"
            run.mkdir()
            (run / "completed").write_text("complete\n", encoding="utf-8")

        barrier = self.fixture.root / "completion-barrier"
        ready_paths = [self.fixture.root / f"ready-{index}" for index in range(2)]
        worker = (
            "import importlib.util,sys,time; from pathlib import Path; "
            "spec=importlib.util.spec_from_file_location('worker_gate',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); "
            "evidence=module.Evidence(Path(sys.argv[2])); "
            "Path(sys.argv[3]).write_text(evidence.run_id); barrier=Path(sys.argv[4]); "
            "\nwhile not barrier.exists(): time.sleep(0.005)"
            "\nevidence.complete('status: PASS\\n')"
            "\nevidence.release_active()"
        )
        processes = [
            subprocess.Popen(
                [
                    sys.executable,
                    "-c",
                    worker,
                    str(CORE_PATH),
                    str(self.fixture.root),
                    str(ready),
                    str(barrier),
                ],
                text=True,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
            )
            for ready in ready_paths
        ]
        deadline = time.monotonic() + 20
        while time.monotonic() < deadline and not all(path.is_file() for path in ready_paths):
            exited = [process for process in processes if process.poll() is not None]
            if exited:
                diagnostics = []
                for process in exited:
                    stdout, stderr = process.communicate()
                    diagnostics.append(f"exit {process.returncode}: {stdout}{stderr}")
                self.fail("concurrent evidence worker exited early: " + " | ".join(diagnostics))
            time.sleep(0.01)
        if not all(path.is_file() for path in ready_paths):
            barrier.write_text("release after timeout\n", encoding="utf-8")
            diagnostics = []
            for process in processes:
                stdout, stderr = process.communicate(timeout=10)
                diagnostics.append(f"exit {process.returncode}: {stdout}{stderr}")
            self.fail("concurrent evidence workers were not ready: " + " | ".join(diagnostics))
        barrier.write_text("release\n", encoding="utf-8")
        for process in processes:
            stdout, stderr = process.communicate(timeout=10)
            self.assertEqual(0, process.returncode, stdout + stderr)

        retained = [path for path in root.iterdir() if (path / "completed").is_file()]
        self.assertLessEqual(len(retained), quality_gate.MAX_COMPLETED_RUNS)
        latest_id = (self.fixture.root / ".quality-gate/latest").read_text().strip()
        latest = root / latest_id
        self.assertTrue((latest / "completed").is_file())
        self.assertIn("status: PASS", (latest / "summary.txt").read_text())

    def test_older_attempt_completing_last_cannot_rewind_latest(self) -> None:
        ready = self.fixture.root / "older-ready"
        release = self.fixture.root / "release-older"
        worker = (
            "import importlib.util,sys,time; from pathlib import Path; "
            "spec=importlib.util.spec_from_file_location('older_gate',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); "
            "evidence=module.Evidence(Path(sys.argv[2])); "
            "Path(sys.argv[3]).write_text(evidence.run_id); release=Path(sys.argv[4]); "
            "\nwhile not release.exists(): time.sleep(0.005)"
            "\nevidence.complete('status: PASS\\n')"
            "\nevidence.release_active()"
        )
        older_process = subprocess.Popen(
            [
                sys.executable,
                "-c",
                worker,
                str(CORE_PATH),
                str(self.fixture.root),
                str(ready),
                str(release),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and not ready.is_file():
            time.sleep(0.01)
        self.assertTrue(ready.is_file(), "older attempt did not initialize")
        older_id = ready.read_text()
        newer = quality_gate.Evidence(self.fixture.root)
        newer.complete("status: PASS\n")
        self.assertLess(older_id, newer.run_id)
        release.write_text("finish older\n", encoding="utf-8")
        stdout, stderr = older_process.communicate(timeout=10)
        self.assertEqual(0, older_process.returncode, stdout + stderr)
        self.assertEqual(newer.run_id, (newer.root / "latest").read_text().strip())
        newer.release_active()

    def test_clock_rollback_uses_monotonic_attempt_order_for_latest_and_retention(self) -> None:
        runs = self.fixture.root / ".quality-gate/runs"
        runs.mkdir(parents=True)
        for index in range(quality_gate.MAX_COMPLETED_RUNS - 1):
            run = runs / f"20000101T000000.{index:06d}Z-1"
            run.mkdir()
            (run / "completed").write_text("complete\n", encoding="utf-8")

        moments = iter(
            (
                dt.datetime(2035, 1, 1, tzinfo=dt.UTC),
                dt.datetime(2025, 1, 1, tzinfo=dt.UTC),
            )
        )

        class RollbackClock(dt.datetime):
            @classmethod
            def now(cls, timezone: dt.tzinfo | None = None) -> dt.datetime:
                del timezone
                return next(moments)

        with mock.patch.object(quality_gate.dt, "datetime", RollbackClock):
            earlier_attempt = quality_gate.Evidence(self.fixture.root)
            earlier_attempt.complete("status: PASS\n")
            earlier_attempt.release_active()
            later_attempt = quality_gate.Evidence(self.fixture.root)
            later_attempt.complete("status: PASS\n")
            later_attempt.release_active()

        self.assertGreater(earlier_attempt.run_id, later_attempt.run_id)
        self.assertLess(earlier_attempt.attempt_sequence, later_attempt.attempt_sequence)
        self.assertEqual(
            later_attempt.run_id,
            (later_attempt.root / "latest").read_text().strip(),
        )
        self.assertTrue(earlier_attempt.run_dir.is_dir())
        self.assertTrue(later_attempt.run_dir.is_dir())
        retained = [path for path in runs.iterdir() if (path / "completed").is_file()]
        self.assertEqual(quality_gate.MAX_COMPLETED_RUNS, len(retained))

    def test_active_origin_survives_concurrent_pruning_until_output_release(self) -> None:
        origin = quality_gate.Evidence(self.fixture.root)
        origin.complete("status: PASS\n")
        worker = (
            "import importlib.util,sys; from pathlib import Path; "
            "spec=importlib.util.spec_from_file_location('pruning_gate',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); "
            "repo=Path(sys.argv[2]); count=int(sys.argv[3]); "
            "\nfor _ in range(count):"
            "\n evidence=module.Evidence(repo)"
            "\n evidence.complete('status: PASS\\n')"
            "\n evidence.release_active()"
        )
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                worker,
                str(CORE_PATH),
                str(self.fixture.root),
                str(quality_gate.MAX_COMPLETED_RUNS),
            ],
            text=True,
            capture_output=True,
            check=False,
            timeout=30,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertTrue(origin.active_path.is_file())
        self.assertIn("status: PASS", origin.summary_path.read_text())
        origin.release_active()
        self.assertFalse(origin.run_dir.exists())
        retained = [
            path
            for path in origin.runs.iterdir()
            if (path / "completed").is_file()
        ]
        self.assertEqual(quality_gate.MAX_COMPLETED_RUNS, len(retained))

    def test_crashed_completed_pins_are_reclaimed_and_retention_stays_bounded(self) -> None:
        worker = (
            "import importlib.util,os,sys; from pathlib import Path; "
            "spec=importlib.util.spec_from_file_location('crash_gate',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); "
            "evidence=module.Evidence(Path(sys.argv[2])); "
            "evidence.complete('status: PASS\\n'); "
            "Path(sys.argv[3]).write_text(evidence.run_id); os._exit(0)"
        )
        crashed_ids: list[str] = []
        for index in range(25):
            result_path = self.fixture.root / f"crashed-{index}"
            result = subprocess.run(
                [
                    sys.executable,
                    "-c",
                    worker,
                    str(CORE_PATH),
                    str(self.fixture.root),
                    str(result_path),
                ],
                text=True,
                capture_output=True,
                check=False,
                timeout=10,
            )
            self.assertEqual(0, result.returncode, result.stdout + result.stderr)
            crashed_ids.append(result_path.read_text())
        sweeper = quality_gate.Evidence(self.fixture.root)
        completed = [
            path
            for path in sweeper.runs.iterdir()
            if (path / "completed").is_file()
        ]
        self.assertLessEqual(len(completed), quality_gate.MAX_COMPLETED_RUNS)
        self.assertTrue(all(not (path / "active").exists() for path in completed))
        self.assertNotIn(crashed_ids[0], {path.name for path in completed})
        sweeper.release_active()

    def test_crashed_partial_run_is_removed_without_reclaiming_live_owner(self) -> None:
        result_path = self.fixture.root / "partial-run"
        worker = (
            "import importlib.util,os,sys; from pathlib import Path; "
            "spec=importlib.util.spec_from_file_location('partial_gate',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); "
            "original=module._open_new_private; marker=Path(sys.argv[3]); "
            "\ndef crash_before_active(path,parent):"
            "\n if path.name.startswith('.active-'):"
            "\n  output=original(path,parent)"
            "\n  output.write('{'); output.flush(); os.fsync(output.fileno())"
            "\n  marker.write_text(path.parent.name)"
            "\n  os._exit(0)"
            "\n return original(path,parent)"
            "\nmodule._open_new_private=crash_before_active"
            "\nmodule.Evidence(Path(sys.argv[2]))"
        )
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                worker,
                str(CORE_PATH),
                str(self.fixture.root),
                str(result_path),
            ],
            text=True,
            capture_output=True,
            check=False,
            timeout=10,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        partial = self.fixture.root / ".quality-gate/runs" / result_path.read_text()
        self.assertTrue((partial / "attempt-sequence").is_file())
        self.assertFalse((partial / "active").exists())
        self.assertEqual(1, len(list(partial.glob(".active-*"))))
        live = quality_gate.Evidence(self.fixture.root)
        self.assertFalse(partial.exists())
        observer = quality_gate.Evidence(self.fixture.root)
        self.assertTrue(live.active_path.is_file())
        observer.release_active()
        live.release_active()

    def test_missing_owner_identity_after_run_creation_is_recoverable(self) -> None:
        runs = self.fixture.root / ".quality-gate/runs"
        before = set(runs.iterdir()) if runs.exists() else set()
        with mock.patch.object(quality_gate, "_process_identity", return_value=None):
            with self.assertRaisesRegex(
                quality_gate.GateError, "cannot determine evidence owner birth identity"
            ):
                quality_gate.Evidence(self.fixture.root)

        partials = set(runs.iterdir()) - before
        self.assertEqual(1, len(partials))
        partial = partials.pop()
        self.assertTrue((partial / "attempt-sequence").is_file())
        self.assertFalse((partial / "active").exists())
        self.assertFalse(list(partial.glob(".*-" + str(os.getpid()))))

        recovered = quality_gate.Evidence(self.fixture.root)
        self.assertFalse(partial.exists())
        self.assertTrue(recovered.active_path.is_file())
        recovered.release_active()

    def test_counter_atomic_crash_debris_and_malformed_target_recover_from_runs(self) -> None:
        baseline = quality_gate.Evidence(self.fixture.root)
        baseline.complete("status: PASS\n")
        baseline.release_active()
        marker = self.fixture.root / "counter-crash"
        worker = (
            "import importlib.util,os,sys; from pathlib import Path; "
            "spec=importlib.util.spec_from_file_location('counter_gate',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); "
            "original=module._open_new_private; marker=Path(sys.argv[3]); "
            "\ndef crash_during_counter(path,parent):"
            "\n output=original(path,parent)"
            "\n if path.name.startswith('.attempt-sequence-counter-'):"
            "\n  output.write('{'); output.flush(); os.fsync(output.fileno())"
            "\n  marker.write_text(path.name); os._exit(0)"
            "\n return output"
            "\nmodule._open_new_private=crash_during_counter"
            "\nmodule.Evidence(Path(sys.argv[2]))"
        )
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                worker,
                str(CORE_PATH),
                str(self.fixture.root),
                str(marker),
            ],
            text=True,
            capture_output=True,
            check=False,
            timeout=10,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        quality_root = self.fixture.root / ".quality-gate"
        self.assertTrue((quality_root / marker.read_text()).is_file())
        counter = quality_root / "attempt-sequence-counter"
        counter.write_text("{", encoding="utf-8")
        counter.chmod(0o600)

        recovered = quality_gate.Evidence(self.fixture.root)
        self.assertGreater(recovered.attempt_sequence, baseline.attempt_sequence)
        self.assertTrue(counter.read_text().strip().isdecimal())
        self.assertFalse(list(quality_root.glob(".attempt-sequence-counter-*")))
        recovered.release_active()

    def test_malformed_active_is_removed_only_for_incomplete_run(self) -> None:
        incomplete = quality_gate.Evidence(self.fixture.root)
        incomplete.active_path.write_text("{", encoding="utf-8")
        incomplete.active_path.chmod(0o600)
        recovered = quality_gate.Evidence(self.fixture.root)
        self.assertFalse(incomplete.run_dir.exists())
        recovered.release_active()

        completed = quality_gate.Evidence(self.fixture.root)
        completed.complete("status: PASS\n")
        completed.active_path.write_text("{", encoding="utf-8")
        completed.active_path.chmod(0o600)
        with self.assertRaisesRegex(quality_gate.GateError, "invalid active evidence"):
            quality_gate.Evidence(self.fixture.root)
        self.assertTrue(completed.run_dir.exists())

    def test_run_validation_and_pruning_do_not_recreate_a_pruned_orphan(self) -> None:
        runs = self.fixture.root / ".quality-gate/runs"
        runs.mkdir(parents=True)
        for index in range(quality_gate.MAX_COMPLETED_RUNS):
            run = runs / f"20000101T000000.{index:06d}Z-1"
            run.mkdir()
            (run / "completed").write_text("complete\n", encoding="utf-8")
        target = runs / "20000101T000000.000000Z-1"
        b_ready = self.fixture.root / "pruner-ready"
        b_go = self.fixture.root / "pruner-go"
        b_entering = self.fixture.root / "pruner-entering"
        worker_b = (
            "import importlib.util,sys,time; from pathlib import Path; "
            "spec=importlib.util.spec_from_file_location('pruner_gate',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); "
            "evidence=module.Evidence(Path(sys.argv[2])); "
            "Path(sys.argv[3]).write_text(evidence.run_id); go=Path(sys.argv[4]); "
            "\nwhile not go.exists(): time.sleep(0.005)"
            "\nPath(sys.argv[5]).write_text('entering')"
            "\nevidence.complete('status: PASS\\n')"
            "\nevidence.release_active()"
        )
        pruner = subprocess.Popen(
            [
                sys.executable,
                "-c",
                worker_b,
                str(CORE_PATH),
                str(self.fixture.root),
                str(b_ready),
                str(b_go),
                str(b_entering),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and not b_ready.is_file():
            time.sleep(0.01)
        self.assertTrue(b_ready.is_file(), "pruner attempt did not initialize")

        validation_paused = self.fixture.root / "validation-paused"
        validation_release = self.fixture.root / "validation-release"
        validator_done = self.fixture.root / "validator-done"
        validator_finish = self.fixture.root / "validator-finish"
        worker_a = (
            "import importlib.util,sys,time; from pathlib import Path; "
            "spec=importlib.util.spec_from_file_location('validator_gate',sys.argv[1]); "
            "module=importlib.util.module_from_spec(spec); spec.loader.exec_module(module); "
            "original=module._validate_run_tree; target=sys.argv[3]; "
            "paused=Path(sys.argv[4]); release=Path(sys.argv[5]); "
            "\ndef wrapped(run,runs):"
            "\n if run.name == target:"
            "\n  paused.write_text('paused')"
            "\n  while not release.exists(): time.sleep(0.005)"
            "\n return original(run,runs)"
            "\nmodule._validate_run_tree=wrapped"
            "\nevidence=module.Evidence(Path(sys.argv[2]))"
            "\nPath(sys.argv[6]).write_text(evidence.run_id)"
            "\nfinish=Path(sys.argv[7])"
            "\nwhile not finish.exists(): time.sleep(0.005)"
        )
        validator = subprocess.Popen(
            [
                sys.executable,
                "-c",
                worker_a,
                str(CORE_PATH),
                str(self.fixture.root),
                target.name,
                str(validation_paused),
                str(validation_release),
                str(validator_done),
                str(validator_finish),
            ],
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and not validation_paused.is_file():
            time.sleep(0.01)
        self.assertTrue(validation_paused.is_file(), "run validation did not pause")
        b_go.write_text("prune\n", encoding="utf-8")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and not b_entering.is_file():
            time.sleep(0.01)
        self.assertTrue(b_entering.is_file(), "pruner did not enter completion")
        time.sleep(0.2)
        validation_release.write_text("continue\n", encoding="utf-8")
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline and not validator_done.is_file():
            time.sleep(0.01)
        self.assertTrue(validator_done.is_file(), "validator did not create its run")
        pruner_stdout, pruner_stderr = pruner.communicate(timeout=10)
        self.assertEqual(0, pruner.returncode, pruner_stdout + pruner_stderr)
        self.assertFalse(target.exists(), "pruned run must not reappear as an orphan")
        validator_run = runs / validator_done.read_text()
        pruner_run = runs / b_ready.read_text()
        self.assertTrue((validator_run / "full.log").is_file())
        self.assertTrue((pruner_run / "completed").is_file())
        validator_finish.write_text("finish\n", encoding="utf-8")
        validator_stdout, validator_stderr = validator.communicate(timeout=10)
        self.assertEqual(0, validator.returncode, validator_stdout + validator_stderr)

    def test_prune_failure_cannot_publish_pass_or_completed_marker(self) -> None:
        root = self.fixture.root / ".quality-gate/runs"
        root.mkdir(parents=True)
        for index in range(quality_gate.MAX_COMPLETED_RUNS):
            run = root / f"20000101T000000.{index:06d}Z-1"
            run.mkdir()
            (run / "completed").write_text("complete\n", encoding="utf-8")
        current = quality_gate.Evidence(self.fixture.root)
        with mock.patch.object(
            quality_gate.shutil,
            "rmtree",
            side_effect=PermissionError("fixture run is undeletable"),
        ):
            with self.assertRaises(PermissionError):
                current.complete("status: PASS\n")
        self.assertFalse(current.summary_path.exists())
        self.assertFalse((current.run_dir / "completed").exists())
        self.assertEqual(current.run_id, (current.root / "latest").read_text().strip())

    def test_invalid_base_replaces_latest_with_failed_run(self) -> None:
        self.install_gate()
        first = quality_gate.Evidence(self.fixture.root)
        first.complete("status: PASS\n")
        result = self.run_gate("auto", "--base", "not-a-revision")
        self.assertNotEqual(0, result.returncode)
        latest = self.latest_run()
        self.assertNotEqual(first.run_dir, latest)
        self.assertIn("status: FAIL", (latest / "summary.txt").read_text())

    def test_extension_mutating_tracked_input_cannot_pass(self) -> None:
        self.install_gate()
        extension = self.fixture.root / "tools/quality/checks.d/common/10-mutate-tracked"
        extension.write_text(
            "#!/usr/bin/env bash\nprintf '# changed\\n' > README.md\n",
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("install tracked mutator")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        result = self.run_gate("auto", "--base", self.base, "--head", head)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("status: FAIL", result.stdout)
        self.assertNotIn("status: PASS", result.stdout)
        self.assertIn("checkout must be clean", result.stdout)

    def test_extension_mutating_untracked_input_cannot_pass(self) -> None:
        self.install_gate()
        extension = self.fixture.root / "tools/quality/checks.d/common/10-mutate-untracked"
        extension.write_text(
            "#!/usr/bin/env bash\ntouch unexpected-gate-output\n",
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("install untracked mutator")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        result = self.run_gate("auto", "--base", self.base, "--head", head)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("status: FAIL", result.stdout)
        self.assertNotIn("status: PASS", result.stdout)
        self.assertIn("unexpected-gate-output", result.stdout)

    def test_concurrent_source_mutation_cannot_change_snapshot_inputs(self) -> None:
        self.install_gate()
        extension = self.fixture.root / "tools/quality/checks.d/common/10-coordinate"
        extension.write_text(
            "#!/usr/bin/env bash\n"
            "touch \"$QUALITY_GATE_RUN_DIR/ready\"\n"
            "while [ ! -f \"$QUALITY_GATE_RUN_DIR/continue\" ]; do sleep 0.05; done\n"
            "grep -q '# Fixture' README.md\n"
            "touch \"$QUALITY_GATE_RUN_DIR/observed\"\n",
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("install coordinating extension")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        process = subprocess.Popen(
            [
                str(self.fixture.root / "tools/quality/quality-gate.sh"),
                "auto",
                "--base",
                self.base,
                "--head",
                head,
            ],
            cwd=self.fixture.root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        run_dir: Path | None = None
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            latest_pointer = self.fixture.root / ".quality-gate/latest"
            if latest_pointer.is_file():
                run_id = latest_pointer.read_text().strip()
                candidate = self.fixture.root / ".quality-gate/runs" / run_id
                if (candidate / "ready").is_file():
                    run_dir = candidate
                    break
            time.sleep(0.05)
        self.assertIsNotNone(run_dir, "coordinating extension did not start")
        assert run_dir is not None
        self.fixture.write("README.md", "# Concurrent mutation\n")
        (run_dir / "continue").write_text("continue\n", encoding="utf-8")
        observed_deadline = time.monotonic() + 10
        while time.monotonic() < observed_deadline and not (run_dir / "observed").is_file():
            time.sleep(0.01)
        self.assertTrue((run_dir / "observed").is_file(), "snapshot did not observe pinned README")
        self.fixture.write("README.md", "# Fixture\n")
        stdout, stderr = process.communicate(timeout=10)
        self.assertEqual(0, process.returncode, stdout + stderr)
        self.assertIn("status: PASS", stdout)
        self.assertTrue((run_dir / "completed").is_file())
        self.assertFalse((run_dir / "checkout").exists())
        self.assertEqual("", self.fixture.git("status", "--porcelain"))

    def test_committed_extension_source_mutation_and_restore_cannot_affect_snapshot(self) -> None:
        self.install_gate()
        source_readme = shlex.quote(str(self.fixture.root / "README.md"))
        extension = self.fixture.root / "tools/quality/checks.d/common/10-source-race"
        extension.write_text(
            "#!/usr/bin/env bash\n"
            f"printf '# transient source mutation\\n' > {source_readme}\n"
            "grep -q '# Fixture' README.md\n"
            f"printf '# Fixture\\n' > {source_readme}\n",
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("install transient source mutator")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        result = self.run_gate("auto", "--base", self.base, "--head", head)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertIn("extension:common/10-source-race: PASS", result.stdout)
        self.assertIn("status: PASS", result.stdout)
        self.assertEqual("# Fixture\n", (self.fixture.root / "README.md").read_text())
        self.assertEqual("", self.fixture.git("status", "--porcelain"))

    def test_interruption_terminates_child_and_completes_evidence(self) -> None:
        self.install_gate()
        extension = self.fixture.root / "tools/quality/checks.d/common/10-wait"
        extension.write_text(
            "#!/usr/bin/env bash\n"
            "echo $$ > \"$QUALITY_GATE_RUN_DIR/leader.pid\"\n"
            "sh -c 'trap \"\" TERM; echo $$ > "
            '"$QUALITY_GATE_RUN_DIR/grandchild.pid"; exec sleep 60' + "' &\n"
            "wait\n",
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("install waiting extension")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        process = subprocess.Popen(
            [
                str(self.fixture.root / "tools/quality/quality-gate.sh"),
                "auto",
                "--base",
                self.base,
                "--head",
                head,
            ],
            cwd=self.fixture.root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        run_dir: Path | None = None
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            latest_pointer = self.fixture.root / ".quality-gate/latest"
            if latest_pointer.is_file():
                run_id = latest_pointer.read_text().strip()
                candidate = self.fixture.root / ".quality-gate/runs" / run_id
                if (candidate / "leader.pid").is_file() and (
                    candidate / "grandchild.pid"
                ).is_file():
                    run_dir = candidate
                    break
            time.sleep(0.05)
        self.assertIsNotNone(run_dir, "waiting extension and resistant grandchild did not start")
        assert run_dir is not None
        leader_pid = int((run_dir / "leader.pid").read_text().strip())
        grandchild_pid = int((run_dir / "grandchild.pid").read_text().strip())
        interrupted_at = time.monotonic()
        os.kill(process.pid, signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=10)
        self.assertEqual(128 + signal.SIGTERM, process.returncode, stdout + stderr)
        self.assertGreaterEqual(time.monotonic() - interrupted_at, quality_gate.PROCESS_GROUP_TERM_SECONDS)
        self.assertIn("status: INTERRUPTED", stdout)
        self.assertTrue((run_dir / "completed").is_file())
        for child_pid in (leader_pid, grandchild_pid):
            with self.assertRaises(ProcessLookupError):
                os.kill(child_pid, 0)

    def test_signal_during_post_wait_drain_cleans_same_group_background(self) -> None:
        self.install_gate()
        core = self.fixture.root / "tools/quality/quality_gate.py"
        source = core.read_text(encoding="utf-8")
        needle = (
            "                    log.flush()\n"
            "                    try:\n"
            "                        _terminate_process_containment(\n"
        )
        instrumented = (
            "                    log.flush()\n"
            '                    (self.evidence.run_dir / "post-wait-drain").write_text("ready\\n")\n'
            "                    time.sleep(0.5)\n"
            "                    try:\n"
            "                        _terminate_process_containment(\n"
        )
        self.assertIn(needle, source)
        core.write_text(source.replace(needle, instrumented, 1), encoding="utf-8")
        extension = self.fixture.root / "tools/quality/checks.d/common/10-background"
        extension.write_text(
            "#!/usr/bin/env bash\n"
            "sleep 60 &\n"
            "echo $! > \"$QUALITY_GATE_RUN_DIR/background.pid\"\n",
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("instrument post-wait process drain")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        process = subprocess.Popen(
            [
                str(self.fixture.root / "tools/quality/quality-gate.sh"),
                "auto",
                "--base",
                self.base,
                "--head",
                head,
            ],
            cwd=self.fixture.root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        run_dir: Path | None = None
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            latest = self.fixture.root / ".quality-gate/latest"
            if latest.is_file():
                candidate = self.fixture.root / ".quality-gate/runs" / latest.read_text().strip()
                if (candidate / "post-wait-drain").is_file():
                    run_dir = candidate
                    break
            time.sleep(0.01)
        self.assertIsNotNone(run_dir, "did not enter post-wait drain")
        assert run_dir is not None
        background_pid = int((run_dir / "background.pid").read_text())
        os.kill(process.pid, signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=10)
        self.assertEqual(128 + signal.SIGTERM, process.returncode, stdout + stderr)
        self.assertIn("status: INTERRUPTED", stdout)
        self.assertTrue((run_dir / "completed").is_file())
        with self.assertRaises(ProcessLookupError):
            os.kill(background_pid, 0)

    def test_known_detached_setsid_descendant_is_cleaned_before_step_result(self) -> None:
        self.install_gate()
        python = shlex.quote(sys.executable)
        extension = self.fixture.root / "tools/quality/checks.d/common/10-detach"
        extension.write_text(
            "#!/usr/bin/env bash\n"
            f"{python} -c 'import os,signal,sys,time; os.setsid(); "
            "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
            "open(sys.argv[1], \"w\").write(str(os.getpid())); time.sleep(60)' "
            '"$QUALITY_GATE_RUN_DIR/detached.pid" &\n'
            'while [ ! -f "$QUALITY_GATE_RUN_DIR/detached.pid" ]; do sleep 0.01; done\n',
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("install detached descendant extension")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        result = self.run_gate("auto", "--base", self.base, "--head", head)
        self.assertNotEqual(0, result.returncode)
        self.assertIn("status: FAIL", result.stdout)
        self.assertIn("contained processes", result.stdout)
        run_dir = self.latest_run()
        detached_pid = int((run_dir / "detached.pid").read_text())
        with self.assertRaises(ProcessLookupError):
            os.kill(detached_pid, 0)
        self.assertTrue((run_dir / "completed").is_file())

    def test_interruption_cleans_known_detached_descendant_before_evidence(self) -> None:
        self.install_gate()
        python = shlex.quote(sys.executable)
        extension = self.fixture.root / "tools/quality/checks.d/common/10-detached-wait"
        extension.write_text(
            "#!/usr/bin/env bash\n"
            "echo $$ > \"$QUALITY_GATE_RUN_DIR/leader.pid\"\n"
            f"{python} -c 'import os,signal,sys,time; os.setsid(); "
            "signal.signal(signal.SIGTERM, signal.SIG_IGN); "
            "open(sys.argv[1], \"w\").write(str(os.getpid())); time.sleep(60)' "
            '"$QUALITY_GATE_RUN_DIR/detached.pid" &\n'
            'while [ ! -f "$QUALITY_GATE_RUN_DIR/detached.pid" ]; do sleep 0.01; done\n'
            "wait\n",
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("install interrupted detached descendant")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        process = subprocess.Popen(
            [
                str(self.fixture.root / "tools/quality/quality-gate.sh"),
                "auto",
                "--base",
                self.base,
                "--head",
                head,
            ],
            cwd=self.fixture.root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        run_dir: Path | None = None
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            latest = self.fixture.root / ".quality-gate/latest"
            if latest.is_file():
                candidate = self.fixture.root / ".quality-gate/runs" / latest.read_text().strip()
                if (candidate / "leader.pid").is_file() and (
                    candidate / "detached.pid"
                ).is_file():
                    run_dir = candidate
                    break
            time.sleep(0.02)
        self.assertIsNotNone(run_dir, "detached descendant did not start")
        assert run_dir is not None
        leader_pid = int((run_dir / "leader.pid").read_text())
        detached_pid = int((run_dir / "detached.pid").read_text())
        os.kill(process.pid, signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=15)
        self.assertEqual(128 + signal.SIGTERM, process.returncode, stdout + stderr)
        self.assertIn("status: INTERRUPTED", stdout)
        self.assertTrue((run_dir / "completed").is_file())
        for pid in (leader_pid, detached_pid):
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)

    def test_signal_immediately_after_launch_reaps_child_and_completes_evidence(self) -> None:
        self.install_gate()
        extension = self.fixture.root / "tools/quality/checks.d/common/10-signal-at-launch"
        extension.write_text(
            "#!/usr/bin/env bash\n"
            "echo $$ > \"$QUALITY_GATE_RUN_DIR/launch.pid\"\n"
            "kill -TERM \"$PPID\"\n"
            "trap '' TERM\n"
            "exec sleep 60\n",
            encoding="utf-8",
        )
        extension.chmod(0o755)
        self.base = self.fixture.commit("install launch-signal extension")
        result = self.run_gate("normal", "--base", self.base, "--head", self.base)
        self.assertEqual(128 + signal.SIGTERM, result.returncode, result.stdout + result.stderr)
        self.assertIn("status: INTERRUPTED", result.stdout)
        run_dir = self.latest_run()
        self.assertTrue((run_dir / "completed").is_file())
        child_pid = int((run_dir / "launch.pid").read_text().strip())
        with self.assertRaises(ProcessLookupError):
            os.kill(child_pid, 0)

    def test_inherited_blocked_pending_signal_completes_interrupted_evidence(self) -> None:
        self.install_gate()
        self.base = self.fixture.commit("install gate")
        launcher = (
            "import os,signal,sys; "
            "signal.pthread_sigmask(signal.SIG_BLOCK,{signal.SIGTERM}); "
            "os.kill(os.getpid(),signal.SIGTERM); "
            "os.execv(sys.argv[1],sys.argv[1:])"
        )
        result = subprocess.run(
            [
                sys.executable,
                "-c",
                launcher,
                str(self.fixture.root / "tools/quality/quality-gate.sh"),
                "auto",
                "--base",
                self.base,
                "--head",
                self.base,
            ],
            cwd=self.fixture.root,
            text=True,
            capture_output=True,
            check=False,
            timeout=15,
        )
        self.assertEqual(128 + signal.SIGTERM, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("Traceback", result.stderr)
        run_dir = self.latest_run()
        self.assertTrue((run_dir / "completed").is_file())
        self.assertIn("status: INTERRUPTED", (run_dir / "summary.txt").read_text())

    def test_signal_in_execution_to_finalization_transition_is_latched(self) -> None:
        self.install_gate()
        core = self.fixture.root / "tools/quality/quality_gate.py"
        source = core.read_text(encoding="utf-8")
        needle = (
            "    signal_state.interruptible = False\n"
            "    consume_pending_watched_signals(signal_state, watched_signals)\n"
        )
        instrumented = (
            '    (evidence.run_dir / "transition-blocked").write_text("ready\\n")\n'
            "    time.sleep(0.5)\n"
            + needle
        )
        self.assertIn(needle, source)
        core.write_text(source.replace(needle, instrumented, 1), encoding="utf-8")
        self.base = self.fixture.commit("instrument execution transition")
        process = subprocess.Popen(
            [
                str(self.fixture.root / "tools/quality/quality-gate.sh"),
                "auto",
                "--base",
                self.base,
                "--head",
                self.base,
            ],
            cwd=self.fixture.root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        run_dir: Path | None = None
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            latest = self.fixture.root / ".quality-gate/latest"
            if latest.is_file():
                candidate = self.fixture.root / ".quality-gate/runs" / latest.read_text().strip()
                if (candidate / "transition-blocked").is_file():
                    run_dir = candidate
                    break
            time.sleep(0.01)
        self.assertIsNotNone(run_dir, "did not enter blocked transition window")
        assert run_dir is not None
        os.kill(process.pid, signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=10)
        self.assertEqual(128 + signal.SIGTERM, process.returncode, stdout + stderr)
        self.assertNotIn("Traceback", stderr)
        self.assertTrue((run_dir / "completed").is_file())
        self.assertIn("status: INTERRUPTED", (run_dir / "summary.txt").read_text())

    def test_signal_during_evidence_pruning_is_recorded_as_interrupted(self) -> None:
        self.install_gate()
        core = self.fixture.root / "tools/quality/quality_gate.py"
        source = core.read_text(encoding="utf-8")
        needle = "            shutil.rmtree(victim)\n"
        instrumented = (
            '            (self.run_dir / "pruning").write_text("ready\\n")\n'
            "            time.sleep(0.5)\n"
            + needle
        )
        self.assertIn(needle, source)
        core.write_text(source.replace(needle, instrumented, 1), encoding="utf-8")
        self.base = self.fixture.commit("install gate")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        runs = self.fixture.root / ".quality-gate/runs"
        runs.mkdir(parents=True)
        for index in range(45):
            old_run = runs / f"20000101T000000.{index:06d}Z-1"
            old_run.mkdir()
            (old_run / "completed").write_text("complete\n", encoding="utf-8")
            for item in range(80):
                (old_run / f"artifact-{item:03d}").write_text("fixture\n", encoding="utf-8")
        process = subprocess.Popen(
            [
                str(self.fixture.root / "tools/quality/quality-gate.sh"),
                "auto",
                "--base",
                self.base,
                "--head",
                head,
            ],
            cwd=self.fixture.root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        run_dir: Path | None = None
        deadline = time.monotonic() + 15
        while time.monotonic() < deadline:
            latest = self.fixture.root / ".quality-gate/latest"
            if latest.is_file():
                candidate = runs / latest.read_text().strip()
                if (candidate / "pruning").is_file():
                    run_dir = candidate
                    break
            time.sleep(0.001)
        self.assertIsNotNone(run_dir, "did not observe evidence finalization window")
        assert run_dir is not None
        os.kill(process.pid, signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=15)
        self.assertEqual(128 + signal.SIGTERM, process.returncode, stdout + stderr)
        self.assertIn("status: INTERRUPTED", stdout)
        self.assertIn("status: INTERRUPTED", (run_dir / "summary.txt").read_text())
        self.assertTrue((run_dir / "completed").is_file())

    def test_signal_in_final_unblocked_handoff_cannot_leave_pass_evidence(self) -> None:
        self.install_gate()
        core = self.fixture.root / "tools/quality/quality_gate.py"
        source = core.read_text(encoding="utf-8")
        needle = "        signal.pthread_sigmask(signal.SIG_SETMASK, handoff_mask)\n"
        instrumented = (
            needle
            + '        (evidence.run_dir / "signals-unblocked").write_text("ready\\n")\n'
            + "        time.sleep(0.5)\n"
        )
        self.assertIn(needle, source)
        core.write_text(source.replace(needle, instrumented, 1), encoding="utf-8")
        self.base = self.fixture.commit("instrument post-completion signal window")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        process = subprocess.Popen(
            [
                str(self.fixture.root / "tools/quality/quality-gate.sh"),
                "auto",
                "--base",
                self.base,
                "--head",
                head,
            ],
            cwd=self.fixture.root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        run_dir: Path | None = None
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            latest = self.fixture.root / ".quality-gate/latest"
            if latest.is_file():
                candidate = self.fixture.root / ".quality-gate/runs" / latest.read_text().strip()
                if (candidate / "signals-unblocked").is_file():
                    run_dir = candidate
                    break
            time.sleep(0.01)
        self.assertIsNotNone(run_dir, "did not enter final unblocked handoff window")
        assert run_dir is not None
        os.kill(process.pid, signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=10)
        self.assertEqual(128 + signal.SIGTERM, process.returncode, stdout + stderr)
        self.assertIn("status: INTERRUPTED", stdout)
        summary = (run_dir / "summary.txt").read_text()
        self.assertIn("status: INTERRUPTED", summary)
        self.assertNotIn("status: PASS", summary)
        self.assertFalse((run_dir / "active").exists())

    def test_signal_during_per_handler_restore_is_interrupted(self) -> None:
        self.install_gate()
        core = self.fixture.root / "tools/quality/quality_gate.py"
        source = core.read_text(encoding="utf-8")
        needle = (
            "            for signum, handler in previous_handlers.items():\n"
            "                signal.signal(signum, handler)\n"
            "            final_signal_drain()\n"
        )
        instrumented = (
            "            for restore_index, (signum, handler) in enumerate(previous_handlers.items()):\n"
            "                signal.signal(signum, handler)\n"
            "                if restore_index == 0:\n"
            '                    (evidence.run_dir / "handler-restore-gap").write_text("ready\\n")\n'
            "                    time.sleep(0.5)\n"
            "            final_signal_drain()\n"
        )
        self.assertIn(needle, source)
        core.write_text(source.replace(needle, instrumented, 1), encoding="utf-8")
        self.base = self.fixture.commit("instrument per-handler restore gap")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        process = subprocess.Popen(
            [
                str(self.fixture.root / "tools/quality/quality-gate.sh"),
                "auto",
                "--base",
                self.base,
                "--head",
                head,
            ],
            cwd=self.fixture.root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        run_dir: Path | None = None
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            latest = self.fixture.root / ".quality-gate/latest"
            if latest.is_file():
                candidate = self.fixture.root / ".quality-gate/runs" / latest.read_text().strip()
                if (candidate / "handler-restore-gap").is_file():
                    run_dir = candidate
                    break
            time.sleep(0.01)
        self.assertIsNotNone(run_dir, "did not enter per-handler restore gap")
        assert run_dir is not None
        os.kill(process.pid, signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=10)
        self.assertEqual(128 + signal.SIGTERM, process.returncode, stdout + stderr)
        self.assertIn("status: INTERRUPTED", stdout)
        summary = (run_dir / "summary.txt").read_text()
        self.assertIn("status: INTERRUPTED", summary)
        self.assertNotIn("status: PASS", summary)

    def test_signal_during_release_prune_is_drained_before_terminal_pass(self) -> None:
        self.install_gate()
        core = self.fixture.root / "tools/quality/quality_gate.py"
        source = core.read_text(encoding="utf-8")
        needle = (
            "            _unlink_existing_private(self.active_path, self.run_dir)\n"
            "            self._prune_unlocked(protect_current=False)\n"
        )
        instrumented = (
            "            _unlink_existing_private(self.active_path, self.run_dir)\n"
            "            for fixture_index in range(5):\n"
            "                fixture_run = self.runs / f'19990101T000000.{fixture_index:06d}Z-1'\n"
            "                fixture_run.mkdir(mode=0o700)\n"
            "                (fixture_run / 'attempt-sequence').write_text('0\\n')\n"
            "                (fixture_run / 'completed').write_text('complete\\n')\n"
            '            (self.run_dir / "release-prune-gap").write_text("ready\\n")\n'
            "            time.sleep(0.5)\n"
            "            self._prune_unlocked(protect_current=False)\n"
        )
        self.assertIn(needle, source)
        core.write_text(source.replace(needle, instrumented, 1), encoding="utf-8")
        self.base = self.fixture.commit("instrument release prune signal window")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        process = subprocess.Popen(
            [
                str(self.fixture.root / "tools/quality/quality-gate.sh"),
                "auto",
                "--base",
                self.base,
                "--head",
                head,
            ],
            cwd=self.fixture.root,
            text=True,
            stdout=subprocess.PIPE,
            stderr=subprocess.PIPE,
        )
        run_dir: Path | None = None
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            latest = self.fixture.root / ".quality-gate/latest"
            if latest.is_file():
                candidate = self.fixture.root / ".quality-gate/runs" / latest.read_text().strip()
                if (candidate / "release-prune-gap").is_file():
                    run_dir = candidate
                    break
            time.sleep(0.01)
        self.assertIsNotNone(run_dir, "did not enter release prune window")
        assert run_dir is not None
        os.kill(process.pid, signal.SIGTERM)
        stdout, stderr = process.communicate(timeout=10)
        self.assertEqual(128 + signal.SIGTERM, process.returncode, stdout + stderr)
        self.assertNotIn("status: PASS", stdout)
        self.assertIn("status: INTERRUPTED", stdout)
        self.assertIn("status: INTERRUPTED", (run_dir / "summary.txt").read_text())
        self.assertFalse((run_dir / "active").exists())


class EvidencePathSafetyTest(unittest.TestCase):
    def test_symlinked_evidence_components_never_mutate_external_target(self) -> None:
        for scenario in ("root", "runs", "latest", "lock", "sequence", "run"):
            with self.subTest(scenario=scenario):
                fixture = RepositoryFixture()
                self.addCleanup(fixture.close)
                with tempfile.TemporaryDirectory() as outside_text:
                    outside = Path(outside_text)
                    sentinel = outside / "sentinel"
                    sentinel.write_text("unchanged\n", encoding="utf-8")
                    quality_root = fixture.root / ".quality-gate"
                    if scenario == "root":
                        os.symlink(outside, quality_root)
                    else:
                        quality_root.mkdir()
                        if scenario == "runs":
                            os.symlink(outside, quality_root / "runs")
                        else:
                            runs = quality_root / "runs"
                            runs.mkdir()
                            if scenario == "latest":
                                os.symlink(sentinel, quality_root / "latest")
                            elif scenario == "lock":
                                os.symlink(sentinel, quality_root / "completion.lock")
                            elif scenario == "sequence":
                                os.symlink(
                                    sentinel,
                                    quality_root / "attempt-sequence-counter",
                                )
                            else:
                                os.symlink(outside, runs / "20000101T000000.000000Z-1")
                    before = {
                        path.name: (path.read_bytes(), stat.S_IMODE(path.stat().st_mode))
                        for path in outside.iterdir()
                    }
                    with self.assertRaises(quality_gate.GateError):
                        quality_gate.Evidence(fixture.root)
                    after = {
                        path.name: (path.read_bytes(), stat.S_IMODE(path.stat().st_mode))
                        for path in outside.iterdir()
                    }
                    self.assertEqual(before, after)

    def test_private_permissions_use_descriptors_without_path_chmod(self) -> None:
        fixture = RepositoryFixture()
        self.addCleanup(fixture.close)
        fixture.write("README.md", "# Fixture\n")
        fixture.commit("base")
        with mock.patch.object(
            quality_gate.os,
            "chmod",
            side_effect=AssertionError("path chmod must not be used"),
        ):
            evidence = quality_gate.Evidence(fixture.root)
            evidence.complete("status: PASS\n")
        self.assertEqual(0o700, stat.S_IMODE(evidence.root.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(evidence.runs.stat().st_mode))
        self.assertEqual(0o700, stat.S_IMODE(evidence.run_dir.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE(evidence.log_path.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE(evidence.summary_path.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE(evidence.completion_lock.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE(evidence.sequence_counter.stat().st_mode))
        self.assertEqual(0o600, stat.S_IMODE(evidence.active_path.stat().st_mode))
        self.assertEqual(
            0o600,
            stat.S_IMODE((evidence.run_dir / "attempt-sequence").stat().st_mode),
        )


class BoundedTailTest(unittest.TestCase):
    def test_tail_streams_full_log_into_bounded_redacted_suffix(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "large.log"
            prefix = "not-in-tail\n" * (quality_gate.MAX_TAIL_BYTES * 3 // 12)
            suffix = "".join(f"tail-{index}\n" for index in range(30))
            path.write_text(prefix + suffix, encoding="utf-8")
            tail = quality_gate.redacted_tail(path)
        self.assertEqual(quality_gate.MAX_FAILURE_LINES, len(tail))
        self.assertEqual("tail-10", tail[0])
        self.assertEqual("tail-29", tail[-1])
        self.assertNotIn("not-in-tail", tail)

    def test_oversized_pem_and_credential_never_enter_retained_tail(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "sensitive.log"
            begin = "-----BEGIN " + "PRIVATE KEY-----"
            end = "-----END " + "PRIVATE KEY-----"
            pem_payload = "pem-sensitive-payload-" * quality_gate.MAX_TAIL_BYTES
            credential_key = "client_" + "secret"
            credential_value = "credential-sensitive-value-" * quality_gate.MAX_TAIL_BYTES
            multiline_gap = "\n" * (quality_gate.MAX_FAILURE_LINES + 5)
            path.write_text(
                f"{begin}\n{pem_payload}\n{end}\n"
                f'"{credential_key}":{multiline_gap} "{credential_value}"\nfailed\n',
                encoding="utf-8",
            )
            tail = quality_gate.redacted_tail(path)
        retained = "\n".join(tail)
        self.assertNotIn("pem-sensitive-payload", retained)
        self.assertNotIn("credential-sensitive-value", retained)
        self.assertNotIn(begin, retained)
        self.assertNotIn(end, retained)
        self.assertIn("[REDACTED_PRIVATE_KEY_BLOCK]", retained)
        self.assertIn("[REDACTED]", retained)
        self.assertLessEqual(len(tail), quality_gate.MAX_FAILURE_LINES)

    def test_oversized_standalone_tokens_never_leak_chunk_continuations(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "standalone-tokens.log"
            bearer_prefix = "Bear" + "er "
            github_prefix = "gh" + "p_"
            slack_prefix = "xo" + "xb-"
            bearer_payload = "B" * 20_000
            github_payload = "G" * 20_000
            slack_payload = "S" * 20_000
            path.write_text(
                f"{bearer_prefix}{bearer_payload}\n"
                f"{github_prefix}{github_payload}\n"
                f"{slack_prefix}{slack_payload}\n"
                "safe-tail\n",
                encoding="utf-8",
            )
            tail = quality_gate.redacted_tail(path)
        retained = "\n".join(tail)
        for fragment in ("B" * 100, "G" * 100, "S" * 100):
            self.assertNotIn(fragment, retained)
        self.assertGreaterEqual(
            sum(line.startswith("[REDACTED") for line in tail), 3
        )
        self.assertIn("safe-tail", retained)

    def test_multiline_single_and_double_quoted_credentials_remain_redacted(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "multiline.log"
            double_key = "client_" + "secret"
            single_key = "access_" + "token"
            double_payload = "double-line-one\nescaped-\\\"-still-secret\ndouble-line-three"
            single_payload = "single-line-one\nescaped-\\'-still-secret\nsingle-line-three"
            path.write_text(
                f'"{double_key}": "{double_payload}"\n'
                f"{single_key}='{single_payload}'\n"
                "after-redaction\n",
                encoding="utf-8",
            )
            tail = quality_gate.redacted_tail(path)
            direct = quality_gate.redact(path.read_text(encoding="utf-8"))
        retained = "\n".join(tail)
        for payload_fragment in (
            "double-line-one",
            "still-secret",
            "double-line-three",
            "single-line-one",
            "single-line-three",
        ):
            self.assertNotIn(payload_fragment, retained)
            self.assertNotIn(payload_fragment, direct)
        self.assertGreaterEqual(retained.count("[REDACTED]"), 2)
        self.assertIn("after-redaction", retained)

    def test_yaml_block_scalars_remain_redacted_until_dedent_with_bounded_state(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "yaml.log"
            credential_key = "pass" + "word"
            payload = "yaml-sensitive-payload-" * (
                quality_gate.MAX_OPEN_CREDENTIAL_CHARS // 20
            )
            path.write_text(
                f"spring:\n  {credential_key}: >2-\n    first-secret\n"
                f"    {payload}\n"
                "  profile: test\n"
                f"  {credential_key}: |+\n    second-secret\n"
                "  enabled: true\n",
                encoding="utf-8",
            )
            tail = quality_gate.redacted_tail(path)
            direct = quality_gate.redact(path.read_text(encoding="utf-8"))
        retained = "\n".join(tail)
        for fragment in ("first-secret", "yaml-sensitive-payload", "second-secret"):
            self.assertNotIn(fragment, retained)
            self.assertNotIn(fragment, direct)
        self.assertIn("[REDACTED_YAML_BLOCK_LIMIT_EXCEEDED]", retained)
        self.assertIn("profile: test", retained)
        self.assertIn("enabled: true", retained)
        self.assertGreaterEqual(retained.count("[REDACTED]"), 2)

    def test_compact_sequence_yaml_blocks_are_redacted_until_mapping_dedent(self) -> None:
        with tempfile.TemporaryDirectory() as temporary:
            path = Path(temporary) / "compact-yaml.log"
            credential_key = "pass" + "word"
            exposed = "compact-yaml-secret-" + "1234567890"
            content = (
                "credentials:\n"
                f"  - {credential_key}: |+\n"
                f"      {exposed}\n"
                "      second-secret-line\n"
                "    enabled: true\n"
                f"  - {credential_key}: >2-\n"
                "      another-secret-line\n"
                "    name: safe\n"
            )
            path.write_text(content, encoding="utf-8")
            tail = quality_gate.redacted_tail(path)
            direct = quality_gate.redact(content)
        retained = "\n".join(tail)
        for fragment in (exposed, "second-secret-line", "another-secret-line"):
            self.assertNotIn(fragment, retained)
            self.assertNotIn(fragment, direct)
        self.assertIn("- password: [REDACTED]", retained)
        self.assertIn("enabled: true", retained)
        self.assertIn("name: safe", retained)


if __name__ == "__main__":
    unittest.main()
