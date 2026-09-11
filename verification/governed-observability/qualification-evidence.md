# Governed observability evidence map

This map describes required evidence, not a completed run. The run's external
index must name the actual base/head, source inputs, commands and exit codes,
original review reports and gate. A tracker's status or a missing historical
report cannot establish a current result. Source-identical focused evidence
retains its original run revision; changed shared inputs need an explicit
impact assessment and current affected checks.

The [complete launcher](verify.sh) owns actual microservice public traffic,
images, external Agent loading, HTTP propagation, durable message legs, stdout,
unsampled requests and receiver outage. Its retained outputs are `inputs.json`,
`traffic.json`, `sampled-database.json`, `kafka-records.txt`, `spans.jsonl`,
`receiver-status.json`, `stdout/`, `outage.json`, both outage database projections,
`qualification.json` and the reference harness output. Raw fixture evidence is
local; session credentials must never enter the index, command arguments or logs.
The harness also retains `harness-stdout/` during cleanup, including failed runs;
`stdout/` is the bounded snapshot used by the completed-chain analyzer.

The reference Gateway forwards the opaque exchanged JWT without locally
authenticating its claims, so its canonical identity fields stay absent. Order
projects the authenticated USER; Catalog and message consumers project their
locally configured SERVICE Actor and the trusted original Initiator. A consumer's
Actor subject is its configured policy value, not necessarily its runtime
`service.name`. Message-id joins use the actual consume canonical's Span ID;
`mq.process` does not require a message-id Span attribute.

The [Notes launcher](verify-notes.sh) separately owns independent Parent/BOM,
public minimal consumers, same-JVM JaCoCo coexistence, Notes HTTP/PostgreSQL/Kafka
and all nine actual registry families. The [Relay launcher](verify-relay-fixture.sh)
and its [publication](analyze_relay_fixture.py) and [redrive](analyze_redrive_fixture.py)
analyzers own the full database/CAS/independent-process fault matrices.
Reference assembly success cannot replace those evidence surfaces.

| Stories | Owning tickets | Evidence required at the applicable seam |
| --- | --- | --- |
| US01 | 02, 17 | ECS formatter/real logging fixture; duplicate-key checked five-service stdout |
| US02 | 02–06, 08–13, 15, 17 | Per-boundary ownership/count/privacy matrices; committed business facts; actual canonical joins |
| US03 | 04, 05, 17 | Public generated UUIDv4 and internally inherited response C |
| US04 | 04, 05, 07, 17 | Protected internal missing/invalid C matrix, actual propagation, mandatory message C |
| US05 | 04, 05, 17 | Dual headers, owned Problem C, transparent downstream Problem and mismatch evidence |
| US06 | 04, 05, 17 | Real sync/async/cancel/timeout/reset fixtures; actual public rejection/completion |
| US07 | 02, 04, 05, 16, 17 | Trusted USER/SERVICE/Initiator projection and absence before authentication |
| US08 | 02, 03, 05, 08 | Thread/Reactor/message interleaving and cleanup inside/after callbacks |
| US09 | 03 | Actual task start, cancellation/rejection, nested scopes and task Agent fixture |
| US10 | 01, 06, 09, 12, 17 | Native HTTP/Kafka ownership; public cold/cache-hit 1/1 versus 0/1 network calls |
| US11 | 09, 16 | Immediate transaction rejection, real ACK/failure and independent Notes consumption |
| US12 | 10, 15, 17 | Atomic append rollback/commit on both databases; committed facts; persisted creation joins |
| US13 | 12, 17 | Same-generation automatic recovery across actual processes; current public Kafka recovery |
| US14 | 13, 16 | Atomic terminal/token CAS redrive; new root/creation Link, two recovered generations |
| US15 | 08, 12, 13, 16 | Separate ACK/writeback truth; stale/crash replay and committed Inbox progress barriers |
| US16 | 10–13 | Both database upgrades, damaged/absent carrier repair under valid claim, restart reuse |
| US17 | 07, 09, 10 | Actual EVENT/ASYNC_COMMAND schemas and mapper fixtures, old-reader rollout boundary |
| US18 | 08, 13, 17 | Native delivery parent, independent creation Link, per-message Actor and deduplication |
| US19 | 14, 16, 17 | Exact registry types/units/tags/counts, actual assemblies, routed finite SDK-signal observation |
| US20 | 01, 16, 17 | Fixed artifact/preflight/handshake failures, image/Notes JaCoCo and receiver outage |
| US21 | 02, 16, 17 | Independent minimal consumers, Notes and five-service public contract; monolith compile only |

| Test decision | Owning tickets | Required retained surface |
| --- | --- | --- |
| TD01 | 02–06, 08–13, 15, 17 | Real structured stdout, safe cause, responsibility and transaction fact tests |
| TD02 | 04–06, 17 | Actual Servlet/Netty lifecycle plus whole public-to-internal HTTP joins |
| TD03 | 02, 03, 05 | Real JDK/virtual-thread/task/Reactor isolation fixtures |
| TD04 | 09, 10, 12, 13, 16 | Actual public publication adapters, database transactions and Kafka |
| TD05 | 10–13 | PostgreSQL/MySQL migrations, row locks/CAS and independent JVM recovery |
| TD06 | 07, 09, 10 | Published schemas, fixed old/new envelopes, actual mapper/TCK checks |
| TD07 | 08, 09, 12, 13, 16, 17 | Wire carrier/native spans versus creation/processing Link and consumption progress |
| TD08 | 14, 16, 17 | Observer/registry and actual Order/Notes meter assertions; no high-cardinality tags |
| TD09 | 01, 06, 09, 12, 16, 17 | Exact external Agents, real exporter/transport, cache and outage fixtures |
| TD10 | 02, 16, 17 | Notes independent consumption, minimal public dependencies, microservices and docs |

For this closure, the explicit runtime selection follows
[ADR 0038](../../docs/adr/0038-retain-monolith-as-on-demand-assembly.md): qualify
microservices, retain monolith compilation/shared architecture. The historical
US21/TD10 second-runtime expectation is recorded as superseded for this run;
do not claim current monolith runtime support. Servlet final-error acceptance
stops at the approved Advice boundary. Full fault evidence must preserve lost
unexported spans as lost, not reconstruct them as successful exports. Negative
message assertions require the existing pre-send progress barrier and observed
consumption; fixed sleeps and producer ACKs alone do not prove consumer absence.

Retain the bounded runtime limitations recorded by the owning tickets: Ticket 06
does not repair the native Reactor cancellation race before body subscription;
Ticket 08 observes a missing native fatal-consumer parent export with Agent
2.31.1 / Spring Kafka 4.1.1, even though the application process context retains
that actual parent. An ordinary successful chain does not resolve either limit.
Custom asynchronous logging appenders remain unsupported. Device, AI, long
connections and the scheduler-lock Candidate are outside this qualification.

Independent Standards and Spec review, the repository's exact-commit Normal
Gate and final acceptance remain separate. No run output automatically closes
a ticket, parent spec or finding, changes Product Surface status, or grants
push/deployment/cleanup authority.
