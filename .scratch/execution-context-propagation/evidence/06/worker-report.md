# Ticket 06 worker report — HTTP execution boundaries

## Accepted review finding closure

Review fixed point: `c36ef5313a4d64c58a96b44db97234b83e84b13b`.

Both accepted P2 findings are implemented in the ticket 06 worktree:

- `DefaultRequestCorrelationIdResolver` now establishes the first accepted
  header or generated correlation in the shared Servlet request attribute.
  Established attributes and App-provided resolver beans remain authoritative.
  A real RSA-signed JWT request with no correlation header captured the pre-fix
  mismatch between the Controller context and 403 Problem, then passed after
  the fix across Controller/Problem, REQUEST/ASYNC, and REQUEST/selected-ERROR
  dispatches with full Scope, Actor, Initiator, and Correlation observations.
- The real Catalog virtual-thread consumer now sends two same-tenant requests
  with distinct SERVICE Actors, USER Initiators, and correlations. Its outer
  Servlet filter records, after each dispatch, that completion remains on the
  same virtual request thread and `ExecutionContextHolder` is empty. A bounded
  latch waits for filter unwinding instead of racing response delivery.

Authoritative review-fix verification:

- `review-fix-correlation-red-runtime.log`: expected pre-fix failure; Controller
  correlation `c4b22d2c-538b-4434-9ab5-68602ec2ccb1` differed from Problem
  correlation `25b70888-55bc-425c-a7e7-c5141b0d826a`.
- `review-fix-correlation-green.log`: focused signed-JWT no-header regression
  passed after the resolver fix.
- `review-fix-auth-final-v2.log`: PASS in 7.306 s; Resource Server unit suite
  18 tests and `HttpExecutionBoundaryIT` 5 tests.
- `review-fix-catalog-final-rerun.log`: PASS in 17.107 s;
  `CatalogApplicationIT` 8 tests.
- Repository-prescribed Spotless pass: PASS. `git diff --check`: PASS.
- The previous Notes and both-topology harness evidence remains applicable;
  neither accepted fix changes Notes assembly, application behavior behind the
  Web Starter correlation filter, or the reference topology contract.

Exact review-fix commands:

- Red runtime regression:
  `./mvnw --log-file /private/tmp/execution-context-frontier-20260905/evidence/06/review-fix-correlation-red-runtime.log -pl framework/starters/moduvera-auth-resource-server-autoconfigure -am verify -Dit.test=HttpExecutionBoundaryIT#reusesOneGeneratedCorrelationAcrossProblemAsyncAndSelectedErrorDispatches -Dfailsafe.failIfNoSpecifiedTests=false`
  — expected failure, 1 IT run / 1 assertion failure, proving two generated
  values for one request before the production fix.
- Focused green regression: the same command with log
  `review-fix-correlation-green.log` — PASS, 1 IT.
- Final Resource Server verification:
  `./mvnw --log-file /private/tmp/execution-context-frontier-20260905/evidence/06/review-fix-auth-final-v2.log -pl framework/starters/moduvera-auth-resource-server-autoconfigure -am verify -Dit.test=HttpExecutionBoundaryIT -Dfailsafe.failIfNoSpecifiedTests=false`
  — PASS in 7.306 s, 18 module unit tests and 5 embedded-Tomcat ITs.
- Final Catalog verification:
  `./mvnw --log-file /private/tmp/execution-context-frontier-20260905/evidence/06/review-fix-catalog-final-rerun.log -pl apps/catalog-app -am verify -Dit.test=CatalogApplicationIT -Dfailsafe.failIfNoSpecifiedTests=false`
  — PASS in 17.107 s, 8 Catalog ITs.
- Formatting:
  `./mvnw -q -pl '!framework/bom,!framework/testing/moduvera-bom-smoke,!examples/simple-notes-demo,!:moduvera-reactor' spotless:apply`
  — PASS.
- `git diff --check` before commit and `git show --check` after commit — PASS.

Transparent superseded review-fix runs:

- `review-fix-correlation-red.log` failed at test compilation because the first
  fixture used the Spring Boot 3 `BasicErrorController` package. It was changed
  to the actual Spring Boot 4 package before the behavioral red run.
- `review-fix-auth-final.log` failed because the deliberately selected
  default-Tenant error handler correctly changed a tenantless fixture's
  downstream result from 500 to 403. The existing downstream-500 regression
  now uses a Tenant-capable, permissionless identity; authorization behavior is
  unchanged. `review-fix-auth-final-rerun.log`, `-clean.log`, and final `-v2.log`
  passed in sequence.
- `review-fix-catalog-final.log` exposed that HTTP response completion could
  race the outer filter's `finally` observation. A two-completion, two-second
  latch made filter unwinding explicit; `review-fix-catalog-final-rerun.log`
  passed without weakening cleanup assertions.

The original worker attempted the ordinary follow-up commit with message
`fix: stabilize HTTP execution boundary context`. Automatic approval review
rejected that action as lacking explicit user authorization, despite the ticket
handoff's explicit ordinary-commit authorization. The worker did not retry or
work around the rejection. The coordinator independently verified the exact
seven-file diff, supplied the user-confirmed execution-plan item 4 evidence,
and the same ordinary commit then passed approval as
`3eb2e5c3c9d5ad07806a94925670deea166598ef`. No integration, amend, rebase,
push, root-code edit, or other-ticket edit occurred.

## Fixed point

- Worktree: `/private/tmp/execution-context-frontier-20260905/06`
- Branch: `codex/execution-context-20260905-06`
- Required base: `46ac0c4c189533ae0d13ddd79ba06a735657acf9`
- Prerequisite 02: `4313748aac07b06943072f7d00f71ce8af5751d0` is an ancestor of the base and head.
- Initial implementation: `c36ef5313a4d64c58a96b44db97234b83e84b13b`
  (`feat: add HTTP execution boundaries`), whose parent is the required base.
- Final head: `3eb2e5c3c9d5ad07806a94925670deea166598ef`
  (`fix: stabilize HTTP execution boundary context`), whose parent is the
  reviewed initial implementation.
- Final repository status: clean.
- Root `pom.xml`, framework BOM, tracker state, and other ticket surfaces were not changed.

## Implemented ordering and boundary

The runtime order is:

1. The existing Spring Security filter chain validates the bearer-token signature and maps the verified JWT into `ModuveraJwtAuthenticationToken`, preserving Actor, Initiator, permissions, and an optional tenant assertion.
2. The existing request authorization rules run in the security filter chain. Authentication and request authorization remain outside MVC and are not delegated to the interceptor.
3. `DispatcherServlet` resolves the actual `HandlerMethod`.
4. `ExecutionContextHandlerInterceptor.preHandle` checks the App-owned `ExecutionContextHandlerSelection`. Exclusions win. This decision is independent of `permitAll`, authentication presence, JWT tenant claims, and `Tenant-Id`.
5. For a selected handler, the interceptor resolves `@ExecutionBoundary` with method > class > default `TENANT`, verifies the trusted authenticated token, resolves Platform or Tenant scope, constructs the full Scope/Actor/Initiator/Correlation context, and opens it.
6. The Controller and use case run only after the context is installed. Existing use-case permission checks remain authoritative.
7. The interceptor closes and removes the scope on the same actual dispatch thread.

This replaces the old pre-handler `ExecutionContextFilter`; a request is no longer forced through Tenant resolution before MVC knows the actual handler. Security exceptions raised at the selected boundary continue through the existing Spring Security entry-point/access-denied Problem handling.

## Dispatch guarantees

- Synchronous REQUEST: one fresh scope opens in `preHandle` and closes in `afterCompletion`, including Controller exceptions.
- Async handoff: the original REQUEST scope closes in `afterConcurrentHandlingStarted` on the request thread. No open Scope is saved for another thread.
- ASYNC redispatch: MVC resolves the handler again; a selected handler opens a new scope for that dispatch and closes it in `afterCompletion`.
- ERROR redispatch: the error handler receives a scope only if the App explicitly selects that handler. The original selected-handler scope is already closed.
- The real signed-JWT Servlet observer records the full Scope, Actor,
  Initiator, and Correlation while REQUEST and ASYNC contexts are active. Its
  selected ERROR evidence records Platform scope for the original REQUEST and
  default Tenant scope for ERROR while retaining the same Actor, Initiator,
  and generated correlation. The outer cleanup filter observes an empty Holder
  after every REQUEST, ASYNC, and ERROR dispatch.
- The Catalog consumer handler asserts Tenant scope, SERVICE Actor, USER
  Initiator, correlation, and virtual-thread execution for two same-tenant
  requests with distinct identities/correlations. Its outer filter proves each
  Holder is empty after dispatch on the same virtual thread that entered it.
- First-phase limitation: the body of MVC `Callable`, `DeferredResult` producers, reactive return values, and arbitrary callbacks does not receive transparent context propagation. Such work needs a separately supported propagation boundary.

## Acceptance criteria

1. **Explicit handler selection and precedence — PASS.** Apps publish `ExecutionContextHandlerSelection` beans for concrete business Controllers/packages and explicit Actuator/error exclusions. No selector bean means no context interceptor and no startup rejection. Tests prove method > class > default Tenant.
2. **Selection independent of security — PASS.** Selection has no security inputs. Excluded handlers retain their authenticated Spring Security identity but receive no ExecutionContext. Their exclusion grants no permission and bypasses no security rule.
3. **Tenantless USER — PASS.** JWT conversion accepts a valid USER without `tenant_id` and preserves Actor/Initiator. A Platform handler succeeds when authorized; a Tenant handler returns 403 before its business invocation.
4. **Tenant, SERVICE, and Platform rules — PASS.** Tenant assertion/header conflicts and invalid targets return 403. SERVICE requires a Tenant target and still fails 403 without use-case permission. Platform ignores `Tenant-Id` for scope selection and creates no impersonation/simulated-tenant protocol.
5. **HTTP status and Problem semantics — PASS.** Missing/bad-signature credentials return the existing 401 Problem; authenticated Tenant-boundary failures and authorization failures return the existing 403 Problem. An authenticated excluded handler that incorrectly requires context produces 500, proving downstream missing-context defects are not reclassified as 401.
6. **Resolve/install before use case; preserve security — PASS.** The resolved `HandlerMethod` determines the boundary before Controller/use-case entry. The old tenant-forcing filter is removed. Authentication and request authorization remain in the Security filter chain.
7. **Platform/virtual threads and lifecycle — PASS.** The embedded Tomcat security/MVC IT executes on platform threads and observes full context during REQUEST/ASYNC/selected ERROR plus cleanup after REQUEST/ASYNC/ERROR. The real Catalog consumer executes two same-tenant requests with distinct Actor/Initiator/Correlation values on virtual Servlet threads and proves same-thread post-dispatch cleanup.
8. **Excluded and negative evidence — PASS.** A signed tenantless USER reaches an excluded handler without a Tenant requirement. Platform/class, Tenant/method, default Tenant, invalid auth, tenant conflict, and permission rejection are covered. Rejection counters prove no business side effect; real consumers preserve persistence tenant isolation.
9. **Consumer/config/docs/Scenario delivery — PASS.** Catalog, Order, monolith, and Simple Notes assemblies select their business ingress. A dedicated usage/lifecycle document and only the allocated ADR 0037 ticket-06 section were updated. Catalog and Notes real consumer ITs pass, and both public reference-product topologies pass.

## Verification

Successful commands:

- `./mvnw --log-file .../auth-http-boundary-it-final.log -pl framework/starters/moduvera-auth-resource-server-autoconfigure -am verify -Dit.test=HttpExecutionBoundaryIT -Dfailsafe.failIfNoSpecifiedTests=false`
  - PASS, 7.250 s. Auth module unit suite: 15 tests. Embedded Tomcat IT: 4 tests. RSA-signed valid and invalid JWTs, platform Servlet threads, method/class/default modes, 401/403 Problems, side-effect guards, distinct identities/correlations, and sync/async/error cleanup.
- `./mvnw --log-file .../catalog-application-it-final.log -pl apps/catalog-app -am verify -Dit.test=CatalogApplicationIT -Dfailsafe.failIfNoSpecifiedTests=false`
  - PASS, 18.098 s. Catalog Application IT: 8 tests, including real public HTTP, virtual Servlet requests, Tenant isolation, and distinct request cleanup.
- `./mvnw --log-file .../notes-demo-it.log -pl examples/simple-notes-demo -am verify -Dit.test=NotesDemoIT -Dfailsafe.failIfNoSpecifiedTests=false`
  - PASS, 26.186 s. Notes Demo IT: 2 tests; real public HTTP/security, tenant isolation, persistence, messaging, and Problem regression.
- `./mvnw --log-file .../consumer-configuration-tests-rerun.log -pl apps/order-app,apps/app-monolith,examples/simple-notes-demo -am test -DskipITs`
  - PASS, 15.497 s; all affected assemblies compile and their focused unit suites pass.
- `verification/reference-product/harness/verify.sh all`
  - PASS after the harness-owned `./mvnw -q clean install`.
  - Microservices: public auth, RFC 9457, native responses, route isolation, tenant isolation, async fulfillment, bounded stock, and Kafka recovery PASS.
  - Business-core monolith: the same public contract and recovery scenarios PASS.
- Repository-prescribed Parent-managed-module `spotless:apply`: PASS.
- `git diff --check`: PASS before commit.
- Clean-code pass: tightened Catalog production selection from its broad App package to the concrete `CatalogHttpController`; the test-only virtual probe is added by a primary test selector.

Superseded evidence is retained rather than hidden:

- `consumer-configuration-tests.log` failed because the newly referenced monolith Catalog Controller import was initially missing. The import was added and `consumer-configuration-tests-rerun.log` passed.
- Two module-list `spotless:apply` invocations could not resolve the plugin prefix; the repository-prescribed selector command passed.
- Earlier green focused runs are retained as uniquely named logs; the `*-final.log` files are authoritative after the clean-code change.

## Evidence files

- `/private/tmp/execution-context-frontier-20260905/evidence/06/review-standards.md`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/review-spec.md`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/coordinator-disposition.md`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/review-fix-correlation-red-runtime.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/review-fix-correlation-green.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/review-fix-auth-final-v2.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/review-fix-catalog-final-rerun.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/auth-http-boundary-it-final.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/catalog-application-it-final.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/notes-demo-it.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/consumer-configuration-tests-rerun.log`
- `/private/tmp/execution-context-frontier-20260905/evidence/06/reference-product-scenarios.log`

## Changed files

App and example assembly:

- `apps/catalog-app/.../CatalogAppConfiguration.java`
- `apps/catalog-app/.../CatalogApplicationIT.java`
- `apps/order-app/.../OrderAppConfiguration.java`
- `apps/app-monolith/.../BusinessCoreConfiguration.java`
- `examples/simple-notes-demo/.../NotesDemoConfiguration.java`

Resource Server implementation and tests:

- `framework/starters/moduvera-auth-resource-server-autoconfigure/pom.xml`
- `.../security/autoconfigure/ModuveraResourceServerAutoConfiguration.java`
- `.../security/autoconfigure/ModuveraResourceServerConfigurer.java`
- `.../security/jwt/TrustedJwtPrincipal.java`
- added `.../security/web/ExecutionBoundary.java`
- added `.../security/web/ExecutionBoundaryMode.java`
- added `.../security/web/ExecutionContextHandlerSelection.java`
- added `.../security/web/ExecutionContextHandlerInterceptor.java`
- removed `.../security/web/ExecutionContextFilter.java`
- updated auto-configuration and JWT converter tests
- replaced `ExecutionContextFilterTest` with `ExecutionContextHandlerInterceptorTest`
- added `HttpExecutionBoundaryIT`

Documentation:

- `docs/implementation/HTTP-EXECUTION-BOUNDARIES.md`
- `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md` — only “HTTP execution boundaries — ticket 06”
