package edu.gzhu.sitesafe.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.security.SessionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeHubTest {
    private ObjectMapper objectMapper;
    private SessionService sessionService;
    private RealtimeHub hub;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        sessionService = new SessionService(480);
        hub = new RealtimeHub(objectMapper, sessionService);
    }

    @Test
    void eventIsDeliveredToConnectionForTheSameSite() throws Exception {
        String token = tokenFor(1L);
        List<TextMessage> messages = new ArrayList<>();
        WebSocketSession connection = connect(token, messages);
        messages.clear();

        hub.publish("alarm.created", Map.of("alarmId", 11L, "siteId", 1L));

        assertEquals(1, messages.size());
        JsonNode event = objectMapper.readTree(messages.get(0).getPayload());
        assertEquals("alarm.created", event.path("type").asText());
        assertEquals(1L, event.at("/payload/siteId").asLong());
        verify(connection, never()).close(any(CloseStatus.class));
    }

    @Test
    void eventDoesNotLeakToConnectionForAnotherSite() throws Exception {
        List<TextMessage> siteOneMessages = new ArrayList<>();
        List<TextMessage> siteTwoMessages = new ArrayList<>();
        connect(tokenFor(1L), siteOneMessages);
        connect(tokenFor(2L), siteTwoMessages);
        siteOneMessages.clear();
        siteTwoMessages.clear();

        hub.publish("telemetry.updated", Map.of("deviceId", 1L, "siteId", 1L));

        assertEquals(1, siteOneMessages.size());
        assertTrue(siteTwoMessages.isEmpty());
    }

    @Test
    void eventWithoutValidSiteScopeIsNotBroadcast() throws Exception {
        List<TextMessage> messages = new ArrayList<>();
        connect(tokenFor(1L), messages);
        messages.clear();

        hub.publish("business.event.without.scope", Map.of("entityId", 99L));
        hub.publish("business.event.with.bad.scope", Map.of("siteId", "not-a-site"));

        assertTrue(messages.isEmpty());
    }

    @Test
    void invalidTokenIsRejectedWithPolicyViolation() throws Exception {
        List<TextMessage> messages = new ArrayList<>();
        WebSocketSession connection = connection("invalid-token", messages);

        hub.afterConnectionEstablished(connection);

        assertTrue(messages.isEmpty());
        verify(connection).close(org.mockito.ArgumentMatchers.argThat(status ->
                status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()
                        && "invalid token".equals(status.getReason())));
    }

    @Test
    void revokedTokenIsClosedBeforeTheNextEventDelivery() throws Exception {
        String token = tokenFor(1L);
        List<TextMessage> messages = new ArrayList<>();
        WebSocketSession connection = connect(token, messages);
        messages.clear();
        sessionService.remove(token);

        hub.publish("alarm.created", Map.of("alarmId", 11L, "siteId", 1L));

        assertTrue(messages.isEmpty());
        verify(connection).close(org.mockito.ArgumentMatchers.argThat(status ->
                status.getCode() == CloseStatus.POLICY_VIOLATION.getCode()
                        && "session expired".equals(status.getReason())));
    }

    private String tokenFor(Long... siteIds) {
        return sessionService.create(1L, "test", "Test User", "ADMIN", Set.of(siteIds)).token();
    }

    private WebSocketSession connect(String token, List<TextMessage> messages) throws Exception {
        WebSocketSession connection = connection(token, messages);
        hub.afterConnectionEstablished(connection);
        assertEquals("connection.ready", objectMapper.readTree(messages.get(0).getPayload()).path("type").asText());
        return connection;
    }

    private WebSocketSession connection(String token, List<TextMessage> messages) throws Exception {
        WebSocketSession connection = mock(WebSocketSession.class);
        String encodedToken = URLEncoder.encode(token, StandardCharsets.UTF_8);
        when(connection.getUri()).thenReturn(URI.create("ws://127.0.0.1/ws/events?token=" + encodedToken));
        when(connection.isOpen()).thenReturn(true);
        doAnswer(invocation -> {
            messages.add(invocation.getArgument(0));
            return null;
        }).when(connection).sendMessage(any(TextMessage.class));
        return connection;
    }
}
