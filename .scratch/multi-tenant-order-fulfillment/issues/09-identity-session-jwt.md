# 09 — 交付 Identity Session 与 Internal JWT

**What to build:** 让 `identity-app` 通过正常登录签发 opaque browser token，并为 Gateway 提供受保护的 token exchange，产生资源服务可验证的短生命周期 internal JWT 和 tenant-level RBAC 上下文。

**Blocked by:** 01 — 固化独立消费者平台门禁.

**Status:** resolved

- [x] Identity 使用真实 PostgreSQL migration 保存演示 user/service identity、tenant membership、permission assignment 与 opaque session。
- [x] 正常 session login 验证凭证后签发不可自描述、可撤销或过期的 opaque browser token。
- [x] token exchange 只允许受信 Gateway service identity 调用，并为有效 session 签发短生命周期 internal JWT。
- [x] internal JWT 具有签名、issuer、audience、期限、actor type、subject、tenant、permissions 和 original initiator claim。
- [x] 提供 tenant-a、tenant-b、无创建权限用户和 Gateway service identity 的验收 fixture，但不增加绕过验证的生产 token endpoint。
- [x] Resource Server contract test 验证合法、过期、错误 issuer/audience/签名及 USER/SERVICE tenant 规则。
- [x] 密钥、密码和 token 不硬编码或写入日志；验收环境通过受控配置和 test migration 注入 fixture。

## Answer

`identity-app` now owns a PostgreSQL-backed opaque browser-session contract and a Gateway-only exchange that issues five-minute RSA-signed internal JWTs. Its real PostgreSQL HTTP integration test covers valid issuance, membership and credential failures, revoked sessions, audience allowlisting, and rejection of expired, wrong-issuer, wrong-audience, and wrong-signature JWTs. USER and SERVICE tenant-entry semantics remain enforced by the platform resource-server contract tests. Runtime database credentials are mandatory environment inputs; acceptance identities and passwords exist only in test setup.
