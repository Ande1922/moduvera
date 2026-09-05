# ExecutionContext frontier execution plan — authorized 2026-09-05

Project: /Users/gaopengcheng/Documents/ChatGPT/micro-service
Code base: ce1636f82f9652b2b14dd9e146e62c748fbf3233 (main)
Spec: .scratch/execution-context-propagation/spec.md
Spec SHA-256: bb1faca70eeaaeb10fbb7923a9a44b0a311be0c315778cbdb3b13631d4078f62
Approved design and DAG remain authoritative; this plan does not amend either.

## Scope and scheduling

Implement all 11 tickets and their 90 acceptance items. Initial frontier is 01.
Dependency layers (not mandatory batch barriers):

- 01: Kernel Scope/Snapshot recovery, regression tests, one propagation ADR.
- 02 after 01: Platform/Tenant model, resource guards, Job/Lock compatibility, CONTEXT and ADR 0003/0035.
- 03, 06, 07, 08, 10 after 02: bound callbacks; HTTP; Reactor; AI request/stream response; tenant-only messaging.
- 04/05 after 03: JDK and Spring executors. 09 after 08: AI tool loop.
- 11 after 04/05/06/07/09/10: composition, consumer qualification, supported-surface evidence.

Each ticket starts only after every code-producing prerequisite is integrated and is an ancestor of its pinned base. Record actual base/head per ticket; future bases cannot be named before their prerequisite commits exist.
At most three agents run concurrently, including reviewers. Start with one writer for 01. Distinct optional adapters are candidates for parallel work; shared POM/BOM, propagation ADR, and consumer assembly writes are order-sensitive. Schedule shared first writers serially; do not add invented business dependencies. Stop on inseparable coupled behavior or integration conflict.

## Proposed local operations requiring one authorization

1. Create integration branch codex/execution-context-20260905-integration and worktree /private/tmp/execution-context-frontier-20260905/integration from the code base.
2. Preserve the approved feature spec, draft, map, and 11 issues in the integration branch with a scoped baseline commit. Read current source-checkout skills/profile as workflow instructions; do not include unrelated dirty files in commits.
3. Create ticket branches codex/execution-context-20260905-01 through -11 and matching worktrees /private/tmp/execution-context-frontier-20260905/01 through /11 on demand from the current verified integration head. Recheck path/ref availability before creation; do not overwrite existing resources.
4. Permit each isolated worker to edit its ticket scope, run required tests, and create ordinary ticket/fix commits. No amend/history rewriting. Coordinator does not edit ticket code while writers are active.
5. Run independent Standards and Spec reviews on identical pinned committed ranges. Route accepted findings to the original owner, rerun affected checks, and refresh review evidence.
6. Integrate reviewed commits into the dedicated integration branch in dependency order using local cherry-pick. Stop on conflict; no automatic conflict resolution. Run focused cross-ticket checks after each integrated wave.
7. Update only this feature's issue statuses, acceptance evidence, map, and execution ledger as delivery is proven. Keep canonical source-checkout feature records synchronized with integrated evidence, preserving concurrent edits. Do not close or change the parent spec or original finding lifecycle.
8. Run final integrated review, repository gate, applicable independent consumers and both reference-product topologies. Keep all code and official gate evidence in the isolated integration branch. Leave main's HEAD and unrelated user edits unchanged; merging back to main is a later explicit operation.

No push, external publication/deployment, paid model calls, worktree/branch deletion, or unrelated topic repairs. Leave created worktrees and branches in place. User confirmed this entire operation plan in the destination task on 2026-09-05.

## Verification and evidence

First ticket: ./mvnw -pl framework/foundation/moduvera-kernel -am test
Later tickets: narrow tests and real infrastructure/framework/consumer evidence required by each ticket, followed by affected checks after Clean Code. Exact optional module names and compatible dependency versions are implementation evidence obligations, not preselected assumptions.
Final clean integrated gate:

    tools/quality/quality-gate.sh normal --base ce1636f82f9652b2b14dd9e146e62c748fbf3233 --head <integrated-HEAD> --ref execution-context-20260905-integration

This gate owns ./mvnw -B -ntp clean verify; do not duplicate an identical successful full-suite run.
Applicable dual topology regression:

    verification/reference-product/harness/verify.sh

Every propagation test observes complete context, missing state, and actual-thread restoration. AI uses test-only scripted ChatModel through real streaming/tool chains; message negatives require consumption barriers and real adapters. Missing evidence is not PASS.
Record ticket/base/head/branch/worktree, exact checks, both review axes, integration status, unresolved blockers, and remaining frontier. A final gate failure stops completion claims.

## Current evidence and preserved state

2026-09-05 preflight: 11 tickets, 90 unchecked criteria, complete blocker references, acyclic graph, initial frontier 01. Spec and draft hashes match handoff. Root remains main at the code base with existing user changes; no feature implementation has run.
Java 26+35, Maven 3.9.16, Docker 29.4.0/Compose v5.1.2 with reachable OrbStack daemon, and uv 0.11.24 are available. Build dependencies, container images and runtime scenarios have not yet been validated.
Proposed branch refs are absent from local and cached origin refs; no fetch performed. Seven unrelated existing linked worktrees are outside scope.
python3 tools/tracker/check.py returns 1 for the existing adjacent .scratch/http-problem-contract/issues/01-unify-external-problem-details.md missing Blocked by metadata. Do not import that untracked topic into isolated worktrees or repair it under this scope; report source-checkout failure separately.
Requested agent settings: read-only preflight gpt-5.6-terra/medium; workers/fixers and both reviewers gpt-5.6-sol/high; fresh contexts and leaf agents. Effective runtime settings are unverified where not exposed.
