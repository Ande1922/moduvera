Type: finding
Status: confirmed
Severity: major
Area: delivery
Claim: 仓库没有托管 CI，工单和文档中的本地 PASS 声明缺少自动复核与独立执行产物。
Evidence: baseline 中不存在 .github/workflows、.gitlab-ci.yml 或 Jenkinsfile；README 提供 ./mvnw clean verify 与完整 harness 命令，第三方能够手工复现，但仓库没有自动执行和留存结果的入口。
Verification: 在 baseline 运行 test ! -e .github/workflows && test ! -e .gitlab-ci.yml && test ! -e Jenkinsfile；枚举仓库内其他 CI 配置并搜索自动执行 ./mvnw verify 或 verify.sh 的定义，同时对照 README 的手工验证入口。
Planned: .scratch/hosted-ci-quality-gates/spec.md

# 无托管 CI 自动复核验证声明

## Verdict

Claim 确认成立。基线与当前 HEAD 均没有托管 CI 配置，现有 Maven 与 reference-product harness 只能由开发者手工触发，不能形成自动、独立且可留存的验证结果。由于第三方仍可按 README 复现完整验证，这不是构建或运行阻塞，Severity 从 blocker 调整为 major。

维护者确认接收该 finding，并要求在独立 topic 中结合其既有质量门禁设计处理方案；在既有门禁清单进入该 topic 前，不预先固定 CI job、触发节奏或合并门禁。

2026-09-03 disposition: 先把 Lingmai change-loop 的门禁骨架按 Moduvera 规则适配为仓库 Normal gate、pre-push hook、证据摘要和独立 Scenario/Mutation 入口；托管 CI 的 job、触发和必需检查后续专题完善。pre-push 可被本地绕过且不是独立执行环境，因此该阶段不会把“无托管 CI”的 finding 标记为已修复。
