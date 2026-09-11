from __future__ import annotations

import copy
import importlib.util
import unittest
from pathlib import Path

SPEC = importlib.util.spec_from_file_location("probe", Path(__file__).resolve().parents[1] / "probe.py")
PROBE = importlib.util.module_from_spec(SPEC)
assert SPEC.loader is not None
SPEC.loader.exec_module(PROBE)


class DurableEvidenceTest(unittest.TestCase):
    def fixture(self):
        trace = "1" * 32
        ids = [f"{number:016x}" for number in range(1, 7)]
        names = ["http", "outbox.append", "outbox.publish", "send", "receive", "mq.process"]
        spans = [{"traceId": trace, "spanId": identity, "parentSpanId": ids[index - 1] if index else "",
                  "name": names[index], "kind": ["SERVER", "INTERNAL", "INTERNAL", "PRODUCER", "CONSUMER", "INTERNAL"][index],
                  "resource": {"service.name": "order" if index < 4 else "inventory"},
                  "scope": {"name": "io.github.ande1922.moduvera.messaging" if index in (1, 2, 5) else "io.opentelemetry.fixture"},
                  "attributes": {"messaging.message.id": "m1"}, "links": []}
                 for index, identity in enumerate(ids)]
        creation = {"traceId": trace, "spanId": ids[1]}
        spans[2]["links"] = [creation]
        spans[5]["links"] = [creation]
        spans[5]["attributes"] = {}  # Actual mq.process has no message-id attribute.
        spans[2]["attributes"].update({"outbox.transport.result": "success", "outbox.write.result": "published"})
        parent = f"00-{trace}-{ids[1]}-01"
        row = {"message_id": "m1", "correlation_id": "c1", "tenant_id": "tenant-a", "actor_type": "SERVICE",
               "actor_subject": "order-service", "initiator_type": "USER", "initiator_subject": "alice",
               "partition_key": "key1", "creation_traceparent": parent, "creation_tracestate": None,
               "publication_traceparent": parent, "publication_tracestate": None, "publication_generation": 0,
               "status": "PUBLISHED", "attempt_count": 0}
        envelope = {"id": "m1", "correlationid": "c1", "tenantid": "tenant-a", "actortype": "SERVICE",
                    "actorsubject": "order-service", "initiatortype": "USER", "initiatorsubject": "alice",
                    "partitionkey": "key1", "traceparent": parent}
        record = {"key": "key1", "traceparent": f"00-{trace}-{ids[3]}-01", "envelope": envelope}
        return spans, record, row

    def check(self, spans, record, row):
        return PROBE.durable_chain(spans, record, row, spans[0], "order", "inventory", "c1")

    def test_accepts_exact_persisted_creation_publication_transport_and_processing_chain(self):
        self.assertEqual(5, len(self.check(*self.fixture())))

    def test_same_trace_does_not_hide_a_broken_parent_edge(self):
        for index in range(1, 6):
            with self.subTest(edge=index):
                spans, record, row = self.fixture()
                spans[index]["parentSpanId"] = "ffffffffffffffff"
                with self.assertRaises(AssertionError):
                    self.check(spans, record, row)

    def test_rejects_missing_creation_links_and_duplicate_processes(self):
        for index in (2, 5):
            spans, record, row = self.fixture()
            spans[index]["links"] = []
            with self.assertRaises(AssertionError):
                self.check(spans, record, row)
        spans, record, row = self.fixture()
        duplicate = copy.deepcopy(spans[5])
        duplicate["spanId"] = "ffffffffffffffff"
        with self.assertRaises(AssertionError):
            self.check([*spans, duplicate], record, row)

    def test_rejects_forged_wire_identity_replaced_generation_and_creation_as_transport(self):
        for field, value in (("tenantid", "tenant-b"), ("actorsubject", "other-service"), ("traceparent", "invalid")):
            spans, record, row = self.fixture()
            record["envelope"][field] = value
            with self.assertRaises(AssertionError):
                self.check(spans, record, row)
        spans, record, row = self.fixture()
        row["publication_generation"] = 1
        with self.assertRaises(AssertionError):
            self.check(spans, record, row)
        spans, record, row = self.fixture()
        record["traceparent"] = row["creation_traceparent"]
        with self.assertRaises(AssertionError):
            self.check(spans, record, row)

    def test_duplicate_json_keys_cannot_hide_a_sensitive_or_identity_field(self):
        with self.assertRaises(AssertionError):
            PROBE.json.loads('{"actor_id":"wrong","actor_id":"alice"}', object_pairs_hook=PROBE.no_duplicate_keys)


if __name__ == "__main__":
    unittest.main()
