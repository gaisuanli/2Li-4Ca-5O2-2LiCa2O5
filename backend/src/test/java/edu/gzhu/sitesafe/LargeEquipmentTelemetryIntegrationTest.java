package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.config.LargeEquipmentDemoTelemetryInitializer;
import edu.gzhu.sitesafe.config.LargeEquipmentDefaultRuleInitializer;
import edu.gzhu.sitesafe.config.TowerExtendedDemoTelemetryInitializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-large-equipment;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.demo-data-enabled=true",
        "app.tcp.enabled=false"
})
@AutoConfigureMockMvc
class LargeEquipmentTelemetryIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private LargeEquipmentDemoTelemetryInitializer initializer;

    @Autowired
    private LargeEquipmentDefaultRuleInitializer ruleInitializer;

    @Autowired
    private TowerExtendedDemoTelemetryInitializer towerInitializer;

    @Test
    void demoBackfillProvidesIdempotentCurrentValuesAndTrends() throws Exception {
        assertDemoMetricCount("EL-001", 48);
        assertDemoMetricCount("FM-001", 48);
        assertDemoMetricCount("PIT-001", 72);

        long before = jdbc.queryForObject("select count(*) from telemetry", Long.class);
        assertEquals(0, initializer.backfill());
        assertEquals(before, jdbc.queryForObject("select count(*) from telemetry", Long.class));

        String admin = login();
        long elevatorId = deviceId("EL-001");
        mockMvc.perform(get("/api/telemetry/latest")
                        .param("deviceId", String.valueOf(elevatorId))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(6)));

        mockMvc.perform(get("/api/telemetry/trend")
                        .param("deviceId", String.valueOf(elevatorId))
                        .param("metric", "load")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(greaterThanOrEqualTo(8))));
    }

    @Test
    void ingestAcceptsDeclaredMetricsAndRejectsCrossTypeOrOutOfRangeMetrics() throws Exception {
        String admin = login();
        postTelemetry(admin, "LARGE-EL-VALID", "EL-001", "load", 980, "kg")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insertedMetrics").value(1));
        postTelemetry(admin, "LARGE-FM-VALID", "FM-001", "axialForce", 142.5, "kN")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insertedMetrics").value(1));
        postTelemetry(admin, "LARGE-PIT-VALID", "PIT-001", "horizontalDisplacement", 5.8, "mm")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insertedMetrics").value(1));

        postTelemetry(admin, "LARGE-EL-MISMATCH", "EL-001", "pm25", 42, "μg/m³")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("METRIC_DEVICE_TYPE_MISMATCH"));

        postTelemetry(admin, "LARGE-EL-RANGE", "EL-001", "floor", 301, "层")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("METRIC_OUT_OF_RANGE"));
    }

    @Test
    void defaultRulesCoverEveryLargeEquipmentFamilyAndRemainIdempotent() throws Exception {
        assertEquals(2L, ruleCountForType("ELEVATOR"));
        assertEquals(2L, ruleCountForType("FORMWORK"));
        assertEquals(2L, ruleCountForType("FOUNDATION_PIT"));
        assertEquals(0, ruleInitializer.backfill());

        String admin = login();
        mockMvc.perform(get("/api/rules")
                        .param("siteId", "1")
                        .param("pageSize", "100")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[?(@.metricCode == 'load')].targetDeviceType", hasItem("ELEVATOR")))
                .andExpect(jsonPath("$.data.items[?(@.metricCode == 'displacement')].targetDeviceType", hasItem("FORMWORK")))
                .andExpect(jsonPath("$.data.items[?(@.metricCode == 'horizontalDisplacement')].targetDeviceType", hasItem("FOUNDATION_PIT")));

        Map<String, Object> ambiguousTypeRule = Map.of(
                "name", "共享轴力类型规则",
                "sourceType", "DEVICE_RULE",
                "metricCode", "axialForce",
                "operator", ">",
                "thresholdValue", 300,
                "severity", "HIGH",
                "scopeType", "TYPE",
                "scopeId", 1,
                "suppressionSeconds", 300
        );
        mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ambiguousTypeRule)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("RULE_METRIC_TYPE_AMBIGUOUS"));
    }

    @Test
    void thresholdTelemetryCreatesAutomaticAlarmsForEveryLargeEquipmentFamily() throws Exception {
        String admin = login();
        postTelemetry(admin, "LARGE-ALARM-EL-LIMIT", "EL-001", "limitStatus", 1, "code")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdAlarmIds", hasSize(1)));
        postTelemetry(admin, "LARGE-ALARM-FM-PRESSURE", "FM-001", "pressure", 28, "kPa")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdAlarmIds", hasSize(1)));
        postTelemetry(admin, "LARGE-ALARM-PIT-RATE", "PIT-001", "settlementRate", 0.28, "mm/h")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.createdAlarmIds", hasSize(1)));

        assertEquals(1L, pendingAlarmCount("EL-001", "limitStatus"));
        assertEquals(1L, pendingAlarmCount("FM-001", "pressure"));
        assertEquals(1L, pendingAlarmCount("PIT-001", "settlementRate"));
    }

    @Test
    void towerBackfillCompletesSevenMetricsForBothDemoTowersAndIsIdempotent() throws Exception {
        assertTowerMetricCount("TC-001", 36);
        assertTowerMetricCount("TC-002", 56);
        assertEquals(0, towerInitializer.backfill());

        String admin = login();
        mockMvc.perform(get("/api/telemetry/latest")
                        .param("deviceId", String.valueOf(deviceId("TC-002")))
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(7)));
    }

    private org.springframework.test.web.servlet.ResultActions postTelemetry(
            String token, String messageId, String deviceCode, String metric, Number value, String unit) throws Exception {
        Map<String, Object> payload = Map.of(
                "protocolVersion", "1.0",
                "messageId", messageId,
                "deviceCode", deviceCode,
                "messageType", "telemetry",
                "collectedAt", OffsetDateTime.now().withNano(0).toString(),
                "metrics", List.of(Map.of("code", metric, "value", value, "unit", unit))
        );
        return mockMvc.perform(post("/api/telemetry")
                .header("Authorization", bearer(token))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(payload)));
    }

    private void assertDemoMetricCount(String deviceCode, long expected) {
        Long count = jdbc.queryForObject(
                "select count(*) from telemetry t join device d on d.id=t.device_id where d.code=? and t.message_id like 'DEMO-LARGE-%'",
                Long.class, deviceCode);
        assertEquals(expected, count);
    }

    private void assertTowerMetricCount(String deviceCode, long expected) {
        Long count = jdbc.queryForObject(
                "select count(*) from telemetry t join device d on d.id=t.device_id where d.code=? and t.message_id like 'DEMO-TOWER-%'",
                Long.class, deviceCode);
        assertEquals(expected, count);
    }

    private long ruleCountForType(String deviceType) {
        return jdbc.queryForObject(
                "select count(*) from alarm_rule where scope_type='TYPE' and scope_id=1 and metric_code in "
                        + switch (deviceType) {
                    case "ELEVATOR" -> "('load','limitStatus')";
                    case "FORMWORK" -> "('displacement','pressure')";
                    case "FOUNDATION_PIT" -> "('horizontalDisplacement','settlementRate')";
                    default -> throw new IllegalArgumentException("Unsupported device type " + deviceType);
                }, Long.class);
    }

    private long pendingAlarmCount(String deviceCode, String metricCode) {
        return jdbc.queryForObject(
                "select count(*) from alarm a join device d on d.id=a.device_id join alarm_rule r on r.id=a.rule_id "
                        + "where d.code=? and r.metric_code=? and a.status='PENDING'",
                Long.class, deviceCode, metricCode);
    }

    private long deviceId(String code) {
        return jdbc.queryForObject("select id from device where code=?", Long.class, code);
    }

    private String login() throws Exception {
        String response = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "password", "Admin@123"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode body = objectMapper.readTree(response);
        return body.path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
