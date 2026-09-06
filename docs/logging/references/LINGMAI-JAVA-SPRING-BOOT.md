# 日志规范：Java / Spring Boot 绑定

本文是 Adapter：把 [LOGGING.md](../LOGGING.md) 的语义映射到 Java / Spring Boot，**不能改变语义**。怎么写 SLF4J / MDC / Filter 在这里；字段、级别、打印时机仍以主规范为准。

## 1. 技术选型

| 项 | 选型 | 说明 |
|---|---|---|
| 日志门面 | SLF4J | 业务代码只依赖 SLF4J API，禁止直接使用 Logback / JUL / `System.out` |
| 日志实现 | Logback | Spring Boot 默认实现，不引入额外依赖 |
| 结构化输出 | 平台 ECS Formatter | `LingmaiEcsStructuredLogFormatter`：在 Boot 原生 ECS 上合并 `error.code` 与 throwable；不要直接使用 `logging.structured.format.console=ecs` |

级别名与主规范的对应：ERROR / WARN / INFO / DEBUG（SLF4J 与主规范同名）。

**不对 SLF4J 做二次封装**，业务代码直接 `LoggerFactory.getLogger(Xxx.class)`。SLF4J 本身就是门面，再包一层会破坏调用位置定位和 IDE 对占位符的静态检查。横切关注点（上下文注入、canonical）封装在 Filter / 包装器中，不在调用点 API 上。唯一例外：固定 schema 的审计日志等合规事件可单独做窄接口（如 `AuditLogger.record(event)`），那是领域 API，不是通用日志门面。

## 2. 输出配置

任何环境都打 ECS JSON（见主规范的[输出、消息与安全](../LOGGING.md#6-输出消息与安全)）。dev 与 prod 只允许级别和 `service.environment` 等运行参数不同。

默认 / 本地开发（`application.properties`）：

```properties
logging.structured.format.console=tech.sunseed.lingmai.common.logging.LingmaiEcsStructuredLogFormatter
logging.structured.ecs.service.name=${spring.application.name}
logging.structured.ecs.service.version=@project.version@
logging.structured.ecs.service.environment=local
logging.level.root=info
logging.level.tech.sunseed.lingmai=debug
```

Starter 会把未设置或仍为 `ecs` 的 `logging.structured.format.console` 改写为上述 Formatter。必须使用平台 Formatter：Boot 原生 `ecs` 在同一条日志上同时写 `error.code` 与 throwable 时会因重复 `error` 键丢掉整行。

prod profile（`application-prod.properties`）：同一 JSON，改环境和默认级别：

```properties
logging.structured.ecs.service.environment=production
logging.level.root=info
logging.level.tech.sunseed.lingmai=info
```

ECS JSON 自动包含信封、MDC 与 fluent key-value。

### 2.1 可选文件输出

仅在主规范[文件输出](../LOGGING.md#61-可选文件输出)允许的环境启用；目录、日志流、命名、滚动、压缩与保留规则以主规范为准。三个级别流超出 Boot `logging.file.*` 属性的能力（它只支持单文件），必须使用 `logback-spring.xml`；编码器统一用 Boot 的 `StructuredLogEncoder` 指向平台 Formatter，禁止 PatternLayout：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <include resource="org/springframework/boot/logging/logback/defaults.xml"/>
    <springProperty name="APP" source="spring.application.name"/>
    <property name="LOG_DIR" value="${LINGMAI_LOG_DIR:-logs}"/>
    <property name="FORMATTER" value="tech.sunseed.lingmai.common.logging.LingmaiEcsStructuredLogFormatter"/>

    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder class="org.springframework.boot.logging.logback.StructuredLogEncoder">
            <format>${FORMATTER}</format>
        </encoder>
    </appender>

    <appender name="FILE_INFO" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/${APP}-info.log</file>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>INFO</level>
        </filter>
        <encoder class="org.springframework.boot.logging.logback.StructuredLogEncoder">
            <format>${FORMATTER}</format>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/${APP}-info-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>200MB</maxFileSize>
            <maxHistory>7</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
    </appender>

    <appender name="FILE_ERROR" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/${APP}-error.log</file>
        <filter class="ch.qos.logback.classic.filter.ThresholdFilter">
            <level>ERROR</level>
        </filter>
        <encoder class="org.springframework.boot.logging.logback.StructuredLogEncoder">
            <format>${FORMATTER}</format>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/${APP}-error-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>200MB</maxFileSize>
            <maxHistory>7</maxHistory>
            <totalSizeCap>2GB</totalSizeCap>
        </rollingPolicy>
    </appender>

    <appender name="FILE_DEBUG" class="ch.qos.logback.core.rolling.RollingFileAppender">
        <file>${LOG_DIR}/${APP}-debug.log</file>
        <filter class="ch.qos.logback.classic.filter.LevelFilter">
            <level>DEBUG</level>
            <onMatch>ACCEPT</onMatch>
            <onMismatch>DENY</onMismatch>
        </filter>
        <encoder class="org.springframework.boot.logging.logback.StructuredLogEncoder">
            <format>${FORMATTER}</format>
        </encoder>
        <rollingPolicy class="ch.qos.logback.core.rolling.SizeAndTimeBasedRollingPolicy">
            <fileNamePattern>${LOG_DIR}/${APP}-debug-%d{yyyy-MM-dd}.%i.log.gz</fileNamePattern>
            <maxFileSize>200MB</maxFileSize>
            <maxHistory>3</maxHistory>
            <totalSizeCap>1GB</totalSizeCap>
        </rollingPolicy>
    </appender>

    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
        <appender-ref ref="FILE_INFO"/>
        <appender-ref ref="FILE_ERROR"/>
        <appender-ref ref="FILE_DEBUG"/>
    </root>
</configuration>
```

- `info` 流用 `ThresholdFilter`（INFO 及以上，含 ERROR），`error` 流用 `ThresholdFilter`（仅 ERROR），`debug` 流用 `LevelFilter` 只放行 DEBUG；三个流之外不加自定义 Appender。
- 文件名模式以 `.gz` 结尾即由 Logback 在滚动时压缩；模式中必须保留 `%d` 与 `%i`，缺 `%i` 时单文件上限不生效。
- `debug` 流要产生内容仍需按模块开启 DEBUG 级别（如 `logging.level.tech.sunseed.lingmai=debug`）；`<root>` 保持 INFO，不为文件输出全局放开 DEBUG。
- 同一主机多实例时通过 `LINGMAI_LOG_DIR` 或文件名注入实例标识，禁止共写同一当前文件。
- 使用本节配置后，`logging.structured.format.console` 属性不再生效，console 编码器已在 XML 中固定为平台 Formatter；容器 / systemd 环境不引入本节文件。

## 3. 上下文（MDC）

关联字段与身份字段通过 **MDC** 承载：

| 主规范字段 | MDC key | 写入方 |
|---|---|---|
| `trace_id` | `trace_id` | 接入前：入口 Filter（W3C 库解析 `traceparent`）；接入后：OTel / Micrometer 日志桥。**业务代码不写** |
| `span_id` | `span_id` | 同上。接入前入口生成一个当前 span；接入后跟随 SDK 当前活跃 span |
| `user_id` / `tenant_id` / `device_id` / `client_id` | 同名 | 认证 / 租户 / 设备网关组件 |

- MDC 由基础设施（接入后含 SDK 桥）统一写入和清理，业务代码不手动操作保留 key，也不手写 `traceparent`。接入前也必须用兼容 W3C Trace Context 的库，禁止正则拆 header。若 SDK 默认写入 `traceId` / `spanId`（Micrometer Tracing），接入时映射到本规范的 key。
- **跨线程传播**：先区分是否创建新的执行单元。同一单元内的纯线程切换，提交时快照完整 MDC、执行时恢复、结束后清理；新的异步任务只继承 `trace_id` 与身份字段，并生成新的 `span_id`，不得复用提交方 `span_id`。接入前由 `TaskDecorator` / `ExecutorService` 包装器实现，接入后用 SDK 的上下文 continuation / fork 能力。见 [ASYNC.md](../scenes/ASYNC.md)。
- 临时结构化字段用 SLF4J fluent API，不污染 MDC：

```java
log.atInfo()
        .setMessage("订单创建成功, orderId={}")
        .addKeyValue("event.action", "order_created")
        .addKeyValue("order_id", order.getId())
        .log(order.getId());
```

## 4. HTTP 载荷字节与重试次数

入站 / 出站 HTTP 的字段名与出现条件见主规范的[字段契约](../LOGGING.md#2-字段契约)、[INBOUND-REQUEST.md](../scenes/INBOUND-REQUEST.md) 与 [OUTBOUND-CALL.md](../scenes/OUTBOUND-CALL.md)。Java 取值：

- 请求体字节：`request.getContentLengthLong()`，`< 0` 则省略；不要读 `InputStream`。
- 响应体字节：不要在 `finally` 里读 response body（流已写出）。有 `Content-Length` 头才记，否则省略。若缺 header 仍要计数，用只累加字节、不缓存内容的 wrapper，且必须透传给客户端；禁止 `ContentCachingResponseWrapper` 为打日志缓存整包。SSE / WebSocket / 大文件下载不要包 wrapper。

重试次数从客户端框架读取，同一路径只选一个计数器（见主规范的[字段契约](../LOGGING.md#2-字段契约)）：Spring Kafka 开 `deliveryAttemptHeader`（`DELIVERY_ATTEMPT`），或 RetryTemplate / Resilience4j 的 retry count；中间件若在消息对象上提供次数（如 RocketMQ `reconsumeTimes`）也映射进来。监听器按主规范的[重试规则](../LOGGING.md#51-重试)输出：还将重试 → WARN；耗尽 → 不再 WARN；成功那条也带 `retry.*`。

## 5. 消息与异常

参数化占位符，禁止拼接。主键等要检索的值仍须 `addKeyValue`，不要只写在 message 里。最终 ERROR 必有 `error.code`（见主规范的[异常与重试](../LOGGING.md#5-异常与重试)）；有异常对象时用 fluent 带上 cause，不要只靠最后一个参数。该写法依赖平台 `LingmaiEcsStructuredLogFormatter`，它会把 `error.code` 与框架生成的 `error.type/message/stack_trace` 写进同一个 `error` 对象。

```java
log.debug("开始创建订单, skuId={}, quantity={}", skuId, quantity);
log.atError()
        .setCause(e)
        .addKeyValue("error.code", "DEP_PAY_CALLBACK_FAILED")
        .setMessage("支付回调处理失败, orderId={}")
        .log(orderId);                                          // 正确：cause + error.code
log.error("支付回调处理失败, orderId={}", orderId, e);           // 错误：有堆栈，缺 error.code
log.error("支付回调处理失败: " + e.getMessage());               // 错误：拼接、丢失堆栈、无 error.code
```

中间层 catch-wrap-rethrow（见主规范的[异常与重试](../LOGGING.md#5-异常与重试)）：不记日志，包装本层上下文后抛出（cause 链）：

```java
} catch (SQLException e) {
    throw new StockException("扣减库存失败, skuId=%s, quantity=%d".formatted(skuId, qty), e);
}
```

业务异常类携带 `error.code`，全局异常处理器统一取出：

```java
public class BizException extends RuntimeException {
    private final String errorCode;   // 如 BIZ_ORDER_STOCK_INSUFFICIENT
    // ...
}
```

禁止 `System.out` / `print` / 裸 `printStackTrace`。

## 6. 完整示例

以「创建订单」为场景。出站调用本身的 INFO 由 HTTP 客户端拦截器输出（[OUTBOUND-CALL.md](../scenes/OUTBOUND-CALL.md)），业务代码不重复打。

```java
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    public Order createOrder(CreateOrderCommand cmd) {
        log.debug("开始创建订单, skuId={}, quantity={}", cmd.skuId(), cmd.quantity());

        StockReservation stock;
        try {
            stock = stockClient.reserve(cmd.skuId(), cmd.quantity());
        } catch (StockTimeoutException e) {
            log.atWarn()
                    .setMessage("库存服务调用超时, 已降级为本地缓存校验, skuId={}")
                    .addKeyValue("duration_ms", e.elapsedMs())
                    .addKeyValue("sku_id", cmd.skuId())
                    .log(cmd.skuId());
            stock = localStockCache.reserve(cmd.skuId(), cmd.quantity());
        }

        Order order = orderRepository.save(Order.create(cmd, stock));

        log.atInfo()
                .setMessage("订单创建成功, orderId={}")
                .addKeyValue("event.action", "order_created")
                .addKeyValue("order_id", order.getId())
                .addKeyValue("sku_id", cmd.skuId())
                .addKeyValue("amount", order.getAmount())
                .log(order.getId());

        return order;
    }
}
```

HTTP 单元的业务拒绝与异常最终处理点。`BizException` 分支是预期业务结果，使用 INFO + `BIZ_` 类 `error.code`，不是需要 `event.action` 的业务事实：

```java
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(BizException.class)
    public ResponseEntity<ApiError> handleBiz(BizException e) {
        log.atInfo()
                .addKeyValue("error.code", e.getErrorCode())
                .log("业务处理被拒绝, code={}", e.getErrorCode());
        return ResponseEntity.status(e.getHttpStatus()).body(ApiError.of(e));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e, HttpServletRequest request) {
        log.atError()
                .setCause(e)
                .addKeyValue("error.code", "SYS_UNEXPECTED")
                .log("未预期异常, uri={}", request.getRequestURI());
        return ResponseEntity.internalServerError().body(ApiError.internal());
    }
}
```

Service / Repository 捕获后如果不消化，就包装本层上下文再抛，不要记日志。

同一条「订单创建成功」INFO，本地与生产都是一行 ECS JSON：

```json
{"@timestamp":"2026-08-23T13:16:46.476Z","log":{"level":"INFO","logger":"tech.sunseed.lingmai.order.OrderService"},"service":{"name":"lingmai-sample","version":"0.0.1-SNAPSHOT","environment":"local"},"message":"订单创建成功, orderId=10086","event":{"action":"order_created"},"trace_id":"9f2c1e7a4b3d4e5f8a6b7c8d9e0f1a2b","span_id":"a1b2c3d4e5f60718","order_id":10086,"sku_id":"SKU-9527","amount":19900,"ecs":{"version":"8.11"}}
```

请求结束时 Filter 的 canonical（**INFO**，结果看 `event.outcome` 与状态码，不升级别）：

```json
{"@timestamp":"2026-08-23T13:16:17.389Z","log":{"level":"INFO","logger":"http.request"},"service":{"name":"lingmai-sample","version":"0.0.1-SNAPSHOT","environment":"local"},"message":"HTTP 请求完成","event":{"outcome":"success"},"trace_id":"9f2c1e7a4b3d4e5f8a6b7c8d9e0f1a2b","span_id":"a1b2c3d4e5f60718","http":{"request":{"method":"POST"},"response":{"status_code":200}},"url":{"path":"/api/orders"},"duration_ms":42,"client":{"ip":"10.0.3.17"},"user_agent":{"original":"okhttp/4.12.0"},"ecs":{"version":"8.11"}}
```
