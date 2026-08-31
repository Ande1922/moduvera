---
status: superseded
superseded-by: 0016
---

# Support two databases and two data adapters as a tested matrix

MySQL and PostgreSQL are first-class databases, while jOOQ is the default data adapter and MyBatis-Plus remains a formally maintained alternative. Portability is proven by a shared Repository contract test matrix rather than by restricting infrastructure to the weakest common SQL subset; supported status depends on the matrix passing. The exact local default and CI cadence remain reversible implementation hypotheses until V0.1 supplies evidence.

This decision is retained as historical evidence. ADR 0016 selects MyBatis-Plus, makes PostgreSQL the Golden Path, keeps MySQL as a long-term compatibility target, and removes the 2 x 2 ORM/database matrix.

Evidence: Q202-Q219, which supersede Q17; Q376/Q380/Q421 remain provisional; see [the question index](../grill/question-index.md).
