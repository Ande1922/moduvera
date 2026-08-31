---
status: accepted
---

# Use native HTTP semantics and service-owned API versions

Successful HTTP responses remain unwrapped, failures use real status codes and a safe RFC 9457 Problem Details body, and breaking major versions remain in the path handled by the service rather than being erased at the gateway. Internal routes are not publicly exposed but still authenticate and authorize, and compatibility is verified for the supported rolling-upgrade window.

Evidence: Q331-Q353, with the later Q344 error shape superseding Q57; see [the question index](../grill/question-index.md).
