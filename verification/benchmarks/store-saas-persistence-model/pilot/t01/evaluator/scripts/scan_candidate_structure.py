#!/usr/bin/env python3
"""Inspect actual MyBatis result/parameter types for the frozen T01 S/U seam."""

from __future__ import annotations

import argparse
import json
import re
from pathlib import Path

from mybatis_mapper_model import STORE_COLUMNS, parse_mapper, store_statements


CANDIDATE_PACKAGE = "io.github.ande1922.moduvera.benchmark.store.candidate."
DOMAIN_STORE = "io.github.ande1922.moduvera.benchmark.store.shared.Store"
GENERIC_TYPES = {
    "map",
    "hashmap",
    "java.util.map",
    "java.util.hashmap",
    "java.lang.object",
    "object",
    "jsonobject",
}
FIELD_NAMES = {
    "tenantId",
    "storeId",
    "code",
    "name",
    "timeZoneId",
    "status",
    "version",
    "createdAt",
    "createdByType",
    "createdById",
    "updatedAt",
    "updatedByType",
    "updatedById",
}


def _candidate_classes(java_files: list[Path]) -> dict[str, tuple[Path, str]]:
    classes = {}
    for path in java_files:
        text = path.read_text(encoding="utf-8")
        package = re.search(r"\bpackage\s+([\w.]+)\s*;", text)
        declaration = re.search(r"\b(?:class|record|interface|enum)\s+(\w+)", text)
        if package and declaration:
            classes[f"{package.group(1)}.{declaration.group(1)}"] = (path, text)
    return classes


def _mapped_carriers(classes: dict[str, tuple[Path, str]]) -> set[str]:
    carriers = set()
    for qualified_name, (_, text) in classes.items():
        declared = set(
            re.findall(
                r"\b(?:private|protected|public)\s+(?:static\s+|final\s+)*"
                r"[\w<>, ?.@\[\]]+\s+(\w+)\s*(?:=|;)",
                text,
            )
        )
        record = re.search(r"\brecord\s+\w+\s*\((.*?)\)\s*\{", text, re.DOTALL)
        if record:
            declared.update(re.findall(r"\b(\w+)\s*(?:,|$)", record.group(1)))
        fields = FIELD_NAMES & declared
        if len(fields) >= 5:
            carriers.add(qualified_name)
    return carriers


def _mapper_source(namespace: str, classes: dict[str, tuple[Path, str]]) -> str:
    entry = classes.get(namespace)
    return entry[1] if entry else ""


def _simple(qualified_name: str) -> str:
    return qualified_name.rsplit(".", 1)[-1]


def _method_signature(mapper_text: str, method_id: str) -> str:
    match = re.search(
        rf"\b{re.escape(method_id)}\s*\((.*?)\)\s*;",
        mapper_text,
        flags=re.DOTALL,
    )
    return match.group(0) if match else ""


def scan(candidate_root: Path, variant: str) -> dict[str, object]:
    source_root = candidate_root / "src" / "main" / "java"
    resource_root = candidate_root / "src" / "main" / "resources"
    java_files = sorted(source_root.rglob("*.java")) if source_root.is_dir() else []
    classes = _candidate_classes(java_files)
    errors: list[str] = []
    provider = CANDIDATE_PACKAGE + "CandidateStorePersistenceProvider"
    if provider not in classes:
        errors.append("missing public CandidateStorePersistenceProvider")

    models = []
    for path in sorted(resource_root.rglob("*.xml")) if resource_root.is_dir() else []:
        try:
            models.append(parse_mapper(path))
        except Exception as error:
            errors.append(f"{path.name}: invalid mapper: {error}")
    selects = [statement for statement in store_statements(models) if statement.operation == "select"]
    if not selects:
        errors.append("no Store SELECT statement found")
    carriers = _mapped_carriers(classes)
    result_types = set()

    for model in models:
        mapper_text = _mapper_source(model.namespace, classes)
        for statement in model.statements:
            if "store_location" not in statement.sql:
                continue
            prefix = statement.qualified_id
            if statement.operation == "select":
                root_type = statement.result_type
                if not root_type:
                    errors.append(f"{prefix}: SELECT has no explicit resultMap/resultType")
                    continue
                result_types.add(root_type)
                if root_type.lower() in GENERIC_TYPES or root_type.endswith("[]"):
                    errors.append(f"{prefix}: generic/array result type is forbidden: {root_type}")
                missing = sorted(STORE_COLUMNS - statement.result_columns)
                extra = sorted(statement.result_columns - STORE_COLUMNS)
                if missing or extra:
                    errors.append(f"{prefix}: mapped columns differ; missing={missing}, extra={extra}")
                simple = _simple(root_type)
                method_id = prefix.rsplit(".", 1)[-1]
                if mapper_text and re.search(rf"\b{re.escape(simple)}\s+{re.escape(method_id)}\s*\(", mapper_text) is None:
                    errors.append(f"{prefix}: Java Mapper return type does not match {root_type}")

    if variant == "U":
        for root_type in result_types:
            if root_type != DOMAIN_STORE:
                errors.append(f"U Store SELECT must map directly to Domain Store, got {root_type}")
        shadow_carriers = {name for name in carriers if name != provider}
        if shadow_carriers:
            errors.append(f"U contains a renamed or named shadow persistence carrier: {sorted(shadow_carriers)}")
        assertion_id = "T01-U01"
    else:
        row_types = {root_type for root_type in result_types if root_type.startswith(CANDIDATE_PACKAGE)}
        if len(row_types) != 1:
            errors.append(f"S requires exactly one candidate typed row result, got {sorted(row_types)}")
        if DOMAIN_STORE in result_types:
            errors.append("S Store SELECT maps directly to Domain Store")
        for row_type in row_types:
            if row_type not in classes:
                errors.append(f"S result type is not a declared candidate class: {row_type}")
                continue
            row_simple = _simple(row_type)
            all_java = "\n".join(text for _, text in classes.values())
            to_row = re.search(rf"\b{re.escape(row_simple)}\s+\w+\s*\([^)]*\bStore\b", all_java)
            to_domain = re.search(rf"\bStore\s+\w+\s*\([^)]*\b{re.escape(row_simple)}\b", all_java)
            if not to_row or not to_domain:
                errors.append(f"S lacks explicit two-way Store <-> {row_simple} conversion methods")
            for model in models:
                mapper_text = _mapper_source(model.namespace, classes)
                for statement in model.statements:
                    if "store_location" not in statement.sql or statement.operation not in {"insert", "update"}:
                        continue
                    method_id = statement.qualified_id.rsplit(".", 1)[-1]
                    parameter_matches = statement.parameter_type == row_type or bool(
                        re.search(
                            rf"\b{re.escape(row_simple)}\b",
                            _method_signature(mapper_text, method_id),
                        )
                    )
                    if not parameter_matches:
                        errors.append(f"{statement.qualified_id}: S write does not use {row_simple}")
        assertion_id = "T01-S01"

    return {
        "assertion_id": assertion_id,
        "status": "PASS" if not errors else "FAIL",
        "errors": errors,
        "java_files": len(java_files),
        "xml_files": len(models),
        "result_types": sorted(result_types),
        "candidate_carriers": sorted(carriers),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--candidate-root", type=Path, required=True)
    parser.add_argument("--variant", choices=("S", "U"), required=True)
    args = parser.parse_args()
    result = scan(args.candidate_root, args.variant)
    print(json.dumps(result, sort_keys=True))
    return 0 if result["status"] == "PASS" else 2


if __name__ == "__main__":
    raise SystemExit(main())
