from __future__ import annotations

import shutil
import sys
import tempfile
import unittest
from pathlib import Path


SCRIPT_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(SCRIPT_ROOT))

from scan_candidate_structure import scan as scan_structure  # noqa: E402
from scan_sql_scope import scan as scan_sql  # noqa: E402


T01_ROOT = SCRIPT_ROOT.parents[1]
ORACLES = T01_ROOT / "evaluator" / "oracles"


class ScannerRegressionTest(unittest.TestCase):
    def test_private_oracles_pass(self) -> None:
        separated = ORACLES / "separated-provider"
        unified = ORACLES / "unified-provider"
        self.assertEqual("PASS", scan_sql(separated)["status"])
        self.assertEqual("PASS", scan_sql(unified)["status"])
        self.assertEqual("PASS", scan_structure(separated, "S")["status"])
        self.assertEqual("PASS", scan_structure(unified, "U")["status"])

    def _mutated_unified(self, old: str, new: str) -> Path:
        temporary = Path(tempfile.mkdtemp(prefix="t01-scanner-"))
        self.addCleanup(shutil.rmtree, temporary)
        root = temporary / "candidate"
        shutil.copytree(ORACLES / "unified-provider", root)
        mapper = next((root / "src" / "main" / "resources").rglob("*.xml"))
        text = mapper.read_text(encoding="utf-8")
        self.assertIn(old, text)
        mapper.write_text(text.replace(old, new, 1), encoding="utf-8")
        return root

    def test_tenant_only_in_projection_does_not_pass(self) -> None:
        root = self._mutated_unified("WHERE tenant_id = #{tenantId,", "WHERE code = #{tenantId,")
        result = scan_sql(root)
        self.assertEqual("FAIL", result["status"])
        self.assertTrue(any("tenant_id" in error for error in result["errors"]))

    def test_version_only_in_set_does_not_count_as_cas(self) -> None:
        root = self._mutated_unified("AND version = #{store.version}", "AND status = #{store.status}")
        result = scan_sql(root)
        self.assertEqual("FAIL", result["status"])
        self.assertTrue(any("version" in error for error in result["errors"]))

    def test_insert_missing_tenant_fails(self) -> None:
        root = self._mutated_unified(
            "INSERT INTO store_location (\n      tenant_id,",
            "INSERT INTO store_location (\n      created_by_id,",
        )
        result = scan_sql(root)
        self.assertEqual("FAIL", result["status"])
        self.assertTrue(any("INSERT columns differ" in error for error in result["errors"]))

    def test_dollar_substitution_fails(self) -> None:
        root = self._mutated_unified("#{tenantId,", "${tenantId,")
        result = scan_sql(root)
        self.assertEqual("FAIL", result["status"])
        self.assertTrue(any("substitution" in error for error in result["errors"]))

    def test_unified_renamed_shadow_carrier_fails(self) -> None:
        root = self._mutated_unified("SELECT tenant_id,", "SELECT tenant_id,")
        carrier = (
            root
            / "src/main/java/io/github/ande1922/moduvera/benchmark/store/candidate/PersistenceSnapshot.java"
        )
        carrier.write_text(
            """package io.github.ande1922.moduvera.benchmark.store.candidate;
final class PersistenceSnapshot {
  private String tenantId; private long storeId; private String code;
  private String name; private String timeZoneId; private String status;
  private long version; private Object createdAt; private String createdByType;
  private String createdById; private Object updatedAt; private String updatedByType;
  private String updatedById;
}
""",
            encoding="utf-8",
        )
        result = scan_structure(root, "U")
        self.assertEqual("FAIL", result["status"])
        self.assertTrue(any("shadow persistence carrier" in error for error in result["errors"]))


if __name__ == "__main__":
    unittest.main()
