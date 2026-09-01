# Domain Docs

This is a single-context repository. Engineering skills consume the root `CONTEXT.md` glossary and the system-wide decisions under `docs/adr/`.

## Before exploring

- Read `CONTEXT.md` and use its canonical vocabulary in specifications, tickets, code, and tests.
- Read every ADR that touches the area being changed, including any ADR named by `AGENTS.md`.
- Surface a conflict with an accepted ADR explicitly; do not silently replace the decision.

## Maintenance

Create or refine glossary entries only when domain language is resolved. Create an ADR only for a hard-to-reverse, surprising decision that selected among real alternatives. Keep implementation mechanics out of `CONTEXT.md` and put enforceable development rules in `AGENTS.md`.
