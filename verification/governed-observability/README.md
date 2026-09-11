# Governed OpenTelemetry Agent verification

This verification owns the external OpenTelemetry Java Agent preparation and
the real runtime evidence for the reference product. The public application
images remain Agent-free. A governed process starts only after the fixed
version and SHA-256 in `agent.lock` match the supplied JAR and the required
repository-built extension matches its fixed SHA-256.

Preflight accepts only an explicit HTTP(S) origin ending in `/v1/traces`; it
rejects whitespace, control characters, userinfo, query strings, fragments,
and JVM-option injection. It also inspects `JAVA_TOOL_OPTIONS`,
`REFERENCE_JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS`, and `JDK_JAVA_OPTIONS` for
pre-existing Java Agents. The only supported coexistence is the exact pinned
JaCoCo 0.8.15 runtime artifact after its digest is verified.

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

The same external extension JAR supplies a small launch guard. The official
Agent loads the extension through its SPI and sets an in-process marker only
after exporter customization is installed; the guard checks that marker in a
later `premain` invocation and aborts before application `main` if SPI loading
did not happen. The verification suite proves this with both the valid artifact
and a byte-preflight-approved JAR whose SPI registration is deliberately
broken.

Run the complete qualification with dedicated external evidence and Maven
cache directories. Supply the local seeded login fixture through environment
variables whose values come from the caller's local test credential source:

```bash
MODUVERA_OBSERVABILITY_LOGIN_USERNAME="${LOCAL_FIXTURE_USERNAME}" \
MODUVERA_OBSERVABILITY_LOGIN_CREDENTIAL="${LOCAL_FIXTURE_CREDENTIAL}" \
MODUVERA_OBSERVABILITY_LOGIN_TENANT="${LOCAL_FIXTURE_TENANT}" \
MODUVERA_OBSERVABILITY_EVIDENCE_DIR=/private/tmp/moduvera-otel-evidence \
MODUVERA_OBSERVABILITY_MAVEN_REPO=/private/tmp/moduvera-otel-m2 \
verification/governed-observability/verify.sh
```

All three login variables are required. The tenant must be the current
canonical `tenant-a` qualification fixture because the analyzer binds Kafka
and persistence evidence to that tenant. An empty, missing, or noncanonical
value exits 64 before downloads, builds, or runtime startup. The supplied
username must identify an enabled `tenant-a` seed member with `catalog:read`,
`order:create`, and `order:read`; the current reference seed supplies `alice`.
The Order service also needs its seeded `catalog:read` permission, which the
scenario checks before the cold-cache probe.

`verify.sh` removes the three caller-facing variables and clears any inherited
export attribute from its private copies before starting unrelated children.
It supplies them only to the two `probe.py` login processes. The credential is
not a command argument, log value, or evidence field. Username and tenant are
business identifiers and can appear in existing span, Kafka, and persistence
evidence; this verification does not add a new redaction rule for them.

The scenario downloads the exact release asset, verifies both runtime artifact
digests, builds and inspects the public images, mounts the Agent and extension
read-only into one image, and proves they can coexist with the existing JaCoCo
Agent. It then runs the real
microservice reference topology through the public HTTP, PostgreSQL, Outbox,
Kafka producer, and Kafka consumer paths. Verification-only reverse proxies
record W3C propagation and correlation identity on the actual Gateway→Identity,
Gateway→Order, Order→Identity, and Order→Catalog calls. Their request relay
supports bounded fixed-length and chunked bodies so observation does not alter
the exercised call. The bounded OTLP/HTTP receiver decodes actual protobuf
spans.

The runtime fixes propagation to `tracecontext`; exports traces with OTLP
`http/protobuf`; disables OTel logs, OTel metrics, and JDBC auto-instrumentation;
keeps HTTP header, servlet parameter, messaging header, GraphQL query, and
Elasticsearch query capture disabled; and uses an always-on parent-based
sampler. The batch span processor is bounded
to 512 queued spans, 128 spans per export, a 100 ms schedule delay, and a
1 second export timeout. JDBC instrumentation is disabled because this
repository forbids SQL text in automatic telemetry; database behavior is
still verified through the production Adapter and real PostgreSQL state.
Process command lines and arguments are disabled as resource attributes. The
export boundary also removes current and legacy User-Agent attributes.

The receiver scans every raw OTLP payload for runtime-generated header, query,
SQL, payload, and credential sentinels before writing a redacted normalized
span record. A committed real-Agent exception fixture exercises request query,
User-Agent, SQL-source, and process-command sentinels and checks retained span
identity. The topology phase creates a first cold-cache order and a second
same-tenant/same-initiator order under different correlation and Trace IDs. It
proves one initial Order→Identity token request, no additional token request on
the cache hit, and one independently propagated Order→Catalog call per order.

The scenario also stops the receiver after startup and proves a new order still
completes its HTTP response, database transaction, Outbox publication, Broker
ACK, and consumer result without an extra business execution caused by the
telemetry outage. Kafka records are captured after injection and matched by the
controlled order's message keys; Order and Inventory database evidence checks
the corresponding header, reservation, Inbox, and Outbox outcomes. These
assertions preserve the transport's at-least-once contract and make no general
exactly-once claim.

`MODUVERA_OBSERVABILITY_SKIP_BUILD=1` reuses current reactor artifacts.
`MODUVERA_OBSERVABILITY_SKIP_IMAGE=1` omits image build and image Agent/JaCoCo
proof; neither option is suitable for full ticket qualification.

The logging qualification is a smaller, ticket-scoped runtime check. It runs a
real Spring Boot process with the same pinned external Agent and extension,
parses every stdout line with duplicate-key rejection, and checks sampled,
unsampled, and absent contexts. It also verifies trusted ExecutionContext
identity, stable scalar types, safe final-error cause output, and that logging
plus short scope installation creates no additional Span. Build the fixture
and extension first, then provide a dedicated evidence directory:

```bash
./mvnw -pl verification/governed-observability/logging-fixture,verification/governed-observability/agent-extension -am package
MODUVERA_OTEL_JAVAAGENT=/path/to/opentelemetry-javaagent-2.31.1.jar \
MODUVERA_LOGGING_EVIDENCE_DIR=/private/tmp/moduvera-logging-evidence \
verification/governed-observability/verify-logging-fixture.sh
```

The Relay fixture also qualifies atomic manual redrive. Supply the pinned Agent,
repository extension, a running local OTLP receiver endpoint, and a fresh evidence
directory through the existing runtime variables, then run:

```bash
verification/governed-observability/verify-relay-fixture.sh
python3 verification/governed-observability/analyze_relay_fixture.py "$MODUVERA_OBSERVABILITY_EVIDENCE_DIR"
python3 verification/governed-observability/analyze_redrive_fixture.py "$MODUVERA_OBSERVABILITY_EVIDENCE_DIR"
```

`RedriveIT` uses production JDBC adapters with real PostgreSQL and MySQL row locks,
conditional updates, independent observation connections, transaction rollback,
SQL rejection, concurrent callers, and complete immutable-column/payload comparisons.
The locked-Agent run additionally verifies no-parent roots, creation/management Links,
original-message diagnostics, and completion after an enclosing transaction decides,
even when its management span has already ended. An ordinary run without an SDK
verifies that no trace identity is fabricated or retained from the previous generation.

`RelayProcessIT` preserves its existing publication fault matrix and adds one message
through generations 0, 1, and 2. After the first real ACK and failed status write, the
consumer has applied the message once. A separate redrive JVM commits generation 1
and blocks in the existing payload-free wake callback; it is killed before the root
ends. Automatic retry, an ACK-before-mark process crash, and another real Relay JVM
retain generation 1. The next accepted redrive creates generation 2, followed by an
automatic retry, another crash, and a fresh Relay restart. Four acknowledged records
produce one Inbox APPLIED and three DUPLICATE outcomes; cumulative failures remain 4.
Every send has a pre-send progress barrier and committed-offset observation before
asserting the absence of another business effect.

The two analyzers separate the original unchanged-generation matrix from the new
redrive chain, then reconcile database carriers, actual record headers, Agent-owned
producer/consumer spans, application children and Links, process IDs, and safe logs.
They explicitly account for one lost committed redrive root and two lost open publish
spans in the new chain. Persisted carriers prove recovery; they do not prove that a
killed process's unexported span is queryable. This remains at-least-once delivery.
