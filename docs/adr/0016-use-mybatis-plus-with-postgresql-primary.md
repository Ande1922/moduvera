---
status: accepted
---

# Use MyBatis-Plus with PostgreSQL as the primary database

MyBatis-Plus is the selected persistence framework. PostgreSQL is the Golden Path database and owns the default documentation, end-to-end flow, and performance baseline. MySQL remains a long-term compatibility target verified through the same service-owned Repository contracts and migration behavior. Database-specific SQL and migrations are allowed behind persistence boundaries; no universal compatibility DSL or weakest-common-SQL layer is introduced. jOOQ is deferred and is not part of the supported matrix.

Optional AI retrieval may use PostgreSQL with pgvector behind a business-owned retrieval interface. MySQL compatibility covers the core transactional capabilities and does not require identical database-native AI extensions.

This supersedes ADR 0008.

Evidence: explicitly confirmed by the user during the 2026-08-30 architecture simplification review.
