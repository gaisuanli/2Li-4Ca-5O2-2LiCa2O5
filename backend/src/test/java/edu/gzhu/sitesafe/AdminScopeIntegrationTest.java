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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-admin-scope;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false"
})
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AdminScopeIntegrationTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbc;

    private long site2Id;
    private long externalUserId;

    @BeforeAll
    void createOtherSiteFixtures() {
        Timestamp time = Timestamp.valueOf(LocalDateTime.of(2026, 7, 20, 8, 0));
        jdbc.update("insert into site(code,name,address,status,updated_at) values(?,?,?,?,?)",
                "SCOPE-SITE-2", "隔离测试二号工地", "测试地址", "ACTIVE", time);
        site2Id = jdbc.queryForObject("select id from site where code='SCOPE-SITE-2'", Long.class);
        jdbc.update("insert into zone(site_id,code,name,status,map_x,map_y,map_width,map_height) values(?,?,?,?,?,?,?,?)",
                site2Id, "SCOPE-ZONE-2", "二号工地区域", "ACTIVE", 0.1, 0.1, 0.2, 0.2);
        long zone2Id = jdbc.queryForObject("select id from zone where code='SCOPE-ZONE-2'", Long.class);
        jdbc.update("insert into device(code,name,type,site_id,zone_id,location,enabled,connection_status,config_json) values(?,?,?,?,?,?,?,?,?)",
                "SCOPE-DEV-2", "二号工地设备", "TOWER_CRANE", site2Id, zone2Id, "测试点", true, "ONLINE", "{}");
        long device2Id = jdbc.queryForObject("select id from device where code='SCOPE-DEV-2'", Long.class);
        jdbc.update("insert into alarm(code,site_id,zone_id,device_id,source_type,severity,title,description,status,first_occurred_at,last_occurred_at,occurrences) values(?,?,?,?,?,?,?,?,?,?,?,?)",
                "SCOPE-ALARM-2", site2Id, zone2Id, device2Id, "SYSTEM", "MEDIUM", "二号工地告警", "隔离测试", "PENDING", time, time, 1);
        long alarm2Id = jdbc.queryForObject("select id from alarm where code='SCOPE-ALARM-2'", Long.class);

        String passwordHash = jdbc.queryForObject("select password_hash from app_user where username='admin'", String.class);
        jdbc.update("insert into app_user(username,password_hash,display_name,role,site_scope,enabled) values(?,?,?,?,?,?)",
                "site2admin", passwordHash, "二号工地管理员", "ADMIN", String.valueOf(site2Id), true);
        externalUserId = jdbc.queryForObject("select id from app_user where username='site2admin'", Long.class);
        long adminId = jdbc.queryForObject("select id from app_user where username='admin'", Long.class);
        jdbc.update("insert into audit_log(user_id,username,action,object_type,object_id,detail,trace_id,created_at) values(?,?,?,?,?,?,?,?)",
                adminId, "admin", "SITE2-AUDIT", "ALARM", String.valueOf(alarm2Id), "二号工地隔离日志", "scope-test", time);
    }

    @Test
    void adminCanOnlyListCreateAndMutateUsersInsideItsSiteScope() throws Exception {
        String token = login();

        MvcResult list = mockMvc.perform(get("/api/users")
                        .param("pageSize", "100")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3))
                .andReturn();
        assertFalse(list.getResponse().getContentAsString().contains("site2admin"));

        mockMvc.perform(post("/api/users")
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "outsideuser",
                                "password", "Outside@123",
                                "displayName", "越权用户",
                                "role", "SUPERVISOR",
                                "siteScope", String.valueOf(site2Id)))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_SCOPE_DENIED"));

        mockMvc.perform(patch("/api/users/{id}/enabled", externalUserId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_SCOPE_DENIED"));
    }

    @Test
    void auditListRequiresASelectedAuthorizedSiteAndDoesNotLeakOtherSites() throws Exception {
        String token = login();

        MvcResult site1 = mockMvc.perform(get("/api/audit-logs")
                        .param("siteId", "1")
                        .param("pageSize", "100")
                        .header("Authorization", bearer(token)))
                .andExpect(status().isOk())
                .andReturn();
        assertFalse(site1.getResponse().getContentAsString().contains("SITE2-AUDIT"));

        mockMvc.perform(get("/api/audit-logs")
                        .param("siteId", String.valueOf(site2Id))
                        .header("Authorization", bearer(token)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("SITE_SCOPE_DENIED"));
    }

    @Test
    void malformedLegacyUserScopeIsHiddenAndCannotBeMutated() throws Exception {
        String username = "malformedscope";
        String passwordHash = jdbc.queryForObject(
                "select password_hash from app_user where username='admin'", String.class);
        jdbc.update("insert into app_user(username,password_hash,display_name,role,site_scope,enabled) values(?,?,?,?,?,?)",
                username, passwordHash, "Malformed scope", "SUPERVISOR", "not-a-site", true);
        long userId = jdbc.queryForObject("select id from app_user where username=?", Long.class, username);
        try {
            String token = login();
            mockMvc.perform(get("/api/users")
                            .param("keyword", username)
                            .header("Authorization", bearer(token)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(0));

            mockMvc.perform(patch("/api/users/{id}/enabled", userId)
                            .header("Authorization", bearer(token))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"enabled\":false}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("SITE_SCOPE_DENIED"));
        } finally {
            jdbc.update("delete from app_user where username=?", username);
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
