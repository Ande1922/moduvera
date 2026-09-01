---
status: accepted
---

# Map at adapters only for semantic differences

Transport adapters always own protocol mechanics such as envelopes, routing, authentication, validation, HTTP status, and error classification, but they introduce separate transport/application models only when meaning, invariants, shape, serialization, or versioning actually differs. A provider-owned, protocol-neutral command or result may pass directly between an adapter and its Application Service while those semantics remain identical; if a later protocol or contract version diverges, that adapter performs the explicit mapping.

For an asynchronous-only capability, the provider publishes versioned command and event records without adding a synchronous Java Service API method solely as an internal handler seam. The message handler invokes the Application Service directly, while a future HTTP entry defines its own contract and mapping according to its actual semantics.
