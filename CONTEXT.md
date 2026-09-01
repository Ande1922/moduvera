# Java 微服务脚手架上下文

本上下文定义一套强约束、可版本化、租户感知的 Java 服务基础底座所使用的统一语言。它覆盖脚手架产品及其验证业务，不替代使用该脚手架的具体业务域模型。

## 产品与装配

**脚手架（Scaffold）**:
为新服务提供已验证默认路径、可替换边界和可执行验证业务的版本化基础底座。
_Avoid_: 组件集合、技术演示、万能框架

**黄金路径（Golden Path）**:
在其他组合获得“受支持”资格前，必须首先端到端成立的唯一完整默认组合。
_Avoid_: 默认示例、推荐代码片段

**验证业务（Reference Business）**:
用于证明脚手架能力能够协同工作的、小而真实的多租户业务纵切面；它不是可复用平台模型的一部分。
_Avoid_: 玩具 Demo、样例 CRUD

**支持矩阵（Support Matrix）**:
明确每项支持声明覆盖的数据库、数据适配器、消息中间件、App 装配及其证据边界；只有声明中的组合通过相应契约测试和验收测试后才获得支持。应用拓扑支持独立于基础设施矩阵，不自动扩张为所有组合的笛卡尔积。
_Avoid_: 理论兼容、依赖能够引入

**业务服务（Business Service）**:
拥有自身用例、数据、公开能力和运行入口的一致业务责任边界。
_Avoid_: 技术服务、任意 Maven 模块

**App 装配（App Assembly）**:
为一个可运行应用选择并激活业务服务、本地或远程协作者、入站适配器及基础设施实现的组合边界；它不拥有协议到业务用例的映射语义。
_Avoid_: Bootstrap 模块、Server 模块

**受支持应用拓扑（Supported Application Topology）**:
由一个或多个 App 装配构成、能够通过其声明的聚焦验收并保持业务语义的运行形态。支持一种应用拓扑不等于为它复制全部数据库和消息中间件验证矩阵。
_Avoid_: POM 可编译、理论可组合、完整基础设施矩阵

**业务核心模块化单体（Business-core Modular Monolith）**:
把 Catalog、Order 和 Inventory 组合进一个 App 装配，同时让 Gateway 和 Identity 继续作为独立外部信任边界的受支持拓扑。它是当前聚焦的单体范围，不排除未来把 Gateway 和 Identity 一并组合。
_Avoid_: 全后端单体、绕过身份的内部 Demo、无外部依赖的单进程系统

当前资格证据由同一套公共 HTTP 黑盒步骤覆盖五 App 微服务 Golden Path 与业务核心模块化单体；两次运行只切换 App 集合、Gateway 前缀策略和目标地址，并继续使用同一 Kafka、Outbox/Inbox、可信消息上下文及恢复语义。重复投递由两种装配各自的真实 Kafka App integration test 注入并验证，不为测试向生产 HTTP 面增加入口。

**服务 API（Service API）**:
业务服务向其他业务服务承诺支持的、与传输协议无关的直接调用能力及跨服务消息契约。直接调用能力可由 Local 或 Remote 适配器实现；只提供异步消息入口的命令只发布消息契约，不因此要求一个同形的同步 Java 接口。Service API 不定义浏览器侧公共 HTTP 路由。
_Avoid_: Feign API、Controller API、Contract 模块

**入站适配器（Inbound Adapter）**:
由提供方业务服务拥有、把 HTTP、消息或任务入口的协议语义映射到服务 API 或应用用例的适配器；同一适配器可由多个 App 装配选择并激活。
_Avoid_: App 业务逻辑、平台自动生成业务入口、用例实现

**入口网关（Edge Gateway）**:
在业务 HTTP Controller 之前执行路由、认证转换、Header 传播和外部访问控制的入口能力；它不定义 Service API，也不复制业务 Controller 或业务 HTTP 映射。
_Avoid_: Gateway Controller、业务 BFF 实现、第二套公共端点

**外部路由前缀（External Route Prefix）**:
公共路径中用于定位业务服务的 `/api/{service}` 部分；它由 Gateway 路由或组合 App 装配处理，而业务 Controller 只拥有其服务本地、带主版本的路径。单服务远程目标由 Gateway 去前缀，多服务组合 App 为各服务公共 Controller 加前缀，并且同一路由只能启用一种前缀变换。
_Avoid_: Service API 命名空间、Controller 重复路径、API 版本

**本地调用（Local Call）**:
服务 API 的实现与调用方位于同一 App 装配中、因而不跨越网络边界的调用。
_Avoid_: 进程内 HTTP、Local Client

**远程调用（Remote Call）**:
提供方与消费方位于不同 App 装配时，通过传输适配器调用同一服务 API。
_Avoid_: 另一套远程业务实现

**公共 API（Public API）**:
面向可信服务网络之外调用方、通过外部路由边界暴露的服务 API。
_Avoid_: 前端 Controller

**内部 API（Internal API）**:
永不通过公共路由暴露，但可按用例规则接受可信 USER 或 SERVICE 身份的服务 API。
_Avoid_: 私有 Java 方法、隐藏的公共端点

## 身份与租户

**认证映射（Authentication Mapping）**:
在系统入口把外部或参考认证实现提供的身份断言转换为可信 Actor 和租户声明的接入契约；它不定义用户目录、凭证生命周期或认证产品。
_Avoid_: 用户体系、内建身份中心、复制外部 Token

**租户（Tenant）**:
身份、数据、消息、任务和权限的隔离边界；在验证业务中运营公司或品牌是租户，门店不是租户。
_Avoid_: 门店、Schema、客户账号

**组织单元（Organization Unit）**:
租户内部的业务分支，例如门店；它不形成独立的数据隔离边界。
_Avoid_: 租户、数据库分区

**租户上下文（Tenant Context）**:
请求、消息或任务进入应用用例前建立、由基础设施能力统一消费并在执行结束时清理的可信当前租户。普通业务用例、领域对象和 Repository 接口默认不携带只为技术隔离服务的 Tenant ID；只有租户身份参与业务规则时才进入业务接口。
_Avoid_: 普通业务方法里的 tenant ID、业务代码直接读取上下文、只用于日志的租户字段

**发起主体（Actor）**:
应用用例代表其执行的、已经认证的用户或系统主体。
_Avoid_: 用户、操作员

**原始发起者（Initiator）**:
跨异步链路保留的最初 USER、SERVICE 或 SYSTEM 审计来源；它不代表当前执行身份，也不参与当前授权。
_Avoid_: Actor、授权凭据

**服务身份（Service Identity）**:
业务服务不代表最终用户调用另一业务服务时使用的机器主体。
_Avoid_: 系统用户、共享服务账号

**权限码（Permission Code）**:
由业务能力提供方拥有、在应用用例边界检查的稳定授权语义；页面改版、组件复用或 API 路由变化不改变其身份。
_Avoid_: 菜单权限、角色名

**用例授权（Use-case Authorization）**:
在可信执行上下文中对 Permission Code 作出的默认拒绝决策；角色、资源、License 或其他授权模型由接入 Adapter 解释，不属于脚手架领域。
_Avoid_: Controller 权限、内建 RBAC、菜单授权

## 用例与集成

**命令（Command）**:
请求改变业务状态的定向用例；接收方拥有并定义该命令能否以及如何执行。
_Avoid_: Event、通用请求 DTO

**查询（Query）**:
读取视图且不改变业务状态的用例。
_Avoid_: 读命令

**领域事件（Domain Event）**:
由领域模型产生、只表达领域内部事实且不承诺跨服务兼容的事件。
_Avoid_: MQ Event、集成消息

**集成事件（Integration Event）**:
由发布方服务 API 拥有、供其他业务服务消费并进行兼容演进的版本化事实。
_Avoid_: Domain Event、通知 DTO

**异步命令（Asynchronous Command）**:
通过消息传递、由接收方服务 API 拥有的命令。
_Avoid_: Command Event、广播命令

**消息信封（Message Envelope）**:
包围集成事件或异步命令的稳定跨服务元数据；它与业务 Payload 分离，只表达契约、路由和审计声明，不携带可被消费方直接信任的授权权限。
_Avoid_: Payload 基类、领域事件包装器、授权凭据

**消息类型（Message Type）**:
集成事件或异步命令的规范契约身份；名称携带破坏性兼容大版本，例如 `.v1`，兼容字段演进不改变该身份。
_Avoid_: Destination、Topic 版本、独立路由版本

**Destination**:
由服务 API 拥有，并在应用装配或运行配置时映射到 Broker Topic 或 Queue 的逻辑路由地址。
_Avoid_: 硬编码 Topic、Binding Name

**Outbox 记录（Outbox Record）**:
与触发它的业务状态原子提交、用于表达待发布集成消息意图的持久记录；其职责止于 Broker 确认接收，不记录消费结果。
_Avoid_: 已发布事件、消息日志

**可靠发布（Durable Publication）**:
通过与业务状态原子持久化且可人工重投的发布意图，保证消息在 Broker 确认接收前不会因进程或网络故障被静默遗失；它不承诺消费方接受消息或业务处理成功。
_Avoid_: 业务最终成功、Exactly Once、无限自动重试

**Inbox 记录（Inbox Record）**:
消费者与本地业务变更原子提交、用于保证重复投递安全的消息标识；它不负责 Broker 确认、重试或死信路由。
_Avoid_: Outbox 记录、死信记录、业务审计日志

**死信（Dead Letter）**:
消息已到达 Broker，但消费适配器在不可重试失败或有限重试耗尽后隔离的原始消费记录；它不表示生产发布失败，也不表示业务已形成最终结果。
_Avoid_: Outbox Terminal、Inbox 记录、业务失败状态

## 数据与运行时能力

**事务边界（Transaction Boundary）**:
由应用用例显式开启的一次顶层、单服务、单数据库事务；其内部只允许本地 Repository 和同库 Outbox/Inbox 操作，禁止嵌套以及 HTTP、Broker、Redis 等外部副作用。
_Avoid_: Work Unit、Transaction Composer、Saga、分布式事务

**聚合仓储（Aggregate Repository）**:
按照业务身份加载和保存完整聚合的领域侧集合抽象。普通仓储的调用者不负责拼装租户过滤条件；持久化实现从可信租户上下文中 fail-closed 地应用隔离。跨租户管理使用独立且显式授权的接口。
_Avoid_: 通用 CRUD Repository、Query Repository、普通仓储的 tenant 参数、隐藏的跨租户开关

**查询仓储（Query Repository）**:
直接返回投影或分页结果、且不把结果伪装成领域聚合的读侧抽象。
_Avoid_: Aggregate Repository、Base Mapper

**Client Group**:
面向同一类下游能力，并共享服务发现、超时、并发和韧性策略的一组远程调用。
_Avoid_: HTTP Client Bean、连接池

**缓存定义（Cache Definition）**:
独立于具体缓存引擎，固定 Key 语义、Value 含义及允许存储选择的具名业务缓存契约。
_Avoid_: Redis Key 前缀、Cache 工具类

**任务（Job）**:
由调度器而不是 HTTP 或消息入口触发的具名应用用例。
_Avoid_: 调度线程、Cron 方法

## 验证业务

**商品目录（Catalog）**:
租户可销售商品及其定价的权威来源；订单通过服务 API 获取下单时所需的商品与价格快照。
_Avoid_: 库存、商品表

**库存（Inventory）**:
租户范围内可预占、释放和调整的商品可用量；它独立拥有库存变化规则。
_Avoid_: 商品目录、订单明细

**订单（Order）**:
记录一次购买意图及其库存确认结果的聚合；创建后先处于等待库存确认状态。
_Avoid_: 支付流程、跨服务工作流实例

**等待库存（PENDING_STOCK）**:
订单已创建、库存预占结果尚未返回的订单状态。
_Avoid_: 处理中、最终成功

**库存预占命令（Reserve Inventory Command）**:
由订单服务发送、库存服务拥有并唯一执行的异步命令。
_Avoid_: 库存事件、广播通知

**库存预占结果（Inventory Reserved / Rejected）**:
库存服务发布的两类集成事实，驱动订单从等待库存状态进入后续结果状态。
_Avoid_: 命令响应 DTO、同步返回值

## 验收语言

**黑盒验收测试（Black-box Acceptance Test）**:
只通过运行中系统的稳定 HTTP、消息等外部契约驱动和断言关键业务链路的测试。
_Avoid_: 模块集成测试、测试专用生产接口

**测试运行标识（Run ID）**:
一次验收测试运行的唯一标识，与独立测试租户共同隔离数据并关联失败证据。
_Avoid_: Trace ID、固定测试租户

**最终一致断言（Eventually Assertion）**:
在明确超时和轮询间隔内等待异步业务条件成立，并在失败时输出诊断证据的断言。
_Avoid_: 固定 sleep、无限等待

## 交付语言

**可复现构建（Reproducible Build）**:
相同源码与受控构建输入应生成内容一致、摘要相同的制品。
_Avoid_: 在制品中注入随机时间、依赖动态版本

**软件物料清单（SBOM）**:
描述某个 App 构建制品实际包含哪些组件及版本的机器可读清单。
_Avoid_: Maven 依赖树、漏洞报告
