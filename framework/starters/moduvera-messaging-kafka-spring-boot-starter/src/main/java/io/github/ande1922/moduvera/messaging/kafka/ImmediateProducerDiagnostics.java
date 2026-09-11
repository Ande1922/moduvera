package io.github.ande1922.moduvera.messaging.kafka;

import io.github.ande1922.moduvera.context.ExecutionContextHolder;
import io.github.ande1922.moduvera.logging.DiagnosticLogSnapshot;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.aopalliance.intercept.MethodInterceptor;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.cloud.stream.binder.kafka.config.ClientFactoryCustomizer;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.LoggingProducerListener;

/** Adapts only the default producer-listener callback for an active synchronous publication. */
public final class ImmediateProducerDiagnostics implements BeanPostProcessor, ClientFactoryCustomizer {
    private static final Logger LOGGER = LoggerFactory.getLogger("mq.produce.propagating");
    private static final ThreadLocal<Attempt> PUBLISHING = new ThreadLocal<>();
    private static final ThreadLocal<CallbackFailure> CALLBACK = new ThreadLocal<>();

    static Scope open() {
        var snapshot = ExecutionContextHolder.current()
                .map(context -> DiagnosticLogSnapshot.capture(context.correlationId())).orElse(null);
        return open(snapshot);
    }

    static Scope open(DiagnosticLogSnapshot snapshot) {
        var scope = new Scope(PUBLISHING.get());
        PUBLISHING.set(new Attempt(snapshot));
        return scope;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) {
        // Exact built-in type and names; never replace application/custom producer listeners.
        if (bean.getClass() != LoggingProducerListener.class
                || !("producerListener".equals(beanName) || "kafkaProducerListener".equals(beanName))) {
            return bean;
        }
        var proxy = new ProxyFactory(bean);
        proxy.addAdvice((MethodInterceptor) invocation -> {
            var callback = CALLBACK.get();
            if (callback != null && "onError".equals(invocation.getMethod().getName())
                    && invocation.getArguments()[0] == callback.record
                    && invocation.getArguments()[2] == callback.failure) {
                try (var ignored = callback.attempt.snapshot == null ? null : callback.attempt.snapshot.openFieldsScope()) {
                    LOGGER.atDebug().setCause(callback.failure).log("同步发布失败继续交给调用方");
                }
                return null;
            }
            return invocation.proceed();
        });
        return proxy.getProxy();
    }

    @Override
    public void configure(ProducerFactory<?, ?> factory) {
        observeFactory(factory);
    }

    private static <K, V> void observeFactory(ProducerFactory<K, V> factory) {
        factory.addPostProcessor(ImmediateProducerDiagnostics::observeProducer);
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Producer<K, V> observeProducer(Producer<K, V> producer) {
        return (Producer<K, V>) Proxy.newProxyInstance(Producer.class.getClassLoader(), new Class<?>[] {Producer.class},
                (proxy, method, arguments) -> {
                    Attempt attempt = PUBLISHING.get();
                    if (attempt != null && "send".equals(method.getName()) && arguments.length == 2) {
                        Callback original = (Callback) arguments[1];
                        Object record = arguments[0];
                        arguments[1] = (Callback) (metadata, failure) -> {
                            var previous = CALLBACK.get();
                            CALLBACK.set(new CallbackFailure(attempt, record, failure));
                            try {
                                if (original != null) {
                                    original.onCompletion(metadata, failure);
                                }
                            } finally {
                                if (previous == null) {
                                    CALLBACK.remove();
                                } else {
                                    CALLBACK.set(previous);
                                }
                            }
                        };
                    }
                    try {
                        return method.invoke(producer, arguments);
                    } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                    }
                });
    }

    private record Attempt(DiagnosticLogSnapshot snapshot) {}
    private record CallbackFailure(Attempt attempt, Object record, Exception failure) {}

    static final class Scope implements AutoCloseable {
        private final Attempt previous;

        private Scope(Attempt previous) {
            this.previous = previous;
        }

        @Override
        public void close() {
            if (previous == null) {
                PUBLISHING.remove();
            } else {
                PUBLISHING.set(previous);
            }
        }
    }
}
