# 08 — 撤回 Object Storage 并冻结无消费者能力

**What to build:** 完整撤回没有业务消费者的 Object Storage 公共承诺，并让当前产品状态清楚区分 Supported、Incubating/Frozen、Deferred 与 Withdrawn，而不改写历史审计记录。

**Blocked by:** None — can start immediately.

**Status:** resolved

- [x] Object Storage artifact、公共类型和自包含合约测试从当前 Reactor 中删除。
- [x] Reactor、BOM、BOM consumer smoke、模块清单、重命名清单和当前能力文档不再发布或宣传 Object Storage 坐标。
- [x] BOM smoke 证明已撤回坐标不存在，其余发布坐标仍可无版本解析。
- [x] 当前决策与产品文档把 Cache 标记为 Deferred、Lock/Scheduler 标记为 Incubating/Frozen、Object Storage 标记为 Withdrawn，并消除相互冲突的现行状态。
- [x] 生产与测试代码不再导入撤回类型；历史 grilling transcript 与问题索引保持原样。
- [x] Lock 与 Scheduler 的代码、API 和既有行为测试不扩展、不删除，并继续通过现有验证。

## Answer

Implemented by commits `8d7b3be` and `f512aeb`, independently reviewed on standards and specification axes, and integrated into `codex/foundation-hardening-integration`. The generated effective consumer POM proves all remaining managed coordinates resolve versionlessly and the withdrawn coordinate is absent; the 26-module verification passed while historical grilling artifacts remained byte-identical.
