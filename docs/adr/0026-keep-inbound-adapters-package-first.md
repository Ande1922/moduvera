---
status: accepted
---

# Keep inbound adapters package-first in the Business Service artifact

Controller and message-Consumer adapters remain in the existing `*-service` ordinary Jar, separated into explicit inbound-adapter packages and independently importable Spring configuration slices. App Assemblies activate the required slices through explicit build-time composition. Package-level architecture rules keep Service API, Domain and Application code independent from transport frameworks even though the concrete adapters share the Business Service artifact.

Do not create one Maven artifact per HTTP or Kafka adapter while only one real implementation exists. If a second real transport implementation appears, extract the varying adapters behind the already-proven seam rather than introducing speculative module surfaces now. Topology-varying outbound adapters may still require separate selection because Local and remote implementations already both exist.

Evidence: explicitly selected by the user during the 2026-08-31 App Assembly review.
