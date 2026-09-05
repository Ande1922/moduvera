# Standards review closure 1 — ticket 10 tenant-only message compatibility

- Fixed base: `928e8f2ca1c86668dbbed699de4bd63bd97d1ed8`
- Fixed head: `73edf47d28ecb5b4e86af485684121635411f0ce`
- Previous reviewed head: `eae6c185c661f0ae75595f719930e61ebfa758f5`
- Range: two commits, 12 changed files, clean checkout; merge base equals the fixed base and `HEAD` equals the fixed head.
- Axis: Standards only
- Accepted finding disposition: fixed
- New findings: 0
- Worst severity: clean

## Accepted finding closure

### Fixed — [P2] Do not mark the trusted-producer boundary verified without broker authentication and ACL evidence

The documentation-only closure changes the ticket-10 status in
`docs/adr/0037-use-explicit-restorable-execution-context-snapshots.md` to name only the verified
tenant-only envelope, reference-consumer contract, context-restoration, and runtime-delivery
surfaces. It explicitly marks broker producer authentication and destination ACL enforcement as
deployment prerequisites outside this ticket's evidence. The same ADR section now states that
source and contract matching are not producer authentication and that the plaintext
Testcontainers scenarios do not verify broker authentication or ACL configuration.

`docs/implementation/TENANT-ONLY-MESSAGE-CONTEXT.md` makes the operational boundary equally
explicit: the endpoint constructs a per-message Execution Context, and that envelope's Tenant,
Initiator, and correlation claims become trustworthy only after the deployment authenticates
allowed producers and enforces destination ACLs. It also distinguishes the verified plaintext
Kafka delivery/consumer behavior from the unverified broker security boundary and points to ADR
0021's rule that source matching alone is not authentication.

This resolves the original finding without adding wire permissions, signatures, another trust
protocol, a broker matrix, production code, or tests.

## Full-range Standards regression review

No additional Standards finding was found across
`928e8f2ca1c86668dbbed699de4bd63bd97d1ed8...73edf47d28ecb5b4e86af485684121635411f0ce`.
The implementation still requires Tenant before outbound descriptor/Outbox construction,
preserves provider-owned wire identities and asynchronous-only API shape, reconstructs inbound
context with the consumer-local Actor, restores prior worker identity after all reviewed paths,
uses a same-partition asynchronous completion barrier, and retains real PostgreSQL/MySQL/Kafka
Adapter evidence. Only the two documentation files changed after the previous head, so the
unchanged runtime suites were inspected as existing evidence and were not repeated.

`git diff --check` for the full range passes, and the checkout remains clean at the fixed head.
