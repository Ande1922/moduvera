Ticket: `.scratch/execution-context-propagation/issues/07-reactor-context-templates.md` — Reactor subscription context templates
Worktree: `/private/tmp/execution-context-frontier-20260905/07`
Branch: `codex/execution-context-20260905-07`
Base: `f3ef17e028802d4072e943c167f9abea0aca5b3a`
Required ancestor: `4313748aac07b06943072f7d00f71ce8af5751d0` (confirmed ancestor)
Head: `77f93f0f468f0beec6e35b2c8f9ca9c385908c51`
Result: committed successfully; worktree clean

## Acceptance evidence

- Mono/Flux public templates: `ReactorExecutionContexts` exposes `withContext`, strict call-time `propagate`, native-only `require(ContextView)`, and synchronous `mapInContext` overloads for both Publisher types.
- Call-time capture: adapter and independent-consumer tests create the propagated Publisher under the request Scope, close the parent Scope, and only then subscribe on a foreign scheduler; the captured full request identity is observed.
- Per-subscription restoration and isolation: a reusable identity-free Flux template is subscribed concurrently on a four-worker real Reactor scheduler with Tenant A, Tenant B, Platform, and same-Tenant/different Actor/Initiator/Correlation contexts. Every mapper sees its own full context and the following native operator observes restored absence.
- Native strictness: `require(ContextView)` rejects missing and wrong-type keys without Holder fallback. `mapInContext` converts a missing native key to `ExecutionContextSnapshot.absent()`, hides a preinstalled foreign worker context, makes Holder `require()` fail, and restores the worker afterward. Platform remains present but `requireTenantId()` fails.
- Lifecycle: real scheduler tests cover normal Mono and Flux signals, original mapper exception, retry with restoration between attempts, and cancellation that interrupts but deliberately keeps a synchronous mapper running. It sees the request after cancellation, and worker restoration is asserted only after the mapper's actual `finally` exit.
- Full identity: restoration and isolation assertions compare `ExecutionScope`, `Actor`, `Initiator`, and correlation ID. Worker probes compare the same four fields.
- Support boundary: the adapter README, public Javadocs, test for a plain native `map`, and ADR 0037 ticket-07 section document that there is no global Hook, scheduler/I/O selection, arbitrary map/flatMap or inner-async guarantee. Fixed-context Publishers cannot be shared across requests; `cache`/`share` data isolation is separate.
- Independent consumer: `verification/moduvera-reactor-context-consumer` uses a versionless BOM-managed `moduvera-reactor-context` dependency and runs the public templates across a real scheduler.
- Dependency closure: the adapter resolves only Kernel plus Reactor at compile scope. Reactor Core resolves to 3.8.7 (Reactive Streams 1.0.4 and JSpecify 1.0.1). The Kernel dependency tree contains no Reactor; the Reactor-only consumer tree contains no Spring AI.

## Changed files

- `pom.xml`: registers the optional Reactor adapter and independent consumer.
- `framework/bom/pom.xml`: manages `moduvera-reactor-context` for versionless consumers.
- `framework/adapters/moduvera-reactor-context/pom.xml`: optional Kernel + Reactor dependency boundary.
- `framework/adapters/moduvera-reactor-context/src/main/java/io/github/ande1922/moduvera/reactor/ReactorExecutionContexts.java`: public native Reactor templates.
- `framework/adapters/moduvera-reactor-context/src/test/java/io/github/ande1922/moduvera/reactor/ReactorExecutionContextsTest.java`: scheduler, isolation, strictness, retry, exception, cancellation, and restoration evidence.
- `framework/adapters/moduvera-reactor-context/README.md`: usage, lifecycle, reuse, and unsupported-boundary documentation.
- `verification/moduvera-reactor-context-consumer/pom.xml`: independent BOM-managed consumer with no AI dependency.
- `verification/moduvera-reactor-context-consumer/src/main/java/io/github/ande1922/moduvera/verification/reactor/ReactorContextConsumer.java`: public consumer usage.
- `verification/moduvera-reactor-context-consumer/src/test/java/io/github/ande1922/moduvera/verification/reactor/ReactorContextConsumerTest.java`: real scheduler and delayed-subscription consumer evidence.
- `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md`: only the allocated ticket-07 section, updated with verified behavior and limits.

No AI source, HTTP/Notes configuration, Kernel implementation, service, app, tracker, or other ticket code was changed.

## Modules and resolved versions

- `moduvera-kernel` — `0.1.0-SNAPSHOT`, JDK-only, no Reactor dependency.
- `moduvera-reactor-context` — `0.1.0-SNAPSHOT`.
- `moduvera-reactor-context-consumer` — `0.1.0-SNAPSHOT`, independent Spring Boot parent with versionless Moduvera adapter dependency.
- Java — 26 (Oracle Corporation).
- Maven wrapper — 3.9.16.
- Spring Boot dependency management — 4.1.1.
- Reactor Core — 3.8.7.
- Reactive Streams — 1.0.4.
- JSpecify — 1.0.1.

## Verification

- `git status --short --branch && git rev-parse HEAD && git merge-base --is-ancestor 4313748aac07b06943072f7d00f71ce8af5751d0 HEAD`: clean start on required branch/base; ancestor exit 0.
- `./mvnw -pl framework/adapters/moduvera-reactor-context -am test`: pass after cancellation coordination fix; 51 tests (43 Kernel + 8 adapter).
- `./mvnw -pl verification/moduvera-reactor-context-consumer -am test`: pass after Clean Code changes; 53 tests (43 Kernel + 8 adapter + 2 consumer).
- `./mvnw -pl verification/moduvera-reactor-context-consumer -am verify`: pass after Clean Code; 53 tests, compilation, Enforcer/dependency convergence, Spotless, PMD, JaCoCo and packaging for Parent, Kernel and adapter; independent consumer tests and packaging pass. Log: `scoped-verify.log`.
- `./mvnw -pl verification/moduvera-reactor-context-consumer -am dependency:tree -Dverbose`: pass; Reactor Core 3.8.7 and versionless consumer closure. Log: `consumer-dependency-tree.log`.
- `./mvnw -pl framework/foundation/moduvera-kernel dependency:tree -Dverbose`: pass; no Reactor artifact. Log: `kernel-dependency-tree.log`.
- `rg -n "org\\.springframework\\.ai|spring-ai" verification/moduvera-reactor-context-consumer/pom.xml consumer-dependency-tree.log`: no matches; Reactor-only consumer has no Spring AI dependency.
- `git diff --cached --check`: pass.
- Staged secret scan for password/secret/token/API-key patterns: no matches.
- Coordinator reconciliation: `git status --short --branch && git rev-parse HEAD && git show --stat --oneline --no-renames HEAD`: clean branch at `77f93f0f468f0beec6e35b2c8f9ca9c385908c51`; 10 files, 775 insertions, 5 deletions.

## Logs

- `/private/tmp/execution-context-frontier-20260905/evidence/07/scoped-verify.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/07/consumer-dependency-tree.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/07/kernel-dependency-tree.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/07/environment.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/07/commit-blocker.log`

## Commit sequence and remaining boundaries

- The worker's first `git commit -m "feat: add Reactor execution context templates"` request was rejected before execution because automatic approval review did not accept the delegated brief as explicit commit authorization. The worker stopped without retrying or bypassing that result.
- The coordinator then verified the exact staged 10-file diff and the user's confirmed execution-plan item 4. With that evidence, the same ordinary commit passed approval and created `77f93f0f468f0beec6e35b2c8f9ca9c385908c51`. The coordinator made no code edits, and the worktree is clean.
- No full repository gate, formal dual-axis review, tracker update, integration, rebase, amend, push, or cleanup was performed, per worker scope.
