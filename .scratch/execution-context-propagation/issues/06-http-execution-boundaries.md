Type: issue
Status: claimed
Blocked by: 02

# HTTP 租户与平台入口闭环

Spec: [spec.md](../spec.md)
Map: [map.md](../map.md)
Stories: US12、US13、US14、US21、US22
Test seams: T04、T08、T09

## Outcome

从可信认证映射到实际 Servlet 处理器建立正确 Platform/Tenant 范围；默认租户入口、显式平台入口与非业务排除能够共存，且不削弱身份验证、权限或线程清理。

## Base and scope

基于 02 的三态模型和恢复内核。范围为 Resource Server 认证/上下文接入、处理器选择与模式声明、测试消费者配置及相应文档，不依赖执行器、Reactor 或 AI 票。

## Acceptance criteria

- [ ] App 配置显式选择受管 Controller/处理器及非业务排除项；模式为方法 > 类 > 默认 TENANT，不因未声明而全量扫描后拒绝启动。
- [ ] permitAll、JWT/Tenant-Id 有无不充当隐式受管选择器；排除只影响此上下文接入，不绕过认证、授权或租户资源检查。
- [ ] 认证映射允许可信且无 tenant 断言的 USER，保持合法 Actor/Initiator；同一身份在 PLATFORM 可执行获授权用例，在 TENANT 拒绝且不降级。
- [ ] TENANT 保留租户断言/目标冲突拒绝；SERVICE 指定租户不自动获得权限；PLATFORM 不因 Header 自动切换，不创建新的模拟租户协议。
- [ ] 受保护入口缺失/无效认证为 401；已认证但不满足 TENANT 范围为 403；下游缺上下文编码错误不一律变为 401，沿用既有 HTTP Problem 形状。
- [ ] 在用例前完成处理器模式解析与安装，不先由旧 Filter 强制 tenant 再由 Controller 修正；认证/授权不能仅依赖 MVC 拦截器。
- [ ] 在平台线程、虚拟线程及必要同步/async/error dispatch 上按实际线程开关 Scope；成功、异常和拒绝后不泄漏，也不保存一个跨线程可关闭 Scope。
- [ ] 签名有效但无 tenant 的 JWT 访问已排除的非业务处理器时不误触发 TENANT 要求；平台/租户声明组合及未授权调用均有负向证据。
- [ ] 真实消费者公共接入、配置迁移、线程/dispatch 支持边界和传播 ADR 更新随票交付；不为了测试增加生产业务端点。

## Verification

- 复用 Resource Server 与既有 HTTP 消费者接缝，运行真实安全链、签名 JWT、处理器解析和 Controller；测试源码可定义最小处理器，不只单测注解 resolver。
- 证明用例内部完整上下文与执行后的线程状态，拒绝在用例/持久化副作用前发生；保留既有认证、租户隔离和 HTTP Problem 回归。
- 聚焦 `framework/starters/moduvera-auth-resource-server-autoconfigure` 相关测试，再执行实际消费者的 Servlet/虚拟线程 IT；发生公共 HTTP/App 装配变更时履行适用 Scenario 回归，11 不替代本票基本证据。
- 在 Answer 中记录具体接入时序、base/head、运行命令和实际支持 dispatch；如无法满足既定契约，带证据返回 Grill，不静默缩小范围。

## Exclusions

不建设 IAM/经销商权限体系、不引入 Header impersonation、不做全端点强制声明启动校验、不承诺完整 MVC 异步类型自动传播，不实施相邻 HTTP Problem 主题。

## Comments

- 2026-09-05：等待 02；HTTP Problem 复用既有接缝，不把相邻未完成 topic 自动变成技术前置。
