Type: finding
Status: wontfix
Severity: minor
Area: framework
Claim: Lock/Scheduler 没有参考产品业务消费者，BOM 管理 Redisson 版本但仓库没有 Redisson Lock Adapter。
Evidence: docs/implementation/SCAFFOLD-PRODUCT-SURFACE.md 已把 Lock/Scheduler 标为 Incubating/Frozen；framework/bom/pom.xml 管理 redisson-spring-boot-starter，而参考产品未使用 LockTemplate 或 JobDefinition。
Verification: 确认 BOM 的 Redisson 坐标、搜索 Lock Adapter 实现，并在 services、apps 和 examples 中搜索 LockTemplate 与 JobDefinition 的生产消费者；判断该现状是否超出已声明的 Frozen 边界。

# Lock 与 Scheduler 尚无业务消费者

## Verdict

Claim 的事实成立，但现状符合已接受的产品边界。Foundation runtime hardening 明确保留 Lock/Scheduler 的现有代码和测试、冻结其公共面；产品面与决策台账也要求等真实业务消费者出现后，才重新建立分布式锁、调度重叠和租户语义。BOM 管理 Redisson 版本不构成 Redisson Adapter 或 Supported 能力声明。

维护者决定暂不处理。本 review 将该 finding 记为 wontfix，维持 Incubating/Frozen 状态，不进入下一版本；未来出现真实业务消费者时，以新的 consumer-driven topic 重新评估，而不是由本 finding 预先扩展能力。
