package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import edu.gzhu.sitesafe.config.AiAgentProperties;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-ai-agent-user-provider;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false",
        "app.sprinkler.timeout-scan-enabled=false",
        "app.ai-agent.mode=DEMO",
        "app.ai-agent.user-config-enabled=true",
        "app.ai-agent.credential-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "app.ai-agent.approved-models=user-approved-model",
        "app.ai-agent.connect-timeout-ms=500",
        "app.ai-agent.read-timeout-ms=1000"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiAgentUserProviderConfigIntegrationTest {
    private static final ProviderFixture PROVIDER = ProviderFixture.start();
    private static final String USER_SECRET = "test-credential-not-a-secret";

    @DynamicPropertySource
    static void providerProperties(DynamicPropertyRegistry registry) {
        registry.add("app.ai-agent.allowed-base-urls", PROVIDER::baseUrl);
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private AiAgentProperties properties;

    private String supervisor;
    private String admin;

    @BeforeAll
    void loginUsers() throws Exception {
        supervisor = login("supervisor", "Safe@123");
        admin = login("admin", "Admin@123");
    }

    @AfterAll
    static void stopProvider() {
        PROVIDER.close();
    }

    @Test
    @Order(1)
    void configIsAuthenticatedAndReadViewNeverContainsCredentialMaterial() throws Exception {
        mockMvc.perform(get("/api/agent/provider-config"))
                .andExpect(status().isUnauthorized());

        MvcResult result = mockMvc.perform(get("/api/agent/provider-config")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(false))
                .andExpect(jsonPath("$.data.credentialStorageAvailable").value(true))
                .andExpect(jsonPath("$.data.userConfigEnabled").value(true))
                .andExpect(jsonPath("$.data.approvedBaseUrls[0]").value(PROVIDER.baseUrl()))
                .andExpect(jsonPath("$.data.approvedModels[0]").value("user-approved-model"))
                .andExpect(jsonPath("$.data.customModelAllowed").value(false))
                .andExpect(jsonPath("$.data.effectiveMode").value("DEMO"))
                .andExpect(jsonPath("$.data.available").value(true))
                .andReturn();
        assertFalse(result.getResponse().getContentAsString().contains("encryptedApiKey"));

        save(supervisor, PROVIDER.baseUrl(), "user-approved-model", "   ", status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENT_PROVIDER_API_KEY"));
        assertEquals(0L, jdbc.queryForObject("select count(*) from ai_agent_provider_config", Long.class));
    }

    @Test
    @Order(2)
    void saveEncryptsKeyAtRestAndUserConfigurationOverridesDemoForRealCalls() throws Exception {
        MvcResult saved = save(supervisor, PROVIDER.baseUrl(), "user-approved-model", USER_SECRET,
                status().isOk()).andReturn();
        String body = saved.getResponse().getContentAsString(StandardCharsets.UTF_8);
        assertFalse(body.contains(USER_SECRET));
        assertFalse(body.contains("encrypted_api_key"));
        assertFalse(body.contains("encryptedApiKey"));

        long supervisorId = userId("supervisor");
        String encrypted = jdbc.queryForObject(
                "select encrypted_api_key from ai_agent_provider_config where user_id=?",
                String.class, supervisorId);
        assertTrue(encrypted.startsWith("v1."));
        assertFalse(encrypted.contains(USER_SECRET));
        assertNotEquals(USER_SECRET, encrypted);

        String auditDetail = jdbc.queryForObject(
                "select detail from audit_log where action='AI_AGENT_PROVIDER_CONFIG_SAVE' and user_id=? "
                        + "order by id desc limit 1", String.class, supervisorId);
        assertTrue(auditDetail.contains("baseUrl=" + PROVIDER.baseUrl()));
        assertTrue(auditDetail.contains("model=user-approved-model"));
        assertFalse(auditDetail.contains(USER_SECRET));

        mockMvc.perform(get("/api/agent/config").header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data.model").value("user-approved-model"))
                .andExpect(jsonPath("$.data.available").value(true));

        long conversationId = createConversation(supervisor);
        MvcResult answer = mockMvc.perform(post("/api/agent/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "使用个人服务商配置"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.assistantMessage.mode").value("OPENAI_COMPATIBLE"))
                .andExpect(jsonPath("$.data.assistantMessage.model").value("user-approved-model"))
                .andReturn();
        assertEquals("用户配置兼容服务回答", read(answer).at("/data/assistantMessage/content").asText());
        assertEquals("Bearer " + USER_SECRET, PROVIDER.authorization.get());
        assertTrue(PROVIDER.requestBody.get().contains("\"model\":\"user-approved-model\""));
    }

    @Test
    @Order(3)
    void blankKeyPreservesExistingCiphertextAndConfigurationsAreIsolatedPerUser() throws Exception {
        long supervisorId = userId("supervisor");
        String before = jdbc.queryForObject(
                "select encrypted_api_key from ai_agent_provider_config where user_id=?",
                String.class, supervisorId);
        save(supervisor, PROVIDER.baseUrl(), "user-approved-model", "   ", status().isOk())
                .andReturn();
        String after = jdbc.queryForObject(
                "select encrypted_api_key from ai_agent_provider_config where user_id=?",
                String.class, supervisorId);
        assertEquals(before, after);

        mockMvc.perform(get("/api/agent/provider-config").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(false));
        mockMvc.perform(get("/api/agent/provider-config").header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(true))
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(true));
    }

    @Test
    @Order(4)
    void whitelistModelAndRequestShapeValidationCannotOverwriteSavedConfiguration() throws Exception {
        long supervisorId = userId("supervisor");
        String before = jdbc.queryForObject(
                "select base_url || '|' || model || '|' || encrypted_api_key "
                        + "from ai_agent_provider_config where user_id=?", String.class, supervisorId);

        save(supervisor, "https://unapproved.example/v1", "user-approved-model", "replacement",
                status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_PROVIDER_BASE_URL_NOT_APPROVED"));
        save(supervisor, PROVIDER.baseUrl(), "unapproved-model", "replacement",
                status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_PROVIDER_MODEL_NOT_APPROVED"));
        save(supervisor, PROVIDER.baseUrl() + "?token=secret", "user-approved-model", "replacement",
                status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_PROVIDER_BASE_URL_NOT_APPROVED"));
        mockMvc.perform(put("/api/agent/provider-config")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("baseUrl", PROVIDER.baseUrl(), "model", "user-approved-model",
                                "apiKey", "replacement", "unexpected", true))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENT_REQUEST"));

        String after = jdbc.queryForObject(
                "select base_url || '|' || model || '|' || encrypted_api_key "
                        + "from ai_agent_provider_config where user_id=?", String.class, supervisorId);
        assertEquals(before, after);
    }

    @Test
    @Order(5)
    void globalDisabledRemainsMasterKillSwitch() throws Exception {
        properties.setMode(AiAgentProperties.Mode.DISABLED);
        try {
            mockMvc.perform(get("/api/agent/config").header("Authorization", bearer(supervisor)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mode").value("DISABLED"))
                    .andExpect(jsonPath("$.data.available").value(false));
            mockMvc.perform(get("/api/agent/provider-config").header("Authorization", bearer(supervisor)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.configured").value(true))
                    .andExpect(jsonPath("$.data.effectiveMode").value("DISABLED"))
                    .andExpect(jsonPath("$.data.available").value(false));
            long conversationId = createConversation(supervisor);
            mockMvc.perform(post("/api/agent/conversations/{id}/messages", conversationId)
                            .header("Authorization", bearer(supervisor))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("content", "总开关应阻止调用"))))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(jsonPath("$.code").value("AI_AGENT_DISABLED"));
        } finally {
            properties.setMode(AiAgentProperties.Mode.DEMO);
        }
    }

    @Test
    @Order(6)
    void deleteClearsOnlyCurrentUsersConfigurationAndRestoresGlobalFallback() throws Exception {
        mockMvc.perform(delete("/api/agent/provider-config").header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk());
        assertEquals(0L, jdbc.queryForObject(
                "select count(*) from ai_agent_provider_config where user_id=?",
                Long.class, userId("supervisor")));
        mockMvc.perform(get("/api/agent/provider-config").header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.configured").value(false))
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(false));
        mockMvc.perform(get("/api/agent/config").header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("DEMO"));
    }

    private org.springframework.test.web.servlet.ResultActions save(
            String token, String baseUrl, String model, String apiKey,
            org.springframework.test.web.servlet.ResultMatcher statusMatcher) throws Exception {
        return mockMvc.perform(put("/api/agent/provider-config")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("baseUrl", baseUrl, "model", model, "apiKey", apiKey))))
                .andExpect(statusMatcher);
    }

    private long createConversation(String token) throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1))))
                .andExpect(status().isOk())
                .andReturn();
        return read(created).at("/data/id").asLong();
    }

    private long userId(String username) {
        return jdbc.queryForObject("select id from app_user where username=?", Long.class, username);
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

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private static final class ProviderFixture implements AutoCloseable {
        private final HttpServer server;
        private final AtomicReference<String> authorization = new AtomicReference<>();
        private final AtomicReference<String> requestBody = new AtomicReference<>();

        private ProviderFixture(HttpServer server) {
            this.server = server;
        }

        static ProviderFixture start() {
            try {
                HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
                ProviderFixture fixture = new ProviderFixture(server);
                server.createContext("/v1/chat/completions", fixture::handle);
                server.start();
                return fixture;
            } catch (IOException ex) {
                throw new ExceptionInInitializerError(ex);
            }
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
        }

        private void handle(HttpExchange exchange) throws IOException {
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] body = "{\"choices\":[{\"message\":{\"content\":\"用户配置兼容服务回答\"}}]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
