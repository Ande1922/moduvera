"""Exercise topology selection without launching Maven, Docker or application JVMs."""
import importlib.util
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[1]


class TopologyScopeTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name)
        self.log = self.root / "calls"
        self.env = {k: v for k, v in os.environ.items()
                    if not k.startswith(("REFERENCE_", "MODUVERA_IMAGE_"))}
        self.env["SCOPE_TEST_LOG"] = str(self.log)

    def copy(self, relative):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(ROOT / relative, target)
        return target

    def stub(self, relative, label, status=0):
        target = self.root / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        target.write_text('#!/usr/bin/env bash\n'
                          f'echo "{label} $*" >> "$SCOPE_TEST_LOG"\nexit {status}\n')
        target.chmod(0o755)

    def reference(self, *args, build_status=0, skip=False):
        prefix = "verification/reference-product/harness/"
        script = self.copy(prefix + "verify.sh")
        for name in ("test-port-plan.sh", "test-cleanup.sh", "test-parallel-scenario.sh"):
            self.stub(prefix + "tests/" + name, "self-test")
        self.stub(prefix + "run-topology.sh", "topology")
        self.stub("mvnw", "maven", build_status)
        if skip:
            self.env["REFERENCE_SKIP_BUILD"] = "1"
        result = subprocess.run(["bash", str(script), *args], env=self.env,
                                capture_output=True, text=True)
        calls = self.log.read_text() if self.log.exists() else ""
        return result, calls

    def test_default_microservices(self):
        result, calls = self.reference()
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertIn("topology microservices", calls)
        self.assertNotIn("topology business-core-monolith", calls)
        self.assertIn("-Dmonolith.skipITs=true", calls)
        self.assertNotIn("ModuveraMonolithApplicationIT", result.stdout)

    def test_explicit_monolith_and_all_enable_integration_tests(self):
        for selected in ("business-core-monolith", "all"):
            with self.subTest(selected=selected):
                self.log.unlink(missing_ok=True)
                result, calls = self.reference(selected)
                self.assertEqual(result.returncode, 0, result.stderr)
                self.assertIn("-Dmonolith.skipITs=false", calls)
                self.assertIn("topology business-core-monolith", calls)
                self.assertEqual("topology microservices" in calls, selected == "all")
                self.assertIn("ModuveraMonolithApplicationIT", result.stdout)

    def test_build_failure_prevents_runtime_and_pass(self):
        result, calls = self.reference("all", build_status=23)
        self.assertEqual(result.returncode, 23)
        self.assertNotIn("topology ", calls)
        self.assertNotIn("PASS", result.stdout)

    def test_skip_build_does_not_claim_integration_evidence(self):
        result, calls = self.reference("business-core-monolith", skip=True)
        self.assertEqual(result.returncode, 0, result.stderr)
        self.assertNotIn("maven", calls)
        self.assertNotIn("duplicate-delivery evidence", result.stdout)

    def test_invalid_topology_has_no_side_effects(self):
        result, calls = self.reference("unknown")
        self.assertEqual(result.returncode, 64)
        self.assertEqual(calls, "")

    def test_image_build_defaults_and_explicit_selection(self):
        prefix = "verification/application-image/"
        script = self.copy(prefix + "build-images.sh")
        self.copy(prefix + "apps.tsv")
        for app in ("gateway-app", "identity-app", "catalog-app", "order-app", "inventory-app", "app-monolith"):
            target = self.root / "apps" / app / "target"
            target.mkdir(parents=True)
            (target / f"{app}-test.jar").touch()
        self.stub("bin/docker", "docker")
        self.env["PATH"] = str(self.root / "bin") + os.pathsep + self.env["PATH"]
        for include, args, count in ((False, [], 5), (True, [], 6), (False, ["app-monolith"], 1)):
            with self.subTest(include=include, args=args):
                self.log.unlink(missing_ok=True)
                self.env["MODUVERA_IMAGE_INCLUDE_MONOLITH"] = str(int(include))
                result = subprocess.run(["bash", str(script), *args], env=self.env,
                                        capture_output=True, text=True)
                self.assertEqual(result.returncode, 0, result.stderr)
                calls = self.log.read_text()
                self.assertEqual(len(calls.splitlines()), count)
                self.assertEqual("app-monolith" in calls, include or bool(args))

    def test_image_inspection_scope_matches_build_scope(self):
        sys.path.insert(0, str(ROOT / "verification/application-image"))
        self.addCleanup(sys.path.pop, 0)
        spec = importlib.util.spec_from_file_location("inspect_images", ROOT / "verification/application-image/inspect_images.py")
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        for include, count in (("0", 5), ("1", 6)):
            with patch.dict(os.environ, {"MODUVERA_IMAGE_INCLUDE_MONOLITH": include}):
                apps = dict(module.apps())
                self.assertEqual(len(apps), count)
                self.assertEqual("app-monolith" in apps, include == "1")


if __name__ == "__main__":
    unittest.main()
