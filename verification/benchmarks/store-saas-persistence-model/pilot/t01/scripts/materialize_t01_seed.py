#!/usr/bin/env python3
"""Materialize one byte-identical T01 candidate seed and one external prompt."""

from __future__ import annotations

import argparse
import hashlib
import json
import shutil
import sys
from pathlib import Path


def sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tree_files(root: Path) -> list[Path]:
    return sorted(
        path
        for path in root.rglob("*")
        if path.is_file() and "target" not in path.relative_to(root).parts
    )


def tree_digest(root: Path) -> str:
    digest = hashlib.sha256()
    for path in tree_files(root):
        relative = path.relative_to(root).as_posix().encode("utf-8")
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        content = path.read_bytes()
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--prompt-output", type=Path, required=True)
    parser.add_argument("--constraint", choices=("separated", "unified"), required=True)
    args = parser.parse_args()

    script = Path(__file__).resolve()
    t01_root = script.parent.parent
    benchmark_root = t01_root.parent.parent
    repository_root = benchmark_root.parent.parent.parent
    template = t01_root / "seed-template"
    task = benchmark_root / "tasks" / "T01-store-lifecycle.md"
    ddl = benchmark_root / "ddl" / "mysql-v1-baseline.sql"
    base_prompt = t01_root / "prompts" / "base.md"
    constraint_prompt = t01_root / "prompts" / f"{args.constraint}-constraint.md"

    if args.output.exists():
        print(f"output already exists: {args.output}", file=sys.stderr)
        return 2
    if args.prompt_output.exists():
        print(f"prompt output already exists: {args.prompt_output}", file=sys.stderr)
        return 2

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.prompt_output.parent.mkdir(parents=True, exist_ok=True)
    shutil.copytree(template, args.output, ignore=shutil.ignore_patterns("target", ".DS_Store"))
    shutil.copy2(repository_root / "mvnw", args.output / "mvnw")
    shutil.copytree(repository_root / ".mvn" / "wrapper", args.output / ".mvn" / "wrapper")
    shutil.copy2(task, args.output / "TASK.md")
    (args.output / "ddl").mkdir()
    shutil.copy2(ddl, args.output / "ddl" / ddl.name)
    resource_ddl = args.output / "src" / "test" / "resources" / "ddl"
    resource_ddl.mkdir(parents=True)
    shutil.copy2(ddl, resource_ddl / ddl.name)

    allowlist = args.output / "candidate-write-allowlist.txt"
    allowlist.write_text(
        "src/main/java/io/github/ande1922/moduvera/benchmark/store/candidate/**\n"
        "src/main/resources/io/github/ande1922/moduvera/benchmark/store/candidate/**\n",
        encoding="utf-8",
    )

    frozen = args.output / "frozen-files.sha256"
    manifest_lines = []
    for path in tree_files(args.output):
        if path == frozen:
            continue
        manifest_lines.append(f"{sha256(path)}  {path.relative_to(args.output).as_posix()}")
    frozen.write_text("\n".join(manifest_lines) + "\n", encoding="utf-8")

    prompt = base_prompt.read_text(encoding="utf-8").rstrip() + "\n\n"
    prompt += constraint_prompt.read_text(encoding="utf-8").rstrip() + "\n"
    args.prompt_output.write_text(prompt, encoding="utf-8")

    summary = {
        "seed_tree_sha256": tree_digest(args.output),
        "frozen_manifest_sha256": sha256(frozen),
        "candidate_prompt_sha256": sha256(args.prompt_output),
        "constraint": args.constraint,
    }
    print(json.dumps(summary, sort_keys=True))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
