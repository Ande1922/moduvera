---
status: accepted
---

# Use internal authentication with opaque browser tokens and internal JWT

The platform owns a closed-loop authentication service and tenant-level RBAC. Browser-facing access tokens are opaque; the gateway exchanges them once for a short-lived internal JWT issued by the sole authorization service, and every resource service validates that JWT while service identities remain distinct from user actors.

Evidence: Q15, Q20-Q39; see [the question index](../grill/question-index.md).
