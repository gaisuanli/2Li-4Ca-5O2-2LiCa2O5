package edu.gzhu.sitesafe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.AiAgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AiAgentAdmissionServiceTest {
    @Test
    void perUserMinuteLimitIsIndependentAndReleasesBulkheadPermitOnRejection() {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setMaxConcurrentRequests(2);
        properties.setPerUserRequestsPerMinute(2);
        properties.setBulkheadWaitMs(0);
        AiAgentAdmissionService admission = new AiAgentAdmissionService(properties);

        assertEquals("one", admission.execute(7L, () -> "one"));
        assertEquals("two", admission.execute(7L, () -> "two"));
        AppException limited = assertThrows(AppException.class,
                () -> admission.execute(7L, () -> "three"));
        assertEquals("AI_AGENT_RATE_LIMITED", limited.code());
        assertEquals(429, limited.status().value());
        assertEquals("other-user", admission.execute(8L, () -> "other-user"));
        assertEquals(2, admission.availablePermits());
    }

    @Test
    void fairGlobalBulkheadRejectsExcessConcurrentCompatibleCall() throws Exception {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setMaxConcurrentRequests(1);
        properties.setPerUserRequestsPerMinute(100);
        properties.setBulkheadWaitMs(25);
        AiAgentAdmissionService admission = new AiAgentAdmissionService(properties);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> first = executor.submit(() -> admission.execute(1L, () -> {
                entered.countDown();
                await(release);
                return "first";
            }));
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
            AppException busy = assertThrows(AppException.class,
                    () -> admission.execute(2L, () -> "second"));
            assertEquals("AI_AGENT_BUSY", busy.code());
            assertEquals(503, busy.status().value());
            release.countDown();
            assertEquals("first", first.get(2, TimeUnit.SECONDS));
            assertEquals(1, admission.availablePermits());
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void demoModeDoesNotConsumeExternalProviderBulkhead() throws Exception {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setMode(AiAgentProperties.Mode.DEMO);
        properties.setMaxConcurrentRequests(1);
        properties.setBulkheadWaitMs(0);
        AiAgentAdmissionService admission = new AiAgentAdmissionService(properties);
        AiAgentSiteSnapshotService snapshots = mock(AiAgentSiteSnapshotService.class);
        when(snapshots.demoAnswer(null, "demo-question")).thenReturn("demo-answer");
        AiAgentProvider provider = new AiAgentProvider(properties, admission, new ObjectMapper());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> held = executor.submit(() -> admission.execute(99L, () -> {
                entered.countDown();
                await(release);
                return "held";
            }));
            org.junit.jupiter.api.Assertions.assertTrue(entered.await(2, TimeUnit.SECONDS));
            AiAgentProvider.ProviderReply reply = provider.answer(
                    1L, "demo-question", "context", List.of(), null, snapshots);
            assertEquals("demo-answer", reply.content());
            release.countDown();
            assertEquals("held", held.get(2, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
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
