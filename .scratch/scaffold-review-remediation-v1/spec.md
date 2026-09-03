Type: spec
Status: ready-for-agent

# 脚手架基线评审整改第一迭代

## Problem Statement

当前 Scaffold 的业务架构和黄金路径已有较强的可执行约束，但本次基线评审暴露出三类会直接拉低下一次开发可靠性的问题：交付流程仍依赖个人 Skill 和手工验证，仓库无法独立恢复同一套规格、实施、Review 和门禁流程；Tenant ID、容器镜像和 Reference Harness 等产品契约存在不一致或缺口；Inventory 的核心预占决策与持久化并发机制糊在一起，无法在纯 Domain 层面直接证明。

如果一次把所有已接收 finding 都展开，还会同时引入 ExecutionContext 跨执行模型、日志与 Trace 治理、可靠消息入站 API 语义、托管 CI 和 Mutation 等需要独立权衡的大范围工作，使第一版无法快速形成可用闭环。因此需要一个有意限制的第一迭代：先让仓库内的交付流程、本地门禁和几项已完全定义的高价值整改可以真实运行，其余主题明确进入下一迭代。

## Solution

第一迭代建立一条仓库可独立执行的交付链：从需求决策、Spec、ticket、实施、Clean Code 和双轴 Review，到 pre-push Normal gate 与最终验收。仓库 Markdown 定义工作流组合，多个小型 Skill 各自拥有清晰责任，确定性判定由仓库脚本拥有。个人 Skill 仍可作为改进上游，但项目运行时不依赖个人目录。

同一迭代只实施已经具备清晰产品边界和测试接缝的整改：统一 1–64 字符的字符串 Tenant ID；把 Inventory Reservation Decision 从 Persistence Adapter 中提取为纯 Domain 决策；删除 Catalog 无生产消费者的废弃装配门面并保留现有业务模块包结构；增加所有 Runnable App Assembly 共用的分层 Dockerfile 基线；提供新增 Business Service 的 AI 可发现配方，但不生成代码。

质量门禁首先以本地 pre-push 形态运行：纯文档变更运行可确定的 docs-only 检查，其他变更运行完整 Normal gate 和干净 Maven Reactor 验证。门禁同时补齐 tracker 一致性、Reference Harness 并行端口隔离和 Kafka healthcheck。托管 CI 之后复用同一门禁入口，但不在第一迭代设计 job 矩阵或合并策略。

## User Stories

1. As a Scaffold 维护者, I want 仓库自带完整交付流程, so that 换一个 agent 或开发者仍能重现同一套工作方式。
2. As a Scaffold 维护者, I want 需求、Spec、ticket、实施、Review 和门禁有明确顺序, so that 完成状态不再依赖会话记忆。
3. As an AI coding agent, I want 每个交付阶段都能独立调用, so that 小任务不必强制执行整个闭环。
4. As a Human reviewer, I want Standards Review 和 Spec Review 使用固定比较点, so that finding 有明确的证据边界。
5. As a Developer, I want Clean Code 整理发生在窄测变绿后、正式 Review 前, so that 反馈快速且 Review 看到最终结构。
6. As a Developer, I want 测试义务强制而 TDD 时序按风险选择, so that 质量证据完整且不为低风险变更制造固定成本。
7. As a Bug fixer, I want 回归测试先复现故障, so that 修复真正覆盖已知失败行为。
8. As a Domain developer, I want 复杂规则可以优先 test-first, so that 边界条件在实现细节之前被明确。
9. As a Repository maintainer, I want pre-push 自动运行仓库质量门禁, so that 大部分问题在离开本机前被发现。
10. As a Documentation author, I want 纯 Markdown、ADR 和 tracker 变更使用 docs-only profile, so that 不为纯文档修改支付整仓 Maven 成本。
11. As a Quality owner, I want 无法可靠分类的变更自动升级到 Normal gate, so that 未知文件不会变成绕过测试的通道。
12. As a Developer, I want Hook 通过显式、幂等的命令安装, so that 普通构建不会暗中改写 Git 配置。
13. As a Developer with existing hooks, I want 安装器在 `core.hooksPath` 冲突时停止, so that 我现有的本地门禁不会被静默覆盖。
14. As a Quality owner, I want 门禁记录 base、head、ref 和 profile 理由, so that 每次结果都能复现。
15. As a Security maintainer, I want 完整门禁日志使用私有权限保存且终端只显示脱敏摘要, so that 质量证据不扩大敏感信息暴露。
16. As a Tracker maintainer, I want spec、issue、blocker、Answer 和验收项有可执行一致性检查, so that `resolved` 不再只是一个无证据状态字段。
17. As a Tracker maintainer, I want 一次性校准已有状态, so that 门禁从真实基线开始而不是保留已知假绿。
18. As an Operations tester, I want Reference Harness 通过显式 run slot 隔离端口, so that 同一宿主机能并行执行多个实例。
19. As an Operations tester, I want 端口冲突在启动前显式失败并输出 manifest, so that 调试地址不会因动态猜测而不可复现。
20. As a Compose orchestrator, I want Kafka 暴露与现有主动探测一致的 healthcheck, so that 外部门禁可以读取可靠的就绪状态。
21. As an Identity developer, I want 用户和服务 JWT 只签发 canonical Tenant ID, so that 签发方不会接受资源服务无法建立的租户上下文。
22. As a Framework consumer, I want Tenant ID 在 HTTP、JWT、消息、任务和 ExecutionContext 中保持同一字符串值域, so that 边界之间不需要隐式类型转换。
23. As a Database maintainer, I want Reference Product 的 tenant 列统一为 `VARCHAR(64)`, so that schema 与 Kernel 契约一致。
24. As a Database maintainer, I want 收窄 tenant 列前显式检测超长数据, so that 迁移不会静默截断或错误映射租户。
25. As a Business-system architect, I want Scaffold 不预设 BIGINT 租户主键或转换 SPI, so that 正式业务系统可以根据自身数据模型做明确选择。
26. As an Inventory domain developer, I want all-or-nothing、缺失商品和库存不足判断形成纯 Domain 决策, so that 规则不需要数据库就能完整验证。
27. As an Inventory persistence developer, I want 行锁、版本条件更新、command 幂等和并发冲突继续归 Persistence Adapter 所有, so that Domain 不会混入基础设施机制。
28. As an Order/Inventory integrator, I want 现有异步命令、结果消息和 Outbox/Inbox 语义保持不变, so that 领域提取不会破坏黄金路径。
29. As a Catalog maintainer, I want 删除无生产消费者的 deprecated configuration facade, so that 新代码不会把废弃入口当成受支持装配方式。
30. As a Catalog maintainer, I want 保留 `catalog.catalog` 业务模块层次, so that 删除兼容门面不会把 package 重新拍平。
31. As an App Assembly maintainer, I want 所有 Runnable App 共用一个参数化 Dockerfile, so that 镜像安全和运行契约不会按 App 漂移。
32. As an Image builder, I want Dockerfile 消费已构建的 Spring Boot 可执行 JAR 并按官方层拆分, so that 应用变更能复用稳定依赖层。
33. As an Operations maintainer, I want 容器以非 root 身份运行并保持外部化配置, so that 镜像具备安全且通用的最小运行基线。
34. As a Local debugger, I want HTTP 和 Debug 端口在运行时独立配置且 JDWP 默认关闭, so that 多 App 调试不会因统一硬编码端口冲突。
35. As an AI coding agent, I want 有一个新增 Business Service 的单一组织入口, so that 我能正确选择 API、Application、Domain、Adapter、migration、App Assembly 和验证范围。
36. As a Scaffold consumer, I want 新服务配方区分同步直接调用和异步-only 能力, so that 消息 Handler 不会被迫建模成伪同步 Java API。
37. As a Scaffold consumer, I want 配方描述决策分支和完成证据而不是直接生成代码, so that 尚未稳定的业务结构不会过早被固化。
38. As a Release reviewer, I want 第一迭代只包含已确认契约的工作, so that 日志、ExecutionContext、CI 和 Mutation 的未决策设计不会拖住可用闭环。

## Implementation Decisions

- 本规格是本次系统评审整改的第一迭代范围契约。各专题规格继续拥有字段、边界和证据细节；本规格拥有哪些专题进入本迭代的范围决定。
- 仓库完整拥有 `grill-with-docs -> to-spec -> to-tickets -> implement/implement-frontier -> code-review -> quality-gate -> final acceptance` 链路，由 Markdown 编排多个可独立调用的 Skill，不引入单一大型 change-loop Skill。
- 仓库同时纳入运行所需的支撑 Skill；TDD 只在用户明确启用或仓库局部规则要求时加载。后续个人 Skill 与仓库 Skill 仅手工同步，本迭代不定义同步工具或版本协议。
- 测试义务强制，但不统一强制 TDD 时序。Bug 修复 regression-first；复杂 Domain 规则、状态转换、幂等、权限和租户隔离推荐 test-first；其他变更可 test-after，但必须在当前 ticket 的 Clean Code 和正式 Review 之前完成。
- 首批质量门禁是仓库本地 pre-push 与手工入口。确定性失败立即阻断；changed-line coverage 和 changed-method CRAP 数值在有限校准阶段只报告，等代表性样本完成后另行确定阈值。
- pre-push 只对外提供自动分类和强制 Normal 两种模式，不允许调用者强制 docs-only。无法确定比较点、变更类型或工作区不干净时失败关闭。
- Hook 只通过显式脚本设置仓库本地 `core.hooksPath`。既有配置指向其他目录时不覆盖；卸载时只移除仍指向项目 Hook 目录的配置。
- Normal gate 在 Review finding 修复后执行干净 Maven Reactor 验证，保留有界脱敏摘要和私有完整日志。门禁后代码再变更会使既有证据失效。
- Reference Harness 用显式 run slot 和固定偏移支持同宿主机并行，输出端口 manifest；端口已被占用时拒绝启动。Reference Compose 增加 Kafka healthcheck，但仍不是生产编排契约。
- Tenant ID 在 Kernel、Identity、HTTP、JWT、消息、任务和 Reference Product 中统一为 1–64 字符的不透明字符串。产品 schema 使用 `VARCHAR(64)`；不新增 String/BIGINT 转换 SPI，也不依赖数据库隐式转换。
- Inventory Reservation Decision 拥有 all-or-nothing、商品缺失/不足和 Reserved/Rejected 选择。Application Service 调用纯 Domain 决策并转换为集成结果；Persistence Adapter 继续拥有加锁、条件更新、版本检查、command 幂等、结果唯一性和并发冲突处理。
- Catalog 直接删除 deprecated service-level configuration facade、仅为它存在的兼容性测试和架构 fixture；保留现有业务模块 package，不拍平。
- Runnable App Assembly 共用一个参数化 Dockerfile，直接消费 Maven 已产出的 Spring Boot 可执行 JAR，使用 Spring Boot tools jarmode 拆分稳定依赖与应用层。运行时非 root，外部化配置保持不变，JDWP 默认关闭。
- 容器的 HTTP、management 和 Debug 端口由 App 默认值与运行时配置决定，不在共享 Dockerfile 中硬编码成同一端口。Dockerfile 中的 `EXPOSE` 只是可参数化元数据。
- 新 Business Service 配方覆盖同步 API、异步-only 消息契约、Application/Domain/Adapter、service-owned migration、App Assembly、架构测试与验收入口；本迭代不提供 archetype、生成器或模板复制脚本。
- 已失去 as-built 权威性的数据模型确认文档直接删除；开放问题只保留尚有真实消费者证据需求的 Aggregate Version 议题。

## Testing Decisions

- 测试只证明可观察行为和稳定契约，不固定私有方法、内部类层次或 Spring wiring 的偶然形状。同一行为已有高层接缝时，不为方便 mock 增加新的公共接口。
- Inventory 使用三层证据：纯 Domain 单元测试覆盖全部预占决策组合；PostgreSQL/MySQL 真实 Adapter 测试覆盖锁、条件更新、幂等与并发；Application/App 测试证明一次性发布和现有异步契约不变。
- Tenant ID 使用共享有效/无效契约数据覆盖 Kernel、Identity 签发、Resource Server 和消息入口；迁移测试要证明超长历史数据失败而不是截断。
- Catalog 删除由现有 App Assembly 启动测试和架构规则证明：受支持 Module Configuration 仍能被选择，仓库不再发布或允许废弃 facade。
- 质量脚本先用 fixture 自测证明分类、失败关闭、日志脱敏、tracker 规则和 Hook 冲突处理，再以真实仓库干净构建作为 Normal gate 验收。不用旧 JaCoCo 报告充当当前证据。
- Reference Harness 验证至少两个不同 run slot 的配置可并存，端口冲突会在启动前失败，Kafka healthcheck 与实际可用性一致。公共 HTTP 黑盒步骤继续覆盖五 App Golden Path 和 Business-core Modular Monolith。
- 镜像验证覆盖所有纳入范围的 Runnable App 能从已构建 JAR 生成镜像，代表性 App 以非 root 身份启动、接收外部配置并通过最小 HTTP/health smoke。验证 JDWP 未配置时不监听 Debug 端口。
- Agent 工作流用结构校验和一个代表性 forward test 证明：在无个人 Skill 目录的环境中，可从仓库入口完成需求、Spec、ticket、实施路由、Review 和门禁选择。
- 新 Business Service 配方用一个代表性能力描述做 forward test，证明 agent 能识别同步/异步-only、持久化、App Assembly、migration 和验收分支，且不产生无消费者空模块。
- 先运行受影响模块的最窄测试，再运行相关 App/Adapter 集成测试、架构测试和最终干净 Normal gate。公共 API、App Assembly、消息路由、migration 或拓扑变化还必须在最终验收前运行适用 Scenario gate。

## Out of Scope

以下内容作为下一迭代或独立专题，不阻塞本规格实施：

- ExecutionContext 在 WebFlux/Reactor、Servlet/虚拟线程、显式 Executor 和 Structured Concurrency 之间的统一传播模型，包括 ThreadLocal、ScopedValue、Reactor Context 和 Micrometer bridge 的选择。
- 日志字段、脱敏、MDC、Trace/Span、OpenTelemetry 与指标的受治理可观测性；该主题等待维护者日志规范合入。
- Reliable Inbound Endpoint 的事务所有权架构门禁具体形状，以及 Factory、Endpoint 和 InboxTemplate 的命名/API 收敛。
- GitHub 托管 CI 的 PR、main、定时和手动 job 组合、必需检查和证据保留策略。本迭代的 pre-push 可被绕过，不宣称已修复“缺少托管 CI”。
- Mutation gate、PIT 参与模块、mutation/coverage/CRAP 数值阈值；先累积真实基线，再升级为阻断条件。
- Outbox 性能基线实施。本迭代只校准其 blocker 与 tracker 状态，不在未有稳定环境前采集产品基线。
- Lock/Scheduler 能力扩展、Redisson Adapter、Identity 生产密钥持久化/轮换和 Inventory HTTP Resource Server。这些 finding 已分别确定为不处理或不适用。
- Business Service 代码生成器、Maven archetype、模板仓库或 IDE wizard。
- Kubernetes、Helm、镜像仓库发布、签名、供应链证明和完整生产部署方案。

## Further Notes

- 本迭代的优先目标是“先把流程跑起来”，因此对不改变产品语义的实现细节采用仓库默认值，不再逐项要求维护者决策。只有架构、公共契约、数据模型或迭代范围发生变化时才重新升级决策。
- 所有已接收 finding 都继续保留原始审计状态。实施完成后在 finding 中记录修复提交，不把 `confirmed` 改成实施状态。
- 数据模型陈旧文档的删除已在当前工作区完成，但在没有实际提交前不记录 `Fixed` 证据。
- 当前仓库为 Java 26、Spring Boot 4.1 多模块 Maven 项目；实施必须继续遵循现有 Service API、App Assembly、事务边界、租户隔离和 Adapter 验证 ADR，不因本次整改重新定义黄金路径。
