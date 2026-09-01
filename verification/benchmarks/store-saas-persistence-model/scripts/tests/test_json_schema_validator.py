from __future__ import annotations

import json
import sys
import unittest
from pathlib import Path


SCRIPT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_ROOT))

from validate_json_schema import validate  # noqa: E402


class JsonSchemaValidatorTest(unittest.TestCase):
    def test_accepts_blocked_manifest_template(self) -> None:
        benchmark_root = SCRIPT_ROOT.parent
        schema = json.loads((benchmark_root / "schemas" / "run-manifest.schema.json").read_text())
        document = json.loads((benchmark_root / "templates" / "run-manifest.example.json").read_text())
        self.assertEqual([], validate(document, schema))

    def test_rejects_ready_placeholder_and_extra_field(self) -> None:
        benchmark_root = SCRIPT_ROOT.parent
        schema = json.loads((benchmark_root / "schemas" / "run-manifest.schema.json").read_text())
        document = json.loads((benchmark_root / "templates" / "run-manifest.example.json").read_text())
        document["preflight_status"] = "READY"
        document["unexpected"] = True
        errors = validate(document, schema)
        self.assertTrue(any("additional property" in error for error in errors))
        self.assertTrue(any("forbidden schema" in error or "expected type" in error for error in errors))


if __name__ == "__main__":
    unittest.main()
