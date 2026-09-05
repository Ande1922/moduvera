# Spring AI execution context adapter

`moduvera-spring-ai-context` is an optional adapter for Spring AI 2.0.1. It keeps
Spring AI and Reactor types outside `moduvera-kernel` and protocol-neutral
Service APIs.

Capture one request context while a trusted `ExecutionContext` is installed,
then install its native request boundary on the per-request
`ChatClientRequestSpec`:

```java
var requestContext = SpringAiExecutionContexts.captureRequest(
        Map.of("advisor-option", advisorOption),
        Map.of("tool-option", toolOption));

Flux<Result> results = requestContext
        .applyTo(chatClient.prompt().user(userText))
        .stream()
        .chatClientResponse()
        .map(SpringAiExecutionContexts.responseMapper(this::handleResponse));
```

Both public output maps reserve
`SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY`. `applyTo` adds the supplied
non-reserved entries and a request-scoped Advisor. At execution time that
Advisor inspects the fully assembled `ChatClientRequest` and concrete
`ToolCallingChatOptions`, including client defaults, direct request context and
request options. It rejects either effective carrier when the reserved key has
a different value, then injects the same captured object while preserving the
other entries and concrete provider options. A supported request must expose
`ToolCallingChatOptions`; without that native ToolContext carrier the request
fails before model delegation.

Do not put a captured `RequestContext` in `ChatClient` defaults or reuse it
across logical requests. Existing context maps may also be supplied to
`captureRequest`; a different reserved value is rejected immediately and a
matching captured value is retained.

The response mapper is stateless: each invocation reads the current
`ChatClientResponse`, opens a Holder scope only for the synchronous delegate,
and restores the worker's prior state after return or failure. It can be shared
when the delegate is thread-safe. Keep `ChatClientResponse` until all work that
needs its context is complete; a content `String` does not retain the response
context.

Wrap every Spring AI tool that requires the Holder once, then register the
wrapper as a normal request or default tool:

```java
ToolCallback contextAwareTool =
        SpringAiExecutionContexts.toolCallback(applicationTool);
```

The wrapper is stateless and reads the reserved value from every native
`ToolContext`; it does not capture the Holder when the wrapper is created. It
rejects a missing ToolContext, missing key, wrong value type, and the
context-free `call(String)` entry before calling the tool. During
`call(String, ToolContext)` it opens a Scope only around the synchronous
delegate and restores the tool thread after either a result or an exception.
The original ToolDefinition (including name, description and input schema),
ToolMetadata, input, result, and exception are delegated unchanged. Sharing is
safe only when the underlying tool is itself safe to share.

The reserved values are native execution metadata. They must not be copied
into user/system prompt text, message metadata, tool parameter schemas, or
tool results. This adapter does not make arbitrary Advisor callbacks
Holder-aware. Custom Advisor asynchronous callbacks need their own explicit
propagation boundary; a Scope surrounding `return nextStream(...)` ends after
Publisher assembly and cannot cover later stream signals.
