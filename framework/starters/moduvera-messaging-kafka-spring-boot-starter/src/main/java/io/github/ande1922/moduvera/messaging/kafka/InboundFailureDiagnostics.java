package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.logging.DiagnosticLogSnapshot;
import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;
import org.aopalliance.intercept.MethodInterceptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.integration.handler.LoggingHandler;
import org.springframework.integration.context.IntegrationContextUtils;
import org.springframework.integration.config.IntegrationConfigUtils;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.cloud.stream.config.ConsumerEndpointCustomizer;
import org.springframework.integration.kafka.inbound.KafkaMessageDrivenChannelAdapter;

/** Restores owned failures only at Spring Integration's existing default error logger. */
public final class InboundFailureDiagnostics implements BeanPostProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger("mq.consume.failure");

    // Only attach to a fresh adapter-owned exception, never to a reusable application throwable.
    static void retain(Throwable failure) {
        retain(failure, null);
    }

    static void retain(Throwable failure, InboundAttemptObservation observation) {
        if (observation != null && InboundDeadLetterDiagnostics.canDeferFinalAttempt()) {
            observation.defer();
        } else {
            observation = null;
        }
        failure.addSuppressed(new RetainedContext(
                DiagnosticLogSnapshot.capture(ExecutionContextHolder.require().correlationId()), observation));
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        if (bean instanceof ConsumerEndpointCustomizer<?>
                && !"moduveraInboundEndpointDiagnostics".equals(beanName)) {
            ProxyFactory proxy = new ProxyFactory(bean);
            proxy.addAdvice((MethodInterceptor) invocation -> {
                Object result = invocation.proceed();
                if ("configure".equals(invocation.getMethod().getName())
                        && invocation.getArguments()[0] instanceof KafkaMessageDrivenChannelAdapter<?, ?> adapter) {
                    InboundDeadLetterDiagnostics.configureEndpoint(adapter);
                }
                return result;
            });
            return proxy.getProxy();
        }
        String defaultLogger = IntegrationContextUtils.ERROR_LOGGER_BEAN_NAME + IntegrationConfigUtils.HANDLER_ALIAS_SUFFIX;
        if (!defaultLogger.equals(beanName) || !(bean instanceof LoggingHandler)) {
            return bean;
        }
        ProxyFactory proxy = new ProxyFactory(bean);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            if ("handleMessage".equals(invocation.getMethod().getName())
                    && invocation.getArguments().length == 1
                    && invocation.getArguments()[0] instanceof ErrorMessage error) {
                RetainedContext retained = retained(error.getPayload());
                if (retained != null) {
                    // This hook owns only the default log subscriber. Binder recovery/DLQ still
                    // receives the original ErrorMessage and original exception/cause unchanged.
                    try (var ignored = retained.openScope()) {
                        LOGGER.atError().addKeyValue("error.code", "SYS_UNEXPECTED")
                                .setCause(error.getPayload()).log("消息处理最终失败");
                    }
                    return null;
                }
            }
            return invocation.proceed();
        });
        return proxy.getProxy();
    }

    static RetainedContext retained(Throwable failure) {
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        for (Throwable current = failure; current != null && seen.add(current); current = current.getCause()) {
            for (Throwable suppressed : current.getSuppressed()) {
                if (suppressed instanceof RetainedContext retained && retained.snapshot != null) {
                    return retained;
                }
            }
        }
        return null;
    }

    /** Local, stackless metadata; it is never a replacement for the business exception/cause. */
    static final class RetainedContext extends RuntimeException {
        private final transient DiagnosticLogSnapshot snapshot;
        private final transient InboundAttemptObservation observation;

        private RetainedContext(DiagnosticLogSnapshot snapshot, InboundAttemptObservation observation) {
            super("Retained inbound diagnostic context", null, false, false);
            this.snapshot = snapshot;
            this.observation = observation;
        }

        DiagnosticLogSnapshot.Scope openScope() {
            return snapshot.openFieldsScope();
        }

        void complete(boolean deadLetter) {
            if (observation != null && snapshot != null) {
                try (var ignored = openScope()) {
                    observation.complete(deadLetter);
                }
            }
        }
    }
}
