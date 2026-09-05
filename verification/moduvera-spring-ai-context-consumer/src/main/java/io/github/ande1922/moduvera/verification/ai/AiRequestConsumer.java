package io.github.ande1922.moduvera.verification.ai;

import java.util.Map;
import java.util.Objects;

import io.github.ande1922.moduvera.ai.SpringAiExecutionContexts;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClientResponse;
import reactor.core.publisher.Flux;

/** Standalone consumer of the public Spring AI execution-context request seam. */
public final class AiRequestConsumer {

    private AiRequestConsumer() {}

    /** Captures the caller's context and starts a native response stream for this request. */
    public static Flux<ChatClientResponse> stream(
            ChatClient.ChatClientRequestSpec request,
            Map<String, ?> advisorContext,
            Map<String, ?> toolContext) {
        Objects.requireNonNull(request, "request");
        return SpringAiExecutionContexts.captureRequest(advisorContext, toolContext)
            .applyTo(request)
            .stream()
            .chatClientResponse();
    }
}
