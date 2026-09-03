Type: finding
Status: rejected
Severity: minor
Area: services
Claim: inventory-app 没有 OAuth2 resource server，因此其安全边界缺失。
Evidence: inventory-app 没有 HTTP 业务入口，预占能力是异步-only，按 ADR 0031 由消息 Adapter 直接调用 Application。Inbound contract 校验 source/destination，并由消费方本地策略建立 execution Actor 与 permissions。
Verification: 比较三个 App 的入口、pom 与安全配置；读取 ADR 0021、ADR 0031 和 ReserveInventoryCommandInboundConfiguration，区分 HTTP resource server 与 Broker authentication/destination ACL 两种不同信任边界。

# Inventory 缺少 resource server

## Verdict

Claim 为假。Inventory 没有 HTTP 业务入口，resource server 不是异步-only 消息能力的适用安全机制。消息路径的真实信任边界是 Broker authentication、destination ACL、契约校验与消费方本地执行策略；其中 Broker 交付证据是否充分可以另立 finding，但不能由“未依赖 resource server”推出。
