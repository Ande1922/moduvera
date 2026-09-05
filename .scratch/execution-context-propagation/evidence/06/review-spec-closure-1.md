# Ticket 06 Spec-axis closure review 1

- Fixed base: `46ac0c4c189533ae0d13ddd79ba06a735657acf9`
- Initial reviewed head: `c36ef5313a4d64c58a96b44db97234b83e84b13b`
- Closure head: `3eb2e5c3c9d5ad07806a94925670deea166598ef`
- Reviewed range: `46ac0c4c189533ae0d13ddd79ba06a735657acf9...3eb2e5c3c9d5ad07806a94925670deea166598ef`
- Axis: Spec only; Standards closure remained independent.
- Result: **clean — 0 open findings; worst severity: none.**

## Accepted finding dispositions

### Resolved — generated correlation is stable for the Servlet request

The accepted P2 required one full context and one Problem correlation across a logical HTTP request, including redispatches when no `X-Correlation-Id` is supplied.

- `framework/starters/moduvera-auth-resource-server-autoconfigure/src/main/java/io/github/ande1922/moduvera/security/web/DefaultRequestCorrelationIdResolver.java:13-24` now stores the first accepted header or generated UUID in the shared request attribute and returns that established value thereafter.
- `framework/starters/moduvera-auth-resource-server-autoconfigure/src/test/java/io/github/ande1922/moduvera/security/web/DefaultRequestCorrelationIdResolverTest.java:21-40` covers accepted-header establishment and generated-value reuse.
- `framework/starters/moduvera-auth-resource-server-autoconfigure/src/test/java/io/github/ande1922/moduvera/security/web/HttpExecutionBoundaryIT.java:189-246` uses real signed JWT requests without a correlation header and proves the Controller context matches the 403 Problem, REQUEST/ASYNC reuse one correlation, REQUEST/selected-ERROR reuse one correlation, full identity is present, and every dispatch exits empty.
- `docs/implementation/HTTP-EXECUTION-BOUNDARIES.md:59-71` now states the default and custom-resolver request-stability contract.

The runtime red log records the pre-fix Controller/Problem mismatch; the focused green and final Resource Server logs pass after the production fix.

### Resolved — T04 now observes full virtual-thread and redispatch identity

The accepted P2 required each covered Servlet scenario to assert ExecutionScope, Actor, Initiator, Correlation, and post-dispatch Holder state.

- `framework/starters/moduvera-auth-resource-server-autoconfigure/src/test/java/io/github/ande1922/moduvera/security/web/HttpExecutionBoundaryIT.java:189-246` asserts full identity while REQUEST, ASYNC, and selected ERROR dispatch contexts are active. The observer at `:376-395` runs after the context interceptor, and the cleanup probe at `:544-573` records absence after each real dispatch.
- `apps/catalog-app/src/test/java/io/github/ande1922/moduvera/reference/app/catalog/CatalogApplicationIT.java:246-269` sends two same-tenant virtual-thread requests with distinct SERVICE Actors, USER Initiators, and correlations and asserts the complete returned values.
- The Catalog cleanup evidence at `apps/catalog-app/src/test/java/io/github/ande1922/moduvera/reference/app/catalog/CatalogApplicationIT.java:460-525` uses a bounded completion latch and records that each REQUEST completes on the same virtual thread with an empty Holder.
- `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md:131-137` now accurately names the strengthened virtual-thread, generated-correlation, and redispatch evidence.

## Full-range regression review

The complete two-commit range still satisfies ticket 06's handler selection, method/class/default precedence, tenantless USER Platform access, Tenant 403 behavior, SERVICE target and permission separation, existing 401/403 Problem shape, use-case-before-side-effect denial, per-dispatch Scope lifecycle, explicit consumer assembly, compatibility boundary, and documented exclusions. The closure commit changes one production seam, its tests, and evidence wording; it does not expand the supported boundary.

No new missing requirement, incorrect behavior, scope creep, or acceptance-evidence gap was found on the Spec axis.

## Evidence validation

- `review-fix-correlation-red-runtime.log`: expected behavioral RED, 1 IT / 1 assertion failure, showing distinct generated Controller and Problem correlations before the fix.
- `review-fix-correlation-green.log`: PASS, focused regression.
- `review-fix-auth-final-v2.log`: PASS, 18 Resource Server unit tests and 5 embedded-Tomcat ITs.
- `review-fix-catalog-final-rerun.log`: PASS, 8 Catalog ITs.
- Full-range `git diff --check`: PASS; closure head and worktree status matched the fixed review input.

No runtime suite was repeated during this read-only closure review.
