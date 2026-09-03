Type: finding
Status: confirmed
Severity: minor
Area: delivery
Claim: 参考产品 harness 虽已隔离 Compose project、Kafka topic、临时目录和测试数据，但默认共享一组静态宿主机端口，且 Compose 没有 Kafka healthcheck，无法作为同一宿主机上的并行质量门禁实例直接运行。
Evidence: run-topology.sh 的默认端口可由环境变量覆盖，并通过 kafka-topics --list 主动探测 Kafka；Compose 本身未声明 Kafka healthcheck。verify.sh 在输出 duplicate-delivery PASS 前执行 Maven/Failsafe 验证，因此原“只 echo PASS”判断不成立。
Verification: 第二读者检查 verify.sh、run-topology.sh、Compose 和 Parent 的 Failsafe binding；并行启动两个未覆盖端口的 harness 以复现冲突，确认脚本探测与 Compose health 状态对外部编排的影响。
Planned: .scratch/hosted-ci-quality-gates/spec.md
Fixed: 9ce527aaaf7d77589320091b2466cdb740651534
Verdict: 接受并合并到托管 CI 与质量门禁 topic。Harness 需要实例级端口隔离并为 Kafka 暴露 Compose healthcheck；不把 Reference Compose 扩展为生产部署方案。

# 参考产品 harness 的端口与就绪契约
