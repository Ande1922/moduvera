---
status: accepted
---

# Keep database migration artifacts service-owned

Each Business Service owns a logical database, database identity, schema definition, migration artifact, and Flyway history even when services share a physical database instance. Migrations use database-specific native SQL, Expand/Contract, and forward fixes. The deployment mechanism, execution identity, release ordering, and whether runtime applications have migration privileges remain open deployment decisions.

Evidence: Q240-Q259. Q419-Q420 are retained as historical deployment proposals, and Q423 was withdrawn as a duplicate rather than treated as a new decision; see [the question index](../grill/question-index.md).
