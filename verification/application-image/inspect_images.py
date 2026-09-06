#!/usr/bin/env python3
"""Inspect locally built Runnable App images against the shared contract."""

from __future__ import annotations

import json
from io import BytesIO
import os
from pathlib import Path
import subprocess

from image_policy import ImagePolicyError, scan_tar_stream


SCRIPT_DIR = Path(__file__).resolve().parent
IMAGE_REPOSITORY = os.environ.get("MODUVERA_IMAGE_REPOSITORY", "moduvera-local")
IMAGE_TAG = os.environ.get("MODUVERA_IMAGE_TAG", "verification")


def apps() -> list[tuple[str, int]]:
    result: list[tuple[str, int]] = []
    for line in (SCRIPT_DIR / "apps.tsv").read_text(encoding="utf-8").splitlines():
        if not line or line.startswith("#"):
            continue
        app, port = line.split("\t")
        if app == "app-monolith" and os.environ.get("MODUVERA_IMAGE_INCLUDE_MONOLITH") != "1":
            continue
        result.append((app, int(port)))
    return result


def run(*command: str) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        check=True,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def run_bytes(*command: str) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(
        command,
        check=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
    )


def fail(message: str) -> None:
    raise SystemExit(message)


def main() -> None:
    app_contracts = apps()
    images = [f"{IMAGE_REPOSITORY}/{app}:{IMAGE_TAG}" for app, _ in app_contracts]
    inspected = json.loads(run("docker", "image", "inspect", *images).stdout)
    if len(inspected) != len(app_contracts):
        fail("Docker did not return one image description per Runnable App")

    loader_layers: set[str] = set()
    for (app, port), image, details in zip(app_contracts, images, inspected, strict=True):
        config = details["Config"]
        if config.get("User") != "10001:10001":
            fail(f"{image} does not run as the fixed non-root identity")
        if config.get("WorkingDir") != "/opt/moduvera":
            fail(f"{image} has an unexpected working directory")
        if config.get("Entrypoint") != [
            "java",
            "org.springframework.boot.loader.launch.JarLauncher",
        ]:
            fail(f"{image} has an unexpected entrypoint")
        exposed = set((config.get("ExposedPorts") or {}).keys())
        if exposed != {f"{port}/tcp"}:
            fail(f"{image} exposes {sorted(exposed)}, expected only {port}/tcp")
        environment = "\n".join(config.get("Env") or [])
        if "jdwp" in environment.lower() or "server_port=" in environment.lower():
            fail(f"{image} bakes a debug or server port into its environment")

        layers = details["RootFS"]["Layers"]
        if len(layers) < 4:
            fail(f"{image} does not contain the four extracted application layers")
        loader_layers.add(layers[-3])

        java_version = run(
            "docker", "run", "--rm", "--entrypoint", "java", image, "-version"
        ).stderr
        if 'version "26.' not in java_version:
            fail(f"{image} does not provide a Java 26 runtime")
        run(
            "docker",
            "run",
            "--rm",
            "--entrypoint",
            "sh",
            image,
            "-ec",
            r"""
            test ! -d /workspace
            test -z "$(find /opt/moduvera -type f \
                \( -name '*.java' -o -name '*.kt' -o -name '*Test.class' \
                   -o -name '*IT.class' -o -name 'settings.xml' \) \
                -print -quit)"
            test -z "$(find /opt/moduvera -type f \
                \( -name 'opentelemetry-javaagent*.jar' -o -name '*governed*extension*.jar' \) \
                -print -quit)"
            test -z "$(find /opt/moduvera -type d \
                \( -name src -o -name .git -o -name .m2 -o -name test-classes \) \
                -print -quit)"
            test -z "$(find /opt/moduvera \( ! -uid 0 -o ! -gid 0 \) -print -quit)"
            test -z "$(find /opt/moduvera -perm /022 -print -quit)"
            test ! -w /opt/moduvera
            ! touch /opt/moduvera/.runtime-write-probe 2>/dev/null
            """,
        )
        filesystem = run_bytes(
            "docker",
            "run",
            "--rm",
            "--entrypoint",
            "tar",
            image,
            "-C",
            "/opt/moduvera",
            "-cf",
            "-",
            ".",
        ).stdout
        try:
            scan_tar_stream(BytesIO(filesystem))
        except ImagePolicyError as error:
            fail(f"{image} failed the sensitive-content policy: {error}")
        print(f"Image contract PASS: {app} ({image})")

    if len(loader_layers) != 1:
        fail("Runnable App images do not reuse one Spring Boot loader layer")
    print(f"Shared Spring Boot loader layer: {loader_layers.pop()}")


if __name__ == "__main__":
    main()
