---
status: accepted
---

# Keep distributed transactions and workflow runtime out of the core scaffold

The first version uses Maven and architecture tests for module boundaries, local transactions plus reliable messages for consistency, and does not embed Spring Modulith, XA/Seata, a Process Manager runtime, a workflow DSL, or a compensation framework. Complex long-running flows belong to business code or a separately chosen orchestration product; this does not exclude the accepted Local/Redisson lock capability.

Evidence: Q44, Q132-Q143; see [the question index](../grill/question-index.md).
