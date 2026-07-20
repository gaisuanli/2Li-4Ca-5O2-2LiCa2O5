package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.DatabasePopulatorUtils;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import javax.sql.DataSource;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Opt-in smoke test for a real MySQL 8 server.
 *
 * <p>The class name intentionally ends in {@code IT}, so the normal H2-backed
 * {@code mvn test} run does not discover it. Use scripts/test-mysql-smoke.ps1,
 * which supplies administrator credentials through process environment
 * variables and selects this test explicitly.</p>
 */
@SpringBootTest(properties = {
        "app.demo-data-enabled=true",
        "app.tcp.enabled=false",
        "app.device-offline-monitor.enabled=false",
        "app.sprinkler.minimum-interval-seconds=0",
        "app.sprinkler.timeout-scan-enabled=false",
        "app.ai-agent.mode=DEMO",
        "app.ai-agent.credential-encryption-key=MDEyMzQ1Njc4OWFiY2RlZjAxMjM0NTY3ODlhYmNkZWY=",
        "spring.datasource.hikari.maximum-pool-size=2",
        "spring.main.banner-mode=off"
})
@ActiveProfiles("mysql")
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MySqlBusinessSmokeIT {
    private static final Pattern CREATE_TABLE = Pattern.compile(
            "(?im)^\\s*create\\s+table\\s+if\\s+not\\s+exists\\s+`?([a-zA-Z0-9_]+)`?\\s*\\(");
    private static final MySqlSandbox SANDBOX = MySqlSandbox.provision();

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SANDBOX::applicationUrl);
        registry.add("spring.datasource.username", SANDBOX::applicationUsername);
        registry.add("spring.datasource.password", SANDBOX::applicationPassword);
        registry.add("spring.datasource.driver-class-name", () -> "com.mysql.cj.jdbc.Driver");
    }

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private DataSource dataSource;
    @Autowired
    private Environment environment;

    @Test
    @Order(1)
    void initializesAnEmptyMysql8DatabaseAndSchemaIsRepeatable() throws Exception {
        assertTrue(environment.acceptsProfiles(Profiles.of("mysql")), "mysql profile must be active");

        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            assertEquals("MySQL", metadata.getDatabaseProductName());
            String databaseProductVersion = metadata.getDatabaseProductVersion();
            assertEquals(8, metadata.getDatabaseMajorVersion(),
                    () -> "expected MySQL 8, got " + databaseProductVersion);
            assertEquals(SANDBOX.databaseName(), connection.getCatalog());
        }

        Integer tableCount = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema=database() and table_type='BASE TABLE'",
                Integer.class);
        int minimumTableCount = positiveIntegerEnvironment("MYSQL_SMOKE_MIN_TABLES", 20);
        assertNotNull(tableCount);
        assertTrue(tableCount >= minimumTableCount,
                () -> "expected at least " + minimumTableCount + " tables, got " + tableCount);

        ClassPathResource schemaResource = new ClassPathResource("schema-mysql.sql");
        Set<String> declaredTables = declaredTables(
                schemaResource.getContentAsString(StandardCharsets.UTF_8));
        assertTrue(declaredTables.size() >= minimumTableCount,
                () -> "schema-mysql.sql declares only " + declaredTables.size()
                        + " tables; expected at least " + minimumTableCount);

        Set<String> actualTables = new LinkedHashSet<>(jdbc.queryForList(
                "select table_name from information_schema.tables where table_schema=database() and table_type='BASE TABLE'",
                String.class));
        assertTrue(actualTables.containsAll(declaredTables),
                () -> "missing tables declared by schema-mysql.sql: " + difference(declaredTables, actualTables));

        Integer nonInnoDbTables = jdbc.queryForObject(
                "select count(*) from information_schema.tables "
                        + "where table_schema=database() and table_name in ("
                        + String.join(",", declaredTables.stream().map(ignored -> "?").toList())
                        + ") and engine<>'InnoDB'",
                Integer.class, declaredTables.toArray());
        assertEquals(0, nonInnoDbTables);

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(
                schemaResource);
        populator.setContinueOnError(false);
        DatabasePopulatorUtils.execute(populator, dataSource);

        Integer countAfterSecondInitialization = jdbc.queryForObject(
                "select count(*) from information_schema.tables where table_schema=database() and table_type='BASE TABLE'",
                Integer.class);
        assertEquals(tableCount, countAfterSecondInitialization,
                "running schema-mysql.sql twice must be idempotent");
        assertEquals(3, jdbc.queryForObject("select count(*) from app_user", Integer.class));
        assertEquals(1, jdbc.queryForObject("select count(*) from site", Integer.class));
    }

    @Test
    @Order(2)
    void smokesLoginDeviceTelemetryAlarmRiskAgentAndSprinklerFlows() throws Exception {
        String admin = login("admin", "Admin@123");
        String supervisor = login("supervisor", "Safe@123");
        String deviceManager = login("device", "Device@123");

        mockMvc.perform(get("/api/auth/me").header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"));

        String deviceCode = "MYSQL-SMOKE-" + shortId();
        MvcResult createdDevice = mockMvc.perform(post("/api/devices")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "code", deviceCode,
                                "name", "MySQL 冒烟环境监测设备",
                                "type", "ENVIRONMENT",
                                "siteId", 1,
                                "zoneId", 1,
                                "location", "自动化验收区",
                                "configJson", "{\"source\":\"mysql-smoke\"}"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value(deviceCode))
                .andExpect(jsonPath("$.data.connectionStatus").value("OFFLINE"))
                .andReturn();
        long createdDeviceId = read(createdDevice).at("/data/id").asLong();
        assertTrue(createdDeviceId > 0);

        String messageId = "MYSQL-SMOKE-TEL-" + shortId();
        Map<String, Object> telemetry = Map.of(
                "protocolVersion", "1.0",
                "messageId", messageId,
                "deviceCode", "TC-002",
                "messageType", "telemetry",
                "collectedAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(10).withNano(0).toString(),
                "metrics", List.of(Map.of("code", "weight", "value", new BigDecimal("81.5"), "unit", "t"))
        );
        MvcResult ingested = mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(deviceManager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insertedMetrics").value(1))
                .andExpect(jsonPath("$.data.duplicateMetrics").value(0))
                .andExpect(jsonPath("$.data.createdAlarmIds.length()").value(1))
                .andReturn();
        long telemetryAlarmId = read(ingested).at("/data/createdAlarmIds/0").asLong();

        mockMvc.perform(post("/api/telemetry")
                        .header("Authorization", bearer(deviceManager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(telemetry)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.insertedMetrics").value(0))
                .andExpect(jsonPath("$.data.duplicateMetrics").value(1));

        alarmAction(telemetryAlarmId, supervisor, "CONFIRM", "PROCESSING");
        alarmAction(telemetryAlarmId, supervisor, "RESOLVE", "RESOLVED");
        alarmAction(telemetryAlarmId, supervisor, "CLOSE", "CLOSED");

        MvcResult createdRisk = mockMvc.perform(post("/api/risks")
                        .header("Authorization", bearer(deviceManager))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "cameraCode", "CAM-001",
                                "riskType", "MySQL 冒烟未佩戴安全帽",
                                "confidence", new BigDecimal("0.9600"),
                                "modelVersion", "mysql-smoke-1",
                                "occurredAt", OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(20).withNano(0).toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"))
                .andReturn();
        long riskId = read(createdRisk).at("/data/id").asLong();

        mockMvc.perform(post("/api/risks/{id}/review", riskId)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "CONFIRM", "note", "MySQL 冒烟人工确认"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.alarmId").isNumber());

        MvcResult conversation = mockMvc.perform(post("/api/agent/conversations")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1, "title", "MySQL 冒烟会话"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.siteId").value(1))
                .andReturn();
        long conversationId = read(conversation).at("/data/id").asLong();

        mockMvc.perform(post("/api/agent/conversations/{id}/messages", conversationId)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("content", "汇总当前工地安全状态"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userMessage.role").value("USER"))
                .andExpect(jsonPath("$.data.assistantMessage.role").value("ASSISTANT"))
                .andExpect(jsonPath("$.data.assistantMessage.mode").value("DEMO"));

        String mysqlProviderSecret = "mysql-smoke-write-only-secret";
        mockMvc.perform(put("/api/agent/provider-config")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "baseUrl", "https://api.openai.com/v1",
                                "model", "mysql-smoke-compatible-model",
                                "apiKey", mysqlProviderSecret))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.apiKeyConfigured").value(true));
        long supervisorId = jdbc.queryForObject(
                "select id from app_user where username='supervisor'", Long.class);
        String encryptedProviderSecret = jdbc.queryForObject(
                "select encrypted_api_key from ai_agent_provider_config where user_id=?",
                String.class, supervisorId);
        assertTrue(encryptedProviderSecret.startsWith("v1."));
        assertFalse(encryptedProviderSecret.contains(mysqlProviderSecret));
        assertEquals(0, jdbc.queryForObject(
                "select count(*) from audit_log where detail like ?", Integer.class,
                "%" + mysqlProviderSecret + "%"));
        mockMvc.perform(delete("/api/agent/provider-config")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk());

        MvcResult knowledge = mockMvc.perform(post("/api/knowledge-documents")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "siteId", 1,
                                "title", "MySQL 冒烟受控知识",
                                "category", "自动化验收",
                                "sourceReference", "MySqlBusinessSmokeIT",
                                "content", "只有人工审核发布后的知识才能进入当前工地 Agent 检索上下文。"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        long knowledgeId = read(knowledge).at("/data/id").asLong();
        mockMvc.perform(post("/api/knowledge-documents/{id}/submit", knowledgeId)
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        mockMvc.perform(post("/api/knowledge-documents/{id}/review", knowledgeId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "APPROVE", "note", "MySQL 冒烟审核通过"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PUBLISHED"));

        MvcResult templates = mockMvc.perform(get("/api/report-templates")
                        .param("siteId", "1")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        long templateId = read(templates).at("/data/items/0/id").asLong();
        MvcResult report = mockMvc.perform(post("/api/reports/generate")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1, "templateId", templateId,
                                "title", "MySQL 冒烟安全报告"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andReturn();
        long reportId = read(report).at("/data/id").asLong();
        mockMvc.perform(post("/api/reports/{id}/submit", reportId)
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("PENDING_REVIEW"));
        mockMvc.perform(post("/api/reports/{id}/review", reportId)
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", "APPROVE", "note", "MySQL 冒烟报告复核"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"));
        MvcResult channels = mockMvc.perform(get("/api/push-channels")
                        .param("siteId", "1")
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items.length()").value(1))
                .andReturn();
        long channelId = read(channels).at("/data/items/0/id").asLong();
        mockMvc.perform(post("/api/reports/{id}/deliveries", reportId)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("channelId", channelId))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SENT"));

        MvcResult sprinkler = mockMvc.perform(post("/api/sprinkler-tasks")
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of(
                                "siteId", 1,
                                "zoneId", 2,
                                "reason", "MySQL 冒烟喷淋联动"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CREATED"))
                .andReturn();
        long sprinklerTaskId = read(sprinkler).at("/data/id").asLong();

        mockMvc.perform(post("/api/sprinkler-tasks/{id}/dispatch", sprinklerTaskId)
                        .header("Authorization", bearer(supervisor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISPATCHED"))
                .andExpect(jsonPath("$.data.commandId").isNotEmpty());
        mockMvc.perform(post("/api/sprinkler-tasks/{id}/ack", sprinklerTaskId)
                        .header("Authorization", bearer(supervisor))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("success", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"));

        assertEquals(1, jdbc.queryForObject(
                "select count(*) from telemetry where message_id=? and metric_code='weight'",
                Integer.class, messageId));
        assertEquals("CLOSED", jdbc.queryForObject(
                "select status from alarm where id=?", String.class, telemetryAlarmId));
        assertEquals("CONFIRMED", jdbc.queryForObject(
                "select status from ai_risk where id=?", String.class, riskId));
        assertEquals(2, jdbc.queryForObject(
                "select count(*) from ai_agent_message where conversation_id=?", Integer.class, conversationId));
        assertEquals("PUBLISHED", jdbc.queryForObject(
                "select status from knowledge_document where id=?", String.class, knowledgeId));
        assertEquals("APPROVED", jdbc.queryForObject(
                "select status from safety_report where id=?", String.class, reportId));
        assertEquals("SENT", jdbc.queryForObject(
                "select status from push_delivery where report_id=?", String.class, reportId));
        assertEquals("EXECUTED", jdbc.queryForObject(
                "select status from sprinkler_task where id=?", String.class, sprinklerTaskId));
        assertTrue(jdbc.queryForObject(
                "select count(*) from audit_log where trace_id is not null and trace_id<>''",
                Integer.class) > 0);
    }

    @AfterAll
    void closePoolAndDropSandbox() {
        if (dataSource instanceof HikariDataSource hikariDataSource) {
            hikariDataSource.close();
        }
        SANDBOX.cleanup();
    }

    private void alarmAction(long alarmId, String token, String action, String expectedStatus) throws Exception {
        mockMvc.perform(post("/api/alarms/{id}/actions", alarmId)
                        .header("Authorization", bearer(token))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("action", action, "note", "MySQL 冒烟：" + action))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value(expectedStatus));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", username, "password", password))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn();
        return read(result).at("/data/token").asText();
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String json(Object value) throws Exception {
        return objectMapper.writeValueAsString(value);
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }

    private String shortId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10).toUpperCase(Locale.ROOT);
    }

    private int positiveIntegerEnvironment(String name, int fallback) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) return fallback;
        try {
            int parsed = Integer.parseInt(value);
            if (parsed <= 0) throw new NumberFormatException("not positive");
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IllegalStateException(name + " must be a positive integer", exception);
        }
    }

    private static Set<String> difference(Set<String> expected, Set<String> actual) {
        Set<String> missing = new LinkedHashSet<>(expected);
        missing.removeAll(actual);
        return missing;
    }

    private static Set<String> declaredTables(String schemaSql) {
        Set<String> tables = new LinkedHashSet<>();
        Matcher matcher = CREATE_TABLE.matcher(schemaSql);
        while (matcher.find()) {
            tables.add(matcher.group(1).toLowerCase(Locale.ROOT));
        }
        if (tables.isEmpty()) {
            throw new IllegalStateException("schema-mysql.sql does not declare any CREATE TABLE statements");
        }
        return tables;
    }

    private record MySqlSandbox(
            String adminUrl,
            String adminUsername,
            String adminPassword,
            String databaseName,
            String applicationUrl,
            String applicationUsername,
            String applicationPassword,
            String applicationHost,
            AtomicBoolean cleaned
    ) {
        private static final Pattern JDBC_URL = Pattern.compile(
                "^(jdbc:mysql://[^/?#]+)(?:/[^?]*)?(\\?.*)?$");
        private static final Pattern MYSQL_ACCOUNT_HOST = Pattern.compile("^[A-Za-z0-9._:%-]+$");

        static MySqlSandbox provision() {
            String adminUrl = requireEnvironment("MYSQL_SMOKE_ADMIN_URL", false);
            String adminUsername = requireEnvironment("MYSQL_SMOKE_ADMIN_USERNAME", false);
            String adminPassword = requireEnvironment("MYSQL_SMOKE_ADMIN_PASSWORD", false);
            String appHost = optionalEnvironment("MYSQL_SMOKE_APP_HOST", "127.0.0.1");
            if (appHost.length() > 255 || !MYSQL_ACCOUNT_HOST.matcher(appHost).matches()) {
                throw new IllegalStateException(
                        "MYSQL_SMOKE_APP_HOST contains unsupported characters; use an exact MySQL account host");
            }

            Matcher url = JDBC_URL.matcher(adminUrl);
            if (!url.matches()) {
                throw new IllegalStateException(
                        "MYSQL_SMOKE_ADMIN_URL must look like jdbc:mysql://host:3306/mysql?properties");
            }
            String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 12).toLowerCase(Locale.ROOT);
            String database = "sitesafe_smoke_" + suffix;
            String appUsername = "ss_smoke_" + suffix;
            String appPassword = UUID.randomUUID().toString().replace("-", "")
                    + UUID.randomUUID().toString().replace("-", "");
            String query = url.group(2) == null
                    ? "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=false"
                    : url.group(2);
            String appUrl = url.group(1) + "/" + database + query;
            MySqlSandbox sandbox = new MySqlSandbox(
                    adminUrl, adminUsername, adminPassword, database, appUrl,
                    appUsername, appPassword, appHost, new AtomicBoolean(false));
            Runtime.getRuntime().addShutdownHook(new Thread(sandbox::cleanup, "mysql-smoke-cleanup"));

            try (Connection connection = DriverManager.getConnection(adminUrl, adminUsername, adminPassword)) {
                DatabaseMetaData metadata = connection.getMetaData();
                if (!"MySQL".equals(metadata.getDatabaseProductName()) || metadata.getDatabaseMajorVersion() != 8) {
                    throw new IllegalStateException("A MySQL 8 server is required; connected to "
                            + metadata.getDatabaseProductName() + " " + metadata.getDatabaseProductVersion());
                }
                try (Statement statement = connection.createStatement()) {
                    statement.execute("create database " + identifier(database)
                            + " character set utf8mb4 collate utf8mb4_0900_ai_ci");
                    statement.execute("create user " + account(appUsername, appHost)
                            + " identified by '" + sqlLiteral(appPassword) + "'");
                    statement.execute("grant select,insert,update,delete,create,alter,index,references on "
                            + identifier(database) + ".* to " + account(appUsername, appHost));
                }
                try (Connection applicationConnection = DriverManager.getConnection(
                        appUrl, appUsername, appPassword);
                     Statement statement = applicationConnection.createStatement();
                     ResultSet result = statement.executeQuery(
                             "select count(*) from information_schema.tables where table_schema=database()")) {
                    if (!result.next() || result.getInt(1) != 0) {
                        throw new IllegalStateException("The generated MySQL smoke database was not empty");
                    }
                } catch (SQLException exception) {
                    throw new IllegalStateException(
                            "The disposable application account could not connect. Set MYSQL_SMOKE_APP_HOST "
                                    + "to the exact client host MySQL recognizes.", exception);
                }
            } catch (Exception exception) {
                sandbox.cleanup();
                throw exception instanceof RuntimeException runtimeException
                        ? runtimeException
                        : new IllegalStateException("Unable to provision the isolated MySQL smoke database", exception);
            }

            System.out.println("Provisioned isolated MySQL smoke database " + database
                    + " with disposable application user " + appUsername + "@" + appHost);
            return sandbox;
        }

        void cleanup() {
            if (cleaned.get()) return;
            synchronized (cleaned) {
                if (cleaned.get()) return;
                try (Connection connection = DriverManager.getConnection(adminUrl, adminUsername, adminPassword);
                     Statement statement = connection.createStatement()) {
                    statement.execute("drop database if exists " + identifier(databaseName));
                    statement.execute("drop user if exists " + account(applicationUsername, applicationHost));
                    cleaned.set(true);
                    System.out.println("Removed isolated MySQL smoke database " + databaseName
                            + " and its disposable application user");
                } catch (SQLException exception) {
                    System.err.println("WARNING: failed to remove MySQL smoke database " + databaseName
                            + "; clean it up with the administrator account. Cause: " + exception.getMessage());
                }
            }
        }

        private static String requireEnvironment(String name, boolean allowEmpty) {
            String value = System.getenv(name);
            if (value == null || (!allowEmpty && value.isBlank())) {
                throw new IllegalStateException(name + " must be supplied in the process environment");
            }
            return value;
        }

        private static String optionalEnvironment(String name, String fallback) {
            String value = System.getenv(name);
            return value == null || value.isBlank() ? fallback : value.trim();
        }

        private static String identifier(String value) {
            if (!value.matches("^[a-z0-9_]+$")) {
                throw new IllegalArgumentException("Unsafe generated MySQL identifier");
            }
            return "`" + value + "`";
        }

        private static String account(String username, String host) {
            return "'" + sqlLiteral(username) + "'@'" + sqlLiteral(host) + "'";
        }

        private static String sqlLiteral(String value) {
            return value.replace("'", "''");
        }
    }
}
