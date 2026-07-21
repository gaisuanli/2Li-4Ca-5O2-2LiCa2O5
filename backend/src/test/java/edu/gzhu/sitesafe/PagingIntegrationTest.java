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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-paging;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PagingIntegrationTest {
    private static final LocalDateTime TASK_BASE = LocalDateTime.of(2031, 1, 2, 8, 0);
    private static final LocalDateTime TELEMETRY_BASE = LocalDateTime.of(2032, 2, 3, 9, 0);

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private long zoneId;
    private long deviceId;

    @BeforeAll
    void createPagingFixtures() {
        zoneId = jdbc.queryForObject("select min(id) from zone where site_id=1", Long.class);
        deviceId = jdbc.queryForObject("select min(id) from device where site_id=1", Long.class);
        long adminId = jdbc.queryForObject("select id from app_user where username='admin'", Long.class);

        Timestamp now = Timestamp.valueOf(LocalDateTime.of(2030, 1, 1, 0, 0));
        jdbc.update("insert into alarm_rule(name,source_type,metric_code,operator,threshold_value,severity,scope_type,scope_id,enabled,suppression_seconds,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                "PAGING-RULE-ENABLED", "DEVICE_RULE", "weight", ">", 10, "MEDIUM", "SITE", 1, true, 300, now);
        jdbc.update("insert into alarm_rule(name,source_type,metric_code,operator,threshold_value,severity,scope_type,scope_id,enabled,suppression_seconds,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                "PAGING-RULE-DISABLED", "ENVIRONMENT_RULE", "pm25", ">", 75, "MEDIUM", "SITE", 1, false, 300, now);

        jdbc.update("insert into camera(code,name,site_id,zone_id,online,stream_url,last_frame_at) values(?,?,?,?,?,?,?)",
                "PAGING-CAM-A", "Paging Camera Alpha", 1, zoneId, true, null, now);
        jdbc.update("insert into camera(code,name,site_id,zone_id,online,stream_url,last_frame_at) values(?,?,?,?,?,?,?)",
                "PAGING-CAM-B", "Paging Camera Beta", 1, zoneId, false, null, now);

        for (int index = 0; index < 4; index++) {
            jdbc.update("insert into sprinkler_task(code,site_id,zone_id,trigger_type,reason,status,planned_at,created_by) values(?,?,?,?,?,?,?,?)",
                    "PAGING-SPRINKLER-" + index, 1, zoneId, "MANUAL", "Paging fixture " + index,
                    "CREATED", Timestamp.valueOf(TASK_BASE.plusMinutes(index)), adminId);
        }

        insertTelemetry("PAGING-TEL-1", 1, TELEMETRY_BASE);
        insertTelemetry("PAGING-TEL-2", 2, TELEMETRY_BASE.plusMinutes(1));
        insertTelemetry("PAGING-TEL-3", 3, TELEMETRY_BASE.plusMinutes(2));
        insertTelemetry("PAGING-TEL-4", 4, TELEMETRY_BASE.plusMinutes(3));
        insertTelemetry("PAGING-TEL-5", 5, TELEMETRY_BASE.plusMinutes(3));
    }

    @Test
    void ruleListRequiresSiteScopeAndAppliesAllFiltersAndPagination() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/rules").header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/rules")
                        .param("siteId", "999999")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_SCOPE_DENIED"));

        mockMvc.perform(get("/api/rules")
                        .param("siteId", "1")
                        .param("keyword", "paging-rule")
                        .param("enabled", "true")
                        .param("sourceType", "DEVICE_RULE")
                        .param("metricCode", "weight")
                        .param("page", "0")
                        .param("pageSize", "999")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(100))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].name").value("PAGING-RULE-ENABLED"));
    }

    @Test
    void cameraListFiltersAndReturnsDisjointStablePages() throws Exception {
        String token = login();
        MvcResult first = mockMvc.perform(get("/api/cameras")
                        .param("siteId", "1")
                        .param("zoneId", String.valueOf(zoneId))
                        .param("keyword", "paging camera")
                        .param("page", "1")
                        .param("pageSize", "1")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        MvcResult second = mockMvc.perform(get("/api/cameras")
                        .param("siteId", "1")
                        .param("zoneId", String.valueOf(zoneId))
                        .param("keyword", "paging camera")
                        .param("page", "2")
                        .param("pageSize", "1")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        assertNotEquals(read(first).at("/data/items/0/id").asLong(), read(second).at("/data/items/0/id").asLong());

        mockMvc.perform(get("/api/cameras")
                        .param("siteId", "1")
                        .param("keyword", "PAGING-CAM")
                        .param("online", "true")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("PAGING-CAM-A"))
                .andExpect(jsonPath("$.data.items[0].playbackStatus").value("NOT_CONFIGURED"));
    }

    @Test
    void sprinklerListUsesInclusiveTimeFiltersAndStablePagination() throws Exception {
        String token = login();
        MvcResult first = mockMvc.perform(get("/api/sprinkler-tasks")
                        .param("siteId", "1")
                        .param("zoneId", String.valueOf(zoneId))
                        .param("status", "CREATED")
                        .param("from", TASK_BASE.plusMinutes(1).toString())
                        .param("to", TASK_BASE.plusMinutes(3).toString())
                        .param("page", "1")
                        .param("pageSize", "2")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn();
        assertEquals("PAGING-SPRINKLER-3", read(first).at("/data/items/0/code").asText());
        assertEquals("PAGING-SPRINKLER-2", read(first).at("/data/items/1/code").asText());

        mockMvc.perform(get("/api/sprinkler-tasks")
                        .param("siteId", "1")
                        .param("from", TASK_BASE.plusHours(1).toString())
                        .param("to", TASK_BASE.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));

        mockMvc.perform(get("/api/sprinkler-tasks")
                        .param("siteId", "1")
                        .param("status", "UNKNOWN")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TASK_STATUS"));
    }

    @Test
    void telemetryHistoryPaginatesWithinTimeRangeAndTrendsReturnRecentPointsAscending() throws Exception {
        String token = login();
        MvcResult history = mockMvc.perform(get("/api/telemetry/history")
                        .param("deviceId", String.valueOf(deviceId))
                        .param("metric", "pagingMetric")
                        .param("from", TELEMETRY_BASE.plusMinutes(1).toString())
                        .param("to", TELEMETRY_BASE.plusMinutes(3).toString())
                        .param("page", "2")
                        .param("pageSize", "2")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(4))
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.items.length()").value(2))
                .andReturn();
        assertEquals(List.of(3, 2), values(read(history).at("/data/items")));

        MvcResult towerTrend = mockMvc.perform(get("/api/telemetry/trend")
                        .param("deviceId", String.valueOf(deviceId))
                        .param("metric", "pagingMetric")
                        .param("limit", "3")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(List.of(3, 4, 5), values(read(towerTrend).at("/data")));

        MvcResult environmentTrend = mockMvc.perform(get("/api/environment/trend")
                        .param("deviceId", String.valueOf(deviceId))
                        .param("metric", "pagingMetric")
                        .param("limit", "2")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertEquals(List.of(4, 5), values(read(environmentTrend).at("/data")));

        mockMvc.perform(get("/api/telemetry/history")
                        .param("deviceId", String.valueOf(deviceId))
                        .param("metric", "pagingMetric")
                        .param("from", TELEMETRY_BASE.plusDays(1).toString())
                        .param("to", TELEMETRY_BASE.toString())
                        .header("Authorization", bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));
    }

    private void insertTelemetry(String messageId, int value, LocalDateTime collectedAt) {
        jdbc.update("insert into telemetry(message_id,device_id,metric_code,metric_value,unit,collected_at) values(?,?,?,?,?,?)",
                messageId, deviceId, "pagingMetric", value, "u", Timestamp.valueOf(collectedAt));
    }

    private List<Integer> values(JsonNode array) {
        return java.util.stream.StreamSupport.stream(array.spliterator(), false)
                .map(item -> item.get("value").decimalValue().intValue())
                .toList();
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "admin",
                                "password", "Admin@123"
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).at("/data/token").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
