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

/** Adds the complete seven-metric tower demo series to new and existing local demo databases. */
@Component
public class TowerExtendedDemoTelemetryInitializer {
    private static final LocalDateTime BASE_TIME = LocalDateTime.of(2026, 7, 19, 16, 30);
    private static final Map<String, Series> TC1_EXTENDED = Map.of(
            "amplitude", series("m", 30, 31, 33, 35, 37, 39, 42, 45, 43, 40, 36, 34),
            "moment", series("kN·m", 820, 880, 940, 1010, 1090, 1160, 1280, 1340, 1220, 1080, 960, 890),
            "obliquity", series("°", 0.18, 0.20, 0.24, 0.28, 0.31, 0.36, 0.48, 0.55, 0.43, 0.35, 0.27, 0.22)
    );
    private static final Map<String, Series> TC2_COMPLETE = Map.ofEntries(
            Map.entry("windSpeed", series("m/s", 4.8, 5.2, 5.9, 6.4, 7.1, 6.8, 6.1, 5.6)),
            Map.entry("weight", series("t", 18, 24, 31, 38, 43, 36, 29, 21)),
            Map.entry("amplitude", series("m", 24, 27, 30, 34, 38, 35, 31, 28)),
            Map.entry("moment", series("kN·m", 620, 710, 840, 970, 1080, 960, 830, 700)),
            Map.entry("rotation", series("°", 36, 52, 68, 83, 102, 116, 128, 141)),
            Map.entry("height", series("m", 42, 44, 47, 49, 52, 50, 47, 45)),
            Map.entry("obliquity", series("°", 0.12, 0.16, 0.19, 0.23, 0.29, 0.25, 0.20, 0.17))
    );

    private final JdbcTemplate jdbc;
    private final boolean enabled;

    public TowerExtendedDemoTelemetryInitializer(
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
        return seedDevice("TC-001", "DEMO-TOWER-TC1", TC1_EXTENDED)
                + seedDevice("TC-002", "DEMO-TOWER-TC2", TC2_COMPLETE);
    }

    private int seedDevice(String deviceCode, String messagePrefix, Map<String, Series> seriesByMetric) {
        var deviceIds = jdbc.query("select id from device where code=?",
                (resultSet, rowNumber) -> resultSet.getLong(1), deviceCode);
        if (deviceIds.isEmpty()) return 0;
        long deviceId = deviceIds.get(0);
        int sampleCount = seriesByMetric.values().iterator().next().values().length;
        int inserted = 0;
        for (int index = 0; index < sampleCount; index++) {
            String messageId = messagePrefix + "-" + String.format("%02d", index + 1);
            LocalDateTime collectedAt = BASE_TIME.minusMinutes((long) (sampleCount - 1 - index) * 5);
            for (Map.Entry<String, Series> entry : seriesByMetric.entrySet()) {
                Series series = entry.getValue();
                if (series.values().length != sampleCount) {
                    throw new IllegalStateException("Tower demo series lengths must match");
                }
                try {
                    jdbc.update("insert into telemetry(message_id,device_id,metric_code,metric_value,unit,collected_at) values(?,?,?,?,?,?)",
                            messageId, deviceId, entry.getKey(), series.values()[index], series.unit(), Timestamp.valueOf(collectedAt));
                    inserted++;
                } catch (DuplicateKeyException ignored) {
                    // Stable messageId + metricCode keeps every restart idempotent.
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
