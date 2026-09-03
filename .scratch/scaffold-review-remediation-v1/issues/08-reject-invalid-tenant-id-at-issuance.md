Type: issue
Status: resolved
Blocked by: None

# 08 — 阻止 Identity 签发非法 Tenant ID

## Outcome

让 Identity 在建立 session 或签发用户与服务 JWT 前应用 canonical Tenant ID 规则，保证每个已签发 tenant claim 都能被下游可信执行上下文接受。

## Acceptance Criteria

- [x] 登录/session 和 service-token 入口都复用 canonical Tenant ID 的 1–64 字符值域，不再维护允许更长值或不同字符集的独立校验。
- [x] 空值、65 字符值和非法字符在签发前被拒绝；1 字符、64 字符及代表性 portable identifier 可正常签发。
- [x] 用户 JWT 的 tenant claim 保持原字符串序列化并能由 Resource Server 建立可信 ExecutionContext；service-token 在签发前校验请求中的 Tenant ID，但继续保持当前不写入 `tenant_id` claim 的安全契约，由受信请求头建立服务租户上下文。
- [x] 非法登录 Tenant ID 保持不泄露用户或租户存在性的失败语义；非法服务上下文保持现有的明确客户端错误语义。
- [x] 共享有效/无效契约数据覆盖 Identity、canonical Tenant ID 和至少一个下游消费边界，避免各入口再次漂移。
- [x] 现有认证、授权和 token 语义除 tenant 校验收紧外保持不变。

## Verification

- 运行 Identity 聚焦测试，覆盖 1/64/65 字符、非法字符和用户/服务 token。
- 用签发结果执行 Resource Server 与消息执行上下文的契约测试。
- 搜索并拒绝生产签发路径中的独立 tenant 正则或 128 字符规则。

## Out of Scope

- 数据库列迁移、BIGINT Tenant ID、转换 SPI 或租户目录设计。

## Answer

Implemented and integrated by `df5a22cc34e05a215bedacb556a8b37fb5775c45`.

- Standards Review: no actionable findings.
- Spec Review: no actionable findings.
- Verification: focused shared-contract, Identity, resource-server and messaging tests passed; PostgreSQL-backed `IdentityApplicationIT` passed 20 tests with no failures, errors or skips; Spotless, PMD and JaCoCo reporting passed in the same reactor.
- Scope: service JWTs remain free of `tenant_id`; database narrowing remains owned by Ticket 09.
