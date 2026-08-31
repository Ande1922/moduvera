#!/usr/bin/env python3
"""Dependency-free validator for the JSON-Schema subset used by this benchmark."""

from __future__ import annotations

import argparse
import json
import math
import re
from datetime import datetime
from pathlib import Path
from typing import Any


def _resolve_pointer(root: dict[str, Any], reference: str) -> Any:
    if not reference.startswith("#/"):
        raise ValueError(f"only local JSON pointers are supported: {reference}")
    value: Any = root
    for raw_part in reference[2:].split("/"):
        part = raw_part.replace("~1", "/").replace("~0", "~")
        value = value[part]
    return value


def _is_type(instance: Any, expected: str) -> bool:
    checks = {
        "null": lambda value: value is None,
        "boolean": lambda value: isinstance(value, bool),
        "integer": lambda value: isinstance(value, int) and not isinstance(value, bool),
        "number": lambda value: (
            isinstance(value, (int, float))
            and not isinstance(value, bool)
            and math.isfinite(value)
        ),
        "string": lambda value: isinstance(value, str),
        "array": lambda value: isinstance(value, list),
        "object": lambda value: isinstance(value, dict),
    }
    return checks[expected](instance)


def _is_datetime(value: str) -> bool:
    try:
        datetime.fromisoformat(value.replace("Z", "+00:00"))
        return "T" in value
    except ValueError:
        return False


def validate(instance: Any, schema: dict[str, Any]) -> list[str]:
    """Validate one instance and return stable, path-qualified errors."""

    root = schema

    def visit(value: Any, rule: Any, path: str) -> list[str]:
        if isinstance(rule, bool):
            return [] if rule else [f"{path}: rejected by false schema"]
        if not isinstance(rule, dict):
            return [f"{path}: schema node is not an object"]
        errors: list[str] = []

        reference = rule.get("$ref")
        if reference is not None:
            try:
                return visit(value, _resolve_pointer(root, reference), path)
            except (KeyError, TypeError, ValueError) as error:
                return [f"{path}: invalid $ref {reference!r}: {error}"]

        if "const" in rule and value != rule["const"]:
            errors.append(f"{path}: expected constant {rule['const']!r}")
        if "enum" in rule and value not in rule["enum"]:
            errors.append(f"{path}: value is not in enum")

        expected_type = rule.get("type")
        if expected_type is not None:
            allowed = [expected_type] if isinstance(expected_type, str) else expected_type
            if not any(_is_type(value, item) for item in allowed):
                return errors + [f"{path}: expected type {allowed!r}"]

        if isinstance(value, dict):
            required = rule.get("required", [])
            for name in required:
                if name not in value:
                    errors.append(f"{path}: missing required property {name!r}")
            properties = rule.get("properties", {})
            for name, child in properties.items():
                if name in value:
                    errors.extend(visit(value[name], child, f"{path}.{name}"))
            if rule.get("additionalProperties") is False:
                for name in value.keys() - properties.keys():
                    errors.append(f"{path}: additional property {name!r} is not allowed")

        if isinstance(value, list):
            if len(value) < rule.get("minItems", 0):
                errors.append(f"{path}: fewer than minItems")
            if "maxItems" in rule and len(value) > rule["maxItems"]:
                errors.append(f"{path}: more than maxItems")
            if "items" in rule:
                for index, item in enumerate(value):
                    errors.extend(visit(item, rule["items"], f"{path}[{index}]"))

        if isinstance(value, str):
            if len(value) < rule.get("minLength", 0):
                errors.append(f"{path}: shorter than minLength")
            if "pattern" in rule and re.search(rule["pattern"], value) is None:
                errors.append(f"{path}: does not match pattern")
            if rule.get("format") == "date-time" and not _is_datetime(value):
                errors.append(f"{path}: invalid date-time")

        if (
            isinstance(value, (int, float))
            and not isinstance(value, bool)
            and "minimum" in rule
            and value < rule["minimum"]
        ):
            errors.append(f"{path}: below minimum")

        for index, child in enumerate(rule.get("allOf", [])):
            errors.extend(visit(value, child, f"{path}<allOf:{index}>"))

        if "oneOf" in rule:
            matches = sum(not visit(value, child, path) for child in rule["oneOf"])
            if matches != 1:
                errors.append(f"{path}: oneOf matched {matches} branches")

        if "not" in rule and not visit(value, rule["not"], path):
            errors.append(f"{path}: matched forbidden schema")

        if "if" in rule:
            branch = "then" if not visit(value, rule["if"], path) else "else"
            if branch in rule:
                errors.extend(visit(value, rule[branch], f"{path}<{branch}>"))
        return errors

    return visit(instance, schema, "$")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--schema", type=Path, required=True)
    parser.add_argument("document", type=Path)
    args = parser.parse_args()
    schema = json.loads(args.schema.read_text(encoding="utf-8"))
    document = json.loads(args.document.read_text(encoding="utf-8"))
    errors = validate(document, schema)
    print(json.dumps({"status": "PASS" if not errors else "FAIL", "errors": errors}))
    return 0 if not errors else 2


if __name__ == "__main__":
    raise SystemExit(main())
