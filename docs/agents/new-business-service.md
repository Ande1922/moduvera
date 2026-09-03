# Shape a new Business Service

Use this recipe to turn a business capability into a reviewable service shape.
Start from named consumers and supported runtime promises. Existing services are
evidence for repository mechanics, not templates for the new capability.

## 1. Complete the shape card

Record each item before proposing modules or files:

- the capability, its business owner, language, use cases, and permission
  semantics;
- every direct caller, message producer or consumer, public HTTP caller, and
  purely internal Application caller;
- durable state, transaction boundaries, queries, publications, and external
  dependencies;
- each standalone or multi-service App Assembly that will support the
  capability; and
- the observable Domain, Application, Adapter, assembly, architecture, and
  acceptance evidence for that support.

An unknown that changes a public contract, data ownership, or supported
topology is a decision, not an implementation detail. Resolve it through the
repository [delivery workflow](delivery-workflow.md) before continuing. The
shape card is complete when every proposed artifact can name the consumer or
support promise that requires it.

## 2. Select the contract and use-case shape

| Need | Shape |
| --- | --- |
| Supported direct Local or Remote call | Publish a protocol-neutral API contract. The Application Service supplies the Local implementation; add a Remote client only for a declared remote consumer. |
| Asynchronous-only command or event | Publish provider-owned, versioned records in the provider API module and let the message Inbound Adapter invoke the Application Service. A synchronous Java `*Api` method exists only for a separately supported direct call. |
| Purely internal use case | Keep a protocol-neutral Application seam inside the service. Introduce no API module or transport Adapter until a named external consumer requires one. |

Apply [ADR 0004](../adr/0004-use-one-service-api-for-local-and-remote-calls.md)
to direct and asynchronous-only capabilities. For messages, follow the
[message-contract verification workflow](message-contract-verification.md)
for identity, asynchronous-only registration, Inbound Adapter contracts, and
negative-assertion barriers. Apply
[ADR 0021](../adr/0021-validate-inbound-message-contracts-without-wire-permissions.md)
at the inbound trust boundary and
[ADR 0031](../adr/0031-map-at-adapters-only-for-semantic-differences.md) before
introducing a second DTO or Mapper.

The contract shape is complete when every API method and message record has a
named consumer, owner, compatibility boundary, and observable verification
seam.

## 3. Shape Application, Domain, and Adapters

Keep the use-case orchestration in Application and business rules in Domain.
Create Domain types for actual invariants and language; a simple Application
use case does not need an empty Domain package. Place Inbound and Outbound
Adapters only at declared protocol or infrastructure boundaries, following
[ADR 0032](../adr/0032-organize-business-services-by-module-and-adapter-direction.md).

For each Adapter, record:

1. the consumer or collaborator that requires it;
2. the protocol mechanics it owns;
3. the protocol-neutral seam it invokes or implements; and
4. the focused contract or integration test that proves it.

This step is complete when the service module contains no empty package,
unused configuration slice, speculative Adapter, or duplicate transport model.

## 4. Select persistence and migration

With durable business state, define the Aggregate or Query Repository seam,
the production persistence Outbound Adapter, and the local transaction and
tenant-isolation behavior. The service owns its migration resources and a
side-effect-free migration definition; the App or release composition owns
execution policy. Follow
[ADR 0033](../adr/0033-separate-migration-definition-from-execution-policy.md).

Without durable state, omit the persistence Adapter and migration definition.
Message Outbox or Inbox storage does not by itself turn platform tables into
service-owned business persistence.

Persistence planning is complete when every table belongs to an owner and
production Adapter, migration, rollback-safe forward path, and real-database
test are accounted for. Infrastructure evidence follows
[ADR 0034](../adr/0034-verify-infrastructure-with-runtime-adapters.md).

## 5. Select App Assemblies

- A **standalone App** selects the service's required module and Inbound
  configuration slices, infrastructure Adapters, routes or bindings, trusted
  Execution Context entry, and migration definition or release policy.
- A **multi-service App** selects the same provider-owned slices plus the Local
  or Remote collaborator implementations required by that assembly. It does
  not recreate Controller, message-handler, or use-case mapping semantics.

Select only assemblies named by the support goal. Build-time composition and
the supported topology boundary follow
[ADR 0022](../adr/0022-support-both-application-topologies.md). This step is
complete when every selected App has a startup or composition test and no
unselected App changes merely for symmetry.

## 6. Build the specification and ticket DAG

The specification records the completed shape card, behavior, contracts,
test seams, decisions, exclusions, and support claims. Tickets are vertical,
independently verifiable slices; each proposed module or file states its
`required by` consumer or support promise. Typical dependency boundaries are:

1. provider contracts required by direct or message consumers;
2. Application and Domain behavior;
3. persistence plus service-owned migration, when state is durable;
4. declared Inbound and Outbound Adapters;
5. selected App Assembly and public acceptance evidence; and
6. repository architecture and compatibility registration.

Use the existing `to-spec`, `to-tickets`, `implement`, or
`implement-frontier` stage selected by the
[delivery workflow](delivery-workflow.md); those Skills remain the source of
truth for tracker shape, implementation, review, and authorization.

## Completion checklist

- [ ] Capability, owner, use cases, consumers, permissions, state, and App
  Assemblies are explicit.
- [ ] Direct, asynchronous-only, and internal-only seams follow their selected
  branches; every public contract has a named consumer.
- [ ] API, Application, Domain, and Adapter artifacts have a `required by`
  reason; there are no empty or speculative modules.
- [ ] Persistence either has a production Adapter, service-owned migration,
  and real-infrastructure evidence, or is explicitly absent.
- [ ] Message identities and Inbound Adapters use the message-contract
  verification workflow.
- [ ] Each selected App has assembly evidence; unselected Apps remain
  untouched.
- [ ] Domain/Application tests, production Adapter integration tests,
  architecture rules, and applicable public acceptance entry are assigned to
  tickets, consistent with
  [ADR 0012](../adr/0012-adopt-layered-testing-with-independent-black-box-acceptance-tests.md).
- [ ] The output is a specification and ticket DAG. Code creation begins only
  through an authorized implementation ticket; no archetype, template copy,
  wizard, or fixed directory manifest is produced by this recipe.
