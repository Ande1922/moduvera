# Ticket 06 Standards closure review — HTTP execution boundaries

## Fixed point

- Base: `46ac0c4c189533ae0d13ddd79ba06a735657acf9`
- Head: `3eb2e5c3c9d5ad07806a94925670deea166598ef`
- Commits in range:
  - `c36ef53 feat: add HTTP execution boundaries`
  - `3eb2e5c fix: stabilize HTTP execution boundary context`
- Review axis: Standards only, full base-to-head range
- Checkout status during review: clean

## Result

Standards closure review completed with **0 open findings** and **0 new findings**.

## Accepted finding disposition

### Original Standards P2 — complete virtual-thread lifecycle and identity evidence

**Resolved.**

`CatalogApplicationIT.establishesAndClearsTenantContextOnVirtualHttpThreads`
now sends two same-tenant requests with distinct SERVICE Actors, USER
Initiators, and correlations and asserts the complete Tenant execution context
inside the real HTTP handler (`CatalogApplicationIT.java:246-269`,
`:436-456`). Its outer Servlet filter records the request and completion thread,
virtual-thread state, dispatcher type, and Holder state after the downstream
chain returns (`:488-525`). A two-completion latch prevents response delivery
from racing the post-dispatch assertion. The focused final Catalog log records
8 ITs passing and `BUILD SUCCESS`.

## Correlation behavior review

The accepted correlation fix is sound on the Standards axis.

- `DefaultRequestCorrelationIdResolver` still honors an already-established
  request attribute, otherwise accepts a valid header or generates a UUID, and
  now stores that first resolved value in the shared request attribute before
  returning it (`DefaultRequestCorrelationIdResolver.java:13-23`). This gives
  the Controller context, security Problem writer, and later selected
  redispatches one request identity without changing the App-provided resolver
  seam.
- Unit tests cover accepted-header persistence, generated-value persistence and
  reuse, and custom resolver bean precedence.
- The RSA-signed no-header runtime regression observes full Scope, Actor,
  Initiator, and correlation for REQUEST/ASYNC/selected ERROR dispatches and
  verifies the 403 Problem reuses the Controller correlation
  (`HttpExecutionBoundaryIT.java:189-246`). The preserved pre-fix red log shows
  the two differing generated IDs; the focused green and final auth logs pass.
- Selecting `BasicErrorController` exists only in the test App and intentionally
  exercises default-Tenant ERROR redispatch behavior. Production App selections
  and authentication/authorization boundaries are unchanged.

## Full-range Standards conclusion

The full range preserves Spring Security ownership of JWT verification and
request authorization, installs context only after the resolved managed
`HandlerMethod`, preserves Tenant/Platform and 401/403 semantics, and closes
each Scope on its actual dispatch thread. The changed tests now substantiate
platform and virtual request lifecycle, REQUEST/ASYNC/selected ERROR identity,
post-dispatch absence, same-tenant distinct identities, and pre-side-effect
denials. App selections remain limited to their concrete business HTTP ingress;
no selection was required merely because a module is an App.

Evidence inspected:

- `review-fix-correlation-red-runtime.log`: expected behavioral red, 1 IT / 1
  assertion failure from mismatched generated IDs.
- `review-fix-correlation-green.log`: 1 focused IT PASS.
- `review-fix-auth-final-v2.log`: 18 Resource Server unit tests and 5
  `HttpExecutionBoundaryIT` tests PASS; `BUILD SUCCESS`.
- `review-fix-catalog-final-rerun.log`: 8 `CatalogApplicationIT` tests PASS;
  `BUILD SUCCESS`.
- Prior Notes 2-IT and both-topology Scenario logs remain green and their
  covered source surfaces were not changed by the closure commit.
- `git diff --check` for the full range and `git show --check` for the closure
  commit are clean.

No repository files, commits, gates, tracker state, or Spec-axis closure
evidence were changed or produced by this review.
