from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import tempfile
import signal
import time
import unittest


HARNESS = Path(__file__).resolve().parents[2] / "reference-product/harness/run-topology.sh"


class ApplicationLaunchTest(unittest.TestCase):
    def test_self_tests_cannot_write_the_actual_cleanup_receipt(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "tests").mkdir()
            for name in ("test-port-plan.sh", "test-cleanup.sh", "test-parallel-scenario.sh"):
                script = root / "tests" / name
                script.write_text('#!/bin/bash\n'
                                  'if [[ -n "${REFERENCE_CLEANUP_STATUS_FILE:-}" ]]; then\n'
                                  '  printf "0\\n" > "$REFERENCE_CLEANUP_STATUS_FILE"\nfi\n')
                script.chmod(0o700)
            dispatch = root / "verify.sh"
            dispatch.write_text(HARNESS.with_name("verify.sh").read_text())
            topology = root / "run-topology.sh"
            topology.write_text('#!/bin/bash\n'
                                'printf "%s" "$REFERENCE_CLEANUP_STATUS_FILE" > "$OWNER_CAPTURE"\n')
            topology.chmod(0o700)
            receipt, owner = root / "receipt", root / "owner"
            receipt.write_text("awaiting actual owner\n")
            environment = dict(os.environ, REFERENCE_SKIP_BUILD="1", REFERENCE_KEEP_RUNNING="1",
                               REFERENCE_CLEANUP_STATUS_FILE=str(receipt), OWNER_CAPTURE=str(owner))
            result = subprocess.run(["/bin/bash", str(dispatch), "microservices"], env=environment,
                                    capture_output=True, text=True, timeout=5)
            self.assertEqual(0, result.returncode, result.stderr)
            self.assertEqual(str(receipt), owner.read_text())
            self.assertEqual("awaiting actual owner\n", receipt.read_text())

    def test_keep_running_dispatch_preserves_the_cleanup_owner_pid(self) -> None:
        with tempfile.TemporaryDirectory(prefix="dispatch probe ") as directory:
            root = Path(directory)
            (root / "tests").mkdir()
            for name in ("test-port-plan.sh", "test-cleanup.sh", "test-parallel-scenario.sh"):
                script = root / "tests" / name
                script.write_text("#!/bin/bash\nexit 0\n")
                script.chmod(0o700)
            dispatch = root / "verify.sh"
            dispatch.write_text(HARNESS.with_name("verify.sh").read_text())
            topology = root / "run-topology.sh"
            topology.write_text(
                '#!/bin/bash\nset -eu\n'
                'trap \'printf done > "$CLEANUP_CAPTURE"; exit 0\' TERM\n'
                'printf "%s\\n" "$$" > "$OWNER_CAPTURE"\n'
                'while true; do sleep 0.1; done\n'
            )
            topology.chmod(0o700)
            owner, cleanup = root / "owner", root / "cleanup"
            environment = dict(os.environ, REFERENCE_SKIP_BUILD="1", REFERENCE_KEEP_RUNNING="1",
                               OWNER_CAPTURE=str(owner), CLEANUP_CAPTURE=str(cleanup))
            process = subprocess.Popen(["/bin/bash", str(dispatch), "microservices"], env=environment,
                                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
            child = None
            try:
                deadline = time.monotonic() + 5
                while not owner.exists() and time.monotonic() < deadline:
                    time.sleep(0.02)
                self.assertTrue(owner.exists(), "topology did not start")
                child = int(owner.read_text())
                self.assertEqual(process.pid, child, "wrapper hides the resource-cleanup owner")
                process.terminate()
                process.wait(timeout=5)
                self.assertEqual("done", cleanup.read_text())
            finally:
                if child is not None and child != process.pid:
                    try:
                        os.kill(child, signal.SIGTERM)
                    except ProcessLookupError:
                        pass
                if process.poll() is None:
                    process.terminate()
                process.wait(timeout=5)

    def test_public_and_internal_apps_reach_the_process_with_correct_ingress(self) -> None:
        # Exercise the real shell launch boundary, including Bash 3.2 nounset on macOS.
        source = HARNESS.read_text()
        launch = source[source.index("start_app() {"):source.index("\nwait_http() {")]
        for app, internal in (("identity", False), ("gateway", False), ("order", True),
                              ("catalog", True), ("inventory", True)):
            with self.subTest(app=app), tempfile.TemporaryDirectory(prefix="launch probe ") as directory:
                root = Path(directory)
                runtime = root / "java"
                receipt = root / "receipt.json"
                runtime.write_text(
                    "#!/usr/bin/env python3\n"
                    "import json, os, sys\n"
                    "from pathlib import Path\n"
                    "Path(os.environ['CAPTURE']).write_text(json.dumps({\n"
                    " 'arguments': sys.argv[1:],\n"
                    " 'address': os.environ.get('SERVER_ADDRESS'),\n"
                    " 'internal': os.environ.get('MODUVERA_WEB_INTERNAL_INGRESS')}))\n"
                )
                runtime.chmod(0o700)
                environment = {key: value for key, value in os.environ.items()
                               if key not in ("SERVER_ADDRESS", "MODUVERA_WEB_INTERNAL_INGRESS")}
                environment.update(RUN_DIR=directory, PROJECT_ROOT=directory, JAVA_BIN=str(runtime),
                                   REFERENCE_JAVA_TOOL_OPTIONS="-Xms64m -Xmx256m",
                                   REFERENCE_GOVERNED_OBSERVABILITY="0", REFERENCE_DEBUG="0")
                result = subprocess.run(
                    ["/bin/bash", "-c", "set -euo pipefail\n" + launch
                     + '\nstart_app "$1" "$1.jar" "CAPTURE=$2"\nwait "$!"\n',
                     "launch-test", app, str(receipt)], env=environment, capture_output=True, text=True,
                    timeout=10,
                )
                self.assertEqual(0, result.returncode, result.stderr)
                actual = json.loads(receipt.read_text())
                self.assertEqual(["-jar", str(root / (app + ".jar"))], actual["arguments"])
                self.assertEqual("127.0.0.1" if internal else None, actual["address"])
                self.assertEqual("true" if internal else None, actual["internal"])


if __name__ == "__main__":
    unittest.main()
