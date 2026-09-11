package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.logging.DiagnosticLogSnapshot;
import io.github.ande1922.moduvera.message.outbox.ClaimedOutboxMessage;
import io.github.ande1922.moduvera.message.outbox.PublicationLifecycle;
import io.github.ande1922.moduvera.message.outbox.PublicationObserver;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.context.Context;
import io.opentelemetry.context.Scope;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.spi.LoggingEventBuilder;

/** Restores the durable publication parent only while a claimed message actually executes. */
public final class RelayPublicationLifecycle implements PublicationLifecycle {
    private static final Logger TASK = LoggerFactory.getLogger("task.execute");
    private static final Logger PRODUCE = LoggerFactory.getLogger("mq.produce");
    private static final Logger RECOVERY = LoggerFactory.getLogger("outbox.recovery");
    private static final Logger FINAL = LoggerFactory.getLogger("outbox.publish.failure");
    private final Set<String> businessBoundaryDestinations;

    public RelayPublicationLifecycle(Set<String> businessBoundaryDestinations) {
        this.businessBoundaryDestinations = Set.copyOf(businessBoundaryDestinations);
    }

    @Override
    public Attempt open(ClaimedOutboxMessage entry, int failureLimit) {
        return new PublicationAttempt(entry, failureLimit,
                businessBoundaryDestinations.contains(entry.message().descriptor().destination().value()));
    }

    private static final class PublicationAttempt implements Attempt {
        private final ClaimedOutboxMessage entry;
        private final int failureLimit;
        private final boolean businessBoundary;
        private final Span span;
        private final Scope telemetry;
        private final DiagnosticLogSnapshot.Scope diagnostic;
        private final ImmediateProducerDiagnostics.Scope producer;
        private final long started;
        private long sendCompleted;
        private Throwable transportFailure;
        private Throwable escapedFailure;
        private String writeResult = "not_attempted";
        private PublicationObserver.Result disposition;
        private boolean updated;
        private boolean interrupted;
        private boolean writeAttempted;

        @SuppressWarnings("PMD.CloseResource") // Ownership transfers to diagnostic; failed construction and close both release it.
        private PublicationAttempt(ClaimedOutboxMessage entry, int failureLimit, boolean businessBoundary) {
            this.entry = entry;
            this.failureLimit = failureLimit;
            this.businessBoundary = businessBoundary;
            var descriptor = entry.message().descriptor();
            var builder = GlobalOpenTelemetry.getTracer("io.github.ande1922.moduvera.messaging")
                    .spanBuilder("outbox.publish").setSpanKind(SpanKind.INTERNAL)
                    .setParent(OutboxPublicationPreparation.extract(
                            entry.publicationTraceParent(), entry.publicationTraceState()))
                    .setAttribute("messaging.message.id", descriptor.id().value());
            var creation = descriptor.creationContext();
            if (creation != null) {
                var link = Span.fromContext(OutboxPublicationPreparation.extract(
                        creation.traceParent(), creation.traceState())).getSpanContext();
                if (link.isValid()) {
                    builder.addLink(link);
                }
            }
            span = builder.startSpan();
            // Do not inherit poll/management Context values, including baggage or authorization.
            telemetry = Context.root().with(span).makeCurrent();
            DiagnosticLogSnapshot.Scope openedDiagnostic = null;
            try {
                var snapshot = DiagnosticLogSnapshot.captureWithoutIdentity(descriptor.correlationId())
                        .withIdentity(descriptor.actor(), descriptor.initiator(), Optional.of(descriptor.tenantId()));
                openedDiagnostic = snapshot.openFieldsScope();
                producer = ImmediateProducerDiagnostics.open(snapshot);
                diagnostic = openedDiagnostic;
                started = System.nanoTime();
            } catch (RuntimeException | Error failure) {
                try {
                    if (openedDiagnostic != null) { openedDiagnostic.close(); }
                } finally {
                    try { telemetry.close(); } finally { span.end(); }
                }
                throw failure;
            }
        }

        @Override
        public void transportCompleted(Throwable failure) {
            sendCompleted = System.nanoTime();
            transportFailure = failure;
            if (businessBoundary) {
                recordTransportResult();
            }
        }

        @Override
        public void stateStarted() {
            writeAttempted = true;
        }

        @Override
        public void stateCompleted(PublicationObserver.Result result, boolean accepted) {
            disposition = result;
            updated = accepted;
            writeResult = accepted ? result.name().toLowerCase(java.util.Locale.ROOT) : "stale";
        }

        @Override
        public void stopped(Throwable failure, boolean wasInterrupted) {
            interrupted = wasInterrupted;
            escapedFailure = failure;
            if (!interrupted && writeAttempted && disposition == null) {
                writeResult = "failed";
            }
        }

        @Override
        public void close() {
            try {
                recordResults();
            } finally {
                try {
                    producer.close();
                } finally {
                    try {
                        diagnostic.close();
                    } finally {
                        try {
                            telemetry.close();
                        } finally {
                            span.end();
                        }
                    }
                }
            }
        }

        private void recordResults() {
            double duration = (System.nanoTime() - started) / 1_000_000.0d;
            String transportResult = sendCompleted == 0 ? "unknown" : transportFailure == null ? "success" : "failure";
            boolean published = updated && disposition == PublicationObserver.Result.PUBLISHED;
            Throwable failure = escapedFailure == null ? transportFailure : escapedFailure;
            span.setAttribute("outbox.transport.result", transportResult);
            span.setAttribute("outbox.write.result", writeResult);
            if (!published) {
                span.setStatus(StatusCode.ERROR);
            }
            if (failure != null) {
                span.setAttribute("error.type", failure.getClass().getName());
            }
            var canonical = facts(TASK.atInfo())
                    .addKeyValue("task_name", "outbox.publish")
                    .addKeyValue("event.outcome", published ? "success" : "failure")
                    .addKeyValue("duration_ms", duration)
                    .addKeyValue("transport_result", transportResult)
                    .addKeyValue("outbox_write_result", writeResult)
                    .addKeyValue("outbox_failed_attempts", entry.failedAttempts()
                            + (updated && disposition != PublicationObserver.Result.PUBLISHED ? 1 : 0))
                    .addKeyValue("outbox_failure_limit", failureLimit);
            if (interrupted) {
                canonical.addKeyValue("termination_reason", "interrupted");
            }
            canonical.setCause(failure).log("Outbox 发布执行结束");
            if (updated && disposition == PublicationObserver.Result.RETRY) {
                facts(RECOVERY.atWarn()).setCause(transportFailure)
                        .addKeyValue("outbox_failed_attempts", entry.failedAttempts() + 1)
                        .addKeyValue("outbox_failure_limit", failureLimit)
                        .log("消息发布失败，已安排重试");
            } else if (updated && disposition == PublicationObserver.Result.TERMINAL) {
                facts(FINAL.atError()).setCause(transportFailure)
                        .addKeyValue("error.code", "DEP_OUTBOX_PUBLICATION_FAILED")
                        .log("消息发布失败，需要人工处理");
            } else if (!businessBoundary && transportFailure != null) {
                // No accepted recovery/final record identifies this failed internal interaction.
                recordTransportResult();
            }
        }

        private void recordTransportResult() {
            facts(PRODUCE.atInfo()).addKeyValue("event.outcome", transportFailure == null ? "success" : "failure")
                    .addKeyValue("duration_ms", (sendCompleted - started) / 1_000_000.0d)
                    .setCause(transportFailure).log("消息发布调用结束");
        }

        private LoggingEventBuilder facts(LoggingEventBuilder event) {
            return event.addKeyValue("message_id", entry.message().descriptor().id().value())
                    .addKeyValue("messaging.system", "kafka")
                    .addKeyValue("topic", entry.message().descriptor().destination().value())
                    .addKeyValue("messaging.message.body.size", entry.message().payload().length);
        }
    }
}
