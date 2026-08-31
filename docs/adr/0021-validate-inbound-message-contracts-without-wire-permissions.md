---
status: accepted
---

# Validate inbound message contracts without wire permissions

Inbound consumers validate the expected message kind, type, source, destination, and required envelope fields before invoking a handler. The envelope carries no authorization permissions, and the current trusted-internal Kafka scope adds no message signature or stronger producer-authentication protocol; source validation is contract validation rather than cryptographic identity proof.
