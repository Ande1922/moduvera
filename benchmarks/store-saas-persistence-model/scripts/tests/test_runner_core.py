from __future__ import annotations

import sys
import unittest
from pathlib import Path


SCRIPT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_ROOT))

from probe_permission_profile import config_args  # noqa: E402
from runner_core import build_permission_profile, permission_config_args  # noqa: E402


class RunnerCoreTest(unittest.TestCase):
    def test_permission_profile_is_default_deny_and_jdk_is_explicit(self) -> None:
        candidate = Path("/private/tmp/candidate")
        jdk = Path("/opt/jdk-26")
        maven = Path("/opt/maven")
        repository = Path("/cache/m2")
        profile = build_permission_profile(candidate, jdk, maven, repository)
        self.assertIn('extends = ":read-only"', profile)
        self.assertIn('\":root\" = \"deny\"', profile)
        self.assertIn('\".\" = \"deny\"', profile)
        self.assertIn('\"src\" = \"read\"', profile)
        self.assertIn('target\" = \"write\"', profile)
        args = permission_config_args(
            profile,
            model="gpt-test",
            reasoning_effort="high",
            service_tier="priority",
            candidate_root=candidate,
            jdk_home=jdk,
            maven_home=maven,
            maven_repository=repository,
        )
        joined = " ".join(args)
        self.assertIn('default_permissions=\"pilot_candidate\"', joined)
        self.assertIn('permissions.pilot_candidate.extends=\":read-only\"', joined)
        self.assertIn('JAVA_HOME\"=\"/opt/jdk-26', joined)
        self.assertIn("features.memories=false", joined)
        self.assertNotIn("sandbox_mode", joined)

    def test_probe_reuses_every_config_override(self) -> None:
        commands = {"initial": ["codex", "exec", "-c", "one=1", "-C", "/tmp/x", "-c", "two=2", "-"]}
        self.assertEqual(["-c", "one=1", "-c", "two=2"], config_args(commands))


if __name__ == "__main__":
    unittest.main()
