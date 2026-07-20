package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.AiAgentProperties;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AiAgentConversationLocksTest {
    @Test
    void stripeWaitIsBoundedAndReturnsBusyInsteadOfBlockingIndefinitely() throws Exception {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setConversationLockStripes(16);
        properties.setConversationLockWaitMs(30);
        AiAgentConversationLocks locks = new AiAgentConversationLocks(properties);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = executor.submit(() -> locks.ordered(42L, () -> {
                entered.countDown();
                await(release);
                return "first";
            }));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            long started = System.nanoTime();
            AppException busy = assertThrows(AppException.class,
                    () -> locks.ordered(42L, () -> "must-not-run"));
            long elapsedMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
            assertEquals("AI_AGENT_BUSY", busy.code());
            assertEquals(503, busy.status().value());
            assertTrue(elapsedMs < 1000, "bounded lock wait took " + elapsedMs + "ms");
            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void interruptedWaitRestoresThreadFlagAndReturnsBusy() throws Exception {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setConversationLockStripes(16);
        properties.setConversationLockWaitMs(5000);
        AiAgentConversationLocks locks = new AiAgentConversationLocks(properties);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Thread holder = new Thread(() -> locks.ordered(9L, () -> {
            entered.countDown();
            await(release);
            return null;
        }));
        holder.start();
        assertTrue(entered.await(2, TimeUnit.SECONDS));

        CountDownLatch waiting = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<String> result = new java.util.concurrent.atomic.AtomicReference<>();
        Thread waiter = new Thread(() -> {
            waiting.countDown();
            try {
                locks.ordered(9L, () -> null);
            } catch (AppException ex) {
                result.set(ex.code() + ":" + Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        assertTrue(waiting.await(1, TimeUnit.SECONDS));
        Thread.sleep(30);
        waiter.interrupt();
        waiter.join(2000);
        assertEquals("AI_AGENT_BUSY:true", result.get());
        release.countDown();
        holder.join(2000);
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("test latch timeout");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
