package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-alarm-query;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false"
})
@AutoConfigureMockMvc
class AlarmQueryExportIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void alarmListFiltersByZoneAndLastOccurredTime() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/alarms")
                        .param("siteId", "1")
                        .param("zoneId", "2")
                        .param("from", "2026-07-19T16:00:00")
                        .param("to", "2026-07-19T17:00:00")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].code").value("ALM-20260719-001"))
                .andExpect(jsonPath("$.data.items[0].zoneId").value(2));
    }

    @Test
    void alarmListRejectsInvalidRangesEnumsAndCrossSiteZones() throws Exception {
        String token = login();

        mockMvc.perform(get("/api/alarms")
                        .param("siteId", "1")
                        .param("from", "2026-07-20T12:00:00")
                        .param("to", "2026-07-19T12:00:00")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_TIME_RANGE"));

        mockMvc.perform(get("/api/alarms")
                        .param("siteId", "1")
                        .param("status", "UNKNOWN")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ALARM_STATUS"));

        mockMvc.perform(get("/api/alarms")
                        .param("siteId", "1")
                        .param("zoneId", "999999")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ZONE"));
    }

    @Test
    void alarmExportUsesTheSameFiltersAndProducesExcelFriendlyUtf8Csv() throws Exception {
        String token = login();

        MvcResult result = mockMvc.perform(get("/api/alarms/export")
                        .param("siteId", "1")
                        .param("zoneId", "2")
                        .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=alarms-site-1.csv"))
                .andReturn();

        String contentType = result.getResponse().getContentType();
        String csv = new String(result.getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);
        assertTrue(contentType != null && contentType.startsWith("text/csv"));
        assertTrue(csv.startsWith("\uFEFF编号,标题"));
        assertTrue(csv.contains("ALM-20260719-001"));
        assertFalse(csv.contains("ALM-20260719-002"));
    }

    @Test
    void systemSourceFilterIncludesSpecificSystemAlarmFamilies() throws Exception {
        String code = "TEST-SYSTEM-OFFLINE-SOURCE";
        Timestamp occurredAt = Timestamp.valueOf(LocalDateTime.of(2026, 7, 20, 9, 0));
        jdbc.update("insert into alarm(code,site_id,zone_id,device_id,source_type,severity,title,description,status,first_occurred_at,last_occurred_at,occurrences) "
                        + "values(?,?,?,?,?,?,?,?,?,?,?,?)",
                code, 1L, 2L, 1L, "SYSTEM_DEVICE_OFFLINE", "HIGH", "Offline source test",
                "Specific system alarm", "PENDING", occurredAt, occurredAt, 1);
        try {
            String token = login();
            mockMvc.perform(get("/api/alarms")
                            .param("siteId", "1")
                            .param("source", "SYSTEM")
                            .param("keyword", code)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.items[0].sourceType").value("SYSTEM_DEVICE_OFFLINE"));

            mockMvc.perform(get("/api/alarms")
                            .param("siteId", "1")
                            .param("source", "SYSTEM_DEVICE_OFFLINE")
                            .param("keyword", code)
                            .header(HttpHeaders.AUTHORIZATION, bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1));
        } finally {
            jdbc.update("delete from alarm where code=?", code);
        }
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", "admin", "password", "Admin@123"))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        return body.path("data").path("token").asText();
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
