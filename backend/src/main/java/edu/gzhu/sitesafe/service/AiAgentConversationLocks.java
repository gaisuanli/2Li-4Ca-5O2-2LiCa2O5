package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.AiAgentProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Fixed-size fair lock stripes keep one conversation ordered without an
 * unbounded conversation-to-lock map. This guarantee is intentionally local
 * to one JVM; multi-instance deployments need sticky routing or a distributed
 * lock keyed by conversation id.
 */
@Component
public class AiAgentConversationLocks {
    private final ReentrantLock[] stripes;
    private final int waitMs;

    public AiAgentConversationLocks(AiAgentProperties properties) {
        stripes = new ReentrantLock[properties.effectiveConversationLockStripes()];
        waitMs = properties.effectiveConversationLockWaitMs();
        for (int index = 0; index < stripes.length; index++) {
            stripes[index] = new ReentrantLock(true);
        }
    }

    public <T> T ordered(long conversationId, Supplier<T> operation) {
        ReentrantLock lock = stripes[Math.floorMod(Long.hashCode(conversationId), stripes.length)];
        boolean acquired;
        try {
            acquired = lock.tryLock(waitMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw busy();
        }
        if (!acquired) throw busy();
        try {
            return operation.get();
        } finally {
            lock.unlock();
        }
    }

    private AppException busy() {
        return new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AI_AGENT_BUSY",
                "当前会话请求较多，请稍后重试");
    }
}
