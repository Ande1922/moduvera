from __future__ import annotations

import importlib.util
import os
from pathlib import Path
import shutil
import stat
import subprocess
import tempfile
import unittest


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
        secret = "ghp_" + "123456789012345678901234567890"
        self.fixture.write("notes.md", f"token={secret}\n")
        head = self.fixture.commit("credential")
        with self.assertRaises(quality_gate.GateError) as caught:
            quality_gate.check_sensitive_content(self.fixture.root, self.base, head)
        self.assertNotIn(secret, str(caught.exception))

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
        self.base = self.fixture.commit("install gate")
        self.fixture.write("guide.md", "# Guide\n")
        head = self.fixture.commit("docs")
        extension = self.fixture.root / "tools/quality/checks.d/common/10-fail.sh"
        extension.parent.mkdir(parents=True, exist_ok=True)
        exposed = "Bear" + "er very-secret-token-1234567890"
        extension.write_text(f"#!/usr/bin/env bash\necho '{exposed}'\nexit 7\n", encoding="utf-8")
        extension.chmod(0o755)
        result = self.run_gate("auto", "--base", self.base, "--head", head, "--ref", "fixture")
        self.assertNotEqual(0, result.returncode)
        self.assertIn("profile: docs-only", result.stdout)
        self.assertNotIn("very-secret-token", result.stdout)
        self.assertIn("Bearer [REDACTED]", result.stdout)
        latest = (self.fixture.root / ".quality-gate/latest").resolve()
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
        self.base = self.fixture.commit("install gate")
        self.fixture.write("src.txt", "code\n")
        head = self.fixture.commit("code")
        mvnw = self.fixture.root / "mvnw"
        mvnw.write_text(
            "#!/usr/bin/env bash\nprintf '%s\\n' \"$*\" > .maven-args\n",
            encoding="utf-8",
        )
        mvnw.chmod(0o755)
        extension = self.fixture.root / "tools/quality/checks.d/normal/40-changed-code"
        extension.parent.mkdir(parents=True, exist_ok=True)
        extension.write_text("#!/usr/bin/env bash\ntest -f .maven-args\n", encoding="utf-8")
        extension.chmod(0o755)
        result = self.run_gate("normal", "--base", self.base, "--head", head)
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        self.assertEqual("-B -ntp clean verify\n", (self.fixture.root / ".maven-args").read_text())
        self.assertIn("extension:normal/40-changed-code: PASS", result.stdout)

    def test_public_entry_rejects_docs_only_mode(self) -> None:
        self.install_gate()
        result = self.run_gate("docs-only", "--base", self.base)
        self.assertEqual(2, result.returncode)
        self.assertIn("auto or normal", result.stderr)

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

    def test_invalid_base_replaces_latest_with_failed_run(self) -> None:
        self.install_gate()
        first = quality_gate.Evidence(self.fixture.root)
        first.complete("status: PASS\n")
        result = self.run_gate("auto", "--base", "not-a-revision")
        self.assertNotEqual(0, result.returncode)
        latest = (self.fixture.root / ".quality-gate/latest").resolve()
        self.assertNotEqual(first.run_dir, latest)
        self.assertIn("status: FAIL", (latest / "summary.txt").read_text())


if __name__ == "__main__":
    unittest.main()
