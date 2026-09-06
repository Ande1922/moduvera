# Governed OpenTelemetry Agent verification

This verification owns the external OpenTelemetry Java Agent preparation and
the real runtime evidence for the reference product. The public application
images remain Agent-free. A governed process starts only after the fixed
version and SHA-256 in `agent.lock` match the supplied JAR and the required
repository-built extension matches its fixed SHA-256.

The pinned Agent is 2.31.1 and its upstream release targets OpenTelemetry SDK
1.65.0. The public BOM manages only `io.opentelemetry:opentelemetry-api:1.65.0`
for framework code that must use the Agent-provided SDK. No SDK, tracing bridge,
or exporter is added to application dependencies.

The extension is supplied outside the application image and registered through
the Agent's supported `otel.javaagent.extensions` setting. It wraps the Agent's
configured span exporter at the final export boundary: `url.query` is removed,
query and fragment material is stripped from `url.full` and legacy `http.url`,
URL userinfo is removed, and malformed URL values are dropped. It also removes
automatic exception message/stack-trace text, captured transport headers and
metadata, SQL text, and status descriptions while preserving span identity,
links, event timing, status code, and batching. A missing or modified extension
fails preflight before an application starts.

Run the complete qualification with dedicated external evidence and Maven
cache directories:

```bash
MODUVERA_OBSERVABILITY_EVIDENCE_DIR=/private/tmp/moduvera-otel-evidence \
MODUVERA_OBSERVABILITY_MAVEN_REPO=/private/tmp/moduvera-otel-m2 \
verification/governed-observability/verify.sh
```

The scenario downloads the exact release asset, verifies both runtime artifact
digests, builds and inspects the public images, mounts the Agent and extension
read-only into one image, and proves they can coexist with the existing JaCoCo
Agent. It then runs the real
microservice reference topology through the public HTTP, PostgreSQL, Outbox,
Kafka producer, and Kafka consumer paths. Verification-only reverse proxies
record only `traceparent`/`tracestate`, while the bounded OTLP/HTTP receiver
decodes actual protobuf spans.

The runtime fixes propagation to `tracecontext`; exports traces with OTLP
`http/protobuf`; disables OTel logs, OTel metrics, and JDBC auto-instrumentation;
keeps HTTP header, servlet parameter, messaging header, GraphQL query, and
Elasticsearch query capture disabled; and uses an always-on parent-based
sampler. The batch span processor is bounded
to 512 queued spans, 128 spans per export, a 100 ms schedule delay, and a
1 second export timeout. JDBC instrumentation is disabled because this
repository forbids SQL text in automatic telemetry; database behavior is
still verified through the production Adapter and real PostgreSQL state.

The receiver scans every raw OTLP payload for runtime-generated header, query,
SQL, payload, and credential sentinels before writing a redacted normalized
span record. The scenario also stops the receiver after startup and proves a
new order still completes its HTTP response, database transaction, Outbox
publication, Broker ACK, and consumer result without business replay.

`MODUVERA_OBSERVABILITY_SKIP_BUILD=1` reuses current reactor artifacts.
`MODUVERA_OBSERVABILITY_SKIP_IMAGE=1` omits image build and image Agent/JaCoCo
proof; neither option is suitable for full ticket qualification.
