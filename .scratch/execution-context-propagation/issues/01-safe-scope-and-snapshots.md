Type: issue
Status: resolved
Blocked by: None

# 安全 Scope 与完整快照恢复

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US02、US03、US04、US06、US07、US11
Test seams: T01

## Outcome

在现有 Tenant 模型上交付一个可靠恢复内核：同步安装、严格捕获、允许缺失捕获和显式可信快照均按真实执行作用域安装并恢复，且不改变原任务异常语义。

## Base and scope

从 map 记录的代码基线开始，无功能前置。修改范围为 Kernel context 的恢复/快照契约、兼容入口、测试和传播 ADR；不等待后续 Platform 或七类 Bound 类型。

## Acceptance criteria

- [x] Holder.open 与 Snapshot.of 拒绝 null；capture 保持缺失立即失败，captureAllowingAbsent 能保存明确缺失，openScope 能恢复二者。
- [x] 提供让原生载体的明确缺失状态进入同一恢复内核的接缝，构造此空状态不读取执行线程身份；在已有身份的线程上同样能明确遮蔽它。不能用“在该线程 captureAllowingAbsent”冒充载体缺失。
- [x] 每次 Scope 有独立绑定身份；同一/不同 context 对象嵌套均要求逆序。未关闭 Scope 跨线程关闭或逆序错误失败且不改状态，合法关闭后的重复关闭幂等。
- [x] 原值存在/缺失与捕获值存在/缺失的组合均正确；正常、RuntimeException、Error、Callable checked Exception 退出后恢复原值，原来缺失则清除。
- [x] 既有 Holder run/call、Snapshot wrap(Runnable/Callable) 保持可调用；Callable 原受检异常不再增加项目私有 RuntimeException 包装；返回值不改变。
- [x] 快照不随父 Scope 关闭自动失效；关闭发生在实际执行线程退出处，不挂在另一线程的取消/完成通知上。
- [x] 更新 Javadoc、当前行为修正说明并建立单一传播 ADR；Kernel 仍为 JDK-only，不声称后续适配已实现或零分配。

## Verification

- 复用 [HolderTest](../../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextHolderTest.java) 和 [SnapshotTest](../../../framework/foundation/moduvera-kernel/src/test/java/io/github/ande1922/moduvera/context/ExecutionContextSnapshotTest.java) 的公开调用路径，先为同对象错误关闭、null 与受检异常现状补回归。
- 首个聚焦命令：`./mvnw -pl framework/foundation/moduvera-kernel -am test`；后续按实际差异补消费者编译和既有架构检查。
- 验证空状态的安装和恢复不依赖“恰好干净”的测试线程；不以未来 Reactor 票的测试充当本票证据。记录实际 base/head、命令、结果和未覆盖项。

## Exclusions

不引入 Platform 模型、七类 Bound 类型、执行器/HTTP/Reactor/AI 适配、ScopedValue、Micrometer 全局注册或任意 ThreadLocal 复制。不提交或实施其他票。

## Comments

- 2026-09-05：按用户批准 DAG 发布，尚未实施或运行功能测试。

## Answer

Implemented and integrated on 2026-09-05.

- Base: `ccaf3b9850bd3485f7accdafbc38461c1a4d7ddc`. Worker head: `89416367c1b009ef7ebe986c1eac9e69dfdfa2a4`.
- Integrated commit: `0226e17cfbc48fdd2af42a2b132f438f5eb94177` on `codex/execution-context-20260905-integration`.
- Worktree: `/private/tmp/execution-context-frontier-20260905/01`; branch: `codex/execution-context-20260905-01`.
- Verification PASS: Worker Kernel verify: 30 tests, Spotless, PMD and JaCoCo passed; production consumer compile across 10 modules passed; jdeps java.base only. Integrated ./mvnw -pl framework/foundation/moduvera-kernel -am test: 30 tests passed, 0 failures/errors/skips. Integrated Kernel and ADR trees match the reviewed worker head.
- Standards review completed clean and Spec review completed clean against the same base/worker head.
- Acceptance mapping and exact commands: [worker report](../evidence/01/worker-report.md).
- Review evidence: [Standards](../evidence/01/review-standards-closure-2.md) and [Spec](../evidence/01/review-spec-closure-2.md).
- This ticket result is not final feature acceptance; parent spec/finding remain unchanged.
