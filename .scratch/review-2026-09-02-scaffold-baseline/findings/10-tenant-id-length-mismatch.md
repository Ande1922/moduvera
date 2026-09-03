Type: finding
Status: confirmed
Severity: major
Area: services
Claim: Tenant ID 没有统一契约：Kernel、Order 和 Inventory 使用 64 字符上限，Catalog、Identity 与可靠消息 schema 使用 128，Identity 服务令牌入口还会接受并签发 65–128 字符的 tenant_id，导致签发方接受而下游 ExecutionContext 拒绝。
Evidence: framework kernel 的 TenantId 只接受 1–64 个 portable identifier 字符；order/inventory、示例与 persistence benchmark 使用 VARCHAR(64)，catalog/identity 和 messaging Outbox/Inbox 使用 VARCHAR(128)。IdentityService.serviceToken 使用独立的 1–128 字符正则，JwtExecutionContextFactory 随后用 canonical TenantId 解析 JWT claim。
Verification: 比较业务服务、Identity、可靠消息、示例与 benchmark 的 PostgreSQL/MySQL SQL；读取 TenantId、IdentityController、IdentityService 和 JwtExecutionContextFactory，确认 65–128 字符 tenant_id 可以被 Identity 签发但不能建立下游可信上下文。
Planned: .scratch/tenant-identifier-contract/spec.md
Verdict: 接受。脚手架 Kernel、传输和 Reference Product 统一保持 1–64 字符的 String Tenant ID，所有产品 schema 对齐 VARCHAR(64)，且不在脚手架实现 String/BIGINT 转换 SPI。正式业务系统是否在源码中改为 BIGINT，或在不可变依赖边界增加转换，作为该业务系统自己的待办。

# Tenant ID 契约在签发方、消费方和数据库之间不一致
