package io.github.ande1922.moduvera.messaging.kafka;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.turbo.TurboFilter;
import ch.qos.logback.core.spi.FilterReply;
import org.slf4j.LoggerFactory;
import org.slf4j.Marker;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;

/** Keeps propagating Kafka/Binder send diagnostics at INFO; the original recovery boundary owns ERROR. */
public final class InboundRecoveryLogFilter extends TurboFilter implements InitializingBean, DisposableBean {
    private static final ThreadLocal<Boolean> REPLAYING = new ThreadLocal<>();
    private LoggerContext loggerContext;

    @Override
    public void afterPropertiesSet() {
        if (LoggerFactory.getILoggerFactory() instanceof LoggerContext context) {
            loggerContext = context;
            setContext(context);
            start();
            context.addTurboFilter(this);
            InboundContainerFailureDiagnostics.install();
        }
    }

    @Override
    public FilterReply decide(Marker marker, Logger logger, Level level, String format, Object[] arguments, Throwable failure) {
        if (level != Level.ERROR || REPLAYING.get() != null) {
            return FilterReply.NEUTRAL;
        }
        var container = InboundContainerFailureDiagnostics.nativeRecord(logger.getName(), format, failure);
        if (container != null) {
            if (container.snapshot() != null) {
                REPLAYING.set(Boolean.TRUE);
                try (var ignored = container.snapshot().openFieldsScope()) {
                    var event = logger.atError().setCause(failure);
                    if (marker != null) {
                        event.addMarker(marker);
                    }
                    event.log(format);
                } finally {
                    REPLAYING.remove();
                }
            }
            return FilterReply.DENY;
        }
        if (!InboundDeadLetterDiagnostics.ownsRecoveryFailure(logger.getName(), failure)) {
            return FilterReply.NEUTRAL;
        }
        var event = logger.atInfo().setCause(failure);
        if (marker != null) {
            event.addMarker(marker);
        }
        if (arguments == null) {
            event.log(format);
        } else {
            event.log(format, arguments);
        }
        return FilterReply.DENY;
    }

    @Override
    public void destroy() {
        if (loggerContext != null) {
            loggerContext.getTurboFilterList().remove(this);
            loggerContext = null;
            InboundContainerFailureDiagnostics.uninstall();
        }
        stop();
    }
}
