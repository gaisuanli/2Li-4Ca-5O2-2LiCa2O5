package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-ai-agent-compatible;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false",
        "app.sprinkler.timeout-scan-enabled=false",
        "app.ai-agent.mode=OPENAI_COMPATIBLE",
        "app.ai-agent.model=test-compatible-model",
        "app.ai-agent.api-key=test-key",
        "app.ai-agent.connect-timeout-ms=500",
        "app.ai-agent.read-timeout-ms=500",
        "app.ai-agent.max-response-chars=100"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiAgentCompatibleIntegrationTest {
    private static final ProviderFixture PROVIDER = ProviderFixture.start();

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ai-agent.base-url", PROVIDER::baseUrl);
        registry.add("app.ai-agent.allowed-base-urls", PROVIDER::baseUrl);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private String token;
    private long conversationId;

    @BeforeAll
    void loginAndCreateConversation() throws Exception {
        token = login("supervisor", "Safe@123");
        MvcResult created = mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1))))
                .andExpect(status().isOk())
                .andReturn();
        conversationId = read(created).at("/data/id").asLong();
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.close();
    }

    @Test
    @Order(1)
    void compatibleConfigAndSuccessfulCallDoNotExposeServerConfiguration() throws Exception {
        MvcResult config = mockMvc.perform(get("/api/agent/config")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data.model").value("test-compatible-model"))
                .andExpect(jsonPath("$.data.available").value(true))
                .andReturn();
        String configBody = config.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(configBody.contains("test-key"));
        assertFalse(configBody.contains(PROVIDER.baseUrl()));

        MvcResult answer = send("normal-compatible-question", status().isOk());
        assertEquals("兼容接口测试回答", read(answer).at("/data/assistantMessage/content").asText());
        assertEquals("OPENAI_COMPATIBLE", read(answer).at("/data/assistantMessage/mode").asText());
        assertEquals("Bearer test-key", PROVIDER.lastAuthorization.get());
        assertTrue(PROVIDER.lastRequestBody.get().contains("\"model\":\"test-compatible-model\""));
        assertTrue(PROVIDER.lastRequestBody.get().contains("site_id=1"));
    }

    @Test
    @Order(2)
    void providerHttpErrorIsSanitizedAndDoesNotPersistPartialQuestion() throws Exception {
        long before = messageCount();
        MvcResult failed = send("trigger-provider-error", status().isBadGateway());
        String response = failed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(response.contains("AI_AGENT_PROVIDER_ERROR"));
        assertFalse(response.contains("upstream-secret-response-body"));
        assertFalse(response.contains("test-key"));
        assertEquals(before, messageCount());
        assertEquals(1L, failedAuditCount("AI_AGENT_PROVIDER_ERROR"));
    }

    @Test
    @Order(3)
    void providerTimeoutUsesDedicatedCodeAndDoesNotPersistPartialQuestion() throws Exception {
        long before = messageCount();
        mockMvc.perform(post("/api/agent/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "trigger-provider-timeout"))))
                .andExpect(status().isGatewayTimeout())
                .andExpect(jsonPath("$.code").value("AI_AGENT_PROVIDER_TIMEOUT"));
        assertEquals(before, messageCount());
        assertEquals(1L, failedAuditCount("AI_AGENT_PROVIDER_TIMEOUT"));
    }

    @Test
    @Order(4)
    void oversizedProviderResponseIsRejectedBeforeFullDeserializationAndDoesNotPersist() throws Exception {
        long before = messageCount();
        MvcResult failed = send("trigger-oversized-response", status().isBadGateway());
        String body = failed.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertTrue(body.contains("AI_AGENT_PROVIDER_ERROR"));
        assertFalse(body.contains("oversized-provider-marker"));
        assertEquals(before, messageCount());
        assertEquals(2L, failedAuditCount("AI_AGENT_PROVIDER_ERROR"));
    }

    @Test
    @Order(5)
    void concurrentSendsToOneConversationAreSerializedAndSecondProviderSeesFirstAnswer() throws Exception {
        long orderedConversation = createConversation();
        ExecutorService callers = Executors.newFixedThreadPool(2);
        PROVIDER.resetOrdering();
        try {
            Future<MvcResult> first = callers.submit(() ->
                    sendTo(orderedConversation, "ordering-first-question", status().isOk()));
            assertTrue(PROVIDER.orderingFirstStarted.await(2, TimeUnit.SECONDS));
            Future<MvcResult> second = callers.submit(() ->
                    sendTo(orderedConversation, "ordering-second-question", status().isOk()));

            assertFalse(PROVIDER.orderingSecondStarted.await(200, TimeUnit.MILLISECONDS),
                    "second provider call must remain behind the same-conversation lock");
            PROVIDER.releaseOrderingFirst.countDown();
            assertEquals("first-order-answer",
                    read(first.get(3, TimeUnit.SECONDS)).at("/data/assistantMessage/content").asText());
            assertEquals("second-order-answer",
                    read(second.get(3, TimeUnit.SECONDS)).at("/data/assistantMessage/content").asText());
            assertTrue(PROVIDER.orderingSecondStarted.await(1, TimeUnit.SECONDS));
            assertTrue(PROVIDER.secondSawFirstAnswer.get(),
                    "second provider request must include the first committed assistant answer in history");

            mockMvc.perform(get("/api/agent/conversations/{id}/messages", orderedConversation)
                            .param("pageSize", "10")
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.items[0].content").value("ordering-first-question"))
                    .andExpect(jsonPath("$.data.items[1].content").value("first-order-answer"))
                    .andExpect(jsonPath("$.data.items[2].content").value("ordering-second-question"))
                    .andExpect(jsonPath("$.data.items[3].content").value("second-order-answer"));
        } finally {
            PROVIDER.releaseOrderingFirst.countDown();
            callers.shutdownNow();
        }
    }

    private MvcResult send(String content,
                           org.springframework.test.web.servlet.ResultMatcher statusMatcher) throws Exception {
        return sendTo(conversationId, content, statusMatcher);
    }

    private MvcResult sendTo(long targetConversationId, String content,
                             org.springframework.test.web.servlet.ResultMatcher statusMatcher) throws Exception {
        return mockMvc.perform(post("/api/agent/conversations/{id}/messages", targetConversationId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", content))))
                .andExpect(statusMatcher)
                .andReturn();
    }

    private long createConversation() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1))))
                .andExpect(status().isOk())
                .andReturn();
        return read(created).at("/data/id").asLong();
    }

    private long failedAuditCount(String code) {
        return jdbc.queryForObject(
                "select count(*) from audit_log where object_type='AI_AGENT_CONVERSATION' "
                        + "and object_id=? and action='AI_AGENT_MESSAGE_FAILED' and detail=?",
                Long.class, String.valueOf(conversationId), "AI Agent 问答失败；code=" + code);
    }

    private long messageCount() {
        return jdbc.queryForObject("select count(*) from ai_agent_message where conversation_id=?",
                Long.class, conversationId);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).at("/data/token").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String value) { return "Bearer " + value; }

    private static final class ProviderFixture {
        private final HttpServer server;
        private final ExecutorService executor;
        private final AtomicReference<String> lastAuthorization = new AtomicReference<>();
        private final AtomicReference<String> lastRequestBody = new AtomicReference<>();
        private volatile CountDownLatch orderingFirstStarted = new CountDownLatch(1);
        private volatile CountDownLatch orderingSecondStarted = new CountDownLatch(1);
        private volatile CountDownLatch releaseOrderingFirst = new CountDownLatch(1);
        private final AtomicBoolean secondSawFirstAnswer = new AtomicBoolean(false);

        private ProviderFixture(HttpServer server, ExecutorService executor) {
            this.server = server;
            this.executor = executor;
        }

        static ProviderFixture start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ExecutorService executor = Executors.newCachedThreadPool();
                ProviderFixture fixture = new ProviderFixture(server, executor);
                server.createContext("/v1/chat/completions", fixture::handle);
                server.setExecutor(executor);
                server.start();
                return fixture;
            } catch (IOException ex) {
                throw new ExceptionInInitializerError(ex);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        void handle(HttpExchange exchange) throws IOException {
            lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastRequestBody.set(body);
            if (body.contains("\"content\":\"ordering-second-question\"")) {
                secondSawFirstAnswer.set(body.contains("first-order-answer"));
                orderingSecondStarted.countDown();
                write(exchange, 200,
                        "{\"choices\":[{\"message\":{\"content\":\"second-order-answer\"}}]}");
                return;
            }
            if (body.contains("\"content\":\"ordering-first-question\"")) {
                orderingFirstStarted.countDown();
                await(releaseOrderingFirst);
                write(exchange, 200,
                        "{\"choices\":[{\"message\":{\"content\":\"first-order-answer\"}}]}");
                return;
            }
            if (body.contains("trigger-oversized-response")) {
                write(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\""
                        + "oversized-provider-marker".repeat(260) + "\"}}]}");
                return;
            }
            if (body.contains("trigger-provider-timeout")) {
                try {
                    Thread.sleep(1200);
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                }
                write(exchange, 200, "{\"choices\":[{\"message\":{\"content\":\"late\"}}]}");
                return;
            }
            if (body.contains("trigger-provider-error")) {
                write(exchange, 401, "{\"error\":\"upstream-secret-response-body\"}");
                return;
            }
            write(exchange, 200,
                    "{\"choices\":[{\"message\":{\"content\":\"兼容接口测试回答\"}}]}");
        }

        void resetOrdering() {
            orderingFirstStarted = new CountDownLatch(1);
            orderingSecondStarted = new CountDownLatch(1);
            releaseOrderingFirst = new CountDownLatch(1);
            secondSawFirstAnswer.set(false);
        }

        void await(CountDownLatch latch) {
            try {
                if (!latch.await(3, TimeUnit.SECONDS)) throw new IllegalStateException("ordering test timeout");
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(ex);
            }
        }

        void write(HttpExchange exchange, int status, String body) throws IOException {
            byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=UTF-8");
            exchange.sendResponseHeaders(status, bytes.length);
            try (var output = exchange.getResponseBody()) {
                output.write(bytes);
            }
        }

        void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
