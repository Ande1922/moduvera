Type: finding
Status: confirmed
Severity: major
Area: agent-workflow
Claim: 仓库没有面向脚手架消费者、受支持的新增业务服务端到端配方或生成器，复制现有模块时容易遗漏契约和验证入口。
Evidence: 未见 Maven archetype、docs/agents/new-business-service.md 或面向消费者的服务生成脚本；benchmark 下虽有 seed-template，但它不是脚手架产品入口。现有约束分散在 AGENTS、ADR、模块结构与测试中。
Verification: 全仓搜索 recipe、template、generator、archetype 与 scaffold；明确排除 benchmark seed-template，再从一个现有服务反推 API、service、adapter、app assembly、migration、architecture test 和 acceptance evidence 的必要步骤，确认是否存在受支持的单一入口。
Planned: .scratch/business-service-onboarding/spec.md

## Verdict

确认，但限定 claim。`README.md:38-61` 提供业务结构和若干所有权原则，`examples/simple-notes-demo/README.md:16-43` 提供可运行消费者的阅读路径和验证证据，因此仓库并非完全没有新增服务参考；全仓仍未发现 Maven archetype、服务生成器或 `docs/agents/new-business-service.md` 之类的单一受支持入口。维护者需要从 Catalog/Order/Inventory 和 Notes 反推何时创建 API、如何组织 Application/Domain/Adapters、注册 App Assembly 与 Gateway、拥有 migration、登记消息身份、进入 architecture tests 和 acceptance harness。Benchmark `seed-template` 只服务受控实验，不是脚手架消费者入口，不能消除该缺口。

Disposition: 接受。下个版本提供 `docs/agents/new-business-service.md` 与项目 `add-business-service` Skill，建立 AI 可发现的整体结构、决策分支、验证入口和完成条件，并复用 `to-spec -> to-tickets -> implement/implement-frontier` 交付链路；当前不提供代码生成器或 Maven archetype，待真实业务系统重复使用并稳定后再评估机械生成。

# 缺少新增业务服务配方
