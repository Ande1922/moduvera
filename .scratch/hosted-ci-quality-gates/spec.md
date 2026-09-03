Type: spec
Status: ready-for-agent
Fixes: review-2026-09-02-scaffold-baseline/01, review-2026-09-02-scaffold-baseline/11, review-2026-09-02-scaffold-baseline/13, review-2026-09-02-scaffold-baseline/18

# 托管 CI 与质量门禁

## Outcome

分阶段建立 Moduvera 的质量执行链：第一阶段先交付仓库拥有的 Normal gate、pre-push hook、证据产物和适用 Scenario 入口；本地门禁稳定并完成指标校准后，再设计托管 CI 的 job、触发和必需检查，使其复用同一套仓库判定而不产生第二套规则。

## Confirmed boundary

- 托管 CI 缺失仍是已确认的 major finding，但下一阶段先交付本地 pre-push 门禁；托管 CI 的具体设计和实现延期，不能把 pre-push 宣称为独立 CI 证据或视为 finding 已完全修复。
- 该工作使用独立 topic 设计，不与其他 review findings 共用一个修复 spec。
- 不把生产部署、镜像发布或额外的基础设施笛卡尔积矩阵隐式带入本 topic。
- PR、主分支、定时或手动 CI job 的精确拆分本轮待定；先用本地门禁验证脚本边界、运行成本、日志证据和指标阈值。
- Reference Product Harness 使用显式 `RUN_SLOT` 与固定端口步长实现实例级隔离，默认 slot 为 0。服务、Kafka、管理端口和启用时的 Debug 端口都由 slot 确定；启动前检测冲突并直接失败，不静默换端口，同时输出端口 manifest。
- Reference Compose 为 Kafka 增加与现有主动探测一致的 healthcheck，供 Compose 和外部质量门禁读取；该就绪状态不把 Compose 提升为生产编排契约。
- 对现有 tracker 执行一次性状态校准：只关闭全部子 issue 已解决且证据完整的 spec，不批量覆盖仍有 blocker、未完成项或新建工作的规格。
- 一次性校准必须复核 `multi-tenant-order-fulfillment` 15 号 Durable Publication ticket 的实际提交和验证证据并补齐 `## Answer`，证据成立后才把仅受其阻塞的 16 号 Outbox 性能基线从 `blocked` 恢复到适当 intake 状态；证据不足时修正 15 的虚假终态。
- 增加仓库拥有的 tracker 一致性 checker，校验 Type/Status 组合、blocker 是否已经解除、spec 与子 issue 终态是否矛盾，以及 resolved issue 是否提供 Answer/验证证据并处理全部验收项。
- checker 兼容旧文件的 `**Status:**` 写法，不要求仅为格式统一而重写历史 tracker。
- 仓库门禁先由项目 `quality-gate` Skill 和 pre-push hook 调用；未来托管 CI 必须复用同一脚本，Skill 和 CI YAML 都不复制门禁判定逻辑。
- pre-push 全量门禁运行在实现、Clean Code 整理、双轴 Review 及 finding 修复之后，并包含一次干净构建；门禁之后若代码发生变化，原通过证据失效。
- 开发中的窄测与增量检查不强制 Maven `clean`；`clean` 随最终全量 Maven 验证执行，避免每个反馈循环都丢失增量构建产物。
- changed-code coverage 与 CRAP 用于发现当前 ticket 遗漏的高风险测试并把问题退回原实现者，不把 pre-push 或 CI 设计成开发结束后的集中补测试阶段；具体阈值等待四类代表性真实基线样本后确定。
- pre-push hook 始终执行 Normal gate，但不固定执行两套 reference-product topology 的完整黑盒 harness。公共 API、App Assembly、消息路由、migration 或拓扑变化必须在最终验收前补相应 Scenario gate；未来托管 CI 应对双拓扑提供独立权威复核，具体 cadence 本轮不定。
- changed-line coverage 与 changed-method CRAP 分两阶段启用：门禁脚本自测、tracker/结构检查、`clean verify`、缺失报告和无法确定比较点等确定性失败从第一天阻断；coverage/CRAP 数值先报告并以真实干净构建校准，在领域逻辑、基础设施 Adapter、配置装配和文档/构建四类代表性变更均有样本后确定阻断阈值。校准不采用无限期 report-only，也不直接复制 Lingmai 的固定数值。
- pre-push 每次触发，但支持确定性的 `docs-only` profile。纯 Markdown、ADR 和 `.scratch` tracker 变更运行 diff whitespace、Markdown 链接、Skill 结构、tracker 一致性与敏感内容检查，不运行 Maven；Java、POM、SQL migration、配置、脚本、Dockerfile 或测试变化运行完整 Normal。无法可靠分类时失败关闭到完整 Normal。profile 与选择理由写入摘要，分类器用正向和负向 fixture 验证。
- Hook 只通过显式安装命令启用，Maven 构建、测试和其他普通开发命令不隐式改写 Git 配置。安装器仅设置仓库本地 `core.hooksPath`：未配置时设为项目 Hook 目录，已是该目录时幂等成功，指向其他目录时报错并保留原配置，不静默覆盖或合并。卸载器只在当前值仍指向项目 Hook 目录时移除该本地配置。
- pre-push 以实际远端状态固定比较基准：已存在的远程分支使用本次 push 输入中的远程旧 SHA；新分支使用其与远程默认分支的 merge-base。多 ref push 取全部变更路径并集，任一 ref 需要完整 Normal 则整次 push 运行 Normal。无法可靠解析 base 时失败关闭；手工运行要求显式 `--base`。摘要记录 base、head、ref 和 profile 分类理由。
- 门禁只对外提供 `auto` 和 `normal`：`auto` 由仓库分类器选择内部 `docs-only` 或完整 Normal，`normal` 只能主动升级；不提供人工强制 `docs-only` 的入口。
- 正式 pre-push 证据要求 Git 工作区干净；已暂存、未暂存或未忽略新文件都使门禁拒绝运行，已忽略的构建和门禁产物不影响判定。
- 门禁证据写入 Git 忽略的 `.quality-gate/runs/<run-id>/`：目录权限 `0700`、日志权限 `0600`，完整日志与脱敏摘要分离，`latest` 指向最近一次运行。只保留最近 20 次完成或失败记录，不删除当前运行；终端只显示有界脱敏摘要。
- 第一阶段不引入 PIT 插件或 Mutation 空壳脚本。Mutation 保留为后续独立门禁，等 Inventory 决策域提取完成且形成测试与运行成本基线后，再确定目标模块和阈值；不进入每次 pre-push。

## First-stage deliverable

本规格已具备进入拆票和实现的边界。第一阶段仅交付：

1. 仓库拥有的 Normal/docs-only 分类、门禁编排和脚本自测。
2. 有界脱敏日志、私有完整证据和可复现摘要。
3. 显式安装/卸载的 pre-push hook，包含比较基准、多 ref 和干净工作区保护。
4. tracker 一致性 checker 和一次性状态校准，包括 15/16 号 Outbox ticket 证据复核。
5. Reference Harness 的 slot 端口隔离、manifest 和 Kafka healthcheck。
6. changed-line coverage/changed-method CRAP 的可评分性检查和有限 report-only 校准数据。

托管 CI、Mutation 和数值阈值升级是后续独立阶段，不阻塞本阶段进入实现。

## Lingmai change-loop assessment

2026-09-03 已读取 Lingmai Platform 的 `change-loop`、`docs/engineering-quality.md`、Normal/Mutation gate、pre-push hook、安装脚本、日志摘要器和质量 checker。合并采用“迁移门禁骨架、重写项目判定”的方式，不复制另一条端到端 change-loop：

- 保留既有 Moduvera 主链路 `grill-with-docs -> to-spec -> to-tickets -> implement/implement-frontier -> code-review -> quality-gate -> final acceptance`；Lingmai change-loop 不作为项目 Skill 引入。
- 沿用 Normal gate 是 pre-push 主入口、Mutation gate 独立且高成本、质量脚本必须有自测、失败保留非零退出、完整日志私有保存且终端只显示脱敏有界摘要等机制。
- 沿用可选安装的仓库 `core.hooksPath` pre-push hook，但 hook 只是提前反馈；托管 CI 是不能被 `--no-verify` 绕过的权威复核。
- Clean Code 继续位于窄测变绿之后；Maven `clean` 位于 Review finding 修复后的 Normal gate。不同于 Lingmai 的“Normal gate 后再完整 Review”，Moduvera 先完成双轴 Review 和修复，再运行昂贵的干净门禁，避免 Review 修改立即使门禁证据失效。
- Lingmai 的 Nacos/配置快照、六阶段部署、固定五模块、`sample-bootstrap` 制品检查和部署场景不适用于 Moduvera，不迁移。
- Lingmai 的全生产模块 PIT 参与/指纹排除以及固定 mutation 70、line 80 不迁移；Moduvera 继续遵循“PIT 可选且定向”，待真实成本和信号证据后才提高强制范围。
- Lingmai 的 CRAP 公式和 JaCoCo XML 读取方式可以复用思想，但其全仓方法阈值 30 不能直接复制；Moduvera 只评价固定比较点以来的变更行/变更方法，并在当前基线测量后确定阈值。
- Moduvera 已由 Maven `verify` 执行 compiler warnings、Enforcer、Spotless check、PMD、JaCoCo report、Surefire/Failsafe 和 ArchUnit；Python/shell checker 不重复实现这些判定，只补 Maven 当前不能表达的跨文件和 tracker 契约。

## Proposed gate topology

### Development loop

- 实现期间运行最窄受影响测试；不执行 Maven `clean`，也不要求每轮跑完整 Reactor。
- 行为变化在当前 ticket 内提供风险相称的测试证据；Bug 修复 regression-first，其他行为的 test-first/test-after 服从已确认策略。
- 窄测通过后执行 Clean Code 简化并复跑受影响测试，然后进入 Standards/Spec 双轴 Review。

### Normal pre-push gate

仓库新增 `tools/quality/quality-gate.sh` 作为唯一编排入口，由薄 `quality-gate` Skill、pre-push hook 和托管 CI 共同调用：

- `auto` 是 hook 和手工运行的默认 profile，只能由脚本分类为内部 `docs-only` 或 Normal；`normal` 允许调用者强制执行完整门禁。
- 不提供可从 CLI 或 Skill 强制指定 `docs-only` 的参数。

1. 运行质量脚本自己的测试，防止 checker 假通过。
2. 运行 tracker 一致性、仓库结构、敏感配置/生成物和适用制品契约等 Moduvera 专属静态 checker。
3. 通过有界日志执行器运行 `./mvnw -B -ntp clean verify`；完整日志存入被 Git 忽略的唯一 run 目录并设为仅当前用户可读，终端输出构建摘要、有限错误和脱敏内容。
4. 从本次 JaCoCo XML 与固定比较点计算 changed-line coverage 和 changed-method CRAP；缺报告、无法解析比较点或参与变更的方法无法评分时失败关闭。
5. 输出本轮命令、比较点、JDK、Reactor 结果、测试摘要、最高变更 CRAP、覆盖结果和证据目录；任一必需步骤非零则门禁失败。

Normal gate 不修改源码，不自动执行 Spotless apply，不通过跳过测试、降低阈值或复用旧报告换取成功。门禁后的源码、POM、配置或测试变化使结果失效。

首次上线时，第 4 步的报告存在性、完整性、比较点和可评分性立即阻断；coverage/CRAP 数值在有限校准阶段只报告。四类代表性变更样本齐备后必须通过一次明确评审设置阻断阈值并结束校准，不能把 report-only 变成永久例外。

### Docs-only pre-push gate

- 同一个 pre-push hook 先根据实际 push 的固定比较点计算 changed paths；只有全部路径都属于 Markdown、ADR 或 `.scratch` tracker 的允许集合时才选择 `docs-only`。
- `docs-only` 仍运行门禁脚本自测、`git diff --check`、Markdown 本地链接、项目 Skill 结构、tracker 一致性和敏感内容检查，并输出独立证据摘要。
- 删除、重命名、符号链接、未知扩展名或同时包含代码/配置路径时升级到完整 Normal；分类器不得因为无法识别而跳过 Maven。
- 分类规则由仓库脚本拥有，hook 和 `quality-gate` Skill 只传递比较点，不复制扩展名列表。

### Scenario gate

- `verification/reference-product/harness/verify.sh` 保持独立场景门禁，验证五 App Golden Path 与 business-core monolith 的公共 HTTP、Kafka 故障恢复和真实进程边界。
- Dockerfile/镜像基线落地后增加独立 image smoke；它不把 Reference Compose 变成生产部署契约。
- 场景门禁与 Normal gate 使用同一证据摘要约定，但不把长时间运行的进程场景静默藏进 Maven 或静态 checker。
- pre-push hook 不无条件启动双拓扑；适用场景由交付工作流在最终验收前要求，托管 CI 则执行双拓扑权威复核。

### Deferred mutation gate

- 第一阶段不新增 PIT 插件、`mutation-gate.sh` 或默认参与模块。
- Inventory 决策域提取完成并形成基线后，再通过独立 ticket 建立定向 Mutation gate；参与范围、阈值和 `failWhenNoMutations` 以实测为准。
- 未来 Mutation 不进入每次 pre-push；存活变异优先通过补可观察行为或删除等价分支解决，不添加实现耦合断言。

### Enforcement boundary

- `tools/quality/install-hooks.sh` 是显式、幂等的启用入口，只读写仓库本地 `core.hooksPath`，不改全局 Git 配置，也不由 Maven 或其他构建命令自动调用。
- 安装时，`core.hooksPath` 未设置则指向项目 Hook 目录，已指向该目录则直接成功；如果已指向其他目录，安装失败并输出人工合并指引，不覆盖或猜测现有 Hook 意图。
- `tools/quality/uninstall-hooks.sh` 只在仓库本地 `core.hooksPath` 仍等于项目 Hook 目录时移除该配置；值已变化时失败保护，不删除他人配置。
- pre-push hook 调用 Normal gate，并把实际 push 的 remote/base 信息传给 changed-code checker；手工运行由 workflow 已固定的 Review 比较点显式提供 base。
- 对已存在远程分支，hook 使用 Git pre-push 输入的 remote old SHA 作为 base；对新分支，使用该 head 与远程默认分支的 merge-base。多 ref push 合并变更集并采用最严格 profile；base 不可得、远程默认分支无法确定或 ref 状态无法解析时直接失败，不退化为 `docs-only`。
- 手工运行 `auto` 或 `normal` 必须显式提供 `--base <revision>`；门禁在运行开始时把解析后的 base、每个 head/ref 及 profile 理由写入证据摘要，保证结果可复现。
- hook 在执行任何门禁前检查 Git 工作区；已暂存、未暂存或未忽略文件存在时失败，并只列出状态与路径，不输出文件内容。
- 运行证据位于 `.quality-gate/runs/<run-id>/`，完整日志与脱敏摘要分离保存，并维护 `latest`。保留策略仅作用于该受控目录内已完成的历史 run，最多保留 20 次。
- 第一阶段只实现本地 pre-push 与手工入口；未来托管 CI 复用同一脚本和参数、不维护第二套质量判断，并成为不能由 `--no-verify` 绕过的合并权威。托管 CI 落地前，pre-push 只是可绕过的本地保护，不能提供独立执行证据。
- Agent 只有在门禁真实退出 0 且不存在未解决 Review finding 时才能报告 PASS；没有 push 授权时只报告门禁结果，不执行 push。

## Deferred follow-ups

- changed-line coverage 与 changed-method CRAP 的初始阈值；阈值在四类代表性变更样本完成后通过单独评审确定，不能直接采用 Lingmai 的全方法 CRAP 30 或 PIT 70/80。
- Mutation gate 的首批目标模块和阈值；待 Inventory 决策域与测试基线完成后以独立 ticket 处理。
- 托管 CI 的平台虽已确定为 GitHub，但 PR、main、定时和手动入口如何分配 Normal、双拓扑 Scenario、镜像 smoke 和 Mutation 本轮待定；等 pre-push 运行成本和指标校准完成后专题设计。
