package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.realtime.RealtimeHub;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-integration;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false"
})
@AutoConfigureMockMvc
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class ApiIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    @SpyBean
    private RealtimeHub realtime;

    @Test
    @Order(1)
    void startupLoginSecurityAndCamelCaseJdbcAliasesWork() throws Exception {
        mockMvc.perform(get("/api/health").header("X-Trace-Id", "integration-health"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "integration-health"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.database").value("UP"));

        mockMvc.perform(get("/api/devices"))
                .andExpect(status().isUnauthorized())
                .andExpect(header().exists("X-Trace-Id"))
                .andExpect(jsonPath("$.code").value("UNAUTHORIZED"))
                .andExpect(jsonPath("$.traceId", not(emptyOrNullString())));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "admin", "password", "wrong-password"))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));

        String admin = login("admin", "Admin@123");
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("admin"))
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        mockMvc.perform(get("/api/devices")
                        .param("siteId", "1")
                        .param("pageSize", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].siteId").value(1))
                .andExpect(jsonPath("$.data.items[0].zoneId").isNumber())
                .andExpect(jsonPath("$.data.items[0].zoneName").isString())
                .andExpect(jsonPath("$.data.items[0].connectionStatus").isString())
                .andExpect(jsonPath("$.data.items[0].lastReportedAt").exists())
                .andExpect(jsonPath("$.data.items[0].connectionstatus").doesNotExist());

        Long users = jdbc.queryForObject("select count(*) from app_user", Long.class);
        Long sites = jdbc.queryForObject("select count(*) from site", Long.class);
        org.junit.jupiter.api.Assertions.assertEquals(3L, users);
        org.junit.jupiter.api.Assertions.assertEquals(1L, sites);
    }

    @Test
    @Order(2)
    void allCollectionEndpointsApplyServerSidePagination() throws Exception {
        String admin = login("admin", "Admin@123");

        mockMvc.perform(get("/api/devices")
                        .param("siteId", "1").param("page", "0").param("pageSize", "2")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.total").value(12))
                .andExpect(jsonPath("$.data.items", hasSize(2)));

        mockMvc.perform(get("/api/devices")
                        .param("siteId", "1").param("page", "2").param("pageSize", "2")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(2))
                .andExpect(jsonPath("$.data.items", hasSize(2)));

        mockMvc.perform(get("/api/alarms")
                        .param("siteId", "1").param("page", "0").param("pageSize", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(1))
                .andExpect(jsonPath("$.data.items", hasSize(1)));

        mockMvc.perform(get("/api/risks")
                        .param("siteId", "1").param("page", "0").param("pageSize", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.items", hasSize(1)));

        mockMvc.perform(get("/api/audit-logs")
                        .param("siteId", "1")
                        .param("page", "0").param("pageSize", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.items", hasSize(1)));

        mockMvc.perform(get("/api/users")
                        .param("page", "0").param("pageSize", "2")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.total").value(3))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].displayName").isString())
                .andExpect(jsonPath("$.data.items[0].siteScope").value("1"));

        mockMvc.perform(get("/api/users")
                        .param("pageSize", "999")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.pageSize").value(100));
    }

    @Test
    @Order(3)
    void refreshRevokesOldTokenAndReturnsUsableNewToken() throws Exception {
        String oldToken = login("supervisor", "Safe@123");
        MvcResult refresh = mockMvc.perform(post("/api/auth/refresh")
                        .header("Authorization", bearer(oldToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", not(emptyOrNullString())))
                .andExpect(jsonPath("$.data.expiresAt").exists())
                .andReturn();
        String newToken = read(refresh).at("/data/token").asText();

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(oldToken)))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(newToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("supervisor"));
    }

    @Test
    @Order(4)
    void rolePermissionsAreEnforced() throws Exception {
        String supervisor = login("supervisor", "Safe@123");
        String device = login("device", "Device@123");
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30).withNano(0);

        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry("IT-PERMISSION", "TC-002", now, "weight", "81"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        mockMvc.perform(get("/api/audit-logs")
                        .param("siteId", "1")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/users").header("Authorization", bearer(supervisor)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/alarms/1/actions")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "CONFIRM", "note", "无权限测试"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @Order(5)
    void telemetryIsIdempotentCreatesAndSuppressesAlarmsAndValidatesTime() throws Exception {
        String device = login("device", "Device@123");
        OffsetDateTime firstTime = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(40).withNano(0);
        String firstMessage = "IT-TEL-IDEMPOTENT-1";

        MvcResult first = mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry(firstMessage, "TC-002", firstTime, "weight", "81.5"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insertedMetrics").value(1))
                .andExpect(jsonPath("$.data.duplicateMetrics").value(0))
                .andExpect(jsonPath("$.data.createdAlarmIds", hasSize(1)))
                .andReturn();
        long alarmId = read(first).at("/data/createdAlarmIds/0").asLong();

        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry(firstMessage, "TC-002", firstTime, "weight", "81.5"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insertedMetrics").value(0))
                .andExpect(jsonPath("$.data.duplicateMetrics").value(1))
                .andExpect(jsonPath("$.data.createdAlarmIds", hasSize(0)));

        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry("IT-TEL-IDEMPOTENT-2", "TC-002",
                                firstTime.plusSeconds(10), "weight", "82"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insertedMetrics").value(1))
                .andExpect(jsonPath("$.data.createdAlarmIds", hasSize(0)));

        Integer occurrences = jdbc.queryForObject("select occurrences from alarm where id=?", Integer.class, alarmId);
        Long firstMetricCount = jdbc.queryForObject(
                "select count(*) from telemetry where message_id=? and metric_code='weight'",
                Long.class, firstMessage);
        org.junit.jupiter.api.Assertions.assertEquals(2, occurrences);
        org.junit.jupiter.api.Assertions.assertEquals(1L, firstMetricCount);

        OffsetDateTime utcInstant = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(2).withNano(0);
        OffsetDateTime chinaInstant = utcInstant.withOffsetSameInstant(ZoneOffset.ofHours(8));
        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry("IT-TEL-OFFSET-Z", "TC-002", utcInstant, "height", "50"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry("IT-TEL-OFFSET-CN", "TC-002", chinaInstant, "height", "51"))))
                .andExpect(status().isOk());
        List<Timestamp> offsetTimes = jdbc.query(
                "select collected_at from telemetry where message_id in ('IT-TEL-OFFSET-Z','IT-TEL-OFFSET-CN') order by message_id",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1));
        org.junit.jupiter.api.Assertions.assertEquals(2, offsetTimes.size());
        org.junit.jupiter.api.Assertions.assertEquals(offsetTimes.get(0).toInstant(), offsetTimes.get(1).toInstant());

        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry("IT-TEL-UNKNOWN", "UNKNOWN-DEVICE",
                                firstTime, "weight", "10"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("UNKNOWN_DEVICE"));

        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry("IT-TEL-FUTURE", "TC-002",
                                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(6), "weight", "10"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TELEMETRY_TIME_INVALID"));

        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry("IT-TEL-OLD", "TC-002",
                                OffsetDateTime.now(ZoneOffset.UTC).minusDays(31), "weight", "10"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("TELEMETRY_TIME_INVALID"));
    }

    @Test
    @Order(6)
    void alarmStateMachineAllowsOnlyValidTransitionsAndAuthorizedRoles() throws Exception {
        String code = "IT-ALARM-STATE";
        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("insert into alarm(code,site_id,zone_id,device_id,rule_id,source_type,severity,title,description,status,first_occurred_at,last_occurred_at,occurrences) values(?,?,?,?,null,?,?,?,?,?,?,?,1)",
                code, 1, 1, 4, "SYSTEM", "HIGH", "状态机集成测试", "用于验证告警状态迁移", "PENDING", now, now);
        Long alarmId = jdbc.queryForObject("select id from alarm where code=?", Long.class, code);
        String device = login("device", "Device@123");
        String supervisor = login("supervisor", "Safe@123");

        mockMvc.perform(post("/api/alarms/{id}/actions", alarmId)
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "CONFIRM", "note", "设备管理员不得处置"))))
                .andExpect(status().isForbidden());

        transition(alarmId, supervisor, "CONFIRM", "PROCESSING");
        transition(alarmId, supervisor, "RESOLVE", "RESOLVED");
        transition(alarmId, supervisor, "CLOSE", "CLOSED");

        mockMvc.perform(post("/api/alarms/{id}/actions", alarmId)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "CONFIRM", "note", "非法重复确认"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVALID_ALARM_TRANSITION"));

        Long actionCount = jdbc.queryForObject("select count(*) from alarm_action where alarm_id=?",
                Long.class, alarmId);
        Long auditCount = jdbc.queryForObject(
                "select count(*) from audit_log where object_type='ALARM' and object_id=? and action like 'ALARM_%'",
                Long.class, String.valueOf(alarmId));
        org.junit.jupiter.api.Assertions.assertEquals(3L, actionCount);
        org.junit.jupiter.api.Assertions.assertEquals(3L, auditCount);
    }

    @Test
    @Order(7)
    void aiRiskIngestValidatesCameraConfidenceSiteScopeAndPublishesRealtimeEvent() throws Exception {
        reset(realtime);
        String device = login("device", "Device@123");
        String supervisor = login("supervisor", "Safe@123");
        OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1).withNano(0);
        Map<String, Object> request = risk("CAM-001", "未佩戴安全帽", "0.96", occurredAt);

        MvcResult created = mockMvc.perform(post("/api/risks")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cameraCode").value("CAM-001"))
                .andExpect(jsonPath("$.data.siteId").value(1))
                .andExpect(jsonPath("$.data.riskType").value("未佩戴安全帽"))
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andReturn();
        long riskId = read(created).at("/data/id").asLong();
        verify(realtime).publish(eq("risk.created"), any());

        mockMvc.perform(post("/api/risks")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(risk("CAM-001", "越权风险", "0.8", occurredAt))))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/risks")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(risk("CAM-001", "置信度无效", "1.01", occurredAt))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/api/risks")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(risk("CAM-NOT-FOUND", "未知摄像头", "0.8", occurredAt))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CAMERA_NOT_FOUND"));

        Timestamp now = Timestamp.valueOf(LocalDateTime.now());
        jdbc.update("insert into site(code,name,address,status,updated_at) values(?,?,?,?,?)",
                "IT-SITE-02", "集成测试二号工地", "测试地址", "ACTIVE", now);
        Long siteId = jdbc.queryForObject("select id from site where code='IT-SITE-02'", Long.class);
        jdbc.update("insert into zone(site_id,code,name,status,map_x,map_y,map_width,map_height) values(?,?,?,?,?,?,?,?)",
                siteId, "IT-ZONE-02", "二号测试区", "CONSTRUCTION", 0.1, 0.1, 0.2, 0.2);
        Long zoneId = jdbc.queryForObject("select id from zone where code='IT-ZONE-02'", Long.class);
        jdbc.update("insert into camera(code,name,site_id,zone_id,online,stream_url,last_frame_at) values(?,?,?,?,?,?,?)",
                "IT-CAM-02", "二号工地摄像头", siteId, zoneId, true, null, now);

        mockMvc.perform(post("/api/risks")
                        .header("Authorization", bearer(device))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(risk("IT-CAM-02", "越站点接入", "0.8", occurredAt))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_SCOPE_DENIED"));

        Long persisted = jdbc.queryForObject("select count(*) from ai_risk where id=? and status='PENDING_REVIEW'",
                Long.class, riskId);
        Long audited = jdbc.queryForObject("select count(*) from audit_log where action='RISK_CREATE' and object_id=?",
                Long.class, String.valueOf(riskId));
        org.junit.jupiter.api.Assertions.assertEquals(1L, persisted);
        org.junit.jupiter.api.Assertions.assertEquals(1L, audited);
    }

    @Test
    @Order(8)
    void adminUserManagementSupportsPagingCreateDisableAndPasswordReset() throws Exception {
        String admin = login("admin", "Admin@123");
        String supervisor = login("supervisor", "Safe@123");

        mockMvc.perform(get("/api/users").header("Authorization", bearer(supervisor)))
                .andExpect(status().isForbidden());

        Map<String, Object> create = Map.of(
                "username", "test.operator",
                "password", "Strong@123",
                "displayName", "集成测试操作员",
                "role", "SUPERVISOR",
                "siteScope", "1"
        );
        MvcResult result = mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(create)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("test.operator"))
                .andExpect(jsonPath("$.data.displayName").value("集成测试操作员"))
                .andExpect(jsonPath("$.data.role").value("SUPERVISOR"))
                .andExpect(jsonPath("$.data.siteScope").value("1"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.password").doesNotExist())
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist())
                .andReturn();
        long userId = read(result).at("/data/id").asLong();

        Map<String, Object> duplicate = new LinkedHashMap<>(create);
        duplicate.put("username", "TEST.OPERATOR");
        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(duplicate)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_EXISTS"));

        Map<String, Object> invalidRole = new LinkedHashMap<>(create);
        invalidRole.put("username", "invalid-role-user");
        invalidRole.put("role", "ROOT");
        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidRole)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ROLE"));

        Map<String, Object> invalidPassword = new LinkedHashMap<>(create);
        invalidPassword.put("username", "short-password-user");
        invalidPassword.put("password", "1234567");
        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidPassword)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        mockMvc.perform(patch("/api/users/1/enabled")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("enabled", false))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CANNOT_DISABLE_SELF"));

        String userOldToken = login("test.operator", "Strong@123");
        mockMvc.perform(post("/api/users/{id}/reset-password", userId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("password", "Changed@456"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(userOldToken)))
                .andExpect(status().isUnauthorized());
        loginExpectFailure("test.operator", "Strong@123");
        login("test.operator", "Changed@456");

        mockMvc.perform(patch("/api/users/{id}/enabled", userId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("enabled", false))))
                .andExpect(status().isOk());
        loginExpectFailure("test.operator", "Changed@456");

        mockMvc.perform(get("/api/users")
                        .param("keyword", "operator")
                        .param("page", "0")
                        .param("pageSize", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items", hasSize(1)))
                .andExpect(jsonPath("$.data.items[0].enabled").value(false));

        Long auditCount = jdbc.queryForObject(
                "select count(*) from audit_log where object_type='APP_USER' and object_id=?",
                Long.class, String.valueOf(userId));
        org.junit.jupiter.api.Assertions.assertTrue(auditCount != null && auditCount >= 3);
    }

    @Test
    @Order(9)
    void invalidEnumsAndMissingResourcesProduceStableClientErrors() throws Exception {
        String admin = login("admin", "Admin@123");

        mockMvc.perform(patch("/api/devices/1/connection")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("status", "BROKEN"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_CONNECTION_STATUS"));

        Map<String, Object> invalidRule = Map.of(
                "name", "非法来源规则",
                "sourceType", "UNKNOWN_RULE",
                "metricCode", "weight",
                "operator", ">",
                "thresholdValue", 10,
                "severity", "HIGH",
                "scopeType", "TYPE",
                "suppressionSeconds", 300
        );
        mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidRule)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SOURCE_TYPE"));

        Map<String, Object> invalidSeverity = new LinkedHashMap<>(invalidRule);
        invalidSeverity.put("sourceType", "DEVICE_RULE");
        invalidSeverity.put("severity", "CRITICAL");
        mockMvc.perform(post("/api/rules")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidSeverity)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_SEVERITY"));

        Map<String, Object> invalidDevice = Map.of(
                "code", "IT-BAD-TYPE",
                "name", "非法类型设备",
                "type", "UNKNOWN",
                "siteId", 1,
                "zoneId", 1
        );
        mockMvc.perform(post("/api/devices")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(invalidDevice)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_DEVICE_TYPE"));

        mockMvc.perform(get("/api/environment/trend")
                        .param("deviceId", "999999")
                        .param("metric", "pm25")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("DEVICE_NOT_FOUND"));

        mockMvc.perform(get("/api/zones/999999/devices")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    @Order(10)
    void validTelemetryAndEnvironmentQueriesReturnMetricValues() throws Exception {
        String admin = login("admin", "Admin@123");

        mockMvc.perform(get("/api/telemetry/latest")
                        .param("deviceId", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(7)))
                .andExpect(jsonPath("$.data[0].code").value("amplitude"))
                .andExpect(jsonPath("$.data[0].value").isNumber())
                .andExpect(jsonPath("$.data[0].unit").isString())
                .andExpect(jsonPath("$.data[0].collectedAt").exists())
                .andExpect(jsonPath("$.data[0].metric_value").doesNotExist());

        mockMvc.perform(get("/api/telemetry/history")
                        .param("deviceId", "1")
                        .param("metric", "windSpeed")
                        .param("page", "1")
                        .param("pageSize", "2")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.page").value(1))
                .andExpect(jsonPath("$.data.pageSize").value(2))
                .andExpect(jsonPath("$.data.items", hasSize(2)))
                .andExpect(jsonPath("$.data.items[0].value").isNumber())
                .andExpect(jsonPath("$.data.items[0].messageId").isString())
                .andExpect(jsonPath("$.data.items[0].collectedAt").exists());

        mockMvc.perform(get("/api/telemetry/trend")
                        .param("deviceId", "1")
                        .param("metric", "weight")
                        .param("limit", "3")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[0].value").isNumber())
                .andExpect(jsonPath("$.data[0].unit").value("t"))
                .andExpect(jsonPath("$.data[0].collectedAt").exists());

        mockMvc.perform(get("/api/environment/summary")
                        .param("siteId", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.stations", hasSize(3)))
                .andExpect(jsonPath("$.data.stations[0].code").value("ENV-001"))
                .andExpect(jsonPath("$.data.stations[0].metrics", hasSize(4)))
                .andExpect(jsonPath("$.data.stations[0].metrics[0].value").isNumber())
                .andExpect(jsonPath("$.data.stations[0].metrics[0].collectedAt").exists());

        mockMvc.perform(get("/api/environment/trend")
                        .param("deviceId", "6")
                        .param("metric", "pm25")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(12)))
                .andExpect(jsonPath("$.data[0].value").isNumber())
                .andExpect(jsonPath("$.data[0].unit").value("μg/m³"))
                .andExpect(jsonPath("$.data[0].collectedAt").exists());
    }

    private void transition(long alarmId, String token, String action, String target) throws Exception {
        mockMvc.perform(post("/api/alarms/{id}/actions", alarmId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", action, "note", "集成测试 " + action))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(target));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token", not(emptyOrNullString())))
                .andReturn();
        return read(result).at("/data/token").asText();
    }

    private void loginExpectFailure(String username, String password) throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("BAD_CREDENTIALS"));
    }

    private Map<String, Object> telemetry(String messageId, String deviceCode, OffsetDateTime collectedAt,
                                          String metric, String value) {
        return Map.of(
                "protocolVersion", "1.0",
                "messageId", messageId,
                "deviceCode", deviceCode,
                "messageType", "telemetry",
                "collectedAt", collectedAt.toString(),
                "metrics", List.of(Map.of(
                        "code", metric,
                        "value", new BigDecimal(value),
                        "unit", "t"
                ))
        );
    }

    private Map<String, Object> risk(String cameraCode, String riskType, String confidence,
                                     OffsetDateTime occurredAt) {
        return Map.of(
                "cameraCode", cameraCode,
                "riskType", riskType,
                "confidence", new BigDecimal(confidence),
                "modelVersion", "integration-model-1.0",
                "occurredAt", occurredAt.toString(),
                "evidenceUrl", "https://example.invalid/evidence/test.jpg"
        );
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
