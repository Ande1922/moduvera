#!/usr/bin/env python3
"""Prepare and verify one fail-closed T01 candidate run without invoking a model."""

from __future__ import annotations

import hashlib
import json
import os
import platform
import shlex
import subprocess
import sys
from dataclasses import dataclass
from datetime import datetime, timezone
from pathlib import Path
from typing import Any

from validate_json_schema import validate


PROFILE_NAME = "pilot_candidate"
REQUIRED_ISOLATION_CHECKS = (
    "seed_read_allowed",
    "candidate_java_write_allowed",
    "candidate_xml_write_allowed",
    "frozen_write_denied",
    "repository_read_denied",
    "evaluator_read_denied",
    "memory_read_denied",
    "network_control_available",
    "network_denied",
    "java_26_effective",
    "maven_java_26_effective",
)


@dataclass(frozen=True)
class Check:
    name: str
    status: str
    detail: str

    def as_dict(self) -> dict[str, str]:
        return {"name": self.name, "status": self.status, "detail": self.detail}


def sha256_bytes(value: bytes) -> str:
    return hashlib.sha256(value).hexdigest()


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def tree_digest(root: Path) -> str:
    digest = hashlib.sha256()
    for path in sorted(item for item in root.rglob("*") if item.is_file()):
        relative = path.relative_to(root).as_posix().encode("utf-8")
        content = path.read_bytes()
        digest.update(len(relative).to_bytes(4, "big"))
        digest.update(relative)
        digest.update(len(content).to_bytes(8, "big"))
        digest.update(content)
    return digest.hexdigest()


def canonical_json(value: Any) -> bytes:
    return json.dumps(value, sort_keys=True, separators=(",", ":")).encode("utf-8")


def run(command: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None) -> subprocess.CompletedProcess[str]:
    return subprocess.run(
        command,
        cwd=cwd,
        env=env,
        text=True,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        timeout=60,
        check=False,
    )


def toml_string(value: str) -> str:
    return json.dumps(value)


def build_permission_profile(
    candidate_root: Path,
    jdk_home: Path,
    maven_home: Path,
    maven_repository: Path,
) -> str:
    workspace_rules = {
        ".": "deny",
        ".mvn": "read",
        "README.md": "read",
        "TASK.md": "read",
        "candidate-write-allowlist.txt": "read",
        "ddl": "read",
        "frozen-files.sha256": "read",
        "mvnw": "read",
        "pom.xml": "read",
        "src": "read",
        "src/main/java/io/github/ande1922/moduvera/benchmark/store/candidate": "write",
        "src/main/resources/io/github/ande1922/moduvera/benchmark/store/candidate": "write",
        "target": "write",
        ".git": "deny",
        ".codex": "deny",
    }
    lines = [
        f"default_permissions = {toml_string(PROFILE_NAME)}",
        "",
        f"[permissions.{PROFILE_NAME}]",
        'extends = ":read-only"',
        "",
        f"[permissions.{PROFILE_NAME}.filesystem]",
        '":root" = "deny"',
        '":minimal" = "read"',
        f"{toml_string(str(jdk_home))} = \"read\"",
        f"{toml_string(str(maven_home))} = \"read\"",
        f"{toml_string(str(maven_repository))} = \"read\"",
        "",
        f"[permissions.{PROFILE_NAME}.filesystem.{toml_string(str(candidate_root))}]",
    ]
    lines.extend(f"{toml_string(path)} = {toml_string(access)}" for path, access in workspace_rules.items())
    lines.extend(
        [
            "",
            f"[permissions.{PROFILE_NAME}.network]",
            "enabled = false",
            "",
            "# Runtime workspace root resolved by Codex -C:",
            f"# {candidate_root}",
        ]
    )
    return "\n".join(lines) + "\n"


def permission_config_args(
    profile_text: str,
    *,
    model: str,
    reasoning_effort: str,
    service_tier: str,
    candidate_root: Path,
    jdk_home: Path,
    maven_home: Path,
    maven_repository: Path,
) -> list[str]:
    del profile_text  # The human-readable file and argv are hashed independently.
    filesystem = {
        ":root": "deny",
        ":minimal": "read",
        str(jdk_home): "read",
        str(maven_home): "read",
        str(maven_repository): "read",
        str(candidate_root): {
            ".": "deny",
            ".mvn": "read",
            "README.md": "read",
            "TASK.md": "read",
            "candidate-write-allowlist.txt": "read",
            "ddl": "read",
            "frozen-files.sha256": "read",
            "mvnw": "read",
            "pom.xml": "read",
            "src": "read",
            "src/main/java/io/github/ande1922/moduvera/benchmark/store/candidate": "write",
            "src/main/resources/io/github/ande1922/moduvera/benchmark/store/candidate": "write",
            "target": "write",
            ".git": "deny",
            ".codex": "deny",
        },
    }
    path = f"{jdk_home}/bin:{maven_home}/bin:/usr/bin:/bin:/usr/sbin:/sbin"
    environment = {
        "JAVA_HOME": str(jdk_home),
        "PATH": path,
        "MAVEN_OPTS": f"-Dmaven.repo.local={maven_repository}",
        "TMPDIR": str(candidate_root / "target" / ".tmp"),
        "TZ": "UTC",
        "LANG": "C.UTF-8",
        "LC_ALL": "C.UTF-8",
    }
    disabled_features = (
        "apps",
        "artifact",
        "browser_use",
        "computer_use",
        "goals",
        "hooks",
        "image_generation",
        "memories",
        "multi_agent",
        "plugins",
        "remote_plugin",
        "request_permissions_tool",
        "skill_mcp_dependency_install",
        "tool_suggest",
        "workspace_dependencies",
    )
    values = [
        f"model={toml_string(model)}",
        f"model_reasoning_effort={toml_string(reasoning_effort)}",
        f"service_tier={toml_string(service_tier)}",
        'approval_policy="never"',
        'web_search="disabled"',
        f"default_permissions={toml_string(PROFILE_NAME)}",
        f"permissions.{PROFILE_NAME}.extends=\":read-only\"",
        f"permissions.{PROFILE_NAME}.filesystem={_toml_inline(filesystem)}",
        f"permissions.{PROFILE_NAME}.network.enabled=false",
        'shell_environment_policy.inherit="none"',
        "shell_environment_policy.ignore_default_excludes=false",
        f"shell_environment_policy.set={_toml_inline(environment)}",
    ]
    values.extend(f"features.{feature}=false" for feature in disabled_features)
    argv: list[str] = []
    for value in values:
        argv.extend(("-c", value))
    return argv


def _toml_inline(value: dict[str, Any]) -> str:
    parts = []
    for key, item in value.items():
        rendered_key = toml_string(key)
        rendered_value = _toml_inline(item) if isinstance(item, dict) else toml_string(item)
        parts.append(f"{rendered_key}={rendered_value}")
    return "{" + ",".join(parts) + "}"


def _check_command_version(command: list[str], marker: str, name: str, env: dict[str, str] | None = None) -> Check:
    try:
        completed = run(command, env=env)
    except (OSError, subprocess.TimeoutExpired) as error:
        return Check(name, "FAIL", f"command failed: {error}")
    output = completed.stdout + completed.stderr
    if completed.returncode == 0 and marker in output:
        return Check(name, "PASS", output.splitlines()[0][:240])
    return Check(name, "FAIL", f"exit={completed.returncode}; expected {marker!r}")


def _load_report(path_value: Any) -> tuple[Path | None, dict[str, Any] | None, str | None]:
    if not isinstance(path_value, str) or not path_value:
        return None, None, "report path not configured"
    path = Path(path_value).expanduser().resolve()
    if not path.is_file():
        return path, None, "report file does not exist"
    try:
        return path, json.loads(path.read_text(encoding="utf-8")), None
    except (OSError, json.JSONDecodeError) as error:
        return path, None, f"invalid report: {error}"


def _configured_report_sha256(path_value: Any) -> str | None:
    if not isinstance(path_value, str) or not path_value:
        return None
    path = Path(path_value).expanduser().resolve()
    return sha256_file(path) if path.is_file() else None


def _isolation_check(spec: dict[str, Any], profile_sha256: str, candidate_root: Path) -> Check:
    path, report, error = _load_report(spec.get("isolation", {}).get("calibration_report"))
    if error:
        return Check("permission_profile_calibration", "FAIL", error)
    assert path is not None and report is not None
    errors = []
    if report.get("profile_sha256") != profile_sha256:
        errors.append("profile digest mismatch")
    if report.get("candidate_root") != str(candidate_root):
        errors.append("candidate root mismatch")
    if report.get("host_context") != "outside-codex-sandbox":
        errors.append("calibration was not executed outside the nested Codex sandbox")
    checks = report.get("checks", {})
    for name in REQUIRED_ISOLATION_CHECKS:
        if checks.get(name) is not True:
            errors.append(f"{name} is not true")
    return Check(
        "permission_profile_calibration",
        "FAIL" if errors else "PASS",
        "; ".join(errors) if errors else f"verified {path.name}",
    )


def _provenance_check(spec: dict[str, Any], codex_version: str) -> tuple[Check, dict[str, Any] | None]:
    path, report, error = _load_report(spec.get("agent", {}).get("effective_provenance_report"))
    if error:
        return Check("effective_agent_provenance", "FAIL", error), None
    assert path is not None and report is not None
    expected = spec["agent"]
    errors = []
    for key, expected_key in (
        ("model_effective", "model"),
        ("reasoning_effort_effective", "reasoning_effort"),
        ("service_tier_effective", "service_tier"),
    ):
        if report.get(key) != expected.get(expected_key):
            errors.append(f"{key} mismatch")
    if report.get("codex_cli_version") != codex_version:
        errors.append("Codex version mismatch")
    return (
        Check(
            "effective_agent_provenance",
            "FAIL" if errors else "PASS",
            "; ".join(errors) if errors else f"verified {path.name}",
        ),
        report,
    )


def _repository_ref(repository_root: Path) -> str:
    completed = run(["git", "rev-parse", "HEAD"], cwd=repository_root)
    if completed.returncode == 0 and completed.stdout.strip():
        return completed.stdout.strip()
    return "uncommitted-no-head"


def prepare_run(spec_path: Path, run_root: Path) -> dict[str, Any]:
    spec = json.loads(spec_path.read_text(encoding="utf-8"))
    script_root = Path(__file__).resolve().parent
    benchmark_root = script_root.parent
    repository_root = benchmark_root.parent.parent
    t01_root = benchmark_root / "pilot" / "t01"
    manifest_schema = json.loads((benchmark_root / "schemas" / "run-manifest.schema.json").read_text(encoding="utf-8"))

    run_root = run_root.expanduser().resolve()
    if run_root.exists():
        raise ValueError(f"run root already exists: {run_root}")
    run_root.mkdir(parents=True)
    candidate_root = run_root / "candidate-workspace"
    control_root = run_root / "control"
    control_root.mkdir()
    prompt_path = control_root / "prompt.md"

    constraint = spec["constraint"]
    materializer = t01_root / "scripts" / "materialize_t01_seed.py"
    materialized = run(
        [
            sys.executable,
            str(materializer),
            "--output",
            str(candidate_root),
            "--prompt-output",
            str(prompt_path),
            "--constraint",
            constraint,
        ],
        cwd=repository_root,
    )
    if materialized.returncode != 0:
        raise RuntimeError(f"materialization failed: {materialized.stderr.strip()}")
    materialization = json.loads(materialized.stdout)
    (candidate_root / "src" / "main" / "java" / "io" / "github" / "ande1922" / "moduvera" / "benchmark" / "store" / "candidate").mkdir(parents=True)
    (candidate_root / "src" / "main" / "resources" / "io" / "github" / "ande1922" / "moduvera" / "benchmark" / "store" / "candidate").mkdir(parents=True)
    (candidate_root / "target" / ".tmp").mkdir(parents=True)

    toolchain = spec["toolchain"]
    jdk_home = Path(toolchain["jdk_home"]).expanduser().resolve()
    maven_home = Path(toolchain["maven_home"]).expanduser().resolve()
    maven_repository = Path(toolchain["maven_repository"]).expanduser().resolve()
    profile_text = build_permission_profile(candidate_root, jdk_home, maven_home, maven_repository)
    profile_path = control_root / "permission-profile.toml"
    profile_path.write_text(profile_text, encoding="utf-8")
    profile_sha256 = sha256_file(profile_path)

    agent = spec["agent"]
    config_args = permission_config_args(
        profile_text,
        model=agent["model"],
        reasoning_effort=agent["reasoning_effort"],
        service_tier=agent["service_tier"],
        candidate_root=candidate_root,
        jdk_home=jdk_home,
        maven_home=maven_home,
        maven_repository=maven_repository,
    )
    common = ["--strict-config", "--ignore-user-config", "--ignore-rules", "--skip-git-repo-check", *config_args, "--json"]
    initial_argv = ["codex", "exec", *common, "-C", str(candidate_root), "-"]
    resume_argv = ["codex", "exec", "resume", *common, "<EXACT_SESSION_ID>", "-"]
    argv_path = control_root / "commands.json"
    argv_path.write_text(
        json.dumps(
            {"initial": initial_argv, "resume": resume_argv, "resume_cwd": str(candidate_root)},
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
    )

    checks: list[Check] = []
    checks.append(Check("candidate_outside_repository", "PASS" if repository_root not in candidate_root.parents else "FAIL", str(candidate_root)))
    checks.append(Check("seed_materialized", "PASS", materialization["seed_tree_sha256"]))
    codex = _check_command_version(["codex", "--version"], "codex-cli", "codex_cli")
    checks.append(codex)
    codex_version = codex.detail.removeprefix("codex-cli ") if codex.status == "PASS" else "unknown"
    checks.append(_check_command_version([str(jdk_home / "bin" / "java"), "-version"], 'version "26', "jdk_26"))
    maven_env = {
        "JAVA_HOME": str(jdk_home),
        "PATH": f"{jdk_home}/bin:{maven_home}/bin:/usr/bin:/bin:/usr/sbin:/sbin",
        "MAVEN_OPTS": f"-Dmaven.repo.local={maven_repository}",
    }
    checks.append(_check_command_version([str(maven_home / "bin" / "mvn"), "-version"], "Java version: 26", "maven_uses_jdk_26", maven_env))
    checks.append(Check("maven_repository", "PASS" if maven_repository.is_dir() else "FAIL", str(maven_repository)))
    login = run(["codex", "login", "status"])
    login_lines = [line.strip() for line in (login.stdout + login.stderr).splitlines()]
    login_status = next((line for line in login_lines if line == "Logged in using ChatGPT"), "")
    checks.append(Check("chatgpt_login", "PASS" if login.returncode == 0 and login_status == "Logged in using ChatGPT" else "FAIL", login_status[:240] or f"exit={login.returncode}"))
    doctor = run(["codex", "doctor", "--json", *config_args])
    try:
        doctor_report = json.loads(doctor.stdout)
        config_load = doctor_report.get("checks", {}).get("config.load", {})
        config_ok = config_load.get("status") == "ok"
        config_detail = config_load.get("summary", "config.load missing")
    except json.JSONDecodeError:
        config_ok = False
        config_detail = "codex doctor did not return JSON"
    checks.append(Check("codex_config_overrides_parse", "PASS" if config_ok else "FAIL", config_detail))
    checks.append(_isolation_check(spec, profile_sha256, candidate_root))
    provenance_check, provenance = _provenance_check(spec, codex_version)
    checks.append(provenance_check)
    database_digest = spec.get("database", {}).get("image_digest")
    checks.append(Check("database_image_digest", "PASS" if isinstance(database_digest, str) and len(database_digest) == 64 and set(database_digest) <= set("0123456789abcdef") else "FAIL", str(database_digest)))
    h10_source = t01_root / "evaluator" / "src" / "test" / "java" / "io" / "github" / "ande1922" / "moduvera" / "benchmark" / "store" / "hiddentest" / "T01HiddenAcceptanceIT.java"
    h10_present = "t01H10" in h10_source.read_text(encoding="utf-8")
    checks.append(Check("t01_19_assertion_sources", "PASS" if h10_present else "FAIL", "H10 present" if h10_present else "T01-H10 source missing"))
    runtime_trace_sources = (
        t01_root / "evaluator" / "src" / "test" / "java" / "io" / "github" / "ande1922" / "moduvera" / "benchmark" / "store" / "hiddentest" / "RecordingDataSource.java",
        t01_root / "evaluator" / "src" / "test" / "java" / "io" / "github" / "ande1922" / "moduvera" / "benchmark" / "store" / "hiddentest" / "MappedStatementTraceInterceptor.java",
        t01_root / "evaluator" / "src" / "test" / "java" / "io" / "github" / "ande1922" / "moduvera" / "benchmark" / "store" / "hiddentest" / "T01MappingStructureIT.java",
    )
    missing_trace_sources = [path.name for path in runtime_trace_sources if not path.is_file()]
    checks.append(
        Check(
            "t01_runtime_mapping_trace",
            "PASS" if not missing_trace_sources else "FAIL",
            "runtime SQL and MappedStatement trace sources present"
            if not missing_trace_sources
            else "missing " + ", ".join(missing_trace_sources),
        )
    )

    preflight_status = "READY" if all(check.status == "PASS" for check in checks) else "BLOCKED"
    created_at = datetime.now(timezone.utc).isoformat().replace("+00:00", "Z")
    instruction_hash = sha256_bytes(canonical_json(provenance or {"status": "unproven"}))
    tools_hash = sha256_bytes(canonical_json(config_args))
    skills_hash = sha256_bytes(b"ignore-user-config;memories=false;plugins=false")
    evaluator_hash = tree_digest(t01_root / "evaluator")
    token_calibration = spec["token_calibration"]
    relative_prefix = spec["artifact_prefix"].rstrip("/")
    canonical_variant = spec["canonical_variant"]
    manifest = {
        "schema_version": "1.0",
        "protocol_version": spec["protocol_version"],
        "preflight_status": preflight_status,
        "experiment_id": spec["experiment_id"],
        "run_id": spec["run_id"],
        "pair_id": spec["pair_id"],
        "task_id": "T01",
        "run_kind": "initial",
        "variant_label": spec["variant_label"],
        "canonical_variant": canonical_variant,
        "repeat_index": spec["repeat_index"],
        "randomization": spec["randomization"],
        "seed": {
            "kind": "neutral",
            "repository_ref": spec.get("repository_ref") or _repository_ref(repository_root),
            "source_sha256": materialization["seed_tree_sha256"],
            "candidate_visible_sha256": materialization["seed_tree_sha256"],
            "evaluator_sha256": evaluator_hash,
        },
        "prompt": {"path": f"{relative_prefix}/control/prompt.md", "sha256": sha256_file(prompt_path)},
        "agent": {
            "codex_cli_version": codex_version,
            "model_requested": agent["model"],
            "model_effective": provenance.get("model_effective") if provenance else None,
            "reasoning_effort_requested": agent["reasoning_effort"],
            "reasoning_effort_effective": provenance.get("reasoning_effort_effective") if provenance else None,
            "service_tier": agent["service_tier"],
            "instructions_sha256": instruction_hash,
            "skills_sha256": skills_hash,
            "tools_sha256": tools_hash,
        },
        "billing": {
            "authentication_mode": "chatgpt",
            "access_route": "chatgpt-plan",
            "api_key_fallback_allowed": False,
            "login_status": "Logged in using ChatGPT",
        },
        "token_calibration": token_calibration,
        "execution": {
            "command_argv": initial_argv,
            "resume_command_argv": resume_argv,
            "resume_cwd": str(candidate_root),
            "sandbox": "permission-profile",
            "permission_profile": {
                "name": PROFILE_NAME,
                "sha256": profile_sha256,
                "default_deny_root": True,
                "command_network": "denied",
                "calibration_report_sha256": _configured_report_sha256(
                    spec.get("isolation", {}).get("calibration_report")
                ),
            },
            "approval_policy": "never",
            "network_policy": f"permission-profile:{PROFILE_NAME};command-network=denied;hosted-tools=disabled",
            "max_repair_turns": spec["execution"]["max_repair_turns"],
            "fresh_session": True,
            "candidate_timeout_seconds": spec["execution"]["candidate_timeout_seconds"],
        },
        "environment": {
            "os": platform.system(),
            "architecture": platform.machine(),
            "jdk": "26",
            "maven": spec["toolchain"]["maven_version"],
            "adapter": "mybatis-plus",
            "database": "mysql",
            "database_image": spec["database"]["image"],
            "database_image_digest": database_digest,
            "dependency_cache": "preheated",
        },
        "artifacts": {
            "run_root": relative_prefix,
            "events_jsonl": f"{relative_prefix}/agent/events.jsonl",
            "result_json": f"{relative_prefix}/result.json",
            "candidate_patch": f"{relative_prefix}/source/candidate.patch",
        },
        "registered_differences": [
            "Variant constraint only: "
            + ("S uses a second typed persistence object and explicit object Mapper" if canonical_variant == "S" else "U maps the aggregate directly in Infrastructure without a second persistence object")
        ],
        "hard_stops": [
            "preflight or permission-profile calibration is not READY",
            "frozen seed, prompt, evaluator, environment or manifest digest drifts",
            "any of the 19 frozen T01 assertions is non-PASS",
            "runtime SQL, bound parameters and MappedStatement/result-type use are not proven",
            "two repair turns complete without green",
            "candidate session, exact resume ID or usage semantics becomes indeterminate",
        ],
        "created_at": created_at,
    }
    manifest_errors = validate(manifest, manifest_schema)
    schema_check = Check("manifest_schema", "PASS" if not manifest_errors else "FAIL", "; ".join(manifest_errors[:5]) or "Draft 2020-12 subset validation passed")
    checks.append(schema_check)
    if schema_check.status == "FAIL" and manifest["preflight_status"] == "READY":
        manifest["preflight_status"] = "BLOCKED"

    # A preflight can legitimately be blocked by calibration inputs that are
    # produced only after materialization. Keep it visibly mutable; the future
    # finalize transition is the only operation allowed to create manifest.json.
    manifest_path = run_root / "manifest.preview.json"
    manifest_path.write_text(json.dumps(manifest, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    readiness = {
        "schema_version": "0.1",
        "state": "PREFLIGHT_READY" if manifest["preflight_status"] == "READY" else "PREFLIGHT_BLOCKED",
        "scored_run_started": False,
        "model_invoked": False,
        "run_root": str(run_root),
        "candidate_root": str(candidate_root),
        "manifest_preview_sha256": sha256_file(manifest_path),
        "permission_profile_sha256": profile_sha256,
        "commands_sha256": sha256_file(argv_path),
        "checks": [check.as_dict() for check in checks],
        "blocking_checks": [check.name for check in checks if check.status != "PASS"],
        "created_at": created_at,
    }
    readiness_path = run_root / "readiness.json"
    readiness_path.write_text(json.dumps(readiness, indent=2, sort_keys=True) + "\n", encoding="utf-8")
    return readiness


def verify_prepared_run(run_root: Path) -> dict[str, Any]:
    run_root = run_root.expanduser().resolve()
    readiness_path = run_root / "readiness.json"
    manifest_path = run_root / "manifest.preview.json"
    if not readiness_path.is_file() or not manifest_path.is_file():
        return {"status": "FAIL", "errors": ["missing readiness.json or manifest.preview.json"]}
    readiness = json.loads(readiness_path.read_text(encoding="utf-8"))
    errors = []
    actual_manifest = sha256_file(manifest_path)
    if readiness.get("manifest_preview_sha256") != actual_manifest:
        errors.append("manifest preview digest drift")
    profile_path = run_root / "control" / "permission-profile.toml"
    if not profile_path.is_file() or readiness.get("permission_profile_sha256") != sha256_file(profile_path):
        errors.append("permission profile digest drift")
    commands_path = run_root / "control" / "commands.json"
    if not commands_path.is_file() or readiness.get("commands_sha256") != sha256_file(commands_path):
        errors.append("command argv digest drift")
    candidate_root = run_root / "candidate-workspace"
    if not candidate_root.is_dir():
        errors.append("candidate workspace missing")
    result = {"status": "PASS" if not errors else "FAIL", "errors": errors, "state": readiness.get("state")}
    return result


def display_argv(argv: list[str]) -> str:
    return " ".join(shlex.quote(item) for item in argv)
