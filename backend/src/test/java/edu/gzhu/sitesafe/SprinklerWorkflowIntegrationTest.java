package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.service.SprinklerTaskService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-sprinkler;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false",
        "app.sprinkler.minimum-interval-seconds=600",
        "app.sprinkler.dispatch-timeout-seconds=60",
        "app.sprinkler.timeout-scan-enabled=false"
})
@AutoConfigureMockMvc
class SprinklerWorkflowIntegrationTest {
    private static final AtomicInteger SEQUENCE = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private SprinklerTaskService tasks;

    private String token;
    private long zoneId;
    private long deviceId;

    @BeforeEach
    void createIsolatedOnlineSprinkler() throws Exception {
        int sequence = SEQUENCE.incrementAndGet();
        token = login("supervisor", "Safe@123");
        String zoneCode = "SPRINKLER-IT-ZONE-" + sequence;
        jdbc.update("insert into zone(site_id,code,name,status,map_x,map_y,map_width,map_height) values(1,?,?, 'CONSTRUCTION',0.1,0.1,0.2,0.2)",
                zoneCode, "喷淋闭环测试区 " + sequence);
        zoneId = jdbc.queryForObject("select id from zone where code=?", Long.class, zoneCode);
        String deviceCode = "SPRINKLER-IT-DEVICE-" + sequence;
        jdbc.update("insert into device(code,name,type,site_id,zone_id,location,enabled,connection_status,config_json) values(?,?,'SPRINKLER',1,?,'测试位置',true,'ONLINE','{}')",
                deviceCode, "喷淋闭环测试设备 " + sequence, zoneId);
        deviceId = jdbc.queryForObject("select id from device where code=?", Long.class, deviceCode);
    }

    @Test
    void createRequiresAvailableDeviceAndEnforcesZoneInterval() throws Exception {
        createTask("首次喷淋任务").andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREATED"));

        createTask("间隔过短的重复任务").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPRINKLER_INTERVAL_CONFLICT"));

        long noDeviceZone = insertZone("NO-DEVICE");
        createTask(noDeviceZone, "无设备任务").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("NO_SPRINKLER"));

        long offlineZone = insertZone("OFFLINE");
        jdbc.update("insert into device(code,name,type,site_id,zone_id,enabled,connection_status,config_json) values(?,?,'SPRINKLER',1,?,true,'OFFLINE','{}')",
                "SPRINKLER-OFFLINE-" + SEQUENCE.incrementAndGet(), "离线喷淋设备", offlineZone);
        createTask(offlineZone, "离线设备任务").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPRINKLER_UNAVAILABLE"));
    }

    @Test
    void dispatchRechecksBindingAndRepeatedDispatchIsIdempotent() throws Exception {
        long taskId = createdTaskId("下发校验任务");
        long movedZone = insertZone("MOVED");
        jdbc.update("update device set zone_id=? where id=?", movedZone, deviceId);
        dispatch(taskId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPRINKLER_BINDING_INVALID"));

        jdbc.update("update device set zone_id=?,connection_status='OFFLINE' where id=?", zoneId, deviceId);
        dispatch(taskId).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("SPRINKLER_UNAVAILABLE"));

        jdbc.update("update device set connection_status='ONLINE' where id=?", deviceId);
        MvcResult first = dispatch(taskId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISPATCHED"))
                .andReturn();
        String commandId = read(first).at("/data/commandId").asText();
        dispatch(taskId).andExpect(status().isOk())
                .andExpect(jsonPath("$.data.commandId").value(commandId));

        Long audits = jdbc.queryForObject(
                "select count(*) from audit_log where action='SPRINKLER_TASK_DISPATCH' and object_id=?",
                Long.class, String.valueOf(taskId));
        assertEquals(1L, audits);
    }

    @Test
    void failedAcknowledgementRequiresReasonAndFinalResultIsIdempotent() throws Exception {
        long taskId = createdTaskId("失败回执任务");
        dispatch(taskId).andExpect(status().isOk());

        acknowledge(taskId, Map.of("success", false)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FAILURE_REASON_REQUIRED"));
        acknowledge(taskId, Map.of("success", false, "failureReason", "设备网关无响应"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("FAILED"))
                .andExpect(jsonPath("$.data.failureReason").value("设备网关无响应"));
        acknowledge(taskId, Map.of("success", false, "failureReason", "重复回执不覆盖"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failureReason").value("设备网关无响应"));
        acknowledge(taskId, Map.of("success", true)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ACK_CONFLICT"));

        Long audits = jdbc.queryForObject(
                "select count(*) from audit_log where action='SPRINKLER_TASK_ACK' and object_id=?",
                Long.class, String.valueOf(taskId));
        assertEquals(1L, audits);
    }

    @Test
    void dispatchedTaskTimesOutOnceAndRejectsLateOppositeAck() throws Exception {
        long taskId = createdTaskId("超时回执任务");
        dispatch(taskId).andExpect(status().isOk());
        LocalDateTime now = LocalDateTime.now().withNano(0);
        jdbc.update("update sprinkler_task set started_at=? where id=?",
                Timestamp.valueOf(now.minusSeconds(90)), taskId);

        assertEquals(1, tasks.expireTimedOutTasks(now));
        assertEquals(0, tasks.expireTimedOutTasks(now.plusSeconds(1)));
        Map<String, Object> task = jdbc.queryForMap(
                "select status,failure_reason as failureReason from sprinkler_task where id=?", taskId);
        assertEquals("FAILED", task.get("status"));
        org.junit.jupiter.api.Assertions.assertTrue(String.valueOf(task.get("failureReason")).contains("超时"));

        acknowledge(taskId, Map.of("success", true)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("TASK_ACK_CONFLICT"));
        Long timeoutAudits = jdbc.queryForObject(
                "select count(*) from audit_log where action='SPRINKLER_TASK_TIMEOUT' and object_id=?",
                Long.class, String.valueOf(taskId));
        assertEquals(1L, timeoutAudits);
    }

    private org.springframework.test.web.servlet.ResultActions createTask(String reason) throws Exception {
        return createTask(zoneId, reason);
    }

    private org.springframework.test.web.servlet.ResultActions createTask(long targetZoneId, String reason) throws Exception {
        return mockMvc.perform(post("/api/sprinkler-tasks")
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "siteId", 1,
                        "zoneId", targetZoneId,
                        "reason", reason))));
    }

    private long createdTaskId(String reason) throws Exception {
        MvcResult result = createTask(reason).andExpect(status().isOk()).andReturn();
        return read(result).at("/data/id").asLong();
    }

    private org.springframework.test.web.servlet.ResultActions dispatch(long taskId) throws Exception {
        return mockMvc.perform(post("/api/sprinkler-tasks/{id}/dispatch", taskId)
                .header("Authorization", "Bearer " + token));
    }

    private org.springframework.test.web.servlet.ResultActions acknowledge(long taskId, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post("/api/sprinkler-tasks/{id}/ack", taskId)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private long insertZone(String suffix) {
        int sequence = SEQUENCE.incrementAndGet();
        String code = "SPRINKLER-" + suffix + "-" + sequence;
        jdbc.update("insert into zone(site_id,code,name,status,map_x,map_y,map_width,map_height) values(1,?,?, 'CONSTRUCTION',0.1,0.1,0.2,0.2)",
                code, suffix + " 测试区");
        return jdbc.queryForObject("select id from zone where code=?", Long.class, code);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andReturn();
        return read(result).at("/data/token").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }
}
