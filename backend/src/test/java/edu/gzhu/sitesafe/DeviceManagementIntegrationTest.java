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

import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-device-management;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false"
})
@AutoConfigureMockMvc
class DeviceManagementIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void deviceManagerCanEditDeviceButInvalidConfigAndSupervisorAreRejected() throws Exception {
        String deviceToken = login("device", "Device@123");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("code", "EL-001");
        request.put("name", "1号施工升降机（已校准）");
        request.put("type", "ELEVATOR");
        request.put("siteId", 1);
        request.put("zoneId", 3);
        request.put("location", "中区主体东侧井道");
        request.put("configJson", "{\"ratedLoad\":2500,\"floorCount\":18}");

        mockMvc.perform(put("/api/devices/3")
                        .header("Authorization", bearer(deviceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("1号施工升降机（已校准）"))
                .andExpect(jsonPath("$.data.location").value("中区主体东侧井道"));

        request.put("configJson", "[]");
        mockMvc.perform(put("/api/devices/3")
                        .header("Authorization", bearer(deviceToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEVICE_CONFIG"));

        String supervisorToken = login("supervisor", "Safe@123");
        request.put("configJson", "{}");
        mockMvc.perform(put("/api/devices/3")
                        .header("Authorization", bearer(supervisorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    void ruleSourceAndDeviceScopeMustMatchTheMetricFamily() throws Exception {
        String supervisorToken = login("supervisor", "Safe@123");
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", "错误来源规则");
        request.put("sourceType", "DEVICE_RULE");
        request.put("metricCode", "pm25");
        request.put("operator", ">");
        request.put("thresholdValue", 75);
        request.put("severity", "MEDIUM");
        request.put("scopeType", "TYPE");
        request.put("scopeId", 1);
        request.put("suppressionSeconds", 300);

        mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(supervisorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RULE_METRIC_SOURCE_MISMATCH"));

        request.put("name", "错误设备范围规则");
        request.put("metricCode", "windSpeed");
        request.put("scopeType", "DEVICE");
        request.put("scopeId", 6);
        mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(supervisorToken))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RULE_METRIC_DEVICE_MISMATCH"));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        assertEquals(username, body.path("data").path("user").path("username").asText());
        return body.path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
