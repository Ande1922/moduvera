Type: finding
Status: wontfix
Severity: minor
Area: apps
Claim: identity-app 每次启动生成新 RSA key pair；使用新 JWKS 的 verifier 无法验证重启前签发的 JWT。
Evidence: IdentityConfiguration 每个 context 创建 IdentitySigningKeys，后者生成 RSA 与随机 kid，JWKS 只发布当前公钥。已缓存旧 JWKS 的 verifier 可能在缓存期内继续接受旧 token；产品面也把临时密钥限定为 Demo evidence。
Verification: 读取 IdentityConfiguration、IdentitySigningKeys、IdentityService、IdentityJwksController 和下游 JWKS 配置；或增加“签发 JWT → 重建 Identity context → 使用新 JWKS 验证旧 JWT”的测试，并明确 JWKS 缓存条件。

# Identity 使用临时 RSA

## Verdict

Claim 的技术事实成立：`IdentitySigningKeys` 每次实例化都会生成新的 RSA key pair 与随机 `kid`，JWKS 只发布当前公钥，因此使用新 JWKS 的 verifier 不能验证重启前签发的 JWT；缓存旧 JWKS 的 verifier 则可能在缓存期内暂时继续接受旧 JWT。

维护者决定不处理。当前产品面已把临时签名密钥限定为 Demo evidence，并明确排除生产 IAM 密钥存储、轮换与多实例运行；ADR 0035 也不让 Scaffold 拥有具体 IAM 产品。该限制已披露且不违反当前承诺，因此状态记为 wontfix，Severity 从 major 调整为 minor，不进入下一版本。
