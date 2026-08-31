---
status: accepted
---

# Use an explicit local TransactionBoundary

Command Application Services express local atomic work through the programmatic `TransactionBoundary.inTransaction` interface. It represents one top-level, single-service, single-database transaction, not a transaction composer or distributed orchestration abstraction. Inside it only local repositories and same-database Outbox/Inbox writes are allowed. Remote clients, direct broker sends and Redis side effects are excluded, trading annotation convenience for visible atomicity.

The interface is framework-free in `moduvera-kernel`. Spring transaction implementation and auto-configuration belong to the MyBatis-Plus Data Starter. The Starter fails application startup when no `PlatformTransactionManager` exists, and nested boundaries fail instead of silently joining an existing transaction.

Evidence: Q227-Q231; see [the question index](../grill/question-index.md).
