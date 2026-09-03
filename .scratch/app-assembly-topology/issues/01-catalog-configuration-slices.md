# 01 — 显式装配 Catalog Local API、Persistence 与 Internal HTTP

**What to build:** 让独立 Catalog App 保持现有受保护的 Internal HTTP 查询，同时把 Catalog Application、Persistence/Migration 和 Internal HTTP Inbound 作为同一 `catalog-service` Jar 内可分别导入的 configuration slices；业务接口和适配器不依赖宽泛组件扫描碰巧激活。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] 独立 Catalog App 显式选择 Application、Persistence/Migration 和 Internal HTTP slices，并继续通过现有 Internal HTTP 契约、鉴权、租户隔离与错误映射测试。
- [x] Local `CatalogApi`、MyBatis `ProductRepository` 与 Internal Controller 由不同配置入口激活；未导入某个 slice 时不会连带激活该能力。
- [x] Repository 业务端口保持 Spring/MyBatis 中立，具体 Persistence Adapter 通过显式 configuration binding 注册。
- [x] 不新增 Maven artifact，不改变 Catalog Internal HTTP 路径或数据库所有权。

## Answer

由 `ea72d7d` 实现。Catalog 的 Application、Persistence 与 Internal HTTP
configuration slices 及其聚焦测试均已提交；当前仓库验证继续覆盖独立 App
装配、鉴权、租户隔离和稳定 HTTP 契约。
