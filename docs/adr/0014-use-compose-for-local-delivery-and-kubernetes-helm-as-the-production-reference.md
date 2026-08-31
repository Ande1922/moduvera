---
status: open
---

# Keep the deployment baseline open

This is a historical production-deployment proposal, not a current decision. Docker Compose was proposed for local development, acceptance testing, and simple single-host deployment, while Kubernetes with a reusable service Helm chart was proposed as the production reference. The user explicitly clarified on 2026-08-30 that deployment has not been confirmed. A later explicit request authorized a disposable Compose dependency harness for the runnable reference-product acceptance; the Apps do not rely on that topology, and it must not be presented as single-host or production deployment guidance.

Historical evidence: Q417-Q418, Q422, and Q424. Q419-Q420 restate ADR 0010, Q421 remains provisional, and Q423 was withdrawn; see [the question index](../grill/question-index.md). Current status is overridden by the user's later direct clarification.
