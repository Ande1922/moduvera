# Tenant-only message Execution Context

Moduvera's existing business messages always belong to one concrete tenant.
The message envelope keeps `tenantid` required and does not represent Platform
or missing Execution Context. Provider-owned `MESSAGE_KIND`, `MESSAGE_TYPE`,
and `DESTINATION` values remain the compatibility identity. The wire carries
the initiating identity and correlation identifier, but it does not carry
trusted permissions.

## Outbound construction

An outbound business-message adapter reads the current Execution Context and
calls `requireTenantId()` before it constructs or appends the message. Missing
context and Platform scope therefore fail at the adapter boundary. When the
adapter is called inside the service's local transaction, the failure also
prevents an Outbox record and rolls back any earlier business change under the
existing transaction contract.

Once a valid tenant-only envelope has been constructed and committed to the
Outbox, it is self-contained. Relay, recovery, and transport code must not
require the originating request's Holder. A background relay normally runs
with no Execution Context and publishes the tenant identity already stored in
the envelope.

## Inbound reconstruction

The reliable inbound endpoint decodes the envelope and validates its required
fields, kind, type, source, and destination before Inbox admission or business
handling. For each delivery attempt it constructs a new per-message Execution
Context from:

- the envelope's required Tenant ID, Initiator, and correlation identifier;
- the consumer's configured local Actor and permissions.

The sender's wire Actor permissions are not trusted. An unrelated identity
already present on a reused listener thread is neither inherited nor a reason
to reject the message. The endpoint installs the per-message context only for
the Inbox transaction and handler call, then restores the exact previous
worker state after success, retry, duplicate delivery, or failure.

Kind, type, source, and destination matching validates the provider contract;
it does not authenticate the producer. A deployment must authenticate allowed
producers and enforce destination ACLs before treating the envelope's Tenant,
Initiator, and correlation claims as trustworthy. The plaintext Kafka
Testcontainers scenarios verify delivery and consumer behavior, but they do
not verify that broker authentication or ACL boundary. Source matching alone
is not authentication, as specified by ADR 0021.

Malformed required identity fields and contract mismatches are rejected before
the Application handler. Tests that send such a rejection asynchronously must
wait for consumer progress, such as a later message on the same Kafka
partition, before asserting that business, Inbox, and Outbox state did not
change. A count that was already unchanged before send is not a completion
barrier.

This compatibility boundary does not add Platform messages, nullable tenant
fields, wire permissions, signatures, a new producer-trust protocol, or a
synchronous Service API for asynchronous-only commands.
