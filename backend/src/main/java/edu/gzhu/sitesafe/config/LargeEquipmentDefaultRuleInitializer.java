package edu.gzhu.sitesafe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

/** Adds the large-equipment demo rules to both new and existing demo databases. */
@Component
public class LargeEquipmentDefaultRuleInitializer {
    private static final LocalDateTime UPDATED_AT = LocalDateTime.of(2026, 7, 19, 16, 30);
    private static final List<RuleSeed> RULES = List.of(
            new RuleSeed("施工升降机载荷超限", "load", ">", "1800", "HIGH", 180),
            new RuleSeed("施工升降机限位触发", "limitStatus", "=", "1", "HIGH", 60),
            new RuleSeed("高支模水平位移预警", "displacement", ">", "1.0", "MEDIUM", 300),
            new RuleSeed("高支模压力预警", "pressure", ">", "26", "HIGH", 300),
            new RuleSeed("深基坑水平位移预警", "horizontalDisplacement", ">", "5.0", "HIGH", 300),
            new RuleSeed("深基坑沉降速率预警", "settlementRate", ">", "0.25", "MEDIUM", 300)
    );

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public LargeEquipmentDefaultRuleInitializer(
            JdbcTemplate jdbc,
            @Value("${app.demo-data-enabled:true}") boolean enabled) {
        this.jdbc = jdbc;
        this.enabled = enabled;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void onApplicationReady() {
        backfill();
    }

    public int backfill() {
        if (!enabled) return 0;
        List<Long> siteIds = jdbc.query("select id from site order by id",
                (resultSet, rowNumber) -> resultSet.getLong(1));
        int inserted = 0;
        for (long siteId : siteIds) {
            for (RuleSeed rule : RULES) {
                Long existing = jdbc.queryForObject(
                        "select count(*) from alarm_rule where source_type='DEVICE_RULE' and metric_code=? and scope_type='TYPE' and scope_id=?",
                        Long.class, rule.metricCode(), siteId);
                if (existing != null && existing > 0) continue;
                jdbc.update("insert into alarm_rule(name,source_type,metric_code,operator,threshold_value,severity,scope_type,scope_id,enabled,suppression_seconds,updated_at) values(?,'DEVICE_RULE',?,?,?,?, 'TYPE',?,true,?,?)",
                        rule.name(), rule.metricCode(), rule.operator(), rule.threshold(), rule.severity(), siteId,
                        rule.suppressionSeconds(), Timestamp.valueOf(UPDATED_AT));
                inserted++;
            }
        }
        return inserted;
    }

    private record RuleSeed(String name, String metricCode, String operator, BigDecimal threshold,
                            String severity, int suppressionSeconds) {
        private RuleSeed(String name, String metricCode, String operator, String threshold,
                         String severity, int suppressionSeconds) {
            this(name, metricCode, operator, new BigDecimal(threshold), severity, suppressionSeconds);
        }
    }
}
