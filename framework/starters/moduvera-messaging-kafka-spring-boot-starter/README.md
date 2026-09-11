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
projects only diagnostic fields and writes one safe `mq.consume.failure` ERROR with the
original throwable available to the governed formatter. It then restores the
error-handler thread. Interrupted backoff uses the current delivery Context;
completed attempt failures retain their attempt Context.

This hook applies only to framework-owned failures at Spring Integration's
default logger. Custom error subscribers retain their own diagnostic ownership;
pre-validation failures have no established business identity. The proxy leaves
Binder recovery/DLQ subscribers and their ErrorMessage intact. It makes no DLQ
decision, adds no retry controller, and does not turn propagation into another
ERROR at the endpoint.

For the existing local-retry assembly (`consumer.max-attempts=1` in Binder), the
endpoint customizer decorates the original output/error channel sends with short,
finally-closed diagnostic associations. Existing user customizers still run. A
terminal canonical retains its finished attempt duration and identity until the
original Binder recovery completes. An additive `ClientFactoryCustomizer` observes
original Producer callbacks: only a matching source topic/partition/offset and
real successful ACK can supply `disposition=dead_letter`. Failures, unattempted
sends and late callbacks never fabricate another canonical. Transactional ACKs
wait for commit; abort does not claim dead-lettering. The orchestration tests do
not substitute for Kafka transaction qualification.

The Logback adaptation matches only the active owned send's exception identity
or cause chain at the Kafka producer listener/Binder loggers. Propagating records
stay INFO, and dispatch completion owns one `mq.consume.recovery.failure` ERROR.
Unrelated exceptions and background Kafka metadata diagnostics remain unchanged.
The retained snapshot never reinstalls authorization or changes the transport
parent while logging/calling the existing recovery subscribers.

An original `Error` still escapes unchanged and stops the Kafka container. Weak
identity references retain only its diagnostic snapshot per consumer thread.
At the exact native container final log, the Logback adapter projects that
snapshot and keeps one ERROR; the subsequent completion log of the same Error
is accounted for as a duplicate. Concurrent reuse does not borrow another
thread's identity. Completed metadata is removed, expired weak keys are pruned,
and the registry is cleared when its logging adapters are disposed. No Error is
wrapped or modified. VM resource exhaustion cannot promise diagnostic allocation.

## Verification

`ReliableInboundEndpointTest` covers context/MDC restoration, recovery,
exhaustion, interruption, reused throwable isolation and default-logger scoping.
`InboundCausalityIT` uses the production StreamBridge transport, a real Kafka
Broker, production JDBC Inbox and real transactions. Its pre-send ProgressBarrier
waits for committed group offsets before duplicate/failure side-effect assertions.
The fixture records raw Kafka headers before Agent adaptation, separately from
Spring Message headers and envelope creation. Same-poll records, local retries,
missing/invalid parent, unsampled delivery and an explicit no-current-context
fallback are reconciled against locked-Agent output. Real invalid-topic failure
proves absence of a DLQ ACK/disposition and one recovery ERROR. A final ordinary
AssertionError exercises original container termination: both sends have Broker
ACKs, the failed and following records remain uncommitted, the handler transaction
rolls back, and a completed container thread bounds the negative assertions.

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

## Locked-Agent fatal-stop export limitation

With Agent 2.31.1 and Spring Kafka 4.1.1, an Error terminates record processing
before the native `afterRecord` callback. The Agent's record interceptor ends
its span in that callback; its thread cleanup only removes thread state.
Consequently the fatal delivery has a valid current Agent Context and a correctly
parented, ended application INTERNAL span, but that one Agent CONSUMER span is
not exported. The analyzer reports this separately: 19 application attempts,
18 sampled processing spans, 12 exported Agent consumer spans and one additional
valid fatal consumer Context without an export. It does not claim complete native
export coverage or manually end/replace an Agent-owned span. The same missing
parent export was reproduced before the native-log repair.

Sources: [Agent 2.31.1 record interceptor](https://github.com/open-telemetry/opentelemetry-java-instrumentation/blob/v2.31.1/instrumentation/spring/spring-kafka-2.7/library/src/main/java/io/opentelemetry/instrumentation/spring/kafka/v2_7/InstrumentedRecordInterceptor.java#L102)
and [Spring Kafka 4.1.1 record loop](https://github.com/spring-projects/spring-kafka/blob/v4.1.1/spring-kafka/src/main/java/org/springframework/kafka/listener/KafkaMessageListenerContainer.java#L2787).

## Immediate publication diagnostics

`ImmediatePublication.publish` still rejects any active database transaction
before sending. Outside transactions it makes exactly one existing transport
call; return means the configured synchronous Broker ACK, while an exception
keeps the original send guarantee and can leave the Broker outcome unknown.
No application retry, persistence or redrive is added.

If a descriptor has no creation Context and the caller has a valid current
Context, the standard W3C propagator captures its pure values into the outgoing
immutable descriptor. Supplied creation and all other original message fields
are preserved. An absent current Context leaves creation absent. Only the Agent
creates producer spans and transport headers; no SDK or duplicate publication
span is introduced.

Assembly configuration `moduvera.messaging.kafka.immediate-business-boundary-destinations`
lists the logical routes whose successful ACK results require INFO. The default
empty list leaves internal success silent; configured entries must exist in
`routes`. Every unhandled synchronous send failure receives an INFO result with
monotonic duration and is rethrown unchanged to its final owner. `topic` names the
logical message destination, and `messaging.message.body.size` is the known
serialized payload byte length, not an inferred transport/envelope size. No
retry count, message body or arbitrary headers are logged.

A default-listener Bean adapter recognizes only the built-in
`LoggingProducerListener` at its standard bean names. Within the original
Immediate callback, identical ProducerRecord and Exception objects identify
that process-only diagnostic; it is safe DEBUG, while the caller owns the
final ERROR. Custom listeners and unmatched records pass through. There is no
new global Logback filter or logger-level change. The additive producer
postprocessor preserves the original callback, future, record and exception.
It retains diagnostic fields for a late callback without reinstalling business
authorization or changing its active transport Context.

`ImmediatePublicationIT` uses production StreamBridge transport, real Kafka and
a real PostgreSQL transaction manager. It reads ACKed bytes/headers back using
the container's uninstrumented console consumer, checks read-only and writable
transaction rejection against actual Producer invocation counts and Broker
end offsets, and stops its own Broker for a real failure. It never interprets
that timeout as proof that a remote business operation did not run. The locked
Agent fixture is `verification/governed-observability/verify-immediate-fixture.sh`;
reconcile its output with `analyze_immediate_fixture.py`. The fixture's absent
caller Context lets the locked Agent create a native Spring Integration root
and a producer child; creation remains absent and the caller result does not
invent a trace. The analyzer accounts for this native shape explicitly.

## Durable append and persisted trace metadata

`JdbcDurablePublication` validates the existing active, writable transaction on
its own DataSource before starting a short `outbox.append` INTERNAL span. When
creation is absent, the standard W3C propagator captures that span's context as
pure values. Already supplied immutable creation is preserved. The intent writer
saves creation and the initial publication context together, with generation 0,
in the same transaction as the business state. No SDK, MDC or permissions are
serialized. Without an effective SDK, absent context remains absent; existing
valid propagation values can still be retained.

Append return and span end mean only that the append call ended. They do not
announce a committed business fact or Broker receipt. The original payload-free
wake still runs only after commit; rollback discards the message and both trace
contexts. The caller's complete OTel context and owned logging fields are
restored on normal and exceptional exits, and the original exception is rethrown.

PostgreSQL and MySQL append V3 migrations with two nullable, bounded propagation
pairs and non-null `publication_generation` defaulting to 0. V1/V2 and existing
rows are retained. Normal startup still validates; explicit assembly/release
policy selects migration execution and database identity initialization. Upgrade
readers and validators before enabling the optional creation extension on the
wire, as described by the existing envelope compatibility contract.

`ClaimedOutboxMessage` returns publication generation and raw nullable propagation
strings separately from the message descriptor's creation. Raw invalid publication
values remain representable for claim-owned preparation; reading them neither
rewrites creation nor treats them as authorization or a valid parent. Append and
claim alone do not qualify Relay trace recovery, publication repair or redrive.

`DurableAppendIT` uses production migration/JDBC/transaction adapters on both real
databases. It upgrades V2 rows including an active lease and terminal history,
compares every legacy column, checks visibility from an independent connection,
commit/rollback/wake behavior, transaction rejection and independent claim metadata.
`verify-durable-fixture.sh` and `analyze_durable_fixture.py` additionally reconcile
the locked Agent's short append spans with actual stored values, including
unsampled execution, absent parent, supplied creation and duplicate-key failure.
Connector/J 9.7.0 defaults to its own OpenTelemetry instrumentation through the
same global SDK, independently of the Agent JDBC instrumentation switch. The
fixture retains and counts these native MySQL CLIENT spans separately from
append spans, including driver error statuses during metadata/transaction work.
The analyzer scans all exports for unsafe query/exception content; it does not
interpret native driver span counts as append attempts or committed outcomes.
