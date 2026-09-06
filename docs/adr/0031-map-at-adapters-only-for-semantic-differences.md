---
status: accepted
---

# Map at adapters only for semantic differences

Transport adapters always own protocol mechanics such as envelopes, routing, authentication, validation, HTTP status, and error classification, but they introduce separate transport/application models only when meaning, invariants, shape, serialization, or versioning actually differs. A provider-owned, protocol-neutral command or result may pass directly between an adapter and its application use-case boundary while those semantics remain identical; if a later protocol or contract version diverges, that adapter performs the explicit mapping.

For an asynchronous-only capability, the provider publishes versioned command and event records without adding a synchronous Java Service API method solely as an internal handler seam. The message Inbound Adapter decodes or maps the provider contract and invokes the independent typed Application Handler that owns the message use case, while a future HTTP entry defines its own contract and mapping according to its actual semantics.
