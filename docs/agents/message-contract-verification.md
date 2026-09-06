# Message-contract verification

Use this workflow when adding or changing a provider-owned message contract, an asynchronous-only capability, or a message Inbound Adapter.

## Contract identity

1. Publish framework-neutral `MESSAGE_KIND`, `MESSAGE_TYPE`, and `DESTINATION` string constants with the provider-owned contract type.
2. Ensure the provider API module is on `moduvera-architecture-testkit`'s test classpath; add a direct test-scope dependency when it is not already reachable.
3. Register the public compatibility values in `RepositoryMessageContractTest`. The repository gate discovers every API type that publishes all three fields and fails when a type is missing from the registry or a registered value changes.
4. Make producers and consumers use the provider constants. Keep source, consumer ID, execution Actor, and permissions with the policy that owns them.

This step is complete when the repository identity gate covers every discovered contract and the API module remains transport-framework independent.

## Asynchronous-only capability

Register each asynchronous-only command with `ModuveraArchitectureRules.asyncOnlyCapabilityDoesNotExposeSynchronousServiceApi`. The rule prevents a public Service API interface from accepting that command. A message Inbound Adapter invokes an independent `ApplicationMessageHandler<P>` after protocol handling; add a synchronous Service API only for a separately supported direct local or remote call.

This step is complete when the generic rule passes against current production classes and its neutral mutation fixtures still fail for renamed interfaces and arbitrary return types.

## Inbound Adapter contract

Implement `InboundMessageContractTck` in the provider's focused Inbound Adapter test and supply a fresh `InboundMessageContractProbe` for each scenario. The probe must:

- deliver the real serialized message through the configured consumer;
- observe an Application invocation and the Execution Context established during that invocation;
- use wire permissions that differ from the consumer's fixed local permissions; and
- assert the decoded provider-owned payload after successful delivery.

The shared TCK accepts the declared kind, type, source, and destination; mutates each identity independently; requires every mismatch to be rejected before an Application invocation; and verifies Tenant, Initiator, Correlation, and the consumer-owned execution Actor.

This step is complete when every changed message Inbound Adapter passes the shared TCK without duplicating its mismatch matrix.

## Application Handler and Inbox

Test each Handler through its public typed seam with scripted collaborators.
Verify that a committed precheck exits before authorization and protected
preparation, authorization precedes protected work on a miss, preparation
failure leaves no Inbox record, and mutable reads and changes occur in the
Inbox callback. The Handler owns a fixed consumer ID through `InboxTemplate`
and does not directly open a top-level transaction.

For replay recovery, first commit a real Inbox row through the production
repository and transaction adapter. Redeliver the same serialized message
through the public `Consumer<Message<byte[]>>` while the preparation
collaborator is unavailable. Require normal return, zero preparation calls,
and exactly one committed Inbox row. A stubbed `isProcessed` result alone is
not recovery evidence.

## Asynchronous negative assertions

An unchanged business count is evidence only after a consumption-completion barrier. Capture a `ProgressBarrier` immediately before sending, then require a monotonic consumer offset, Inbox marker, or unique same-partition barrier to advance. Assert the unchanged business state only after that barrier succeeds.

This step is complete when the test would time out if the message were never consumed. A condition that was already true before send is not a completion barrier.

## Evidence placement

Keep generic envelope validation, trusted-context construction, retry classification, and context cleanup in the message framework tests; the framework consumer must not open the business transaction. Verify Inbox/Outbox atomicity, persistence, concurrency, and recovery through the production runtime Adapters and real infrastructure according to ADR 0034. App tests retain only assembly, Broker delivery, and end-to-end business evidence that lower seams cannot prove.
