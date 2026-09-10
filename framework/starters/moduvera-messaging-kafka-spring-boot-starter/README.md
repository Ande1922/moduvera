# Kafka message diagnostics

`ReliableMessageConsumerFactory` preserves validation → trusted identity → local
retry ordering. Every actual Inbox/handler attempt opens an INTERNAL `mq.process`
Span beneath the complete current Agent Context. A valid creation context is a
Link. Missing or malformed creation values are ignored by the existing Mapper;
missing transport parent reuses an effective Agent root. Only an absent effective
current Span causes the INTERNAL processing Span itself to be a root.

Each attempt establishes trusted execution context from the envelope C, tenant
and Initiator, plus the local contract Actor and permissions. A short logging
scope projects C, tenant, Actor and Initiator. Closing it restores the
previous business context, OTel Context and owned MDC keys. INFO `mq.consume`
records actual wire body bytes and elapsed time. Retry fields come from this
endpoint's controller; a recovery WARN records each decision to retry. An
interrupted backoff preserves the interrupt flag and propagates a terminal
failure. Fatal `Error` values propagate to the container after scope restoration.

The default Spring Integration error-log subscriber is the final diagnostic
owner for terminal RuntimeExceptions. The endpoint creates a fresh adapter-owned
`NonRetryableMessageException`, retains the application failure as its cause,
and attaches a private, stackless, process-local diagnostic snapshot. A reused
application exception is never modified. The snapshot is transient and conveys
no serialized identity. At the existing default logger Bean, a narrow proxy
restores this snapshot and writes one safe `mq.consume.failure` ERROR with the
original throwable available to the governed formatter. It then restores the
error-handler thread. Interrupted backoff uses the current delivery Context;
completed attempt failures retain their attempt Context.

This hook applies only to framework-owned failures at Spring Integration's
default logger. Custom error subscribers retain their own diagnostic ownership;
pre-validation failures have no established business identity. The proxy leaves
Binder recovery/DLQ subscribers and their ErrorMessage intact. It makes no DLQ
decision, adds no retry controller, and does not turn propagation into another
ERROR at the endpoint. Container-fatal `Error` diagnostics remain container-owned.

## Verification

`ReliableInboundEndpointTest` covers context/MDC restoration, recovery,
exhaustion, interruption, reused throwable isolation and default-logger scoping.
`InboundCausalityIT` uses the production StreamBridge transport, a real Kafka
Broker, production JDBC Inbox and real transactions. Its pre-send ProgressBarrier
waits for committed group offsets before duplicate/failure side-effect assertions.
The fixture records raw Kafka headers before Agent adaptation, separately from
Spring Message headers and envelope creation. Same-poll records, local retries,
missing/invalid parent, unsampled delivery and an explicit no-current-context
fallback are reconciled against locked-Agent output.

The fixture uses native byte encoding/decoding to preserve the structured
envelope, explicit String producer keys, byte-array DLQ serializers and the
existing ACK budget. These are fixture assembly settings, not changed Binder
policy. Its two out-of-process console sends deliberately omit or corrupt only
transport `traceparent`; the content type and business envelope remain valid.

From the repository root, with the governed Agent/extension and local OTLP receiver configured, run
`bash verification/governed-observability/verify-inbound-fixture.sh`, then
`python3 verification/governed-observability/analyze_inbound_fixture.py "$MODUVERA_OBSERVABILITY_EVIDENCE_DIR"`.
The receiver should be drained before analysis. Module verification is
`./mvnw -pl framework/starters/moduvera-messaging-kafka-spring-boot-starter -am verify`.
