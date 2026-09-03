Type: finding
Status: confirmed
Severity: minor
Area: delivery
Claim: 仓库没有应用镜像构建路径，镜像与生产部署能力仍处于明确延期/open 状态。
Evidence: 未见 Dockerfile、Jib 或 Spring Boot build-image 配置。ADR 0013 明确排除 container construction/scanning/signing，现有 Parent 只落实 outputTimestamp 与 CycloneDX SBOM；ADR 0014 为 open，产品面也把生产部署模板列为 Deferred。
Verification: 搜索 Dockerfile、jib、build-image 与镜像发布配置；读取 ADR 0013、ADR 0014、Parent build plugins 与 SCAFFOLD-PRODUCT-SURFACE，判断是否只是已披露边界，还是阻碍当前声明支持的交付场景。
Planned: .scratch/application-container-image/spec.md
Fixed: 5b1090c102ddeef5a7436116250a719a3c50ff59
Verdict: 接受。下个版本增加仓库拥有的 Dockerfile 和应用容器镜像基线；不由此确认 Kubernetes、Helm、镜像发布、签名或完整生产部署契约。

# 缺少应用容器镜像基线
