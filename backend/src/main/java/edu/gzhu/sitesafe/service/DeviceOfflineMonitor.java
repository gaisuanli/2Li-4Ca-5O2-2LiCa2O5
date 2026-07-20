package edu.gzhu.sitesafe.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

@Service
public class DeviceOfflineMonitor {
    private static final Logger log = LoggerFactory.getLogger(DeviceOfflineMonitor.class);

    private final JdbcTemplate jdbc;
    private final DeviceConnectivityService connectivity;
    private final boolean enabled;
    private final long timeoutSeconds;
    private final int batchSize;

    public DeviceOfflineMonitor(JdbcTemplate jdbc,
                                DeviceConnectivityService connectivity,
                                @Value("${app.device-offline-monitor.enabled:false}") boolean enabled,
                                @Value("${app.device-offline-monitor.timeout-seconds:300}") long timeoutSeconds,
                                @Value("${app.device-offline-monitor.batch-size:200}") int batchSize) {
        this.jdbc = jdbc;
        this.connectivity = connectivity;
        this.enabled = enabled;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.batchSize = Math.max(1, Math.min(batchSize, 1000));
    }

    @Scheduled(
            fixedDelayString = "${app.device-offline-monitor.scan-interval-ms:30000}",
            initialDelayString = "${app.device-offline-monitor.initial-delay-ms:60000}"
    )
    public void scheduledScan() {
        if (!enabled) {
            return;
        }
        ScanResult result = scanNow(Instant.now());
        if (result.transitioned() > 0) {
            log.info("Device offline scan inspected {} candidate(s), transitioned {} device(s)",
                    result.candidates(), result.transitioned());
        }
    }

    /** Visible for deterministic integration tests and operational diagnostics. */
    public ScanResult scanNow(Instant now) {
        Timestamp cutoff = Timestamp.from(now.minusSeconds(timeoutSeconds));
        Timestamp detectedAt = Timestamp.from(now);
        List<DeviceConnectivityService.OfflineCandidate> candidates = jdbc.query("""
                        select id,code,name,site_id,zone_id,last_reported_at
                        from device
                        where enabled=true and connection_status='ONLINE'
                          and last_reported_at is not null and last_reported_at<=?
                        order by last_reported_at,id
                        limit ?
                        """,
                (resultSet, rowNumber) -> new DeviceConnectivityService.OfflineCandidate(
                        resultSet.getLong("id"),
                        resultSet.getString("code"),
                        resultSet.getString("name"),
                        resultSet.getLong("site_id"),
                        resultSet.getLong("zone_id"),
                        resultSet.getTimestamp("last_reported_at")
                ), cutoff, batchSize);

        int transitioned = 0;
        for (DeviceConnectivityService.OfflineCandidate candidate : candidates) {
            if (connectivity.markOfflineIfTimedOut(candidate, cutoff, detectedAt).changed()) {
                transitioned++;
            }
        }
        return new ScanResult(candidates.size(), transitioned, cutoff.toInstant());
    }

    public record ScanResult(int candidates, int transitioned, Instant cutoff) {
    }
}
