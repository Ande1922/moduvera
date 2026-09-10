# Subagent execution profile

Use this profile when an entrypoint already requires delegation. It selects
execution settings; the entrypoint still owns scope, authorization, review,
and completion. It does not turn a single-ticket implementation into a frontier.

## Harness defaults

| Harness | Main-task preference | Subtask default |
| --- | --- | --- |
| Codex | GPT-6 Astra / high (`gpt-6-astra`) | GPT-6 Astra / medium (`gpt-6-astra`) |
| Cursor | Grok 4.6 HighFast | Grok 4.6 HighFast |
| Claude Code | Opus 5 / high | Opus 5 / medium |

The subtask default applies to every delegated role, including workers,
fixers, Standards/Spec reviewers, and read-only explorers. Main-task preferences
guide the user's model picker; preserve the running coordinator's actual model
and effort. Selecting a different main-task setting alone does not change the
subtask default.

Resolve settings at dispatch:

1. Apply any explicit user choice for the role before these defaults.
2. Identify the current Harness from the runtime identity or an explicit user
   statement, then select its row. Repository files alone do not identify the
   running Harness.
3. Resolve the preferred model and preset through that Harness's exposed model
   catalog, dispatch metadata, or an explicit user-configured alias. These are
   preference labels, not a claim of model availability or portable API IDs.
   Treat HighFast as one preset label unless the Harness exposes its components;
   do not invent model IDs, effort values, or alias mappings.
4. If the Harness, mapping, or requested dispatch capability is unavailable,
   report the missing setting before dispatch instead of silently substituting
   another model or inheriting the main-task settings.

Keep difficult cross-ticket judgments with the coordinator rather than
automatically raising a worker's effort. This table is the single default
mapping; entrypoint Skills link here instead of maintaining their own copies.

## Dispatch and continuation

- Start each delegated role in a fresh context with the resolved model and
  supported effort or preset explicitly selected. In Codex, use `model`,
  `reasoning_effort`, and `fork_turns="none"`; in other Harnesses use their
  exposed equivalents. Supply the goal, scope, exclusions, repository rules,
  source paths, fixed base/head when applicable, and expected evidence.
- The coordinator creates roles and owns further decomposition. Workers,
  reviewers, and explorers create no agents. Within a registered
  [ticket review loop](ticket-review-loop.md), existing peers may continue one
  another for the same ticket and role; this is not authority to create a
  coordinator or assign a different task. Escalations return to the coordinator.
- Reserve the coordinator slot. Run at most three delegated agents at once,
  or fewer when the available capacity is lower. Keep independent reviewer
  contexts even when capacity requires sequential execution.
- Continue the original agent for the same task and role under its entrypoint's
  ownership and closure rules. Preserve its model/effort on follow-up; do not
  rotate sessions merely to apply this profile or meet a token threshold.
- In the existing ledger or result summary, record the Harness, each agent's
  role, preferred label, and resolved dispatch settings, plus effective settings
  when the runtime exposes them.
  Label unavailable effective settings as unverified. Use observed checks,
  rework, elapsed time, and usage for trial comparisons; the mapping table is not
  evidence of quality or cost improvement.
