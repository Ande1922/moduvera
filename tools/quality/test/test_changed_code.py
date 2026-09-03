from __future__ import annotations

import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import time
import unittest


MODULE_PATH = Path(__file__).resolve().parents[1] / "changed_code.py"
SPEC = importlib.util.spec_from_file_location("changed_code", MODULE_PATH)
assert SPEC and SPEC.loader
changed_code = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(changed_code)


class ChangedCodeFixture:
    def __init__(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name) / "repo"
        self.evidence = Path(self.temporary.name) / "evidence"
        self.root.mkdir()
        self.evidence.mkdir(mode=0o700)
        self.git("init", "-q")
        self.git("config", "user.email", "changed-code@example.invalid")
        self.git("config", "user.name", "Changed Code Fixture")
        self.write(".gitignore", "target/\n")
        self.write("README.md", "# Fixture\n")
        self.base = self.commit("base")

    def close(self) -> None:
        self.temporary.cleanup()

    def git(self, *arguments: str) -> str:
        return subprocess.run(
            ["git", *arguments],
            cwd=self.root,
            check=True,
            text=True,
            capture_output=True,
        ).stdout.strip()

    def write(self, relative: str, content: str) -> Path:
        path = self.root / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content, encoding="utf-8")
        return path

    def commit(self, message: str) -> str:
        self.git("add", "-A")
        self.git("commit", "-q", "-m", message)
        return self.git("rev-parse", "HEAD")

    def add_java_change(
        self,
        relative: str,
        *,
        covered: int = 1,
        missed: int = 0,
        complexity: int = 1,
    ) -> tuple[str, Path]:
        module_text, source_text = relative.split("/src/main/java/", 1)
        package_path = str(Path(source_text).parent).replace(os.sep, "/")
        package_name = package_path.replace("/", ".")
        class_name = Path(source_text).stem
        self.write(f"{module_text}/pom.xml", "<project/>\n")
        source = self.write(
            relative,
            f"package {package_name};\n"
            f"public final class {class_name} {{\n"
            "    public int value() {\n"
            "        return 1;\n"
            "    }\n"
            "}\n",
        )
        prior = self.commit(f"add {class_name}")
        source.write_text(source.read_text().replace("return 1", "return 2"), encoding="utf-8")
        head = self.commit(f"change {class_name}")

        start = time.time_ns()
        classes = self.root / module_text / "target/classes"
        classes.mkdir(parents=True)
        subprocess.run(
            ["javac", "-g", "-d", str(classes), str(source)],
            cwd=self.root,
            check=True,
            text=True,
            capture_output=True,
        )
        report = self.root / module_text / "target/site/jacoco/jacoco.xml"
        report.parent.mkdir(parents=True)
        report.write_text(
            "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
            "<report name=\"fixture\">"
            f"<package name=\"{package_path}\">"
            f"<class name=\"{package_path}/{class_name}\" sourcefilename=\"{class_name}.java\">"
            f"<method name=\"value\" desc=\"()I\" line=\"4\">"
            f"<counter type=\"LINE\" missed=\"{missed}\" covered=\"{covered}\"/>"
            f"<counter type=\"COMPLEXITY\" missed=\"{complexity}\" covered=\"0\"/>"
            "</method></class>"
            f"<sourcefile name=\"{class_name}.java\">"
            f"<line nr=\"4\" mi=\"{missed}\" ci=\"{covered}\" mb=\"0\" cb=\"0\"/>"
            "</sourcefile></package></report>\n",
            encoding="utf-8",
        )
        completed = time.time_ns()
        self.write_provenance(prior, head, start, completed)
        return head, report

    def write_provenance(
        self,
        base: str,
        head: str,
        started_ns: int,
        completed_ns: int,
    ) -> Path:
        path = self.evidence / "maven-provenance.json"
        path.write_text(
            json.dumps(
                {
                    "base": base,
                    "head": head,
                    "command": ["./mvnw", "-B", "-ntp", "clean", "verify"],
                    "started_ns": started_ns,
                    "completed_ns": completed_ns,
                    "status": "PASS",
                }
            )
            + "\n",
            encoding="utf-8",
        )
        path.chmod(0o600)
        return path

    def analyze(self, base: str, head: str):
        return changed_code.analyze(
            self.root,
            base,
            head,
            self.evidence / "maven-provenance.json",
        )


class ChangedCodeAnalysisTest(unittest.TestCase):
    def setUp(self) -> None:
        self.fixture = ChangedCodeFixture()
        self.addCleanup(self.fixture.close)

    def test_four_repeatable_calibration_classes(self) -> None:
        samples = (
            (
                "domain",
                (
                    "services/sample/sample-service/src/main/java/"
                    "example/domain/Policy.java"
                ),
                1,
                0,
                1,
                1.0,
            ),
            (
                "infrastructure-adapter",
                (
                    "services/sample/sample-service/src/main/java/"
                    "example/adapter/outbound/persistence/Store.java"
                ),
                0,
                1,
                2,
                6.0,
            ),
            (
                "configuration-assembly",
                "services/sample/sample-service/src/main/java/example/SampleConfiguration.java",
                1,
                1,
                2,
                2.5,
            ),
        )
        for name, path, covered, missed, complexity, expected_crap in samples:
            with self.subTest(name=name):
                fixture = ChangedCodeFixture()
                self.addCleanup(fixture.close)
                head, _report = fixture.add_java_change(
                    path,
                    covered=covered,
                    missed=missed,
                    complexity=complexity,
                )
                result = fixture.analyze(fixture.git("rev-parse", "HEAD^"), head)
                self.assertEqual(1, result.scoreable_lines)
                self.assertEqual(covered > 0, result.lines[0].covered)
                self.assertEqual(1, len(result.methods))
                self.assertAlmostEqual(expected_crap, result.methods[0].crap)

        docs = ChangedCodeFixture()
        self.addCleanup(docs.close)
        docs.write("docs/guide.md", "# Guide\n")
        docs.write("pom.xml", "<project/>\n")
        docs_head = docs.commit("docs and build")
        now = time.time_ns()
        docs.write_provenance(docs.base, docs_head, now, now + 1)
        docs_result = docs.analyze(docs.base, docs_head)
        self.assertEqual(0, docs_result.scoreable_lines)
        self.assertEqual(2, len(docs_result.exclusions))
        self.assertEqual(
            {"documentation or build change"},
            {item.reason for item in docs_result.exclusions},
        )

    def test_added_modified_and_deleted_files_are_classified(self) -> None:
        head, _report = self.fixture.add_java_change(
            "services/sample/sample-service/src/main/java/example/domain/Policy.java"
        )
        result = self.fixture.analyze(self.fixture.git("rev-parse", "HEAD^"), head)
        self.assertEqual([4], [line.line for line in result.lines])

        source = self.fixture.root / (
            "services/sample/sample-service/src/main/java/example/domain/Policy.java"
        )
        source.unlink()
        deleted_head = self.fixture.commit("delete policy")
        now = time.time_ns()
        self.fixture.write_provenance(head, deleted_head, now, now + 1)
        deleted = self.fixture.analyze(head, deleted_head)
        self.assertEqual(0, deleted.scoreable_lines)
        self.assertEqual("deleted production Java source", deleted.exclusions[0].reason)

    def test_missing_stale_mismatched_and_unscorable_evidence_fail_closed(self) -> None:
        head, report = self.fixture.add_java_change(
            "services/sample/sample-service/src/main/java/example/domain/Policy.java"
        )
        base = self.fixture.git("rev-parse", "HEAD^")

        report.unlink()
        with self.assertRaisesRegex(changed_code.ChangedCodeError, "missing JaCoCo report"):
            self.fixture.analyze(base, head)

        head, report = self.fixture.add_java_change(
            "services/second/second-service/src/main/java/example/adapter/Adapter.java"
        )
        base = self.fixture.git("rev-parse", "HEAD^")
        stale_time = report.stat().st_mtime_ns + 1_000_000
        self.fixture.write_provenance(base, head, stale_time, stale_time + 1)
        with self.assertRaisesRegex(changed_code.ChangedCodeError, "outside Maven build window"):
            self.fixture.analyze(base, head)

        self.fixture.write_provenance(
            base, self.fixture.git("rev-parse", "HEAD^"), 0, time.time_ns()
        )
        with self.assertRaisesRegex(changed_code.ChangedCodeError, "provenance head mismatch"):
            self.fixture.analyze(base, head)

        report.write_text(
            report.read_text().replace('name=\"value\"', 'name=\"other\"'),
            encoding="utf-8",
        )
        class_mtime = min(
            path.stat().st_mtime_ns
            for path in self.fixture.root.rglob("*.class")
        )
        started = min(class_mtime, report.stat().st_mtime_ns) - 1
        self.fixture.write_provenance(base, head, started, time.time_ns())
        with self.assertRaisesRegex(
            changed_code.ChangedCodeError, "cannot map changed executable line"
        ):
            self.fixture.analyze(base, head)

    def test_non_executable_changed_line_is_explicitly_excluded(self) -> None:
        path = "services/sample/sample-service/src/main/java/example/domain/Policy.java"
        head, _report = self.fixture.add_java_change(path)
        source = self.fixture.root / path
        source.write_text(source.read_text() + "// calibration comment\n", encoding="utf-8")
        comment_head = self.fixture.commit("comment only")
        start = time.time_ns()
        report = self.fixture.root / "services/sample/sample-service/target/site/jacoco/jacoco.xml"
        report.touch()
        classes = self.fixture.root / "services/sample/sample-service/target/classes"
        for class_file in classes.rglob("*.class"):
            class_file.touch()
        self.fixture.write_provenance(head, comment_head, start, time.time_ns())
        result = self.fixture.analyze(head, comment_head)
        self.assertEqual(0, result.scoreable_lines)
        self.assertEqual("non-executable production Java line", result.exclusions[0].reason)

    def test_summary_is_bounded_and_report_only(self) -> None:
        head, _report = self.fixture.add_java_change(
            "services/sample/sample-service/src/main/java/example/domain/Policy.java",
            covered=0,
            missed=1,
            complexity=8,
        )
        result = self.fixture.analyze(self.fixture.git("rev-parse", "HEAD^"), head)
        summary = changed_code.render_summary(result)
        self.assertIn("scoreable scope: 1 changed executable line(s), 1 changed method(s)", summary)
        self.assertIn("changed-line coverage: 0.00% (0/1)", summary)
        self.assertIn("highest changed-method CRAP: 72.000", summary)
        self.assertIn("numeric policy: report-only", summary)
        self.assertLessEqual(len(summary.encode()), changed_code.MAX_EXTENSION_SUMMARY_BYTES)

    def test_normal_extension_writes_private_provenance_bound_evidence(self) -> None:
        tool = self.fixture.root / "tools/quality/changed_code.py"
        tool.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(MODULE_PATH, tool)
        extension_source = MODULE_PATH.parent / "checks.d/normal/40-changed-code"
        extension = self.fixture.root / "tools/quality/checks.d/normal/40-changed-code"
        extension.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(extension_source, extension)
        head, _report = self.fixture.add_java_change(
            "services/sample/sample-service/src/main/java/example/domain/Policy.java"
        )
        base = self.fixture.git("rev-parse", "HEAD^")
        summary = self.fixture.evidence / "extension-summary.txt"
        environment = os.environ.copy()
        environment.update(
            {
                "QUALITY_GATE_BASE": base,
                "QUALITY_GATE_HEAD": head,
                "QUALITY_GATE_PROFILE": "normal",
                "QUALITY_GATE_RUN_DIR": str(self.fixture.evidence),
                "QUALITY_GATE_MAVEN_PROVENANCE": str(
                    self.fixture.evidence / "maven-provenance.json"
                ),
                "QUALITY_GATE_SUMMARY_PATH": str(summary),
            }
        )
        result = subprocess.run(
            [str(extension)],
            cwd=self.fixture.root,
            env=environment,
            text=True,
            capture_output=True,
            check=False,
        )
        self.assertEqual(0, result.returncode, result.stdout + result.stderr)
        detail = self.fixture.evidence / "changed-code.json"
        payload = json.loads(detail.read_text(encoding="utf-8"))
        artifact_paths = {item["path"] for item in payload["artifacts"]}
        self.assertIn(
            "services/sample/sample-service/src/main/java/example/domain/Policy.java",
            artifact_paths,
        )
        self.assertIn(
            "services/sample/sample-service/target/site/jacoco/jacoco.xml",
            artifact_paths,
        )
        self.assertEqual("maven-provenance.json", payload["mavenProvenance"]["path"])
        self.assertEqual(0o600, detail.stat().st_mode & 0o777)
        self.assertEqual(0o600, summary.stat().st_mode & 0o777)
        self.assertIn("numeric policy: report-only", summary.read_text())


if __name__ == "__main__":
    unittest.main()
