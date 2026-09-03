Type: finding
Status: confirmed
Severity: minor
Area: services
Claim: Catalog 已经由 App 直接选择业务配置切片，但仓库仍发布、测试并在架构规则中登记一个无生产消费者的 deprecated service-level configuration facade，容易让后续维护者误认为旧装配入口仍受支持。
Evidence: catalog-service 同时存在 reference.catalog.CatalogModuleConfiguration compatibility facade 与 reference.catalog.catalog.CatalogModuleConfiguration；CatalogApplication 已直接导入后者。前者仅转发到后者，但另有 CompatibilityTest、架构规则谓词和负向 fixture 维持其兼容承诺。catalog.catalog 业务模块包本身不是该问题，确认保留且不压平。
Verification: 读取两个 CatalogModuleConfiguration、CatalogApplication、兼容性测试、允许并限制 compatibility facade 的架构规则及其负向 fixture；搜索确认当前生产 App 不依赖废弃 facade。
Planned: .scratch/business-service-module-layout/spec.md
Verdict: 接受。下个版本删除 reference.catalog.CatalogModuleConfiguration、对应兼容性测试和只服务于该类型的架构规则/fixture，并修订原有兼容 shim 约束；保留 catalog.catalog 包结构。

# 删除 Catalog 废弃装配门面
