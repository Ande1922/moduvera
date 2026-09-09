#!/usr/bin/env python3
"""Validate actual Agent exports and ECS stdout from the controlled task fixture."""
import json
from pathlib import Path
import sys

from analyze_logging_fixture import read_json_lines


def flatten(record: dict, prefix: str = "") -> dict:
    fields = {}
    for key, value in record.items():
        name = prefix + key
        if isinstance(value, dict):
            fields.update(flatten(value, name + "."))
        else:
            fields[name] = value
    return fields


def validate(directory: Path) -> dict:
    logs = [flatten(record) for record in read_json_lines(directory / "task-fixture.stdout.jsonl")]
    spans = [json.loads(line) for line in (directory / "task-spans.jsonl").read_text().splitlines()]
    status = json.loads((directory / "task-receiver-status.json").read_text())
    tasks = [span for span in spans if span["scope"]["name"] == "io.github.ande1922.moduvera.tasks"]
    canonical = [log for log in logs if log.get("log.logger") == "task.execute"]
    expected = {"queued-1", "queued-2", "queued-3", "queued-absent", "nested", "virtual", "caller-runs", "running-cancel", "failed", "fatal"}
    assert len(tasks) == len(expected) and len(canonical) == len(expected) + 1, (len(tasks), len(canonical))
    assert {span["name"] for span in tasks} == expected
    assert {log["task_name"] for log in canonical} == expected | {"unsampled"}
    fixture_parents = {
        "parent-queued-1", "parent-queued-2", "parent-queued-3",
        "parent-worker-queued-1", "parent-worker-queued-2", "parent-worker-queued-3",
        "parent-worker-queued-absent", "parent-callback", "parent-worker-callback",
        "parent-thread-modes", "parent-worker-virtual", "parent-cancel",
    }
    assert len(spans) == len(expected | fixture_parents)
    assert {span["name"] for span in spans} == expected | fixture_parents
    by_name = {span["name"]: span for span in spans}
    by_task = {log["task_name"]: log for log in canonical}
    unsampled = by_task["unsampled"]
    assert unsampled["trace_id"] == "33333333333333333333333333333333"
    assert unsampled["span_id"] not in {"4444444444444444", "0000000000000000"}
    assert unsampled["log.level"] == "INFO" and unsampled["event.outcome"] == "success"
    assert not any(span["traceId"] == unsampled["trace_id"] for span in spans)
    failures = {"running-cancel", "failed", "fatal"}
    for task in tasks:
        log = by_task[task["name"]]
        assert log["log.level"] == "INFO", log
        assert log["event.outcome"] == ("failure" if task["name"] in failures else "success"), log
        assert log["trace_id"] == task["traceId"] and log["span_id"] == task["spanId"], log
        assert isinstance(log["duration_ms"], (int, float)) and log["duration_ms"] >= 0, log
        assert "error.stack_trace" not in log and "error.message" not in log, log
        span_duration_ms = (task["endUnixNano"] - task["startUnixNano"]) / 1_000_000
        assert 0 <= log["duration_ms"] <= span_duration_ms + 0.001, (log, task)
        if task["name"] in failures:
            assert task["status"]["code"] == 2, task
    for name in ("queued-1", "queued-2", "queued-3"):
        task, parent = by_name[name], by_name["parent-" + name]
        assert task["parentSpanId"] == parent["spanId"] and task["traceId"] == parent["traceId"]
        assert task["startUnixNano"] > parent["endUnixNano"], "parent ended before actual queued execution"
    nested, outer = by_name["nested"], by_name["queued-1"]
    assert nested["parentSpanId"] == outer["spanId"] and nested["traceId"] == outer["traceId"]
    for name, parent_name in (("virtual", "thread-modes"), ("caller-runs", "thread-modes"), ("running-cancel", "cancel")):
        assert by_name[name]["parentSpanId"] == by_name["parent-" + parent_name]["spanId"]
    assert "parentSpanId" not in by_name["queued-absent"], by_name["queued-absent"]
    observed = [log for log in logs if "fixture_case" in log]
    for log in observed:
        if log["fixture_case"] == "callback" and log["fixture_phase"] in {"inline", "external"}:
            assert log["span_id"] == by_name["parent-callback"]["spanId"], log
        if log["fixture_phase"] == "worker-restored":
            assert log["actor_id"] == "worker" and log["correlation_id"] == "correlation-worker", log
            assert log["span_id"] == by_name["parent-worker-" + log["fixture_case"]]["spanId"], log
    assert len([log for log in observed if log["fixture_phase"] == "worker-restored"]) == 6
    assert any(log.get("fixture_virtual") is True and log.get("fixture_case") == "virtual" for log in logs)
    assert {log["fixture_phase"] for log in observed if log["fixture_case"] == "callback"} >= {"inline", "external"}
    release = next(i for i, log in enumerate(logs) if log.get("fixture_phase") == "queue-release")
    assert not any(log.get("log.logger") == "task.execute" for log in logs[:release])
    notified = next(i for i, log in enumerate(logs) if log.get("fixture_phase") == "cancellation-notified")
    finished = next(i for i, log in enumerate(logs) if log.get("task_name") == "running-cancel")
    before_exit = next(i for i, log in enumerate(logs) if log.get("fixture_phase") == "before-exit")
    assert notified < before_exit < finished
    assert sum(log.get("log.level") == "WARN" for log in logs) == 1
    errors = [log for log in logs if log.get("log.level") == "ERROR"]
    assert len(errors) == 1 and errors[0]["error.code"] == "SYS_UNEXPECTED"
    assert "error.stack_trace" in errors[0]
    assert any(log.get("fixture_phase") == "complete" for log in logs)
    assert status["postRequestsBySignal"]["logs"] == 0
    assert status["postRequestsBySignal"]["metrics"] == 0
    return {"result": "PASS", "task_spans": len(tasks), "canonical_info": len(canonical), "all_spans": len(spans), "worker_restorations": 6, "unsampled_task_logs": 1, "callback_spans": 0, "never_started_spans": 0}


if __name__ == "__main__":
    result = validate(Path(sys.argv[1]))
    (Path(sys.argv[1]) / "task-analysis.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, sort_keys=True))
