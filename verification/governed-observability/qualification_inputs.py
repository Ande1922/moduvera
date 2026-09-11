#!/usr/bin/env python3
"""Bind topology evidence to checkout sources and the actual packaged applications."""
from __future__ import annotations

import hashlib
import json
from pathlib import Path
import subprocess
import sys


def digest(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def inputs(root: Path) -> dict:
    def git(*arguments: str) -> str:
        return subprocess.check_output(["git", *arguments], cwd=root, text=True).strip()

    paths = subprocess.check_output(
        ["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=root
    ).decode().split("\0")
    sources = {name: digest(root / name) for name in sorted(set(paths)) if name and (root / name).is_file()}
    artifacts = {}
    assemblies = sorted(path for path in (root / "apps").iterdir() if (path / "pom.xml").is_file())
    for module in [*assemblies, root / "verification/governed-observability/agent-extension"]:
        jars = [path for path in (module / "target").glob("*.jar")
                if not path.name.endswith(("-sources.jar", "-javadoc.jar", "-tests.jar"))]
        if len(jars) != 1:
            raise AssertionError(f"expected one packaged artifact: {module.name}")
        artifacts[str(jars[0].relative_to(root))] = digest(jars[0])
    return {"checkout": str(root.resolve()), "head": git("rev-parse", "HEAD"),
            "branch": git("branch", "--show-current"), "status": git("status", "--porcelain=v1"),
            "sources": sources, "artifacts": artifacts}


def main() -> None:
    operation, repository, target = sys.argv[1:]
    current = inputs(Path(repository))
    path = Path(target)
    if operation == "capture":
        path.write_text(json.dumps(current, sort_keys=True, indent=2) + "\n")
    elif operation == "check":
        if json.loads(path.read_text()) != current:
            raise AssertionError("qualification source or packaged runtime inputs changed")
    else:
        raise ValueError("expected capture or check")
    print(f"Topology input {operation}: PASS ({len(current['sources'])} sources; {len(current['artifacts'])} artifacts)")


if __name__ == "__main__":
    main()
