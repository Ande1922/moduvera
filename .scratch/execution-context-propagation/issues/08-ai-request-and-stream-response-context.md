Type: issue
Status: blocked
Blocked by: 02

# AI 请求注入与流式响应消费

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US17、US19、US21、US22
Test seams: T06、T08、T09

## Outcome

交付“请求建立 → Spring AI 原生响应 → 同步业务消费”的完整闭环：每请求捕获一次可信上下文，无状态响应适配器从当前 ChatClientResponse 恢复 Holder。

## Base and scope

基于 02 的模型/快照/Scope。仅依赖实际 Spring AI 与其原生 Reactor 机制，不使用尚未完成的 07 helper；同票包含请求注入、原生保留键契约及实际响应消费，不拆出只有注入的半成品。

## Acceptance criteria

- [ ] 每次 AI 请求严格捕获同一个可信 ExecutionContext，分别写入 Advisor request context 与 ToolContext 对应选项；保留其他键、调用参数和配置。
- [ ] 保留键已有不同上下文时拒绝误复用，不静默覆盖；共享 ChatClient 默认状态不保存某次请求身份，捕获缺失在调用前失败。
- [ ] 上下文不进入 prompt、模型输入、工具参数 schema 或结果；两个原生通道的键与值契约作为 09 可直接复用的公共适配输出。
- [ ] 保留 ChatClientResponse 直至完成需上下文的处理；响应适配器每次严格读取当前响应的保留键，缺失/错误类型先拒绝，不回退工作线程 Holder。
- [ ] 仅在实际同步处理期间开 Scope，成功、异常退出均恢复；不同线程完成、并发不同请求及同租户不同身份不串上下文。
- [ ] 响应适配器不在创建时捕获身份，满足委托线程安全前提时可共享；与固定快照 Bound 对象的跨请求禁用规则明确区分。
- [ ] 通过真实 ChatClient 的流式 ChatClientResponse 订阅路径与测试源码脚本化 ChatModel 驱动消费，证明订阅后信号换线程及并发请求下的原生通道和处理回调；仅同步 call 或手工构造响应不能替代流式证据。不调用付费模型，不把脚本模型发布为生产适配器。
- [ ] 锁定与仓库 Java/Boot 基线兼容的实际依赖，证明 Kernel 与非 AI 消费者不被迫引入 AI；协议无关 Service API 不增加 ChatClientResponse 参数。
- [ ] 本场景公共消费者、配置/保留键冲突与响应生命周期说明、依赖检查随票交付；说明仅剩内容字符串后不能自动恢复原生响应上下文。

## Verification

- 真实 Spring AI ChatClient 流式订阅/响应链，测试专用模型控制订阅后的延迟、异常和线程切换；在实际请求选项观察两个通道，在每次业务响应函数内观察完整 Holder，在退出后观察原值恢复。同步响应测试可以补充，不能替代流式路径。
- 同一共享 ChatClient/响应适配器并发处理 Platform、Tenant 和同租户不同请求；缺失、错误类型、冲突键的负向测试必须先于业务委托失败。
- 运行实际 AI 适配模块与独立消费者的聚焦 verify/测试，记录选定版本、依赖闭包和精确命令。早期外部源码研究版本不是本票运行证明。

## Exclusions

不实现 ToolCallback 或多轮工具循环（09 负责），不依赖自有 Reactor helper、不构建 Agent 业务或授权产品、不承诺任意 Advisor 自动有 Holder。

## Comments

- 2026-09-05：等待 02；09 复用本票请求注入和原生键契约，而非将响应 mapper 当作工具执行依赖。
