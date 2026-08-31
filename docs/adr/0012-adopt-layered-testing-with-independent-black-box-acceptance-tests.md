---
status: accepted
---

# Adopt layered testing with independent black-box acceptance tests

Domain and Application behavior is protected by fast unit tests, infrastructure adapters by real-component integration tests, and critical cross-service flows by a separately managed black-box acceptance suite. Acceptance tests drive the running system through public contracts, isolate each run, and use bounded eventual assertions. Flaky tests are diagnosed rather than hidden by automatic retries, protocol-specific device simulators are not part of the scaffold, and mutation testing remains an optional targeted profile until the Demo supplies cost and signal evidence.

Evidence: the revised Q375 and Q381-Q397. Q376-Q380 remain provisional; see [the question index](../grill/question-index.md).
