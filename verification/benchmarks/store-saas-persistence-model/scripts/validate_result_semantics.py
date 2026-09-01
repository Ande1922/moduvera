#!/usr/bin/env python3
"""Validate benchmark-result arithmetic and cross-field invariants."""

from __future__ import annotations

import argparse
import json
import math
import sys
from pathlib import Path
from typing import Any


USAGE_FIELDS = (
    "turns",
    "input_tokens",
    "cached_input_tokens",
    "uncached_input_tokens",
    "output_tokens",
    "reasoning_output_tokens",
    "visible_output_tokens",
    "gross_total_tokens",
)


def require(condition: bool, message: str, errors: list[str]) -> None:
    if not condition:
        errors.append(message)


def validate_usage(usage: Any, path: str, errors: list[str]) -> None:
    if not isinstance(usage, dict):
        errors.append(f"{path} must be an object")
        return
    for field in USAGE_FIELDS:
        value = usage.get(field)
        require(
            isinstance(value, int) and not isinstance(value, bool) and value >= 0,
            f"{path}.{field} must be a non-negative integer",
            errors,
        )
    if errors and any(item.startswith(path) for item in errors):
        return
    require(
        usage["cached_input_tokens"] <= usage["input_tokens"],
        f"{path}.cached_input_tokens exceeds input_tokens",
        errors,
    )
    require(
        usage["reasoning_output_tokens"] <= usage["output_tokens"],
        f"{path}.reasoning_output_tokens exceeds output_tokens",
        errors,
    )
    require(
        usage["uncached_input_tokens"]
        == usage["input_tokens"] - usage["cached_input_tokens"],
        f"{path}.uncached_input_tokens formula mismatch",
        errors,
    )
    require(
        usage["visible_output_tokens"]
        == usage["output_tokens"] - usage["reasoning_output_tokens"],
        f"{path}.visible_output_tokens formula mismatch",
        errors,
    )
    require(
        usage["gross_total_tokens"]
        == usage["input_tokens"] + usage["output_tokens"],
        f"{path}.gross_total_tokens formula mismatch",
        errors,
    )


def add_usage(total: dict[str, int], usage: dict[str, int]) -> None:
    for field in USAGE_FIELDS:
        total[field] += usage[field]


def validate_result(result: Any) -> list[str]:
    errors: list[str] = []
    if not isinstance(result, dict):
        return ["result root must be an object"]

    status = result.get("final_status")
    excluded = result.get("excluded_from_efficiency")
    reasons = result.get("exclusion_reasons")
    attempts = result.get("attempts")
    usage_total = result.get("usage_total")
    tokens_to_green = result.get("tokens_to_green")
    correctness = result.get("correctness")

    require(isinstance(attempts, list), "attempts must be an array", errors)
    require(isinstance(reasons, list), "exclusion_reasons must be an array", errors)
    validate_usage(usage_total, "usage_total", errors)

    normalized_total = {field: 0 for field in USAGE_FIELDS}
    assertion_ids: set[str] | None = None
    passed_by_attempt: list[int] = []
    final_hard_failures: list[str] = []
    candidate_thread_id: str | None = None
    if isinstance(attempts, list):
        for index, attempt in enumerate(attempts):
            path = f"attempts[{index}]"
            if not isinstance(attempt, dict):
                errors.append(f"{path} must be an object")
                continue
            usage = attempt.get("usage")
            if usage is None:
                require(
                    status == "INFRA_FAILURE",
                    f"{path}.usage may be null only for INFRA_FAILURE",
                    errors,
                )
            else:
                validate_usage(usage, f"{path}.usage", errors)
                if isinstance(usage, dict) and all(
                    isinstance(usage.get(field), int) for field in USAGE_FIELDS
                ):
                    require(
                        usage["turns"] > 0 or status == "INFRA_FAILURE",
                        f"{path}.usage.turns must be positive outside INFRA_FAILURE",
                        errors,
                    )
                    add_usage(normalized_total, usage)

            require(
                attempt.get("attempt_index") == index,
                f"{path}.attempt_index must equal {index}",
                errors,
            )
            require(
                attempt.get("kind") == ("initial" if index == 0 else "repair"),
                f"{path}.kind does not match its position",
                errors,
            )
            thread_id = attempt.get("thread_id")
            require(
                thread_id is not None or status == "INFRA_FAILURE",
                f"{path}.thread_id may be null only for INFRA_FAILURE",
                errors,
            )
            if isinstance(thread_id, str):
                if candidate_thread_id is None:
                    candidate_thread_id = thread_id
                else:
                    require(
                        thread_id == candidate_thread_id,
                        f"{path}.thread_id must resume the initial candidate thread",
                        errors,
                    )

            assertions = attempt.get("assertions")
            require(isinstance(assertions, list), f"{path}.assertions must be an array", errors)
            current_ids: set[str] = set()
            current_passed = 0
            current_hard_failures: list[str] = []
            if isinstance(assertions, list):
                for assertion_index, assertion in enumerate(assertions):
                    assertion_path = f"{path}.assertions[{assertion_index}]"
                    if not isinstance(assertion, dict):
                        errors.append(f"{assertion_path} must be an object")
                        continue
                    assertion_id = assertion.get("id")
                    require(
                        isinstance(assertion_id, str) and bool(assertion_id),
                        f"{assertion_path}.id must be non-empty",
                        errors,
                    )
                    if isinstance(assertion_id, str):
                        require(
                            assertion_id not in current_ids,
                            f"{assertion_path}.id is duplicated in the attempt",
                            errors,
                        )
                        current_ids.add(assertion_id)
                    assertion_status = assertion.get("status")
                    require(
                        assertion_status in {"PASS", "FAIL", "NOT_RUN"},
                        f"{assertion_path}.status is invalid",
                        errors,
                    )
                    if assertion_status == "PASS":
                        current_passed += 1
                    if assertion.get("hard_gate") is True and assertion_status != "PASS":
                        if isinstance(assertion_id, str):
                            current_hard_failures.append(assertion_id)
            if status != "INFRA_FAILURE":
                require(bool(current_ids), f"{path}.assertions must not be empty", errors)
            if assertion_ids is None:
                assertion_ids = current_ids
            else:
                require(
                    current_ids == assertion_ids,
                    f"{path}.assertions must contain the same frozen IDs as attempt 0",
                    errors,
                )
            passed_by_attempt.append(current_passed)
            final_hard_failures = sorted(current_hard_failures)

    if isinstance(usage_total, dict) and all(
        isinstance(usage_total.get(field), int) for field in USAGE_FIELDS
    ):
        require(
            all(usage_total[field] == normalized_total[field] for field in USAGE_FIELDS),
            "usage_total must equal the sum of normalized non-null attempt usage",
            errors,
        )

    if not isinstance(correctness, dict):
        errors.append("correctness must be an object")
    else:
        total = correctness.get("atomic_assertions_total")
        first = correctness.get("first_passed")
        final = correctness.get("final_passed")
        first_rate = correctness.get("first_pass_rate")
        final_rate = correctness.get("final_pass_rate")
        hard_failures = correctness.get("hard_gate_failures")
        if all(isinstance(value, int) for value in (total, first, final)) and total > 0:
            if assertion_ids is not None:
                require(
                    total == len(assertion_ids),
                    "atomic_assertions_total must equal the frozen assertion-ID count",
                    errors,
                )
            if passed_by_attempt:
                require(
                    first == passed_by_attempt[0],
                    "first_passed must be derived from attempt 0 assertions",
                    errors,
                )
                require(
                    final == passed_by_attempt[-1],
                    "final_passed must be derived from final-attempt assertions",
                    errors,
                )
            require(0 <= first <= total, "first_passed must be within total", errors)
            require(0 <= final <= total, "final_passed must be within total", errors)
            if isinstance(first_rate, (int, float)):
                require(
                    math.isclose(first_rate, first / total, rel_tol=0, abs_tol=1e-12),
                    "first_pass_rate does not equal first_passed / total",
                    errors,
                )
            if isinstance(final_rate, (int, float)):
                require(
                    math.isclose(final_rate, final / total, rel_tol=0, abs_tol=1e-12),
                    "final_pass_rate does not equal final_passed / total",
                    errors,
                )
            if status == "GREEN":
                require(final == total, "GREEN requires all assertions passed", errors)
        require(
            hard_failures == final_hard_failures,
            "hard_gate_failures must equal final non-PASS hard-gate assertion IDs",
            errors,
        )

    if status == "GREEN":
        require(excluded is False, "GREEN must not be excluded", errors)
        require(reasons == [], "GREEN must have no exclusion reasons", errors)
        require(isinstance(attempts, list) and len(attempts) > 0,
                "GREEN requires at least one attempt", errors)
        if isinstance(attempts, list) and attempts:
            final_attempt = attempts[-1]
            if isinstance(final_attempt, dict):
                require(
                    final_attempt.get("agent_exit_code") == 0,
                    "GREEN requires final agent_exit_code 0",
                    errors,
                )
                require(
                    final_attempt.get("public_test_exit_code") == 0,
                    "GREEN requires final public_test_exit_code 0",
                    errors,
                )
                require(
                    final_attempt.get("hidden_test_exit_code") == 0,
                    "GREEN requires final hidden_test_exit_code 0",
                    errors,
                )
        if isinstance(usage_total, dict):
            require(
                usage_total.get("turns", 0) > 0,
                "GREEN usage_total.turns must be positive",
                errors,
            )
            require(
                tokens_to_green == usage_total.get("gross_total_tokens"),
                "GREEN tokens_to_green must equal usage_total.gross_total_tokens",
                errors,
            )
    else:
        require(excluded is True, "non-GREEN result must be excluded", errors)
        require(isinstance(reasons, list) and len(reasons) > 0,
                "non-GREEN result requires an exclusion reason", errors)
        require(tokens_to_green is None,
                "non-GREEN tokens_to_green must be null", errors)
        if status != "INFRA_FAILURE":
            require(isinstance(attempts, list) and len(attempts) > 0,
                    "non-infrastructure result requires an attempt", errors)

    return errors


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("result", type=Path)
    args = parser.parse_args()
    try:
        result = json.loads(args.result.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError) as error:
        print(f"result semantic validation failed: {error}", file=sys.stderr)
        return 2

    errors = validate_result(result)
    if errors:
        for error in errors:
            print(f"result semantic validation failed: {error}", file=sys.stderr)
        return 2
    print("result semantic validation passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
