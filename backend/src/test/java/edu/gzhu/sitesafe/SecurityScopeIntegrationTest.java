package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-security-scope;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SecurityScopeIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private long secondSiteId;
    private long secondDeviceId;
    private long secondTypeRuleId;
    private long secondDeviceRuleId;

    @BeforeAll
    void createSecondSiteFixtures() {
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("insert into site(code,name,address,status,updated_at) values(?,?,?,?,?)",
                "SCOPE-SITE-02", "Scope test site", "Test address", "ACTIVE", now);
        secondSiteId = jdbc.queryForObject("select id from site where code='SCOPE-SITE-02'", Long.class);
        jdbc.update("insert into zone(site_id,code,name,status,map_x,map_y,map_width,map_height) values(?,?,?,?,?,?,?,?)",
                secondSiteId, "SCOPE-ZONE-02", "Scope test zone", "CONSTRUCTION", 0.1, 0.1, 0.2, 0.2);
        long secondZoneId = jdbc.queryForObject("select id from zone where code='SCOPE-ZONE-02'", Long.class);
        jdbc.update("insert into device(code,name,type,site_id,zone_id,location,enabled,connection_status,last_reported_at,config_json) values(?,?,?,?,?,?,true,'OFFLINE',?,?)",
                "SCOPE-TC-02", "Scope tower", "TOWER_CRANE", secondSiteId, secondZoneId, "Test location", now, "{}");
        secondDeviceId = jdbc.queryForObject("select id from device where code='SCOPE-TC-02'", Long.class);

        insertRule("Second site SITE rule", "SITE", secondSiteId, now);
        secondTypeRuleId = insertRule("Second site TYPE rule", "TYPE", secondSiteId, now);
        secondDeviceRuleId = insertRule("Second site DEVICE rule", "DEVICE", secondDeviceId, now);
    }

    @Test
    void restTelemetryIngestRejectsDeviceFromAnotherSite() throws Exception {
        String token = login("device", "Device@123");
        long before = jdbc.queryForObject("select count(*) from telemetry where device_id=?", Long.class, secondDeviceId);

        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry("SCOPE-CROSS-SITE", "SCOPE-TC-02", "weight", "12"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_SCOPE_DENIED"));

        long after = jdbc.queryForObject("select count(*) from telemetry where device_id=?", Long.class, secondDeviceId);
        assertEquals(before, after);
    }

    @Test
    void ruleListOnlyReturnsRulesForAccessibleSites() throws Exception {
        String token = login("supervisor", "Safe@123");
        MvcResult result = mockMvc.perform(get("/api/rules")
                        .param("siteId", "1")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();

        List<Map<String, Object>> rows = objectMapper.convertValue(read(result).at("/data/items"),
                objectMapper.getTypeFactory().constructCollectionType(List.class, Map.class));
        List<String> names = rows.stream()
                .map(row -> String.valueOf(row.get("name")))
                .toList();
        assertFalse(names.contains("Second site SITE rule"));
        assertFalse(names.contains("Second site TYPE rule"));
        assertFalse(names.contains("Second site DEVICE rule"));
        assertTrue(rows.stream().anyMatch(row -> ((Number) row.get("id")).longValue() == 2L));
    }

    @Test
    void deviceManagerCannotOpenTheRuleAdministrationEndpoint() throws Exception {
        String token = login("device", "Device@123");

        mockMvc.perform(get("/api/rules")
                        .param("siteId", "1")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void ruleUpdateAndEnableRejectRulesFromAnotherSite() throws Exception {
        String token = login("supervisor", "Safe@123");
        Map<String, Object> update = ruleRequest("Blocked update", "TYPE", secondSiteId);

        mockMvc.perform(put("/api/rules/{id}", secondTypeRuleId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(update)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_SCOPE_DENIED"));

        mockMvc.perform(patch("/api/rules/{id}/enabled", secondDeviceRuleId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("enabled", false))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_SCOPE_DENIED"));

        assertEquals("Second site TYPE rule",
                jdbc.queryForObject("select name from alarm_rule where id=?", String.class, secondTypeRuleId));
        assertTrue(jdbc.queryForObject("select enabled from alarm_rule where id=?", Boolean.class, secondDeviceRuleId));
    }

    @Test
    void typeRuleDefaultsToSingleAccessibleSiteAndOnlyMatchesItsDeviceType() throws Exception {
        String supervisor = login("supervisor", "Safe@123");
        Map<String, Object> request = ruleRequest("Scoped tower type rule", "TYPE", null);
        MvcResult created = mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scopeId").value(1))
                .andExpect(jsonPath("$.data.targetDeviceType").value("TOWER_CRANE"))
                .andReturn();
        long ruleId = read(created).at("/data/id").asLong();

        String device = login("device", "Device@123");
        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry("SCOPE-ENV-" + UUID.randomUUID(), "ENV-001", "pm25", "2"))))
                .andExpect(status().isOk());
        assertEquals(0L, jdbc.queryForObject("select count(*) from alarm where rule_id=?", Long.class, ruleId));

        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry("SCOPE-TOWER-" + UUID.randomUUID(), "TC-002", "weight", "2"))))
                .andExpect(status().isOk());
        assertEquals(1L, jdbc.queryForObject("select count(*) from alarm where rule_id=?", Long.class, ruleId));
    }

    private long insertRule(String name, String scopeType, long scopeId, Timestamp now) {
        jdbc.update("insert into alarm_rule(name,source_type,metric_code,operator,threshold_value,severity,scope_type,scope_id,enabled,suppression_seconds,updated_at) values(?,?,?,?,?,?,?,?,true,?,?)",
                name, "DEVICE_RULE", "weight", ">", 1, "MEDIUM", scopeType, scopeId, 300, now);
        return jdbc.queryForObject("select id from alarm_rule where name=?", Long.class, name);
    }

    private Map<String, Object> ruleRequest(String name, String scopeType, Long scopeId) {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("name", name);
        request.put("sourceType", "DEVICE_RULE");
        request.put("metricCode", "weight");
        request.put("operator", ">");
        request.put("thresholdValue", 1);
        request.put("severity", "MEDIUM");
        request.put("scopeType", scopeType);
        request.put("scopeId", scopeId);
        request.put("suppressionSeconds", 300);
        return request;
    }

    private Map<String, Object> telemetry(String messageId, String deviceCode, String metricCode, String value) {
        return Map.of(
                "protocolVersion", "1.0",
                "messageId", messageId,
                "deviceCode", deviceCode,
                "messageType", "telemetry",
                "collectedAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(5).withNano(0).toString(),
                "metrics", List.of(Map.of("code", metricCode, "value", value, "unit", "t"))
        );
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
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
