package io.github.ande1922.moduvera.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.ExecutionScope;
import io.github.ande1922.moduvera.context.Initiator;
import io.github.ande1922.moduvera.context.MissingExecutionContextException;
import io.github.ande1922.moduvera.context.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientResponse;

class SpringAiExecutionContextsTest {

    private static final ExecutionContext REQUEST = context("tenant-a", "request-user", "request-1");
    private static final ExecutionContext WORKER = context("worker", "worker-user", "worker-1");

    @Test
    void capturesOneContextIntoBothImmutableNativeMapsAndRetainsOtherEntries() {
        SpringAiExecutionContexts.RequestContext captured = ExecutionContextHolder.call(
                REQUEST,
                () -> SpringAiExecutionContexts.captureRequest(
                        Map.of("advisor-setting", "kept"), Map.of("tool-setting", 42)));

        assertThat(captured.executionContext()).isSameAs(REQUEST);
        assertThat(captured.advisorContext())
                .containsEntry("advisor-setting", "kept")
                .containsEntry(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, REQUEST);
        assertThat(captured.toolContext())
                .containsEntry("tool-setting", 42)
                .containsEntry(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, REQUEST);
        assertThat(captured.advisorContext().get(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY))
                .isSameAs(captured.toolContext().get(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY));
        assertThatThrownBy(() -> captured.toolContext().put("late", "mutation"))
                .isInstanceOf(UnsupportedOperationException.class);

        SpringAiExecutionContexts.RequestContext alreadyBound = ExecutionContextHolder.call(
                REQUEST,
                () -> SpringAiExecutionContexts.captureRequest(
                        Map.of(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, REQUEST),
                        Map.of(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, REQUEST)));
        assertThat(alreadyBound.executionContext()).isSameAs(REQUEST);
    }

    @Test
    void refusesAbsentCaptureAndDifferentReservedValues() {
        assertThatThrownBy(SpringAiExecutionContexts::captureRequest)
                .isInstanceOf(MissingExecutionContextException.class);

        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        REQUEST,
                        () -> SpringAiExecutionContexts.captureRequest(
                                Map.of(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, WORKER), Map.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Advisor request context");

        assertThatThrownBy(() -> ExecutionContextHolder.call(
                        REQUEST,
                        () -> SpringAiExecutionContexts.captureRequest(
                                Map.of(),
                                Map.of(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, "wrong"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ToolContext");
    }

    @Test
    void responseMapperReadsEachResponseAndRestoresAfterSuccessAndFailure() {
        var mapper = SpringAiExecutionContexts.responseMapper(response -> {
            assertThat(ExecutionContextHolder.require()).isSameAs(REQUEST);
            return "handled";
        });
        ChatClientResponse response = responseWith(REQUEST);

        ExecutionContextHolder.run(WORKER, () -> {
            assertThat(mapper.apply(response)).isEqualTo("handled");
            assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
        });

        var failing = SpringAiExecutionContexts.responseMapper(ignored -> {
            assertThat(ExecutionContextHolder.require()).isSameAs(REQUEST);
            throw new TestFailure();
        });
        ExecutionContextHolder.run(WORKER, () -> {
            assertThatThrownBy(() -> failing.apply(response)).isInstanceOf(TestFailure.class);
            assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
        });
    }

    @Test
    void responseMapperRejectsMissingAndWrongTypeBeforeDelegate() {
        AtomicInteger delegateCalls = new AtomicInteger();
        var mapper = SpringAiExecutionContexts.responseMapper(response -> delegateCalls.incrementAndGet());

        ExecutionContextHolder.run(WORKER, () -> {
            assertThatThrownBy(() -> mapper.apply(ChatClientResponse.builder().build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("missing");
            assertThatThrownBy(() -> mapper.apply(ChatClientResponse.builder()
                            .context(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, "untrusted")
                            .build()))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("wrong type");
            assertThat(ExecutionContextHolder.require()).isSameAs(WORKER);
        });
        assertThat(delegateCalls).hasValue(0);
    }

    private static ChatClientResponse responseWith(ExecutionContext context) {
        return ChatClientResponse.builder()
            .context(SpringAiExecutionContexts.EXECUTION_CONTEXT_KEY, context)
            .build();
    }

    private static ExecutionContext context(String tenant, String subject, String correlation) {
        Actor actor = new Actor(ActorType.USER, subject);
        return new ExecutionContext(
                ExecutionScope.tenant(new TenantId(tenant)), actor, Initiator.from(actor), correlation);
    }

    private static final class TestFailure extends RuntimeException {}
}
