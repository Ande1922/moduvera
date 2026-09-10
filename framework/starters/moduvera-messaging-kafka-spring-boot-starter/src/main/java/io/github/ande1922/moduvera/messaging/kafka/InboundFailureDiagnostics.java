package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.logging.LoggingContextSnapshot;
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

/** Restores owned failures only at Spring Integration's existing default error logger. */
public final class InboundFailureDiagnostics implements BeanPostProcessor {

    private static final Logger LOGGER = LoggerFactory.getLogger("mq.consume.failure");

    // Only attach to a fresh adapter-owned exception, never to a reusable application throwable.
    static void retain(Throwable failure) {
        failure.addSuppressed(new RetainedContext(LoggingContextSnapshot.capture()));
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
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
                    try (var ignored = retained.snapshot.openScope()) {
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

    private static RetainedContext retained(Throwable failure) {
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
    private static final class RetainedContext extends RuntimeException {
        private final transient LoggingContextSnapshot snapshot;

        private RetainedContext(LoggingContextSnapshot snapshot) {
            super("Retained inbound diagnostic context", null, false, false);
            this.snapshot = snapshot;
        }
    }
}
