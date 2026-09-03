Type: spec
Status: ready-for-agent
Fixes: review-2026-09-02-scaffold-baseline/12

# 应用容器镜像基线

## Outcome

为 Runnable App Assembly 提供仓库拥有、可重复使用并由质量门禁验证的 Dockerfile 基线，使脚手架消费者能够把已构建的应用制品封装为行为一致的 OCI 镜像，而不把本工作扩张成生产部署平台。

## Confirmed boundary

- 下个版本增加 Dockerfile，不再把应用镜像构建整体列为 Deferred。
- 所有 `apps/*` 共用一个参数化 Dockerfile，消费 Maven 已构建的 Spring Boot 可执行 JAR；不在每个 App 复制 Dockerfile，也不在每次镜像封装中重新构建 Maven reactor。
- Dockerfile 使用 Spring Boot 4.1 `jarmode=tools` 提取 `dependencies`、`spring-boot-loader`、`snapshot-dependencies` 和 `application` 层，并分别写入 OCI layers。
- 镜像必须运行当前 Java 26 Spring Boot App Assembly，并保留现有外部配置和 Actuator 行为。
- Dockerfile 不设置统一的 `SERVER_PORT` 或 Debug 端口。各 App 保留自身默认 HTTP 端口，并允许运行时覆盖；镜像构建可通过 `APP_PORT` 记录对应的 `EXPOSE` 元数据。
- JDWP 默认关闭。开发者通过运行时 JVM 选项和端口映射显式开启，每个 App 可以独立选择 Debug 端口。
- 建立最小运行时安全基调，包括非 root 进程、明确入口、可预测的工作目录和不在镜像中携带源码构建凭据。
- 镜像构建必须进入仓库质量门禁，至少证明所有纳入范围的 Runnable App 能构建，并对代表性镜像执行启动冒烟验证。
- Dockerfile 基线不确认生产部署拓扑，也不新增 Kubernetes、Helm、release orchestration、registry publication 或运行时 secrets 方案。

## Implementation defaults

- 共享 Dockerfile 通过一个集中的 build argument 选择 Eclipse Temurin Java 26 JRE 运行时镜像，仓库默认值使用不可变 digest。安全更新通过可审查的 digest 升级完成，不使用静默漂移的 floating runtime 作为可复现基线。
- Dockerfile 不声明通用 `HEALTHCHECK`。App 可用性取决于其实际端口、安全和 Actuator 配置；容器启动 smoke 与 Reference Compose/未来编排层使用实际运行配置执行 readiness 检查，避免在共享镜像中固化一个对所有 App 都不真实的端点。
- 第一迭代的必需镜像范围是 `apps/` 下的 Runnable App Assembly。`simple-notes-demo` 作为脚手架消费示例继续使用同一 Dockerfile 契约，但不阻断本迭代验收；等消费者镜像作为独立支持面后再升级为必需构建。

## Required evidence

- 每个纳入范围的 App 镜像能够从干净构建产物创建。
- 容器以非 root 身份启动，并能通过外部环境变量覆盖端口、数据库、身份和消息配置。
- 分层镜像中依赖层可被缓存复用，业务代码变化不重写稳定依赖层。
- JDWP 未配置时不监听 Debug 端口；显式配置后可通过独立宿主机端口连接调试。
- 至少一个 Reference App 镜像连接真实依赖后通过健康检查和最小 HTTP 冒烟验证。
- 镜像中不包含仓库源码、Maven 凭据、测试资产或本地 secrets。
- 镜像能力的文档不把 Reference Compose 或 Dockerfile 描述为生产部署模板。
