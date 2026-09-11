from __future__ import annotations

import os
from pathlib import Path
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[3]


class QualificationCleanupTest(unittest.TestCase):
    def run_stop(self, retention_failure=False, primary_status=0, missing_receipt=False,
                 fallback_failure=False):
        outer = (ROOT / "verification/governed-observability/verify.sh").read_text()
        inner = (ROOT / "verification/reference-product/harness/run-topology.sh").read_text()
        outer_functions = outer[outer.index("stop_process() {"):outer.index("trap cleanup EXIT")]
        inner_cleanup = inner[inner.index("retain_stdout() {"):inner.index("start_app() {")]
        completion = "stop_" + outer.rsplit("\nstop_", 1)[1]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            run = root / "run"
            run.mkdir()
            (run / "gateway.log").write_text("shutdown event\n")
            destination = root / "retained"
            if retention_failure:
                destination.write_text("not a directory\n")
            child = root / "child.sh"
            child.write_text("#!/usr/bin/env bash\nset -euo pipefail\n"
                             "stop_apps() { :; }\nreference_release_run_locks() { :; }\n"
                             "FAILED=0\nCOMPOSE_STARTED=0\n" + inner_cleanup
                             + ("trap 'exit 143' TERM\ntrap - EXIT\n" if missing_receipt else "")
                             + ': > "$EVIDENCE_DIR/ready"\nwhile true; do sleep 0.05; done\n')
            script = "set -euo pipefail\n" + outer_functions + """
collect_topology_stdout() { :; }
COMPOSE_PROJECT="$TEST_COMPOSE_PROJECT"
COMPOSE_FILE="unused"
docker() { return 42; }
ORDER_PROXY_PID=""
CATALOG_PROXY_PID=""
IDENTITY_PROXY_PID=""
RECEIVER_PID=""
bash "$CHILD" &
HARNESS_PID=$!
trap cleanup EXIT
until [[ -f "$EVIDENCE_DIR/ready" ]]; do sleep 0.01; done
""" + (f"exit {primary_status}\n" if primary_status else completion)
            configured = dict(os.environ, EVIDENCE_DIR=directory, RUN_DIR=str(run), CHILD=str(child),
                              REFERENCE_STDOUT_EVIDENCE_DIR=str(destination),
                              TEST_COMPOSE_PROJECT="fixture" if fallback_failure else "",
                              REFERENCE_CLEANUP_STATUS_FILE=str(root / "harness-cleanup.exit"))
            result = subprocess.run(["bash", "-c", script], env=configured, capture_output=True,
                                    text=True, timeout=10)
            retained = (destination / "gateway.log").is_file()
            return result, retained, run.exists()

    def test_successful_requested_shutdown_retains_stdout_and_allows_pass(self):
        result, retained, run_exists = self.run_stop()
        self.assertEqual(0, result.returncode, result.stderr)
        self.assertIn("verification: PASS", result.stdout)
        self.assertTrue(retained)
        self.assertFalse(run_exists)

    def test_failed_requested_stdout_copy_fails_outer_qualification(self):
        result, retained, run_exists = self.run_stop(retention_failure=True)
        self.assertEqual(70, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("verification: PASS", result.stdout)
        self.assertFalse(retained)
        self.assertFalse(run_exists)

    def test_missing_cleanup_receipt_cannot_be_accepted_as_normal_term(self):
        result, _, _ = self.run_stop(missing_receipt=True)
        self.assertEqual(70, result.returncode, result.stdout + result.stderr)
        self.assertNotIn("verification: PASS", result.stdout)

    def test_cleanup_failure_preserves_original_run_failure(self):
        result, _, _ = self.run_stop(retention_failure=True, primary_status=23)
        self.assertEqual(23, result.returncode, result.stderr)
        self.assertIn("cleanup failure", result.stderr)

    def test_fallback_cleanup_failure_prevents_final_pass(self):
        result, retained, _ = self.run_stop(fallback_failure=True)
        self.assertEqual(70, result.returncode, result.stderr)
        self.assertNotIn("verification: PASS", result.stdout)
        self.assertTrue(retained)


if __name__ == "__main__":
    unittest.main()
