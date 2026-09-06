# Subagent execution profile

Use this profile when an entrypoint already requires delegation. It selects
execution settings; the entrypoint still owns scope, authorization, review,
and completion. It does not turn a single-ticket implementation into a frontier.

## Role settings

| Role | Default model | Reasoning effort |
| --- | --- | --- |
| Main task: clarification and coordination | `gpt-6-astra` | `high` |
| Ticket worker or fixer | `gpt-5.6-sol` | `high` |
| Standards or Spec reviewer | `gpt-5.6-sol` | `high` |
| Read-only preflight or evidence explorer | `gpt-5.6-terra` | `medium` |

The main-task row is a recommendation for the user's model picker, not a
request to replace the running coordinator or spawn another coordinator.
Preserve the active model and effort. Extra High (`xhigh`), Max, and Ultra
remain user-selected modes; this profile never enables them automatically.
An explicit user choice for a delegated role overrides that role's default.
Selecting Extra High or Ultra for the main task alone leaves the worker and
reviewer defaults above unchanged. Keep difficult cross-ticket judgments with
the coordinator; give it the unresolved evidence rather than automatically
raising a worker's effort.

## Dispatch and continuation

- Start each delegated role with explicit `model`, `reasoning_effort`, and
  `fork_turns="none"`. Supply its goal, scope, exclusions, repository rules,
  source paths, fixed base/head when applicable, and expected evidence. A full
  history fork inherits the parent's settings and cannot implement this mixed
  profile.
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
- If a requested model, effort, or fresh context is unavailable, report the
  mismatch before that dispatch. Do not silently inherit or substitute a
  different configuration.
- In the existing ledger or result summary, record each agent's role and
  requested settings, plus effective settings when the runtime exposes them.
  Label unavailable effective settings as unverified. Use observed checks,
  rework, elapsed time, and usage for trial comparisons; the role table is not
  evidence of quality or cost improvement.
