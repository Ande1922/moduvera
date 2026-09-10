package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.github.ande1922.moduvera.context.Actor;
import io.github.ande1922.moduvera.context.ActorType;
import io.github.ande1922.moduvera.context.ExecutionContext;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.context.TenantId;
import io.github.ande1922.moduvera.logging.ModuveraEcsStructuredLogFormatter;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.env.MockEnvironment;

class InboundContainerFailureDiagnosticsTest {
    @Test void reusedErrorKeepsEachThreadsIdentityAndDeduplicatesLaterCompletionCallbacks() throws Exception {
        Logger logger = (Logger) LoggerFactory.getLogger("org.springframework.kafka.listener.KafkaMessageListenerContainer");
        var formatter = new ModuveraEcsStructuredLogFormatter(new MockEnvironment());
        var encoded = new CopyOnWriteArrayList<String>();
        var appender = new ListAppender<ILoggingEvent>() {
            @Override
            protected void append(ILoggingEvent event) {
                assertThat(ExecutionContextHolder.current()).isEmpty();
                encoded.add(formatter.format(event));
            }
        };
        appender.start();
        logger.addAppender(appender);
        var filter = new InboundRecoveryLogFilter();
        filter.afterPropertiesSet();
        Error original = new AssertionError("container-private-cause");
        var captured = new CountDownLatch(2);
        try {
            var tasks = new java.util.ArrayList<FutureTask<Void>>();
            for (String id : List.of("one", "two")) {
                var task = new FutureTask<Void>(() -> {
                    var identity = ExecutionContext.initiatedBy(new TenantId("tenant-" + id),
                            new Actor(ActorType.SERVICE, "consumer-" + id), "correlation-" + id);
                    try (var scope = ExecutionContextHolder.open(identity)) {
                        InboundContainerFailureDiagnostics.retain(original);
                    }
                    captured.countDown();
                    assertThat(captured.await(5, TimeUnit.SECONDS)).isTrue();
                    logger.error("Stopping container due to an Error", original);
                    return null;
                });
                tasks.add(task);
                Thread.ofPlatform().start(task);
            }
            for (var task : tasks) {
                task.get(10, TimeUnit.SECONDS);
            }
            assertThat(encoded).hasSize(2);
            for (String id : List.of("one", "two")) {
                assertThat(encoded.stream().filter(json -> json.contains("\"correlation_id\":\"correlation-" + id + "\"")))
                        .singleElement().asString().contains("\"tenant_id\":\"tenant-" + id + "\"")
                        .contains("\"actor_id\":\"consumer-" + id + "\"");
            }
            logger.error("Error while stopping the container", new CompletionException(original));
            logger.error("Error while stopping the container", new CompletionException(original));
            assertThat(encoded).hasSize(2).allSatisfy(json -> assertThat(json).doesNotContain("container-private-cause"));
            // Both completed records were consumed; no old identity can enrich a later use.
            logger.error("Stopping container due to an Error", original);
            assertThat(encoded).hasSize(3);
            assertThat(encoded.getLast()).doesNotContain("correlation_id", "tenant_id", "actor_id");
            assertThat(original.getSuppressed()).isEmpty();
            assertThat(original.getCause()).isNull();
        } finally {
            filter.destroy();
            logger.detachAppender(appender);
            appender.stop();
        }
    }
}
