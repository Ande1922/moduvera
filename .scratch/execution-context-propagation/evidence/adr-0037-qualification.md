# ADR 0037 historical qualification excerpts

Source baseline: `f1d9e1f7cab4f386f043daf4b53860b0bb5dcd7a`.
Source: `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md`
at that commit. The line references below identify that immutable version.

This file preserves the qualification statements formerly embedded in
[ADR 0037](../../../docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md).
Read it when tracing recorded tests, versions, or consumer qualification.
The recorded statuses and test descriptions are historical claims, not current
support declarations or verification of the present checkout. Extracting them
here did not rerun any runtime tests or qualify the source baseline as a tested
delivery head. Individual worker records retain their own fixed points and
limits; the immutable source below preserves the provenance of these excerpts.

The source text can be recovered with:

```sh
git show f1d9e1f7cab4f386f043daf4b53860b0bb5dcd7a:docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md
```

## Kernel, resource guards and compatibility

Source lines 49–78; no separate status label was recorded.

PostgreSQL and MySQL runtime Adapter tests prove Platform and absence reject
reads and writes without persisting the rejected change, while normal tenant
isolation remains. The compatibility assessment recorded that repository
consumers did not serialize or reflectively destructure ExecutionContext.

Evidence: Kernel tests for the three states, strict tenant reads, full-value
restoration, null rejection, unique binding order, cross-thread and out-of-order
failure atomicity, exception transparency, legacy calls, and post-parent-lifetime
Snapshot execution; production Adapter tests against PostgreSQL and MySQL;
focused tenant-only HTTP and Job/Lock tests; and an independent Maven consumer.

## Request-bound callbacks — ticket 03

Source lines 82, 104–109.

Recorded status: verified for the Kernel callback binding surface and independent consumer usage.

Evidence: Kernel tests exercise all seven public JDK shapes, original outcomes, present and
absent restoration, completed/externally completed/async CompletableFuture callbacks,
parent-Scope exit, retries, same-tenant distinct identities and the `thenCompose` boundary.
The independent `simple-notes-demo` consumer compiles and runs per-request function binding
through an SDK-shaped callback registration/later-trigger driver, plus a separate
per-trusted-event long-lived listener example, using only the public Kernel API.

## JDK executors — ticket 04

Source lines 113, 130–137.

Recorded status: verified for explicitly decorated JDK Executor and ExecutorService instances.

Evidence: Kernel tests compare original and decorated real JDK executors for values, failures,
Future cancellation, rejection, bulk calls, timeout and lifecycle behavior. They also exercise a
reused single platform worker with distinct tenants, same-tenant distinct identity, Platform and
absence; a virtual-thread-per-task executor; inline and CallerRuns execution; cancellation before
start and during delayed interruption; delegate-owned queued Future tasks; explicit Bound
nesting; and restoration after actual exit. The independent `simple-notes-demo` consumer shows
that executor submission capture covers the submitted task, while a Future callback still needs
its own registration-time binding.

## Spring task executors — ticket 05

Source lines 141, 161–169.

Recorded status: verified for explicitly configured Spring TaskExecutor instances and Async proxy routes.

Evidence: Spring Framework 7.0.9 under Spring Boot 4.1.1 and Java 26; focused adapter tests; and
an independent BOM-managed Spring consumer with a real ApplicationContext, single-worker
`ThreadPoolTaskExecutor` reuse, selected and unselected executors, and an actual `@Async` proxy.
The evidence covers Tenant, Platform, absence, same-tenant distinct full identities, a worker's
pre-existing identity, delayed execution after parent-Scope exit, inline and CallerRuns behavior,
values, Future failures, rejection, cancellation before start and during execution, timed Future
observation, actual-exit cleanup and ApplicationContext-owned shutdown. Dependency closure
contains Spring Core/Context and the Kernel without Reactor or Spring AI. Usage and limits are
documented in the adapter README.

## HTTP execution boundaries — ticket 06

Source lines 173, 198–206.

Recorded status: implemented and verified.

Evidence: focused resolver/interceptor tests; embedded Tomcat with RSA-signed
JWTs, virtual request threads, real Security and MVC chains, method/class/default
selection, 401/403 Problem responses, side-effect guards, excluded handlers,
same-tenant distinct identities and REQUEST/ASYNC/ERROR cleanup; Catalog App
consumer IT with full virtual-thread identity and same-thread cleanup; one
generated correlation across Controller, security Problem and selected
redispatches; and the applicable reference-product HTTP scenarios. Usage and
the supported lifecycle are documented in
[`HTTP-EXECUTION-BOUNDARIES.md`](../../../docs/implementation/HTTP-EXECUTION-BOUNDARIES.md).

## Reactor context templates — ticket 07

Source lines 210, 234–239.

Recorded status: verified for the optional Reactor adapter and independent Reactor-only consumer.

Evidence: Reactor Core 3.8.7 with Java 26; real scheduler tests for delayed
subscription after the parent Scope exits, foreign worker restoration,
concurrent Tenant/Platform/same-Tenant identities, explicit absence, wrong
types, exception, retry, and cancellation during a still-running synchronous
mapper; and an independent BOM-managed Reactor-only consumer. Dependency
closure verifies Kernel has no Reactor and the consumer has no Spring AI.

## AI request and streaming response context — ticket 08

Source lines 243, 260–264.

Recorded status: implemented.

Evidence: Spring AI 2.0.1 with Spring Boot 4.1.1 and Java 26; Adapter unit tests;
and an independent consumer using a delayed, thread-shifting real
`ChatClient.stream().chatClientResponse()` subscription with concurrent Tenant,
same-Tenant/different-identity, and Platform requests. Tool callback wrapping
and multi-round tool-loop evidence remain owned by ticket 09.

## AI tool loop context — ticket 09

Source lines 268, 280–292.

Recorded status: implemented.

Evidence: a real streaming ChatClient and ToolCallingAdvisor loop driven by a
test-source scripted model performs two tool rounds before the final response.
The same wrapper isolates four requests covering
same-Tenant/different-identity, a different Tenant, and Platform. A bounded
first-round barrier holds every actual delegate until all four have entered,
so overlap is observed rather than inferred from scheduler timing. The loop
crosses model, bounded-elastic tool, and response-worker threads; the final
ChatClientResponse is consumed through the ticket 08 response mapper.
Missing/wrong native contexts stop before the delegate and a delegate failure
remains the same exception. A test-only outer callback records the Holder
immediately before and after the production wrapper on the same actual tool
thread, proving exact restoration after normal return and failure without
resampling a shared scheduler.

## Tenant-only message compatibility — ticket 10

Source lines 301–303, 317, 319–325.

Recorded status: verified for tenant-only envelope compatibility, reference-consumer contract handling,
context restoration, and runtime Adapter delivery. Broker producer authentication and
destination ACL enforcement remain deployment prerequisites outside this ticket's evidence.

Both reference consumers run the shared inbound contract TCK.

Evidence: message mapper and shared TCK tests preserve required tenant and identity fields,
provider identities and the no-wire-permissions contract; focused consumer and outbound tests
cover tenant construction and prior-worker restoration; PostgreSQL Outbox relay tests publish a
persisted message after the request Scope closes; and the inventory application test uses real
Kafka plus a same-partition consumer-progress barrier before asserting rejected-message side
effects. Those plaintext Testcontainers scenarios do not verify broker authentication or ACL
configuration. See [the tenant-only message context guide](../../../docs/implementation/TENANT-ONLY-MESSAGE-CONTEXT.md).

## Composition and consumer qualification — ticket 11

Source lines 329, 331–355.

Recorded status: verified for the bounded public consumers and combinations described below.

The independent Notes consumer composes an explicitly installed trusted caller context with a
directly submitted propagating JDK task and a real PostgreSQL-backed Application Service read.
A second composition captures a request-owned Function when it is registered, completes its
Future later on an external SDK-shaped worker, and performs the same business read inside the
bound callback. The compositions cover distinct tenants, two complete identities in one tenant,
Platform and absence where tenant data must fail closed, and exact restoration of a foreign
Platform worker after each actual success or failure exit. Row, Outbox and receipt counts remain
unchanged by the read and rejected branches. This is an explicit trusted-Holder consumer seam;
it does not claim an unimplemented HTTP-to-async chain. Servlet entry behavior and negative
write/message side effects retain their separate runtime evidence.

The Reactor-only consumer uses the native subscription Context and a real scheduler without an
AI dependency. The AI consumer provides the combined request, two-round ToolCallingAdvisor loop,
tool callback and final streaming-response path already described above; its test-source scripted
model makes no external provider claim. The Spring task consumer uses a real ApplicationContext,
selected TaskExecutor and Async proxies. Resolved closures bind the qualification to Java 26,
Spring Boot 4.1.1, Spring Framework 7.0.9, Reactor Core 3.8.7 and Spring AI 2.0.1. The Kernel
artifact remains JDK-only; Spring task and Reactor consumers do not acquire Spring AI, and
consumers that use none of these optional adapters acquire none of their frameworks.

The supported adoption and lifecycle boundary is recorded in
[`EXECUTION-CONTEXT-PROPAGATION.md`](../../../docs/implementation/EXECUTION-CONTEXT-PROPAGATION.md). Support
is limited to the explicit public seams and consumers named there. In particular, plaintext Kafka
runtime tests do not verify producer authentication or destination ACLs; those remain deployment
prerequisites for trusting message producers.
