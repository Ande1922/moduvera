package io.github.ande1922.moduvera.messaging.kafka;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.cloud.stream.binder.kafka.KafkaMessageChannelBinder;
import org.springframework.cloud.stream.binder.kafka.config.ClientFactoryCustomizer;
import org.springframework.integration.kafka.inbound.KafkaMessageDrivenChannelAdapter;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.support.ErrorMessage;
import org.springframework.integration.StaticMessageHeaderAccessor;

/** Observes original Binder sends; it never decides recovery, destinations, ACKs or transactions. */
public final class InboundDeadLetterDiagnostics implements ClientFactoryCustomizer {
    private static final ThreadLocal<ArrayDeque<Boolean>> DELIVERIES = new ThreadLocal<>();
    private static final ThreadLocal<ArrayDeque<Dispatch>> DISPATCHES = new ThreadLocal<>();
    private static final ThreadLocal<Dispatch> CALLBACK = new ThreadLocal<>();
    private static final Logger FAILURE_LOGGER = LoggerFactory.getLogger("mq.consume.recovery.failure");

    static boolean ownsRecoveryFailure(String logger, Throwable failure) {
        if (failure == null || !(logger.equals("org.springframework.kafka.support.LoggingProducerListener")
                || logger.equals(KafkaMessageChannelBinder.class.getName()))) {
            return false;
        }
        Dispatch dispatch = CALLBACK.get();
        if (dispatch == null) {
            var stack = DISPATCHES.get();
            dispatch = stack == null ? null : stack.peek();
        }
        if (dispatch != null && dispatch.sendAttempted
                && (sameFailure(dispatch.failure, failure)
                        || (dispatch.failure == null && failure instanceof java.util.concurrent.TimeoutException))) {
            dispatch.failure = failure;
            return true;
        }
        return false;
    }

    private static boolean sameFailure(Throwable observed, Throwable candidate) {
        var causes = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = observed; cause != null && causes.add(cause); cause = cause.getCause()) {
            // Identity, not exception text or class, associates native records with this send.
        }
        var visited = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable, Boolean>());
        for (Throwable cause = candidate; cause != null && visited.add(cause); cause = cause.getCause()) {
            if (causes.contains(cause)) {
                return true;
            }
        }
        return false;
    }

    static boolean isDeliveryObserved() {
        var deliveries = DELIVERIES.get();
        return deliveries != null && !deliveries.isEmpty();
    }

    static boolean canDeferFinalAttempt() {
        var deliveries = DELIVERIES.get();
        return deliveries != null && Boolean.TRUE.equals(deliveries.peek());
    }

    public static void configureEndpoint(KafkaMessageDrivenChannelAdapter<?, ?> adapter) {
        // This repository assigns retry to ReliableInboundEndpoint, so Binder max-attempts is 1.
        // A custom endpoint without this error channel keeps immediate attempt completion.
        MessageChannel errors = adapter.getErrorChannel();
        MessageChannel input = adapter.getOutputChannel();
        if (input != null && !(input instanceof DeliveryChannel)) {
            if (errors != null) {
                adapter.setErrorChannel(new ErrorDispatchChannel(errors));
            }
            adapter.setOutputChannel(new DeliveryChannel(input, errors != null));
        }
    }

    @Override
    public void configure(ProducerFactory<?, ?> factory) {
        observeFactory(factory);
    }

    private static <K, V> void observeFactory(ProducerFactory<K, V> factory) {
        factory.addPostProcessor(producer -> observeProducer(producer, factory.transactionCapable()));
    }

    @SuppressWarnings("unchecked")
    private static <K, V> Producer<K, V> observeProducer(Producer<K, V> producer, boolean transactional) {
        List<Dispatch> transaction = new ArrayList<>();
        return (Producer<K, V>) Proxy.newProxyInstance(Producer.class.getClassLoader(),
                new Class<?>[] {Producer.class}, (proxy, method, arguments) -> {
                    Dispatch dispatch = null;
                    if ("send".equals(method.getName()) && arguments.length == 2
                            && arguments[0] instanceof ProducerRecord<?, ?> record) {
                        var stack = DISPATCHES.get();
                        Dispatch current = stack == null ? null : stack.peek();
                        if (current != null && current.matches(record)) {
                            dispatch = current;
                            Dispatch sent = current;
                            sent.sendAttempted = true;
                            Callback original = (Callback) arguments[1];
                            if (transactional) {
                                sent.transactionPending = true;
                                transaction.add(sent);
                            }
                            arguments[1] = (Callback) (metadata, exception) -> {
                                boolean acknowledged = exception == null && metadata != null && metadata.hasOffset();
                                sent.acknowledged = acknowledged;
                                if (exception != null) {
                                    sent.failure = exception;
                                }
                                Dispatch previous = CALLBACK.get();
                                CALLBACK.set(sent);
                                try (var ignored = sent.retained.openScope()) {
                                    if (!transactional || !acknowledged) {
                                        sent.complete(acknowledged);
                                    }
                                    if (original != null) {
                                        original.onCompletion(metadata, exception);
                                    }
                                } finally {
                                    if (previous == null) {
                                        CALLBACK.remove();
                                    } else {
                                        CALLBACK.set(previous);
                                    }
                                    if (sent.closed) {
                                        sent.reportFailure();
                                    }
                                }
                            };
                        }
                    }
                    try {
                        Object result = method.invoke(producer, arguments);
                        if ("commitTransaction".equals(method.getName())) {
                            transaction.forEach(sent -> sent.complete(sent.acknowledged));
                            transaction.clear();
                        } else if ("abortTransaction".equals(method.getName()) || "close".equals(method.getName())) {
                            transaction.forEach(sent -> sent.complete(false));
                            transaction.clear();
                        }
                        return result;
                    } catch (InvocationTargetException failure) {
                        if (dispatch != null) {
                            dispatch.failure = failure.getCause();
                            dispatch.complete(false);
                        }
                        if ("commitTransaction".equals(method.getName()) || "abortTransaction".equals(method.getName())) {
                            transaction.forEach(sent -> sent.complete(false));
                            transaction.clear();
                        }
                        throw failure.getCause();
                    }
                });
    }

    private static final class DeliveryChannel implements MessageChannel {
        private final MessageChannel delegate;
        private final boolean errorChannelObserved;

        private DeliveryChannel(MessageChannel delegate, boolean errorChannelObserved) {
            this.delegate = delegate;
            this.errorChannelObserved = errorChannelObserved;
        }

        @Override
        public boolean send(Message<?> message) {
            return withinDelivery(() -> delegate.send(message));
        }

        @Override
        public boolean send(Message<?> message, long timeout) {
            return withinDelivery(() -> delegate.send(message, timeout));
        }

        private boolean withinDelivery(BooleanSupplier send) {
            var stack = DELIVERIES.get();
            if (stack == null) {
                stack = new ArrayDeque<>();
                DELIVERIES.set(stack);
            }
            stack.push(errorChannelObserved);
            try {
                return send.getAsBoolean();
            } finally {
                stack.pop();
                if (stack.isEmpty()) {
                    DELIVERIES.remove();
                }
            }
        }
    }

    private static final class ErrorDispatchChannel implements MessageChannel {
        private final MessageChannel delegate;

        private ErrorDispatchChannel(MessageChannel delegate) {
            this.delegate = delegate;
        }

        @Override
        public boolean send(Message<?> message) {
            return withinDispatch(message, () -> delegate.send(message));
        }

        @Override
        public boolean send(Message<?> message, long timeout) {
            return withinDispatch(message, () -> delegate.send(message, timeout));
        }

        private static boolean withinDispatch(Message<?> message, BooleanSupplier send) {
            var stack = DISPATCHES.get();
            if (stack == null) {
                stack = new ArrayDeque<>();
                DISPATCHES.set(stack);
            }
            var retained = message instanceof ErrorMessage error
                    ? InboundFailureDiagnostics.retained(error.getPayload()) : null;
            Object source = StaticMessageHeaderAccessor.getSourceData(message);
            var dispatch = new Dispatch(retained, source instanceof ConsumerRecord<?, ?> record ? record : null);
            stack.push(dispatch);
            try (var ignored = retained == null ? null : retained.openScope()) {
                return send.getAsBoolean();
            } finally {
                stack.pop();
                try {
                    // Binder can swallow a failure or return after a timeout. Ignore late ACKs
                    // after this once-only completion; they cannot fabricate another canonical.
                    if (!dispatch.transactionPending) {
                        dispatch.complete(false);
                    }
                    dispatch.closed = true;
                    dispatch.reportFailure();
                } finally {
                    if (stack.isEmpty()) {
                        DISPATCHES.remove();
                    }
                }
            }
        }
    }

    private static final class Dispatch {
        private final InboundFailureDiagnostics.RetainedContext retained;
        private final ConsumerRecord<?, ?> source;
        private boolean transactionPending;
        private volatile boolean sendAttempted;
        private volatile boolean acknowledged;
        private volatile boolean closed;
        private volatile Throwable failure;
        private final java.util.concurrent.atomic.AtomicBoolean failureReported = new java.util.concurrent.atomic.AtomicBoolean();

        private Dispatch(InboundFailureDiagnostics.RetainedContext retained, ConsumerRecord<?, ?> source) {
            this.retained = retained;
            this.source = source;
        }

        private boolean matches(ProducerRecord<?, ?> record) {
            return retained != null && source != null
                    && headerMatches(record, KafkaMessageChannelBinder.X_ORIGINAL_TOPIC,
                            source.topic().getBytes(StandardCharsets.UTF_8))
                    && headerMatches(record, KafkaMessageChannelBinder.X_ORIGINAL_PARTITION,
                            ByteBuffer.allocate(Integer.BYTES).putInt(source.partition()).array())
                    && headerMatches(record, KafkaMessageChannelBinder.X_ORIGINAL_OFFSET,
                            ByteBuffer.allocate(Long.BYTES).putLong(source.offset()).array());
        }

        private static boolean headerMatches(ProducerRecord<?, ?> record, String name, byte[] expected) {
            Header header = record.headers().lastHeader(name);
            return header != null && Arrays.equals(header.value(), expected);
        }

        private void complete(boolean deadLetter) {
            if (retained != null) {
                retained.complete(deadLetter);
            }
        }

        private void reportFailure() {
            Throwable finalFailure = failure;
            if (retained != null && finalFailure != null && failureReported.compareAndSet(false, true)) {
                try (var ignored = retained.openScope()) {
                    FAILURE_LOGGER.atError().addKeyValue("error.code", "DEP_DLQ_SEND")
                            .setCause(finalFailure).log("消息死信投递最终失败");
                }
            }
        }
    }
}
