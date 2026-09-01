Status: ready-for-agent

# 收紧异步库存预占的 Service API 与适配接缝

## Problem Statement

库存预占是由 Order 发送、Inventory 唯一执行的 Asynchronous Command，但当前实现同时暴露了一个同步形状的 `InventoryApi.reserve` Java 接口。该接口没有受支持的直接 Local Call 或 Remote Call，实际生产调用者只有消息 Inbound Adapter，因此把异步契约误呈现成了可同步调用的 Service API 能力。后续开发者容易据此继续为每个消息 Handler 增加同形 `*Api` 方法，扩大没有真实变化点的接口接缝。

当前消息消费配置还把两类信息放在同一处：Message Kind、Message Type、Source、Destination 是对生产消息的契约校验；Execution Actor 与 Permission Code 是消费端基于可信 Broker 通道建立的本地执行策略。这种代码形状容易被理解为消费端在收到任意消息后自行补充权限，也容易诱导未来把可由生产者伪造的权限放入 Message Envelope。

Transport Adapter 与模型转换也被混为一谈。HTTP 或消息入口始终需要适配协议机制，但当公开 Command/Result 已经是提供方拥有、协议无关且与 Application 语义完全一致的类型时，再增加字段一一对应的内部 DTO 和 Mapper 只会产生浅层样板代码。需要用明确、可注入、可验证的规则防止同步接口、镜像 DTO、权限声明和传输细节逐步污染业务模式。

## Solution

保持库存预占为异步唯一入口。Inventory 继续在其 API 模块发布版本化的 `Reserve Inventory Command`、`Inventory Reserved` 和 `Inventory Rejected` 消息契约，但移除仅供消息 Handler 调用的同步 `InventoryApi.reserve` 接口。消息 Inbound Adapter 完成信封校验、反序列化、错误分类、Inbox 幂等和可信 Execution Context 建立后，直接调用 Inventory Application Service。

区分 Transport Adaptation 与 Model Conversion。Adapter 始终拥有协议机制；只有 meaning、invariant、shape、serialization 或 versioning 存在可观察差异时才引入独立 DTO/Mapper。当前版本的协议无关库存 Command 与 Result 直接在 Adapter、Application 和既有业务实现之间复用。未来消息版本或 HTTP 契约发生真实语义分叉时，由对应 Adapter 显式转换。

把提供方拥有的 canonical kind、type 和 destination 值集中在 Inventory API 消息契约中，并保持该模块不依赖 Spring、Kafka 或消息框架类型。Order Publisher 与 Inventory Consumer 使用相同规范值；consumer ID、允许的 source、Execution Actor 和 permissions 继续是 Inventory 消费端的本地策略。

可信生产者由 Broker authentication 与 Destination ACL 建立，消费端再校验 Message Kind、Message Type、Source 和 Destination，并只授予固定的最小 `inventory:reserve` 权限。Message Envelope 不携带可直接信任的权限，source 字符串匹配也不单独承担身份认证。

## User Stories

1. As an Inventory 开发者, I want 库存预占只暴露真实存在的异步入口, so that Service API 不会暗示一个并未支持的同步调用能力。
2. As an Order 开发者, I want 从 Inventory API 模块获得提供方拥有的 Reserve Inventory Command 契约, so that Order 可以构造兼容的异步消息而无需依赖 Inventory 实现。
3. As an Order 开发者, I want 使用 Inventory 发布的 canonical message type 和 destination, so that Producer 与 Consumer 不会因重复字面量漂移。
4. As an Inventory 开发者, I want Message Handler 直接调用 Inventory Application Service, so that 一个只有内部单一调用者的同步 Java 接口不会成为假接缝。
5. As an Inventory 开发者, I want Handler 继续负责 Message Envelope 和 Payload 的适配, so that Application 与 Domain 不依赖 Spring Messaging、Kafka 或序列化机制。
6. As an Inventory 开发者, I want 在 Command 语义与内部用例完全一致时复用同一协议无关类型, so that 不产生字段一一复制的 DTO 和 Mapper。
7. As an Inventory 开发者, I want 只有在可指出真实语义或协议差异时引入 Mapper, so that模型转换承担明确价值而不是装饰分层。
8. As a Future API developer, I want 新的 Inventory HTTP 入口独立定义其契约, so that HTTP 用例不被迫与异步命令保持完全一致。
9. As a Future API developer, I want 在 HTTP validation、response shape、status 或 version 确有差异时执行映射, so that协议差异不会泄漏进 Application。
10. As a Message Contract 维护者, I want breaking contract versions 在 Message Type 中显式表达, so that旧 Consumer 与新 Consumer 可以按适配器独立演进。
11. As a Message Contract 维护者, I want 当前 v1 契约保持不变, so that此次接缝收紧不会改变已发布消息 Payload。
12. As a Security 维护者, I want Broker authentication 和 Destination ACL 限制谁能写入库存命令通道, so that消费成功所代表的生产者信任具有基础设施证据。
13. As a Security 维护者, I want Consumer 只配置固定的最小执行权限, so that生产者不能通过消息声明扩大授权范围。
14. As a Security 维护者, I want source validation 被视为契约校验而非身份认证, so that后续实现不会把可伪造字符串当作安全边界。
15. As an Audit 维护者, I want Initiator、Tenant 和 Correlation 继续从经过验证的 Message Envelope 传播, so that异步调用链保留审计与诊断信息。
16. As an Application 开发者, I want Inventory Application Service 继续执行授权、预占与结果发布, so that移除 Java 接口不会移动或复制业务用例逻辑。
17. As a Domain 开发者, I want 库存全成或全拒、幂等和并发不变量保持不变, so that架构收紧不会改变业务行为。
18. As an Operations 维护者, I want Outbox、Inbox、bounded retry 和 Dead Letter 语义保持不变, so that接口重构不会削弱可靠性。
19. As a Modular Monolith 维护者, I want 模块化单体继续激活同一个 Inventory 消息 Inbound Adapter, so that构建拓扑不会复制异步处理语义。
20. As a Microservices 维护者, I want 独立 Inventory App 继续通过真实 Kafka 消费同一契约, so that Golden Path 保持真实进程边界。
21. As a Test 维护者, I want 通过最高层级的真实 Kafka Inventory App seam 验证行为, so that测试证明最终效果而不是类之间的转发次数。
22. As a Test 维护者, I want focused Application tests 保留授权、预占和结果发布断言, so that移除接口后核心用例仍有快速反馈。
23. As an Architecture 维护者, I want配置与架构测试明确禁止重新引入仅供异步 Handler 使用的同步 Inventory API, so that该模式不会在后续重构中回流。
24. As an Architecture 维护者, I want API 模块继续不依赖 Transport Framework, so that消息契约可以被 Producer 轻量消费。
25. As an AI 编码代理, I want 在 `AGENTS.md` 中看到新增接口和 Mapper 的完成门槛, so that生成代码时不会机械复制传统分层模式。
26. As a Human Reviewer, I want 每个新增 DTO/Mapper 都说明其保护的可观察差异, so that评审可以删除没有收益的抽象。
27. As a Human Reviewer, I want 每个新增同步 `*Api` 方法都指出受支持的直接调用者, so that异步能力不会被错误包装成同步 Service API。
28. As a Repository 维护者, I want Context、ADR、Agent 指令和实现使用相同术语, so that长期演进不会出现文档与代码互相矛盾。
29. As a Downstream Consumer, I want此次修改不改变 Message Type、Destination 和 Payload 字段, so that现有集成不需要迁移。
30. As a Maintainer, I want 将结果事件类型是否拆分等相邻议题推迟到独立规格, so that本次工作保持聚焦。

## Implementation Decisions

- Inventory reservation remains an Asynchronous Command and the only supported entry for the current reserve use case.
- Remove the synchronous-shaped Inventory Java Service API interface and its `reserve` method because no supported direct Local Call or Remote Call uses that seam.
- The Inventory message Inbound Adapter invokes the concrete Inventory Application Service after generic reliable-consumer processing and Payload deserialization.
- The Inventory Application Service no longer implements the removed Java interface; its authorization, inventory decision, idempotency interaction and result publication behavior remain unchanged.
- Keep `Reserve Inventory Command`, its line records, `Inventory Reserved`, `Inventory Rejected`, and their shared result abstraction in the Inventory API module as provider-owned, protocol-neutral message contracts.
- Do not add mirror application DTOs or one-to-one Mappers for the current Command and Result types. Reuse them while their meaning, invariants and shape remain identical.
- A future divergent contract version receives a version-specific Adapter and explicit mapping into stable Application semantics; current code does not prebuild that mapping.
- A future HTTP entry is designed independently and is not required to expose the same request, result, timing or failure semantics as the asynchronous command.
- Publish canonical message kind, type and logical destination values with the Inventory-owned message contract using framework-independent values.
- The Order Outbox publisher and Inventory inbound configuration consume the same canonical values rather than owning duplicate literals.
- Keep consumer ID, accepted source, Execution Actor and `inventory:reserve` permission in the Inventory consumer configuration because they are local processing and trust policy.
- Continue validating expected Message Kind, Message Type, Source and Destination before invoking business handling.
- Continue deriving Tenant, Initiator and Correlation from the validated Message Envelope while deriving the current execution Actor and permissions from trusted consumer policy.
- Do not add permissions to the wire envelope. Broker authentication and Destination ACL remain required parts of trusted-producer verification; source equality is not cryptographic authentication.
- Keep the Inventory API module free of Spring Web, Spring Messaging, Kafka Binder and Moduvera transport abstractions.
- Preserve existing Outbox/Inbox atomicity, at-least-once delivery, duplicate handling, bounded retries, result events and supported application topologies.
- Update Inventory inbound wiring, Application Service declarations and focused tests that currently depend on the removed interface.
- Update modular-monolith and configuration-slice assertions so they verify the selected Application Service and inbound adapter rather than the removed Inventory API bean.
- Keep the repository Agent guidance, Service API glossary and ADR 0004/0021/0031 aligned with the implemented shape.
- No database schema, public HTTP route, Broker destination, message Payload schema or business state transition changes are required.

## Testing Decisions

- A good test observes authorization, reservation outcome, published result, idempotency or contract rejection through a supported seam. It does not assert that a Mapper, interface method or delegation call exists merely because those structures were previously present.
- The highest product seam is the existing Inventory App integration test with real Kafka. It must prove that a valid Reserve Inventory Command is accepted, processed once from the business perspective and produces the expected Inventory Reserved or Inventory Rejected result.
- The same real-Kafka seam must continue proving Message Envelope validation, trusted Execution Context construction, tenant/correlation propagation and duplicate-delivery safety.
- Existing wrong-kind, wrong-type, wrong-source and wrong-destination scenarios remain contract-rejection coverage and must fail before Application behavior executes.
- Existing invalid-JSON coverage remains a focused Inbound Adapter test and must classify malformed Payload as non-retryable without requiring a duplicate internal DTO.
- Inventory Application Service tests remain the fast seam for `inventory:reserve` authorization, Inventory Store invocation, created-result publication and replay behavior.
- Configuration-slice tests must prove that the Inventory Application slice creates the Application Service and that the message inbound slice can be activated without a public Inventory Java API interface.
- Modular-monolith integration coverage must prove the same Inventory Application Service and reusable message Inbound Adapter are active without expecting an Inventory API bean.
- Architecture tests should guard the stable structural rules that can be expressed reliably: business API modules remain transport-framework independent, App Assemblies do not own message handlers, and async-only Inventory handling does not require a synchronous public interface.
- Do not add an Architecture rule that guesses whether arbitrary DTO fields are semantically identical. The enforceable review gate for Mapper value lives in `AGENTS.md`; behavioral tests cover the observable contract.
- Reuse the existing reliable-consumer tests as prior art for contract validation, trusted context, Inbox behavior and retry classification; this feature should not duplicate generic messaging tests in Inventory.
- Reuse existing Inventory Application and real-Kafka App tests as prior art rather than introducing a new test-only interface solely for mocking.
- Run the narrow Inventory service/module tests first, then Inventory App integration tests, modular-monolith focused tests and the architecture testkit affected by the structural change.
- Completion requires current evidence from the focused tests above plus formatting/static checks for every modified module. A successful compile alone is insufficient.

## Out of Scope

- Adding a synchronous or asynchronous Inventory HTTP endpoint.
- Guaranteeing that a future HTTP use case has identical semantics to Reserve Inventory Command.
- Changing Reserve Inventory Command or Inventory result Payload fields.
- Renaming the Inventory API Maven module.
- Splitting Inventory Reserved and Inventory Rejected into different Message Types.
- Adding a DTO mapping framework or speculative version-conversion layer.
- Adding message signatures, mutual TLS, Kafka ACL provisioning or a new producer-authentication protocol.
- Changing Broker technology, Topic topology, Outbox/Inbox algorithms, retries or Dead Letter routing.
- Changing Inventory persistence, concurrency control, transaction boundaries or database schema.
- Changing Order state transitions, result handling or public Order HTTP contracts.
- Refactoring Catalog or Order Service APIs beyond consuming the shared Inventory message constants where required.
- Creating a new Maven artifact solely for Inventory HTTP or messaging adapters.

## Further Notes

- ADR 0004 applies the shared Local/Remote Service API pattern only to capabilities offered as direct calls; it no longer implies a Java method for every asynchronous command.
- ADR 0021 remains the security basis: the envelope carries no trusted permissions, and source validation is contract validation rather than producer authentication.
- ADR 0031 is the model-conversion basis: Adapter protocol mechanics are mandatory, duplicate transport/application models are conditional on an observable semantic difference.
- Repository Agent guidance is part of this feature's durability mechanism. It supplies the interface and Mapper decision gates on every future coding task, while ADRs retain rationale and applicability.
- The current implementation still contains the Inventory Java interface described by the Problem Statement. This specification authorizes its removal but does not treat the documentation-only changes already made as implementation completion.
