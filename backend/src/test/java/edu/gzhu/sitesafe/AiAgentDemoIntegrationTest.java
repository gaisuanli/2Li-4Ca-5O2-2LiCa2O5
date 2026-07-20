package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-ai-agent-demo;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false",
        "app.sprinkler.timeout-scan-enabled=false",
        "app.ai-agent.mode=DEMO",
        "app.ai-agent.model=demo-site-summary"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AiAgentDemoIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @Autowired private JdbcTemplate jdbc;

    private String supervisor;
    private String admin;
    private long conversationId;
    private long secondSiteId;

    @BeforeAll
    void loginUsers() throws Exception {
        jdbc.update("insert into site(code,name,address,status,updated_at) values(?,?,?,?,current_timestamp)",
                "AI-AUDIT-SITE-2", "AI 审计隔离二号工地", "测试地址", "ACTIVE");
        secondSiteId = jdbc.queryForObject("select id from site where code='AI-AUDIT-SITE-2'", Long.class);
        jdbc.update("update app_user set site_scope=? where username='admin'", "1," + secondSiteId);
        supervisor = login("supervisor", "Safe@123");
        admin = login("admin", "Admin@123");
    }

    @Test
    @Order(1)
    void configIsAuthenticatedAndNeverDisclosesProviderSecretsOrBaseUrl() throws Exception {
        mockMvc.perform(get("/api/agent/config"))
                .andExpect(status().isUnauthorized());

        MvcResult result = mockMvc.perform(get("/api/agent/config")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.mode").value("DEMO"))
                .andExpect(jsonPath("$.data.model").value("demo-site-summary"))
                .andExpect(jsonPath("$.data.available").value(true))
                .andExpect(jsonPath("$.data.maxContentChars").value(8000))
                .andReturn();

        String response = result.getResponse().getContentAsString();
        assertFalse(response.contains("apiKey"));
        assertFalse(response.contains("baseUrl"));
        assertFalse(response.contains("allowedBaseUrls"));
    }

    @Test
    @Order(2)
    void conversationsArePagedAndStrictlyIsolatedByUserAndSite() throws Exception {
        MvcResult created = mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteId").value(1))
                .andExpect(jsonPath("$.data.title").value("新对话"))
                .andExpect(jsonPath("$.data.messageCount").value(0))
                .andReturn();
        conversationId = read(created).at("/data/id").asLong();

        for (int index = 0; index < 2; index++) {
            mockMvc.perform(post("/api/agent/conversations")
                            .header("Authorization", bearer(supervisor))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("siteId", 1, "title", "分页会话 " + index))))
                    .andExpect(status().isOk());
        }
        mockMvc.perform(get("/api/agent/conversations")
                        .param("siteId", "1").param("page", "1").param("pageSize", "2")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(2));

        MvcResult adminConversation = mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1, "title", "管理员私有会话"))))
                .andExpect(status().isOk())
                .andReturn();
        long adminConversationId = read(adminConversation).at("/data/id").asLong();

        mockMvc.perform(get("/api/agent/conversations/{id}/messages", adminConversationId)
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AI_CONVERSATION_NOT_FOUND"));
        mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 999))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_SCOPE_DENIED"));
    }

    @Test
    @Order(3)
    void demoAnswerPersistsAtomicExchangeUsesLiveSiteSummaryAndGeneratesTitle() throws Exception {
        MvcResult first = send(conversationId, "请告诉我待复核 ai 风险和当前告警情况");
        String answer = read(first).at("/data/assistantMessage/content").asText();
        assertTrue(answer.contains("【演示模式】"));
        assertTrue(answer.contains("统计时间："));
        assertTrue(answer.contains("数据来源口径："));
        assertTrue(answer.contains("待复核 AI 风险 1 条"), answer);
        assertEquals("DEMO", read(first).at("/data/assistantMessage/mode").asText());
        assertEquals("请告诉我待复核 ai 风险和当前告警情况",
                read(first).at("/data/conversation/title").asText());

        send(conversationId, "设备在线情况怎么样？");
        mockMvc.perform(get("/api/agent/conversations/{id}/messages", conversationId)
                        .param("page", "1").param("pageSize", "2")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.items[0].role").value("USER"))
                .andExpect(jsonPath("$.data.items[1].role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.items[1].mode").value("DEMO"));

        assertEquals(4L, jdbc.queryForObject(
                "select count(*) from ai_agent_message where conversation_id=?", Long.class, conversationId));
        assertEquals(2L, jdbc.queryForObject(
                "select count(*) from audit_log where object_type='AI_AGENT_CONVERSATION' and object_id=? "
                        + "and action='AI_AGENT_MESSAGE_SEND' and detail like '%mode=DEMO%'",
                Long.class, String.valueOf(conversationId)));
    }

    @Test
    @Order(4)
    void requestBodiesRejectBrowserSuppliedProviderConfigurationAndInvalidContent() throws Exception {
        long before = jdbc.queryForObject(
                "select count(*) from ai_agent_message where conversation_id=?", Long.class, conversationId);
        mockMvc.perform(post("/api/agent/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "content", "恶意覆盖配置",
                                "apiKey", "browser-secret",
                                "baseUrl", "http://example.invalid"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENT_REQUEST"));
        mockMvc.perform(post("/api/agent/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "   "))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_AGENT_CONTENT"));
        assertEquals(before, jdbc.queryForObject(
                "select count(*) from ai_agent_message where conversation_id=?", Long.class, conversationId));
    }

    @Test
    @Order(5)
    void agentAuditScopeIsResolvedFromConversationInsteadOfMultiSiteActorFallback() throws Exception {
        MvcResult siteOne = mockMvc.perform(get("/api/audit-logs")
                        .param("siteId", "1").param("pageSize", "100")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        assertTrue(siteOne.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("AI_AGENT_CONVERSATION"));

        MvcResult siteTwo = mockMvc.perform(get("/api/audit-logs")
                        .param("siteId", String.valueOf(secondSiteId)).param("pageSize", "100")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(siteTwo.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8)
                .contains("AI_AGENT_CONVERSATION"));
    }

    private MvcResult send(long id, String content) throws Exception {
        return mockMvc.perform(post("/api/agent/conversations/{id}/messages", id)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", content))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userMessage.role").value("USER"))
                .andExpect(jsonPath("$.data.assistantMessage.role").value("ASSISTANT"))
                .andReturn();
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
        return objectMapper.readTree(result.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8));
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
