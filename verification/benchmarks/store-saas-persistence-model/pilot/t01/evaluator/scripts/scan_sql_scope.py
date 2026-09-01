#!/usr/bin/env python3
"""Fail-closed static Tenant/CAS inspection for the frozen T01 MyBatis subset."""

from __future__ import annotations

import argparse
import hashlib
import json
import re
from pathlib import Path

from mybatis_mapper_model import (
    STORE_COLUMNS,
    bound_equality,
    parse_insert,
    parse_mapper,
    store_statements,
)


def scan(candidate_root: Path) -> dict[str, object]:
    resource_root = candidate_root / "src" / "main" / "resources"
    java_root = candidate_root / "src" / "main" / "java"
    errors: list[str] = []
    models = []
    for path in sorted(resource_root.rglob("*.xml")) if resource_root.is_dir() else []:
        try:
            models.append(parse_mapper(path))
        except Exception as error:  # XML/parser failures are evaluator failures, not skips.
            errors.append(f"{path.name}: invalid mapper: {error}")
    statements = store_statements(models)
    operations = {statement.operation for statement in statements}
    for required in ("select", "insert", "update"):
        if required not in operations:
            errors.append(f"missing Store {required} statement")

    observations = []
    for statement in statements:
        prefix = statement.qualified_id
        if statement.dynamic_tags:
            errors.append(f"{prefix}: conditional SQL is outside the frozen T01 subset: {sorted(statement.dynamic_tags)}")
        if "${" in statement.sql:
            errors.append(f"{prefix}: unsafe ${{}} substitution is forbidden")
        observation: dict[str, object] = {
            "statement_id": prefix,
            "operation": statement.operation,
            "canonical_sql_sha256": hashlib.sha256(statement.sql.encode()).hexdigest(),
        }
        if statement.operation in {"select", "update", "delete"}:
            _, separator, where = statement.sql.partition(" where ")
            if not separator:
                errors.append(f"{prefix}: Store {statement.operation} has no WHERE clause")
                where = ""
            required_columns = ["tenant_id", "store_id"]
            if statement.operation == "update":
                required_columns.append("version")
            bindings = {column: bound_equality(where, column) for column in required_columns}
            observation["where_bindings"] = bindings
            for column, binding in bindings.items():
                if binding is None:
                    errors.append(f"{prefix}: WHERE lacks bound equality for {column}")
        elif statement.operation == "insert":
            parsed = parse_insert(statement)
            if parsed is None:
                errors.append(f"{prefix}: INSERT must use an explicit column/value list")
            else:
                columns, values = parsed
                observation["insert_columns"] = columns
                if len(columns) != len(values):
                    errors.append(f"{prefix}: INSERT column/value count differs")
                if len(columns) != len(set(columns)):
                    errors.append(f"{prefix}: INSERT contains duplicate columns")
                missing = sorted(STORE_COLUMNS - set(columns))
                extra = sorted(set(columns) - STORE_COLUMNS)
                if missing or extra:
                    errors.append(f"{prefix}: INSERT columns differ; missing={missing}, extra={extra}")
                if "tenant_id" in columns and len(columns) == len(values):
                    tenant_value = values[columns.index("tenant_id")]
                    observation["tenant_value"] = tenant_value
                    if not re.match(r"^#\{\s*[^,}]+", tenant_value):
                        errors.append(f"{prefix}: tenant_id INSERT value is not a bound parameter")
        observations.append(observation)

    java_text = (
        "\n".join(
            path.read_text(encoding="utf-8")
            for path in sorted(java_root.rglob("*.java"))
            if path.is_file()
        )
        if java_root.is_dir()
        else ""
    )
    if re.search(r"@(Select|Insert|Update|Delete)\b", java_text):
        errors.append("annotation SQL is forbidden; Store SQL must be in inspected MyBatis XML")
    if re.search(r"\b(prepareStatement|createStatement|DriverManager\.getConnection)\s*\(", java_text):
        errors.append("candidate raw JDBC is forbidden; runtime SQL cannot be tied to inspected XML")

    return {
        "assertion_id": "T01-H04",
        "status": "PASS" if not errors else "FAIL",
        "errors": errors,
        "statements": observations,
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-root", type=Path, required=True)
    args = parser.parse_args()
    result = scan(args.candidate_root)
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
