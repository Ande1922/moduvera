# 07 — 完成 Order 到 Catalog 的 declarative Client Group 链路

**What to build:** 让 Order 的远程 Catalog lookup 使用独立具名 Client Group，并通过真实 Identity-token-to-Catalog 路径证明声明式 transport 没有改变安全上下文、业务错误或本地拓扑语义。

**Blocked by:** 06 — Identity Token 调用迁移到 declarative Client Group.

**Status:** ready-for-agent

- [ ] Catalog 声明式 transport 位于 Order Outbound HTTP Adapter 内，协议中立的 `CatalogApi` 及公共 HTTP 契约保持不变。
- [ ] `catalog` Client Group 拥有必填 base URL、独立连接/读取超时、Apache HC5 池化连接和原生 observations，且不启用自动重试或其他推测性弹性策略。
- [ ] Outbound Adapter 继续从可信 Execution Context 建立 Service Token、Tenant 与 Correlation headers，并把空响应、非成功状态和 transport failure 转换为现有 Catalog call failure 语义。
- [ ] Order App 集成测试证明完整远程链路保留 Tenant、Initiator 与 Correlation 语义，并在相同安全作用域内复用有效 Service Token。
- [ ] 旧的自定义 base URL/timeout 配置被一致迁移，缺失 group 配置在启动时失败；Monolith 仍选择 Local Catalog 且无需远程 Client Group 配置。
