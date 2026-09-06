# Moduvera Logging Spring Boot Starter

This starter selects the governed ECS formatter before Spring Boot initializes
its logging system. Applications continue to use SLF4J and Logback. The starter
adds no tracing SDK, exporter, Servlet, Reactor, or messaging dependency.

Every runtime must configure a stable service name, version, and environment.
The formatter reads the standard Boot ECS properties first and falls back to
`spring.application.name`, `spring.application.version`, and the active Spring
profiles where applicable:

```properties
spring.application.name=order-app
logging.structured.ecs.service.version=0.1.0
logging.structured.ecs.service.environment=production
```

`LoggingContextSnapshot` captures the current complete OpenTelemetry Context
and the framework ExecutionContext for a short callback scope. It projects only
trusted context values into its owned MDC keys and restores those keys, the OTel
Scope, and the business context on close. Callers must not hold the scope across
an asynchronous wait.

The formatter renders ordinary parameterized messages, removes query material
from URLs, redacts common credential assignments, rejects sensitive structured
keys, and emits exception class and stack frames without throwable messages.
These controls are a final output boundary; application code must still follow
the logging specification's allowlist and must not construct payload, SQL, or
credential log values.
