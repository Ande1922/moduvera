package io.github.ande1922.moduvera.messaging.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.ande1922.moduvera.message.MessageId;
import io.github.ande1922.moduvera.message.inbox.InboxOutcome;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;

final class InboxDatabaseConcurrency {

    private InboxDatabaseConcurrency() {}

    static void assertPrecheckVisibility(
            Operations operations, String messageId, boolean rollBack) throws Exception {
        var id = new MessageId(messageId);
        CountDownLatch inboxInserted = new CountDownLatch(1);
        CountDownLatch finishTransaction = new CountDownLatch(1);

        try (var worker = Executors.newSingleThreadExecutor()) {
            var handling = worker.submit(() -> operations.handle(id, () -> {
                inboxInserted.countDown();
                await(finishTransaction);
                if (rollBack) {
                    throw new IllegalStateException("rollback first writer");
                }
            }));
            try {
                await(inboxInserted);
                assertThat(operations.isProcessed(id)).isFalse();
            } finally {
                finishTransaction.countDown();
            }

            if (rollBack) {
                assertThatThrownBy(() -> handling.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
            } else {
                assertThat(handling.get(10, TimeUnit.SECONDS)).isEqualTo(InboxOutcome.APPLIED);
            }
        }

        assertThat(operations.isProcessed(id)).isEqualTo(!rollBack);
    }

    static void assertPrecheckRace(
            Operations operations,
            String messageId,
            boolean rollBackFirst,
            BiFunction<MessageId, String, Runnable> atomicWork,
            BooleanSupplier databaseContentionObserved)
            throws Exception {
        var id = new MessageId(messageId);
        CountDownLatch bothPrechecksPassed = new CountDownLatch(2);
        CountDownLatch firstBusinessStarted = new CountDownLatch(1);
        CountDownLatch secondAttemptingHandle = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);

        try (var workers = Executors.newFixedThreadPool(2)) {
            var first = workers.submit(() -> {
                assertThat(operations.isProcessed(id)).isFalse();
                bothPrechecksPassed.countDown();
                await(bothPrechecksPassed);
                return operations.handle(id, () -> {
                    atomicWork.apply(id, messageId + "-event").run();
                    firstBusinessStarted.countDown();
                    await(finishFirst);
                    if (rollBackFirst) {
                        throw new IllegalStateException("rollback first writer");
                    }
                });
            });
            var second = workers.submit(() -> {
                assertThat(operations.isProcessed(id)).isFalse();
                bothPrechecksPassed.countDown();
                await(bothPrechecksPassed);
                await(firstBusinessStarted);
                secondAttemptingHandle.countDown();
                return operations.handle(id, atomicWork.apply(id, messageId + "-event"));
            });

            try {
                await(bothPrechecksPassed);
                await(firstBusinessStarted);
                await(secondAttemptingHandle);
                awaitDatabaseContention(databaseContentionObserved);
            } finally {
                finishFirst.countDown();
            }

            if (rollBackFirst) {
                assertThatThrownBy(() -> first.get(10, TimeUnit.SECONDS))
                        .isInstanceOf(ExecutionException.class)
                        .hasCauseInstanceOf(IllegalStateException.class);
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(InboxOutcome.APPLIED);
            } else {
                assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(InboxOutcome.APPLIED);
                assertThat(second.get(10, TimeUnit.SECONDS)).isEqualTo(InboxOutcome.DUPLICATE);
            }
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("timed out waiting for deterministic test barrier");
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        }
    }

    private static void awaitDatabaseContention(BooleanSupplier contentionObserved) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (contentionObserved.getAsBoolean()) {
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new IllegalStateException("interrupted while observing database contention");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        throw new IllegalStateException("timed out observing the competing Inbox insert wait");
    }

    interface Operations {

        boolean isProcessed(MessageId messageId);

        InboxOutcome handle(MessageId messageId, Runnable businessChange);
    }
}
