package edu.gzhu.sitesafe.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class SessionService {
    private final Map<String, UserSession> sessions = new ConcurrentHashMap<>();
    private final SecureRandom secureRandom = new SecureRandom();
    private final Duration ttl;

    public SessionService(@Value("${app.token-ttl-minutes:480}") long ttlMinutes) {
        this.ttl = Duration.ofMinutes(ttlMinutes);
    }

    public LoginSession create(long id, String username, String displayName, String role, Set<Long> siteIds) {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UserSession session = new UserSession(id, username, displayName, role, Set.copyOf(siteIds), Instant.now().plus(ttl));
        sessions.put(token, session);
        return new LoginSession(token, session.expiresAt(), session);
    }

    public Optional<UserSession> find(String token) {
        UserSession session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        if (session.expiresAt().isBefore(Instant.now())) {
            sessions.remove(token);
            return Optional.empty();
        }
        return Optional.of(session);
    }

    public Optional<LoginSession> refresh(String token) {
        if (token == null) {
            return Optional.empty();
        }
        UserSession previous = sessions.remove(token);
        if (previous == null || previous.expiresAt().isBefore(Instant.now())) {
            return Optional.empty();
        }
        return Optional.of(create(previous.id(), previous.username(), previous.displayName(),
                previous.role(), previous.siteIds()));
    }

    public void remove(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    public void removeByUserId(long userId) {
        sessions.entrySet().removeIf(entry -> entry.getValue().id() == userId);
    }

    public record LoginSession(String token, Instant expiresAt, UserSession user) {}
}
