#!/usr/bin/env python3
"""Extract exact token usage from Codex JSONL after semantics calibration."""

from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path


REQUIRED_USAGE_FIELDS = (
    "input_tokens",
    "cached_input_tokens",
    "output_tokens",
    "reasoning_output_tokens",
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument(
        "events",
        type=Path,
        nargs="+",
        help="one or more ordered Codex --json stdout JSONL files",
    )
    parser.add_argument(
        "--through-turn",
        type=int,
        help="include only the first N completed turns",
    )
    parser.add_argument(
        "--usage-semantics",
        choices=("single", "per-turn", "session-cumulative"),
        default="single",
        help=(
            "meaning of turn.completed usage counters; default 'single' rejects "
            "multi-turn input until an initial+resume probe has calibrated the CLI"
        ),
    )
    return parser.parse_args()


def read_completed_turns(paths: list[Path]) -> list[dict[str, int]]:
    turns: list[dict[str, int]] = []
    for path in paths:
        with path.open("r", encoding="utf-8") as stream:
            for line_number, raw_line in enumerate(stream, start=1):
                line = raw_line.strip()
                if not line:
                    continue
                try:
                    event = json.loads(line)
                except json.JSONDecodeError as error:
                    raise ValueError(
                        f"{path}: line {line_number} is not JSON: {error}"
                    ) from error
                if event.get("type") != "turn.completed":
                    continue

                usage = event.get("usage")
                if not isinstance(usage, dict):
                    raise ValueError(f"{path}: line {line_number} has no usage object")
                missing = [field for field in REQUIRED_USAGE_FIELDS if field not in usage]
                if missing:
                    raise ValueError(
                        f"{path}: line {line_number} usage is missing fields: "
                        f"{', '.join(missing)}"
                    )
                values: dict[str, int] = {}
                for field in REQUIRED_USAGE_FIELDS:
                    value = usage[field]
                    if not isinstance(value, int) or isinstance(value, bool) or value < 0:
                        raise ValueError(
                            f"{path}: line {line_number} usage.{field} must be "
                            "a non-negative integer"
                        )
                    values[field] = value
                if values["cached_input_tokens"] > values["input_tokens"]:
                    raise ValueError(
                        f"{path}: line {line_number} cached input exceeds total input"
                    )
                if values["reasoning_output_tokens"] > values["output_tokens"]:
                    raise ValueError(
                        f"{path}: line {line_number} reasoning output exceeds total output"
                    )
                turns.append(values)
    if not turns:
        raise ValueError("no turn.completed usage events found")
    return turns


def normalize_turns(
    turns: list[dict[str, int]], semantics: str
) -> list[dict[str, int]]:
    if semantics == "single":
        if len(turns) != 1:
            raise ValueError(
                "multiple completed turns require a calibrated "
                "--usage-semantics=per-turn or session-cumulative"
            )
        return turns
    if semantics == "per-turn":
        return turns
    if semantics != "session-cumulative":
        raise ValueError(f"unsupported usage semantics: {semantics}")

    normalized: list[dict[str, int]] = []
    previous = {field: 0 for field in REQUIRED_USAGE_FIELDS}
    for index, current in enumerate(turns, start=1):
        delta: dict[str, int] = {}
        for field in REQUIRED_USAGE_FIELDS:
            if current[field] < previous[field]:
                raise ValueError(
                    f"turn {index} cumulative {field} decreased from "
                    f"{previous[field]} to {current[field]}"
                )
            delta[field] = current[field] - previous[field]
        if delta["cached_input_tokens"] > delta["input_tokens"]:
            raise ValueError(f"turn {index} cached-input delta exceeds input delta")
        if delta["reasoning_output_tokens"] > delta["output_tokens"]:
            raise ValueError(f"turn {index} reasoning-output delta exceeds output delta")
        normalized.append(delta)
        previous = current
    return normalized


def summarize(turns: list[dict[str, int]], semantics: str) -> dict[str, object]:
    turns = normalize_turns(turns, semantics)
    per_turn: list[dict[str, int]] = []
    totals = {field: 0 for field in REQUIRED_USAGE_FIELDS}
    for index, usage in enumerate(turns, start=1):
        for field in REQUIRED_USAGE_FIELDS:
            totals[field] += usage[field]
        per_turn.append(
            {
                "turn": index,
                **usage,
                "uncached_input_tokens": usage["input_tokens"]
                - usage["cached_input_tokens"],
                "visible_output_tokens": usage["output_tokens"]
                - usage["reasoning_output_tokens"],
                "gross_total_tokens": usage["input_tokens"] + usage["output_tokens"],
            }
        )

    return {
        "usage_semantics": semantics,
        "turns": len(turns),
        "input_tokens": totals["input_tokens"],
        "cached_input_tokens": totals["cached_input_tokens"],
        "uncached_input_tokens": totals["input_tokens"]
        - totals["cached_input_tokens"],
        "output_tokens": totals["output_tokens"],
        "reasoning_output_tokens": totals["reasoning_output_tokens"],
        "visible_output_tokens": totals["output_tokens"]
        - totals["reasoning_output_tokens"],
        "gross_total_tokens": totals["input_tokens"] + totals["output_tokens"],
        "per_turn": per_turn,
    }


def main() -> int:
    args = parse_args()
    try:
        turns = read_completed_turns(args.events)
        if args.through_turn is not None:
            if args.through_turn < 1 or args.through_turn > len(turns):
                raise ValueError(
                    f"--through-turn must be between 1 and {len(turns)}"
                )
            turns = turns[: args.through_turn]
        json.dump(
            summarize(turns, args.usage_semantics),
            sys.stdout,
            ensure_ascii=False,
            indent=2,
        )
        sys.stdout.write("\n")
        return 0
    except (OSError, ValueError) as error:
        print(f"usage extraction failed: {error}", file=sys.stderr)
        return 2


if __name__ == "__main__":
    raise SystemExit(main())
