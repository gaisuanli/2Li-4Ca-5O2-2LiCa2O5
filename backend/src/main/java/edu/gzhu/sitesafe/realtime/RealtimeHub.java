package edu.gzhu.sitesafe.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.security.SessionService;
import edu.gzhu.sitesafe.security.UserSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class RealtimeHub extends TextWebSocketHandler {
    private static final Logger log = LoggerFactory.getLogger(RealtimeHub.class);

    private final Map<WebSocketSession, ConnectionContext> sessions = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final SessionService sessionService;

    public RealtimeHub(ObjectMapper objectMapper, SessionService sessionService) {
        this.objectMapper = objectMapper;
        this.sessionService = sessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String token = queryParam(session, "token");
        Optional<UserSession> user = token == null ? Optional.empty() : sessionService.find(token);
        if (user.isEmpty()) {
            session.close(CloseStatus.POLICY_VIOLATION.withReason("invalid token"));
            return;
        }
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                "type", "connection.ready",
                "occurredAt", Instant.now().toString()
        ))));
        sessions.put(session, new ConnectionContext(token));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        closeAndRemove(session, CloseStatus.SERVER_ERROR);
    }

    public void publish(String type, Object payload) {
        Set<Long> eventSiteIds = eventSiteIds(payload);
        if (eventSiteIds.isEmpty()) {
            log.warn("Dropped realtime event without a valid siteId: {}", type);
            return;
        }
        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", type,
                    "occurredAt", Instant.now().toString(),
                    "payload", payload
            ));
            TextMessage message = new TextMessage(json);
            for (Map.Entry<WebSocketSession, ConnectionContext> entry : sessions.entrySet()) {
                WebSocketSession session = entry.getKey();
                if (!session.isOpen()) {
                    sessions.remove(session);
                    continue;
                }
                Optional<UserSession> currentUser = sessionService.find(entry.getValue().token());
                if (currentUser.isEmpty()) {
                    closeAndRemove(session, CloseStatus.POLICY_VIOLATION.withReason("session expired"));
                    continue;
                }
                if (currentUser.get().siteIds().stream().noneMatch(eventSiteIds::contains)) {
                    continue;
                }
                try {
                    synchronized (session) {
                        session.sendMessage(message);
                    }
                } catch (IOException ignored) {
                    closeAndRemove(session, CloseStatus.SERVER_ERROR);
                }
            }
        } catch (Exception ignored) {
            // Realtime delivery is best effort; the REST API remains the recovery source.
        }
    }

    private Set<Long> eventSiteIds(Object payload) {
        if (payload == null) {
            return Set.of();
        }
        try {
            JsonNode siteIdNode = objectMapper.valueToTree(payload).path("siteId");
            long siteId;
            if (siteIdNode.isIntegralNumber() && siteIdNode.canConvertToLong()) {
                siteId = siteIdNode.longValue();
            } else if (siteIdNode.isTextual()) {
                try {
                    siteId = Long.parseLong(siteIdNode.textValue().trim());
                } catch (NumberFormatException ignored) {
                    return Set.of();
                }
            } else {
                return Set.of();
            }
            return siteId > 0 ? Set.of(siteId) : Set.of();
        } catch (IllegalArgumentException ignored) {
            // An unserializable or invalid scope is treated like a missing scope.
            return Set.of();
        }
    }

    private void closeAndRemove(WebSocketSession session, CloseStatus status) {
        sessions.remove(session);
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ignored) {
            // The connection is already absent from the delivery registry.
        }
    }

    private String queryParam(WebSocketSession session, String name) {
        String query = session.getUri() == null ? null : session.getUri().getQuery();
        if (query == null) return null;
        for (String part : query.split("&")) {
            String[] pair = part.split("=", 2);
            if (pair.length == 2 && pair[0].equals(name)) {
                return URLDecoder.decode(pair[1], StandardCharsets.UTF_8);
            }
        }
        return null;
    }

    private record ConnectionContext(String token) {}
}
