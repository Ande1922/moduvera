# Shape a new Business Service

Use this recipe to turn a business capability into a reviewable service shape.
Start from named consumers and supported runtime promises. Existing services are
evidence for repository mechanics, not templates for the new capability.

## Authoritative Shape Contract

The JSON block below is the single source for the intake branches, required
shape-card fields, and artifact roles. Humans and agents apply it through
the ordered steps below; `tools/agent-delivery/delivery_contract.py` parses the
same block for forward validation. Change an architecture branch here, its
explanation, and its forward scenario together.

<!-- business-service-contract:start -->
```json
{
  "schema": 1,
  "required_description_fields": [
    "name",
    "capability",
    "owner",
    "use_cases",
    "dependencies",
    "support_promises",
    "apps",
    "acceptance_consumers",
    "decisions",
    "exclusions",
    "durable_state"
  ],
  "required_dependency_fields": ["blocked", "blocker"],
  "required_participant_fields": ["role", "name"],
  "required_promise_fields": [
    "key",
    "kind",
    "use_case",
    "participants",
    "permission",
    "app",
    "verification"
  ],
  "optional_promise_fields": ["provider_adapter"],
  "required_app_fields": [
    "name",
    "topology",
    "support_promises",
    "verification"
  ],
  "required_acceptance_fields": [
    "name",
    "topology",
    "app",
    "support_promises",
    "verification"
  ],
  "artifact_templates": {
    "provider-api": "services/{service}/{service}-api",
    "synchronous-api": "{service} synchronous Service API",
    "service": "services/{service}/{service}-service",
    "provider-adapter": "{provider_adapter}",
    "assembly": "{app} assembly",
    "message-verification": "repository message-contract verification for {promise}",
    "architecture-verification": "repository architecture rules for {promise}",
    "persistence-adapter": "{service} persistence outbound adapter for {state}",
    "service-owned-migration": "{service} service-owned migration for {state}",
    "real-database-verification": "real-database verification for {state}"
  },
  "scenarios": {
    "local-direct": {
      "intent": "A named caller uses the provider-owned protocol-neutral API through an in-process Application implementation.",
      "participant_roles": ["caller"],
      "provider_adapter": "forbidden",
      "required_roles": ["provider-api", "synchronous-api", "service", "assembly", "architecture-verification"],
      "forbidden_roles": ["provider-adapter", "message-verification"],
      "artifact_roles": [
        "provider-api",
        "synchronous-api",
        "service",
        "assembly",
        "architecture-verification"
      ]
    },
    "remote-direct": {
      "intent": "A named remote caller has a supported direct call and the provider owns its declared inbound adapter.",
      "participant_roles": ["caller"],
      "provider_adapter": "required",
      "required_roles": ["provider-api", "synchronous-api", "service", "provider-adapter", "assembly", "architecture-verification"],
      "forbidden_roles": ["message-verification"],
      "artifact_roles": [
        "provider-api",
        "synchronous-api",
        "service",
        "provider-adapter",
        "assembly",
        "architecture-verification"
      ]
    },
    "http": {
      "intent": "A named HTTP caller reaches one Application use case through a provider-owned HTTP inbound adapter.",
      "participant_roles": ["caller"],
      "provider_adapter": "required",
      "required_roles": ["service", "provider-adapter", "assembly", "architecture-verification"],
      "forbidden_roles": ["synchronous-api", "message-verification"],
      "artifact_roles": [
        "service",
        "provider-adapter",
        "assembly",
        "architecture-verification"
      ]
    },
    "message-command": {
      "intent": "A named producer sends a provider-owned versioned command to a named consumer adapter; asynchronous-only support publishes no synchronous Service API.",
      "participant_roles": ["producer", "consumer"],
      "provider_adapter": "required",
      "required_roles": ["provider-api", "service", "provider-adapter", "assembly", "message-verification", "architecture-verification"],
      "forbidden_roles": ["synchronous-api"],
      "artifact_roles": [
        "provider-api",
        "service",
        "provider-adapter",
        "assembly",
        "message-verification",
        "architecture-verification"
      ]
    },
    "message-event": {
      "intent": "The provider publishes a versioned event through its outbound adapter for named consumers; event support alone publishes no synchronous Service API.",
      "participant_roles": ["producer", "consumer"],
      "provider_adapter": "required",
      "required_roles": ["provider-api", "service", "provider-adapter", "assembly", "message-verification", "architecture-verification"],
      "forbidden_roles": ["synchronous-api"],
      "artifact_roles": [
        "provider-api",
        "service",
        "provider-adapter",
        "assembly",
        "message-verification",
        "architecture-verification"
      ]
    },
    "internal": {
      "intent": "A named internal caller uses a protocol-neutral Application seam without an external API module or transport adapter.",
      "participant_roles": ["caller"],
      "provider_adapter": "forbidden",
      "required_roles": ["service", "assembly", "architecture-verification"],
      "forbidden_roles": ["provider-api", "synchronous-api", "provider-adapter", "message-verification"],
      "artifact_roles": [
        "service",
        "assembly",
        "architecture-verification"
      ]
    }
  },
  "durable_state": {
    "required_fields": [
      "name",
      "use_case",
      "transaction_boundary",
      "tenant_isolation",
      "repository_seam",
      "migration",
      "verification"
    ],
    "required_roles": [
      "persistence-adapter",
      "service-owned-migration",
      "real-database-verification"
    ],
    "artifact_roles": [
      "persistence-adapter",
      "service-owned-migration",
      "real-database-verification"
    ]
  },
  "app_topologies": ["standalone", "multi-service"],
  "acceptance_topologies": {
    "reference-product": "reference-product acceptance entry for {consumer}",
    "declared-app": "{app} acceptance entry for {consumer}",
    "consumer-contract": "consumer contract acceptance for {consumer}"
  }
}
```
<!-- business-service-contract:end -->

## 1. Complete the shape card

Record the required description fields from the Shape Contract. Each use-case
dependency names the blocked use case and its genuine prerequisite. Each support
promise names one use case, its direct or HTTP caller or message
producer/consumer, permission semantics, provider adapter when required, App
Assembly, and verification seam. Durable state names its transaction boundary,
tenant isolation, Repository seam, migration, and real-database evidence. Each
App declares its topology and startup evidence. Each acceptance consumer names
the promises and topology it accepts.

An unknown that changes a public contract, authorization, data ownership, or
supported topology is a decision, not an implementation detail. Resolve it
through the repository [delivery workflow](delivery-workflow.md). The card is
complete when every field is nonblank, identifiers and collections are unique,
every dependency is acyclic, every promise is assigned to a declared App and
acceptance consumer, and every artifact can trace to named consumers, use
cases, and support promises.

## 2. Select contract and use-case branches

Choose exactly one Shape Contract scenario for each support promise. Apply
[ADR 0004](../adr/0004-use-one-service-api-for-local-and-remote-calls.md) to
direct and asynchronous-only capabilities. For messages, follow the
[message-contract verification workflow](message-contract-verification.md)
for identity, registration, Application Handler ownership, Inbound Adapter
contracts, replay recovery, and negative-assertion barriers. Apply
[ADR 0021](../adr/0021-validate-inbound-message-contracts-without-wire-permissions.md)
at the inbound trust boundary and
[ADR 0031](../adr/0031-map-at-adapters-only-for-semantic-differences.md) before
introducing a second DTO or Mapper.

The branch is complete when every contract and message identity has a named
owner, participant, compatibility boundary, permission, assembly, and
observable verification seam.

## 3. Shape Application, Domain, and Adapters

Keep use-case orchestration in Application and business rules in Domain.
Create Domain types for actual invariants and language; a simple Application
use case does not need an empty Domain package. Each required provider adapter
records the protocol mechanics it owns and the protocol-neutral seam it invokes
or implements. Follow
[ADR 0032](../adr/0032-organize-business-services-by-module-and-adapter-direction.md).

For each message use case, create one independent Application Handler that
implements `ApplicationMessageHandler<P>` for the decoded provider-owned
payload. Give it the original `MessageId` and a fixed-consumer `InboxTemplate`.
It checks committed Inbox state before authorization and protected preparation,
then performs mutable reads, domain changes, Inbox recording, and same-database
Outbox writes as transaction-internal processing in one complete local
transaction. It does not depend directly on `TransactionBoundary`, receive a
serialized envelope, or forward the same responsibility to an empty Service.
The inbound Adapter retains envelope decoding and any justified semantic or
error mapping, while the reliable consumer transport retains validation,
trusted context, bounded retry, and cleanup.

This step is complete when each artifact role selected by the Shape Contract
has traceability and the service has no empty package, speculative Adapter, or
duplicate transport model.

## 4. Select persistence and migration

For every durable-state entry, define the Aggregate or Query Repository seam,
production persistence Outbound Adapter, transaction and tenant behavior,
service-owned migration resources, and side-effect-free migration definition.
The App or release composition owns execution policy. Follow
[ADR 0033](../adr/0033-separate-migration-definition-from-execution-policy.md)
and verify production semantics according to
[ADR 0034](../adr/0034-verify-infrastructure-with-runtime-adapters.md).

With no durable-state entry, the Shape Contract selects no persistence Adapter
or migration. Message Outbox or Inbox storage does not make platform tables
service-owned business persistence.

## 5. Select App Assemblies and acceptance

Each declared App selects only the provider slices, infrastructure adapters,
routes or bindings, trusted Execution Context entry, and migration policy
needed by its support promises. A multi-service App also selects the Local or
Remote collaborator implementations required by that assembly; it does not
recreate transport or use-case mapping. Follow
[ADR 0038](../adr/0038-retain-monolith-as-on-demand-assembly.md). Default to microservice acceptance;
select monolith adaptation and runtime evidence only for an explicit monolith
requirement. New services need not join the retained monolith assembly.

Acceptance follows the declared acceptance consumer and topology. Select the
reference-product entry only for `reference-product`; use the named App or
consumer contract entry for the other Shape Contract topologies. This step is
complete when every selected App has startup evidence, every promise has one
acceptance owner, and unselected Apps remain untouched.

## 6. Build the specification and ticket DAG

The specification carries the complete shape card, support claims, decisions,
exclusions, and test seams. Draft consumer/use-case vertical slices. Each
ticket names its outcome, support claims, acceptance criteria, test seams,
decisions, exclusions, artifacts, and only genuine blockers. Shared artifacts
retain traceability to every consuming slice. Forward validation returns the
validated shape card alongside those tickets so capability, ownership,
dependencies, App topology, durable state, and acceptance promises remain
reviewable rather than collapsing into section headings.

Select the next stage from the current route table in the
[delivery workflow](delivery-workflow.md). The linked `to-spec`, `to-tickets`,
`implement`, and `implement-frontier` Skills own their respective process,
authorization, and stopping condition.

## Completion checklist

- [ ] The Shape Contract fields are complete, safe, unique, and traceable.
- [ ] Every support promise selects one scenario and names participants,
  permission, App, adapter when required, verification, and acceptance owner.
- [ ] Asynchronous-only and internal-only promises select no synchronous API;
  internal-only promises select no external API module or transport Adapter.
- [ ] Durable state has transaction, tenancy, Repository, migration, and real
  adapter evidence, or the state collection is explicitly empty.
- [ ] Each selected App and acceptance topology has its declared evidence;
  reference-product acceptance appears only when selected.
- [ ] Every vertical ticket contains support claims, acceptance criteria, test
  seams, decisions, exclusions, traceable artifacts, and precise blockers,
  consistent with
  [ADR 0012](../adr/0012-adopt-layered-testing-with-independent-black-box-acceptance-tests.md).
- [ ] The output is a specification and proposed ticket DAG. Code creation
  begins only through an authorized implementation ticket; this recipe emits
  no archetype, copied template, wizard, or fixed directory manifest.
