# Spec-axis closure review 1 — ticket 10 tenant-only message compatibility

- Role: independent Spec reviewer
- Fixed base: `928e8f2ca1c86668dbbed699de4bd63bd97d1ed8`
- Fixed head: `73edf47d28ecb5b4e86af485684121635411f0ce`
- Prior implementation head: `eae6c185c661f0ae75595f719930e61ebfa758f5`
- Range validation: base is an ancestor of head; `HEAD` equals the supplied head; worktree is clean; the full range contains `eae6c18 feat: preserve tenant-only message context` followed by `73edf47 docs: clarify tenant message trust verification limits`
- Closure delta: exactly two documentation files, `docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md` and `docs/implementation/TENANT-ONLY-MESSAGE-CONTEXT.md`; no source, test, configuration, protocol, provider contract, migration, or tracker change
- Axis: Spec only. The Standards closure report was not read or reranked.

## Result

**Clean — 0 Spec findings. Worst severity: none.**

The full fixed range still satisfies ticket 10. The closure commit corrects the support/evidence boundary without removing an acceptance criterion, weakening required runtime behavior, or expanding the ticket into a new trust protocol.

## Full-range regression assessment

1. The ticket requires the inbound boundary to retain contract-field and trusted-producer-boundary validation while adding “不新增……更强信任协议” and excluding a “新消费授权协议” (`10-tenant-only-message-compatibility.md:22-25,38-40`). ADR 0021 states that source validation is contract validation rather than producer authentication. The closure now says exactly that at `TENANT-ONLY-MESSAGE-CONTEXT.md:41-47` and `0037-use-explicit-restorable-execution-context-snapshots.md:171-176`. This clarifies the trust precondition; it does not reduce kind/type/source/destination validation or per-message context reconstruction.

2. The ticket's implemented behavior remains represented without change: concrete-Tenant outbound construction and no empty-tenant Outbox (`TENANT-ONLY-MESSAGE-CONTEXT.md:12-17`), relay delivery after the originating Holder closes (`:19-23`), reconstruction from envelope Tenant/Initiator/Correlation plus consumer-local Actor/permissions (`:27-39`), completion-barrier guidance for asynchronous rejection (`:49-54`), and the explicit no-platform-message/no-wire-permission/no-new-protocol/no-synchronous-API boundary (`:56-58`). The ADR's ticket-10 section retains the same behavior and evidence at `0037-use-explicit-restorable-execution-context-snapshots.md:165-185`.

3. The refined ADR status at `0037-use-explicit-restorable-execution-context-snapshots.md:161-163` accurately limits “verified” to tenant-only envelope compatibility, reference-consumer contract handling, context restoration, and runtime Adapter delivery. Broker producer authentication and destination ACL enforcement are identified as deployment prerequisites outside the plaintext Testcontainers evidence. This is consistent with the repository integration-contract guidance and the ticket exclusion against introducing a stronger trust protocol.

4. The implementation commit and all previously reviewed acceptance evidence remain unchanged. The existing external logs continue to cover PostgreSQL Outbox relay (6 tests), PostgreSQL/Kafka inventory flow (8 tests), MySQL messaging JDBC (8 tests), PostgreSQL/Kafka order flow (7 tests), and architecture/message-contract checks (20 tests), each with zero failures/errors/skips and `BUILD SUCCESS`. Because the closure changes only explanatory documentation, no runtime assertion was invalidated or requires reinterpretation.

5. Full-range scope remains bounded to the existing publishers, shared TCK/consumer tests, runtime Adapter tests, the ticket-10 ADR section, and the tenant-only guide. The full diff still adds no synchronous API, platform message, wire permission/signature, protocol DTO/conversion, provider identity change, Job/Lock behavior, broker/ORM matrix expansion, or unrelated product surface.

## Evidence notes

- Rechecked the clean fixed head, full base-to-head file list, both commits, the exact documentation closure delta, ticket acceptance criteria/exclusions, ADR 0021, and the refreshed worker report.
- Reused the unchanged runtime logs from the implementation head. No additional tests were run because the closure contains no executable or configuration change and no Spec evidence gap emerged.
