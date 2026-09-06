---
status: accepted
---

# Use an explicit local TransactionBoundary

Application use cases express local atomic work through the programmatic `TransactionBoundary.inTransaction` interface. It represents one top-level, single-service, single-database transaction, not a transaction composer or distributed orchestration abstraction. Inside it only local repositories and same-database Outbox/Inbox writes are allowed. Remote clients, direct broker sends and Redis side effects are excluded, trading annotation convenience for visible atomicity.

For an idempotent message use case, the Application Handler owns a fixed `InboxTemplate` rather than depending directly on `TransactionBoundary`. It checks committed Inbox state before authorization and any protected preparation. On a miss, authorization and allowed repeatable preparation precede `InboxTemplate.handle`; mutable consistency reads, domain changes, the Inbox record, and same-database Outbox writes stay in that callback as one complete local transaction. A duplicate skips preparation, and preparation, domain, Outbox, or commit failures propagate without leaving a processed record.

The interface is framework-free in `moduvera-kernel`. Spring transaction implementation and auto-configuration belong to the MyBatis-Plus Data Starter. The Starter fails application startup when no `PlatformTransactionManager` exists, and nested boundaries fail instead of silently joining an existing transaction.

Evidence: Q227-Q231; see [the question index](../grill/question-index.md).
