package edu.gzhu.sitesafe.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Backfills deterministic telemetry for the three non-tower large-equipment
 * demo devices. Stable message identifiers make the operation safe on every
 * application start, including databases created by an earlier project build.
 */
@Component
public class LargeEquipmentDemoTelemetryInitializer {
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 7, 19, 16, 30);

    private static final Map<String, Series> ELEVATOR_SERIES = Map.of(
            "load", series("kg", 640, 720, 810, 930, 1040, 1180, 1260, 1120),
            "floor", series("层", 1, 3, 5, 8, 10, 12, 9, 6),
            "speed", series("m/s", 0, 0.8, 1.1, 1.3, 1.5, 1.2, 0.9, 0.4),
            "direction", series("code", 1, 2, 2, 2, 2, 1, 3, 3),
            "doorStatus", series("code", 1, 0, 0, 0, 0, 1, 0, 1),
            "limitStatus", series("code", 0, 0, 0, 0, 0, 0, 0, 0)
    );

    private static final Map<String, Series> FORMWORK_SERIES = Map.of(
            "axialForce", series("kN", 118, 121, 125, 129, 132, 130, 134, 136),
            "displacement", series("mm", 0.8, 0.9, 0.9, 1.0, 1.1, 1.0, 1.2, 1.2),
            "settlement", series("mm", 0.4, 0.5, 0.5, 0.6, 0.7, 0.7, 0.8, 0.9),
            "xAngle", series("°", 0.08, 0.09, 0.08, 0.10, 0.11, 0.10, 0.12, 0.11),
            "yAngle", series("°", -0.05, -0.04, -0.05, -0.03, -0.02, -0.03, -0.01, -0.02),
            "pressure", series("kPa", 22, 23, 23, 24, 25, 25, 26, 27)
    );

    private static final Map<String, Series> FOUNDATION_PIT_SERIES = Map.ofEntries(
            Map.entry("horizontalDisplacement", series("mm", 4.2, 4.4, 4.5, 4.7, 4.9, 5.1, 5.4, 5.6)),
            Map.entry("settlement", series("mm", 2.1, 2.2, 2.2, 2.4, 2.5, 2.6, 2.7, 2.8)),
            Map.entry("axialForce", series("kN", 240, 244, 247, 252, 255, 258, 261, 264)),
            Map.entry("xAngle", series("°", 0.10, 0.11, 0.12, 0.12, 0.13, 0.14, 0.14, 0.15)),
            Map.entry("yAngle", series("°", 0.04, 0.04, 0.05, 0.05, 0.06, 0.06, 0.07, 0.07)),
            Map.entry("waterLevel", series("m", -3.2, -3.1, -3.0, -2.9, -2.9, -2.8, -2.7, -2.6)),
            Map.entry("earthPressure", series("kPa", 48, 49, 50, 50, 51, 52, 52, 53)),
            Map.entry("strain", series("με", 115, 118, 121, 124, 128, 131, 135, 139)),
            Map.entry("settlementRate", series("mm/h", 0.18, 0.20, 0.19, 0.22, 0.24, 0.23, 0.25, 0.27))
    );

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public LargeEquipmentDemoTelemetryInitializer(
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
        int inserted = 0;
        inserted += seedDevice("EL-001", "DEMO-LARGE-EL", ELEVATOR_SERIES);
        inserted += seedDevice("FM-001", "DEMO-LARGE-FM", FORMWORK_SERIES);
        inserted += seedDevice("PIT-001", "DEMO-LARGE-PIT", FOUNDATION_PIT_SERIES);
        return inserted;
    }

    private int seedDevice(String deviceCode, String messagePrefix, Map<String, Series> seriesByMetric) {
        var deviceIds = jdbc.query("select id from device where code=?",
                (resultSet, rowNumber) -> resultSet.getLong(1), deviceCode);
        if (deviceIds.isEmpty() || seriesByMetric.isEmpty()) return 0;

        long deviceId = deviceIds.get(0);
        int sampleCount = seriesByMetric.values().iterator().next().values().length;
        for (Series series : seriesByMetric.values()) {
            if (series.values().length != sampleCount) {
                throw new IllegalStateException("Large-equipment demo series lengths must match");
            }
        }

        int inserted = 0;
        for (int index = 0; index < sampleCount; index++) {
            String messageId = messagePrefix + "-" + String.format("%02d", index + 1);
            LocalDateTime collectedAt = BASE_TIME.minusMinutes((long) (sampleCount - 1 - index) * 10);
            for (Map.Entry<String, Series> entry : seriesByMetric.entrySet()) {
                Series series = entry.getValue();
                Long existing = jdbc.queryForObject(
                        "select count(*) from telemetry where message_id=? and metric_code=?",
                        Long.class, messageId, entry.getKey());
                if (existing != null && existing > 0) continue;
                try {
                    jdbc.update("insert into telemetry(message_id,device_id,metric_code,metric_value,unit,collected_at) values(?,?,?,?,?,?)",
                            messageId, deviceId, entry.getKey(), series.values()[index], series.unit(),
                            Timestamp.valueOf(collectedAt));
                    inserted++;
                } catch (DuplicateKeyException ignored) {
                    // Stable messageId + metricCode is the idempotency key.
                }
            }
        }
        return inserted;
    }

    private static Series series(String unit, double... values) {
        return new Series(unit, values);
    }

    private record Series(String unit, double[] values) {}
}
