Type: issue
Status: ready-for-agent
Blocked by: None

# 06 — 让 Reference Harness 支持并行实例

## Outcome

让同一宿主机上的多个 Reference Harness 实例通过显式 run slot 获得可预测且互不冲突的端口与运行资源，并让 Kafka 就绪状态可被 Compose 和外部门禁可靠读取。

## Acceptance Criteria

- [ ] Harness 接受默认值为 0 的显式 run slot，并以固定步长派生服务、Kafka、management 和启用时的 Debug 端口；既有拓扑、topic、临时目录和测试数据隔离保持成立。
- [ ] 每次运行在启动前输出机器可读和便于人工诊断的端口 manifest；任一所需端口已占用时直接失败，不静默选择随机端口。
- [ ] Reference Compose 为 Kafka 暴露与现有主动探测一致的 healthcheck，Harness 只在真实可用后继续执行。
- [ ] 至少两个不同 run slot 的配置可同时存在并通过聚焦并行验证；相同 slot 或人为占用端口会在启动进程前给出明确失败。
- [ ] 五 App Golden Path 与 business-core monolith 继续复用原公共 HTTP 黑盒步骤，默认 slot 0 保持现有调用兼容。

## Verification

- 运行端口派生、manifest 和冲突检测的确定性测试。
- 同时启动两个不同 run slot 的代表性 harness 实例并核对隔离资源。
- 验证 Kafka health 状态与现有实际可用性探测一致。

## Out of Scope

- 把 Reference Compose 宣称为生产编排契约或引入动态端口猜测。
