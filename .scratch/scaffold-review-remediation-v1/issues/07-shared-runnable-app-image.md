Type: issue
Status: resolved
Blocked by: None

# 07 — 建立 Runnable App 的共享容器镜像基线

## Outcome

让所有 Runnable App Assembly 使用同一个参数化 Dockerfile，把已构建的 Spring Boot 可执行 JAR 封装为分层、非 root、可外部配置的 Java 26 OCI 镜像。

## Acceptance Criteria

- [x] 所有纳入范围的 Runnable App 共用一个 Dockerfile；它消费 Maven 已产出的可执行 JAR，不在镜像构建中重新运行 Reactor，也不按 App 复制实现。
- [x] Dockerfile 使用 Spring Boot tools jarmode 拆分依赖、loader、snapshot dependency 和 application 层，使仅业务代码变化时稳定依赖层可复用。
- [x] 默认 Java 26 JRE 基础镜像由可审查的不可变 digest 固定；运行进程为非 root，工作目录和入口明确，镜像不包含源码、构建凭据、测试资产或本地 secrets。
- [x] 外部配置与现有 Actuator 行为保持可用；Dockerfile 不统一设置服务端口或 Debug 端口，`EXPOSE` 仅通过参数记录元数据，JDWP 默认不监听。
- [x] 所有必需 App 镜像可从干净构建产物创建；至少一个代表性 App 连接真实依赖后，以非 root 身份通过健康检查和最小 HTTP smoke，并验证端口覆盖与 JDWP 默认关闭。
- [x] 产品与架构文档准确描述容器构造的新支持边界，同时明确不把共享 Dockerfile 或 Reference Compose 宣称为生产部署模板。

## Verification

- 构建所有纳入范围的 App 镜像并检查 OCI layer 复用。
- 对代表性镜像执行用户身份、外部配置、health/HTTP 和 Debug 端口 smoke。
- 检查镜像文件系统和构建上下文没有带入源码、凭据、测试资产或 secrets。

## Out of Scope

- Kubernetes、Helm、镜像发布、签名、供应链证明和生产 secrets 方案。

## Answer

- 实现分支：`codex/remediation-v1-07-runnable-image`，最终提交 `5b1090c102ddeef5a7436116250a719a3c50ff59`。
- 最终 Standards Review 与 Spec Review 均为 no findings；六个 Runnable App 共用 `build/docker/Dockerfile.jvm`，消费预构建 executable JAR 并生成 Spring Boot 四类应用层。
- 六镜像构建、root-owned/non-root 权限、Java 26 digest、per-App EXPOSE、层复用、源码/测试/凭据/secret 扫描全部通过；敏感策略覆盖 quoted JSON、literal env fallback 与 UTF-16/NUL/解码失败且不回显值。
- Catalog 连接真实 PostgreSQL 的外部端口、Actuator UP、HTTP 401、UID/GID、JDWP 默认关闭/运行时显式启用 smoke 通过；Monolith 默认监听与映射 8083 通过，临时容器和网络无残留。
- ADR 0036 与产品文档明确该能力仅定义共享镜像构造，不是生产部署模板；未发布镜像或引入编排/签名方案。
