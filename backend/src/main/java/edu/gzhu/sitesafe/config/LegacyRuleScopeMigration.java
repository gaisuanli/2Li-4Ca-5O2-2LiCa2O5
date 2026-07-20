package edu.gzhu.sitesafe.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@Order(Ordered.LOWEST_PRECEDENCE)
public class LegacyRuleScopeMigration implements ApplicationRunner {
    private final JdbcTemplate jdbc;

    public LegacyRuleScopeMigration(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        List<Long> siteIds = jdbc.query("select id from site order by id", (rs, rowNum) -> rs.getLong(1));
        if (siteIds.size() == 1) {
            jdbc.update("update alarm_rule set scope_id=? where scope_type='TYPE' and scope_id is null", siteIds.get(0));
        }
    }
}
