package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.AiAgentProperties;
import org.springframework.http.HttpStatus;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

/**
 * Per-instance admission control for outbound compatible-model calls. A
 * distributed limiter is required when the backend is horizontally scaled.
 */
@Component
public class AiAgentAdmissionService {
    private final AiAgentProperties properties;
    private final Clock clock;
    private final Semaphore bulkhead;
    private final ConcurrentHashMap<Long, RateWindow> userWindows = new ConcurrentHashMap<>();
    private final AtomicLong lastCleanupMinute = new AtomicLong(Long.MIN_VALUE);

    @Autowired
    public AiAgentAdmissionService(AiAgentProperties properties) {
        this(properties, Clock.systemUTC());
    }

    AiAgentAdmissionService(AiAgentProperties properties, Clock clock) {
        this.properties = properties;
        this.clock = clock;
        this.bulkhead = new Semaphore(properties.effectiveMaxConcurrentRequests(), true);
    }

    public <T> T execute(long userId, Supplier<T> providerCall) {
        boolean acquired;
        try {
            acquired = bulkhead.tryAcquire(properties.effectiveBulkheadWaitMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        if (!acquired) throw busy();
        try {
            requireRateAllowance(userId);
            return providerCall.get();
        } finally {
            bulkhead.release();
        }
    }

    int availablePermits() {
        return bulkhead.availablePermits();
    }

    private void requireRateAllowance(long userId) {
        long minute = Math.floorDiv(clock.instant().getEpochSecond(), 60L);
        cleanupExpiredWindows(minute);
        AtomicBoolean accepted = new AtomicBoolean(false);
        userWindows.compute(userId, (ignored, current) -> {
            if (current == null || current.minute() != minute) {
                accepted.set(true);
                return new RateWindow(minute, 1);
            }
            if (current.count() >= properties.effectivePerUserRequestsPerMinute()) {
                return current;
            }
            accepted.set(true);
            return new RateWindow(minute, current.count() + 1);
        });
        if (!accepted.get()) {
            throw new AppException(HttpStatus.TOO_MANY_REQUESTS, "AI_AGENT_RATE_LIMITED",
                    "AI Agent 请求过于频繁，请稍后再试");
        }
    }

    private void cleanupExpiredWindows(long minute) {
        long previous = lastCleanupMinute.get();
        if (previous >= minute || !lastCleanupMinute.compareAndSet(previous, minute)) return;
        for (Map.Entry<Long, RateWindow> item : userWindows.entrySet()) {
            if (item.getValue().minute() < minute - 1) {
                userWindows.remove(item.getKey(), item.getValue());
            }
        }
    }

    private AppException busy() {
        return new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AI_AGENT_BUSY",
                "AI Agent 当前请求较多，请稍后重试");
    }

    private record RateWindow(long minute, int count) {}
}
