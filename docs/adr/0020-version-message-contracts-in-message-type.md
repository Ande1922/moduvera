---
status: accepted
---

# Version message contracts in Message Type, not Destination

The canonical Message Type carries the breaking-contract major version, such as `.v1`; compatible field additions retain that type, while breaking changes introduce a new type and use consumer-first rollout. Logical Destinations and Broker Topics remain stable and version-free, so routing identity does not become a second message-version system. Physical Kafka topics must use lowercase, hyphen-separated names; startup validation and the repository architecture gate reject dots, underscores, uppercase characters, malformed separators, and `v<number>` segments.
