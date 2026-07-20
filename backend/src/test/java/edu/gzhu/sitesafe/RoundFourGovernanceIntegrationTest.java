package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.blankOrNullString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-round-four;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false",
        "app.integrations.vision-ai.enabled=false",
        "app.integrations.sprinkler-gateway.mode=DEMO",
        "app.push.webhook-enabled=false"
})
@AutoConfigureMockMvc
class RoundFourGovernanceIntegrationTest {
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Test
    void knowledgeReportReviewAndControlledDeliveryCompleteEndToEnd() throws Exception {
        String supervisor = login("supervisor", "Safe@123");
        String admin = login("admin", "Admin@123");

        long knowledgeId = dataId(mockMvc.perform(post("/api/knowledge-documents")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "siteId", 1,
                                "title", "塔吊大风停机要求",
                                "category", "设备操作规程",
                                "sourceReference", "项目安全管理制度 4.2",
                                "content", "塔吊现场风速达到停机阈值时应停止回转和吊装，并由负责人确认。"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn());

        mockMvc.perform(post("/api/knowledge-documents/{id}/submit", knowledgeId)
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        mockMvc.perform(post("/api/knowledge-documents/{id}/review", knowledgeId)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "APPROVE"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/knowledge-documents/{id}/review", knowledgeId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "APPROVE", "note", "制度来源与现场阈值已核对"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.data.reviewedByName").value("系统管理员"));

        MvcResult templates = mockMvc.perform(get("/api/report-templates")
                        .param("siteId", "1")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andReturn();
        long templateId = objectMapper.readTree(templates.getResponse().getContentAsString())
                .path("data").path("items").path(0).path("id").asLong();

        long reportId = dataId(mockMvc.perform(post("/api/reports/generate")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1, "templateId", templateId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.content", not(blankOrNullString())))
                .andReturn());

        mockMvc.perform(post("/api/reports/{id}/submit", reportId)
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));

        long channelId = objectMapper.readTree(mockMvc.perform(get("/api/push-channels")
                        .param("siteId", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString())
                .path("data").path("items").path(0).path("id").asLong();

        mockMvc.perform(post("/api/reports/{id}/deliveries", reportId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("channelId", channelId))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REPORT_NOT_APPROVED"));

        mockMvc.perform(post("/api/reports/{id}/review", reportId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "APPROVE", "note", "数据与现场日报一致"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));

        mockMvc.perform(post("/api/reports/{id}/deliveries", reportId)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("channelId", channelId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"))
                .andExpect(jsonPath("$.data.channelType").value("LOG"));

        mockMvc.perform(post("/api/report-templates")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "siteId", 1,
                                "name", "非法模板",
                                "bodyTemplate", "未知字段 {{inventedMetric}}"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("UNKNOWN_TEMPLATE_FIELD"));
    }

    @Test
    void externalIntegrationCenterReportsTruthfulStatesAndProtectsConfiguration() throws Exception {
        String admin = login("admin", "Admin@123");
        mockMvc.perform(get("/api/integrations")
                        .param("siteId", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items", hasSize(4)))
                .andExpect(jsonPath("$.data.items[0].type").value("VIDEO"))
                .andExpect(jsonPath("$.data.items[0].state").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.items[1].state").value("NOT_CONFIGURED"))
                .andExpect(jsonPath("$.data.items[2].state").value("SIMULATED"))
                .andExpect(jsonPath("$.data.items[3].state").value("CONFIGURED"));

        mockMvc.perform(post("/api/integrations/PRODUCTION_MONITORING/check")
                        .param("siteId", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READY"))
                .andExpect(jsonPath("$.data.registeredMeters").isNumber());

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/actuator/prometheus").header("Authorization", bearer(admin)))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/cameras/1/stream")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1, "streamUrl", "https://unapproved.example/live.m3u8"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VIDEO_HOST_NOT_ALLOWED"));

        mockMvc.perform(put("/api/cameras/1/stream")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1, "streamUrl", "http://127.0.0.1:19090/live.m3u8"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.streamProtocol").value("HTTP"));

        mockMvc.perform(post("/api/integrations/vision-ai/infer")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1, "cameraId", 1, "imageBase64", "eA=="))))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("VISION_AI_DISABLED"));

        mockMvc.perform(post("/api/integration-callbacks/sprinkler")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("commandId", "CMD-NOT-REAL", "success", true))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_GATEWAY_CALLBACK_TOKEN"));
    }

    private long dataId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk()).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("token").asText();
    }

    private String bearer(String token) { return "Bearer " + token; }
    private String json(Object value) throws Exception { return objectMapper.writeValueAsString(value); }
}
