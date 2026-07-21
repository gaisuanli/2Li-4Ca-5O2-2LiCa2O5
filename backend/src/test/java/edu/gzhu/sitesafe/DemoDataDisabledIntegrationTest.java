package edu.gzhu.sitesafe;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-no-demo;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.demo-data-enabled=false",
        "app.tcp.enabled=false"
})
class DemoDataDisabledIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void disabledDemoInitializerLeavesTheNewSchemaEmpty() {
        assertEquals(0L, jdbc.queryForObject("select count(*) from app_user", Long.class));
        assertEquals(0L, jdbc.queryForObject("select count(*) from site", Long.class));
        assertEquals(0L, jdbc.queryForObject("select count(*) from device", Long.class));
    }
}
