package edu.gzhu.sitesafe;

import edu.gzhu.sitesafe.realtime.RealtimeHub;
import edu.gzhu.sitesafe.service.DeviceConnectivityService;
import edu.gzhu.sitesafe.service.DeviceOfflineMonitor;
import edu.gzhu.sitesafe.service.TelemetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-device-offline;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.demo-data-enabled=false",
        "app.tcp.enabled=false",
        "app.device-offline-monitor.enabled=false",
        "app.device-offline-monitor.timeout-seconds=300"
})
class DeviceOfflineMonitorIntegrationTest {
    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private DeviceOfflineMonitor monitor;

    @Autowired
    private TelemetryService telemetry;

    @SpyBean
    private RealtimeHub realtime;

    @BeforeEach
    void resetData() {
        jdbc.update("delete from alarm_action");
        jdbc.update("delete from alarm");
        jdbc.update("delete from telemetry");
        jdbc.update("delete from device");
        jdbc.update("delete from zone");
        jdbc.update("delete from site");
        reset(realtime);
    }

    @Test
    void scanTimesOutOnlyDueDevicesAndKeepsCorrectSiteAndZoneOnSystemAlarms() {
        Instant now = Instant.parse("2026-07-20T04:00:00Z");
        SiteFixture first = createSite("OFFLINE-SITE-A", "OFFLINE-ZONE-A");
        SiteFixture second = createSite("OFFLINE-SITE-B", "OFFLINE-ZONE-B");
        long firstDue = createDevice("OFF-DUE-A", first, true, "ONLINE", now.minusSeconds(301));
        long secondDue = createDevice("OFF-DUE-B", second, true, "ONLINE", now.minusSeconds(900));
        long fresh = createDevice("OFF-FRESH-B", second, true, "ONLINE", now.minusSeconds(60));
        long disabled = createDevice("OFF-DISABLED-A", first, false, "ONLINE", now.minusSeconds(900));
        long neverReported = createDevice("OFF-NULL-A", first, true, "ONLINE", null);

        DeviceOfflineMonitor.ScanResult result = monitor.scanNow(now);

        assertEquals(2, result.candidates());
        assertEquals(2, result.transitioned());
        assertEquals("OFFLINE", status(firstDue));
        assertEquals("OFFLINE", status(secondDue));
        assertEquals("ONLINE", status(fresh));
        assertEquals("ONLINE", status(disabled));
        assertEquals("ONLINE", status(neverReported));

        List<Map<String, Object>> alarms = jdbc.queryForList("""
                select device_id,site_id,zone_id,source_type,severity,status
                from alarm where source_type=? order by device_id
                """, DeviceConnectivityService.OFFLINE_ALARM_SOURCE);
        assertEquals(2, alarms.size());
        assertAlarmScope(alarms.get(0), firstDue, first);
        assertAlarmScope(alarms.get(1), secondDue, second);
        verify(realtime, atLeastOnce()).publish(eq("device.status.changed"), argThat(payload ->
                payload instanceof Map<?, ?> map
                        && "OFFLINE".equals(map.get("connectionStatus"))
                        && "HEARTBEAT_TIMEOUT".equals(map.get("source"))));
        verify(realtime, atLeastOnce()).publish(eq("alarm.created"), argThat(payload ->
                payload instanceof Map<?, ?> map
                        && DeviceConnectivityService.OFFLINE_ALARM_SOURCE.equals(map.get("sourceType"))));
    }

    @Test
    void repeatedScansAreIdempotentAndARealNewOfflineTransitionMergesTheAlarm() {
        Instant now = Instant.parse("2026-07-20T05:00:00Z");
        SiteFixture site = createSite("OFFLINE-SITE-C", "OFFLINE-ZONE-C");
        long deviceId = createDevice("OFF-DUE-C", site, true, "ONLINE", now.minusSeconds(600));

        assertEquals(1, monitor.scanNow(now).transitioned());
        assertEquals(0, monitor.scanNow(now.plusSeconds(30)).transitioned());
        assertEquals(1L, alarmCount(deviceId));
        assertEquals(1, alarmOccurrences(deviceId));

        jdbc.update("update device set connection_status='ONLINE' where id=?", deviceId);
        assertEquals(1, monitor.scanNow(now.plusSeconds(60)).transitioned());
        assertEquals(1L, alarmCount(deviceId));
        assertEquals(2, alarmOccurrences(deviceId));
        assertEquals("PENDING", offlineAlarmStatus(deviceId));
    }

    @Test
    void telemetryRestoresOnlineWithoutRegressingHeartbeatAndResolvesOfflineAlarm() {
        Instant now = Instant.now().minusSeconds(1);
        SiteFixture site = createSite("OFFLINE-SITE-D", "OFFLINE-ZONE-D");
        long deviceId = createDevice("OFF-DUE-D", site, true, "ONLINE", now.minusSeconds(600));
        assertEquals(1, monitor.scanNow(now).transitioned());
        reset(realtime);

        OffsetDateTime reportedAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
        TelemetryService.IngestResult result = telemetry.ingest(new TelemetryService.TelemetryRequest(
                "1.0",
                "OFFLINE-RECOVERY-1",
                "OFF-DUE-D",
                "telemetry",
                reportedAt,
                List.of(new TelemetryService.Metric("windSpeed", BigDecimal.valueOf(4.2), "m/s"))
        ));

        assertEquals(1, result.insertedMetrics());
        assertEquals("ONLINE", status(deviceId));
        Timestamp lastReportedAt = jdbc.queryForObject(
                "select last_reported_at from device where id=?", Timestamp.class, deviceId);
        assertNotNull(lastReportedAt);
        assertEquals(reportedAt.toInstant(), lastReportedAt.toInstant());
        assertEquals("RESOLVED", offlineAlarmStatus(deviceId));
        assertEquals(0, monitor.scanNow(reportedAt.toInstant().plusSeconds(60)).transitioned());
        assertEquals(1L, alarmCount(deviceId));

        verify(realtime).publish(eq("device.status.changed"), argThat(payload ->
                payload instanceof Map<?, ?> map
                        && "ONLINE".equals(map.get("connectionStatus"))
                        && "TELEMETRY_REPORT".equals(map.get("source"))));
        verify(realtime).publish(eq("alarm.status.changed"), argThat(payload ->
                payload instanceof Map<?, ?> map
                        && "RESOLVED".equals(map.get("toStatus"))
                        && DeviceConnectivityService.OFFLINE_ALARM_SOURCE.equals(map.get("sourceType"))));
    }

    private SiteFixture createSite(String siteCode, String zoneCode) {
        jdbc.update("insert into site(code,name,address,status,updated_at) values(?,?,?,?,current_timestamp)",
                siteCode, siteCode, "test", "ACTIVE");
        long siteId = jdbc.queryForObject("select id from site where code=?", Long.class, siteCode);
        jdbc.update("""
                insert into zone(site_id,code,name,status,map_x,map_y,map_width,map_height)
                values(?,?,?,'CONSTRUCTION',0.1,0.1,0.2,0.2)
                """, siteId, zoneCode, zoneCode);
        long zoneId = jdbc.queryForObject("select id from zone where code=?", Long.class, zoneCode);
        return new SiteFixture(siteId, zoneId);
    }

    private long createDevice(String code, SiteFixture site, boolean enabled, String connectionStatus, Instant lastReportedAt) {
        jdbc.update("""
                insert into device(code,name,type,site_id,zone_id,location,enabled,connection_status,last_reported_at,config_json)
                values(?,?, 'TOWER_CRANE',?,?,?, ?,?,?, '{}')
                """, code, code, site.siteId(), site.zoneId(), "test", enabled, connectionStatus,
                lastReportedAt == null ? null : Timestamp.from(lastReportedAt));
        return jdbc.queryForObject("select id from device where code=?", Long.class, code);
    }

    private String status(long deviceId) {
        return jdbc.queryForObject("select connection_status from device where id=?", String.class, deviceId);
    }

    private long alarmCount(long deviceId) {
        return jdbc.queryForObject("select count(*) from alarm where device_id=? and source_type=?", Long.class,
                deviceId, DeviceConnectivityService.OFFLINE_ALARM_SOURCE);
    }

    private int alarmOccurrences(long deviceId) {
        return jdbc.queryForObject("select occurrences from alarm where device_id=? and source_type=?", Integer.class,
                deviceId, DeviceConnectivityService.OFFLINE_ALARM_SOURCE);
    }

    private String offlineAlarmStatus(long deviceId) {
        return jdbc.queryForObject("select status from alarm where device_id=? and source_type=?", String.class,
                deviceId, DeviceConnectivityService.OFFLINE_ALARM_SOURCE);
    }

    private void assertAlarmScope(Map<String, Object> alarm, long deviceId, SiteFixture fixture) {
        assertEquals(deviceId, ((Number) alarm.get("device_id")).longValue());
        assertEquals(fixture.siteId(), ((Number) alarm.get("site_id")).longValue());
        assertEquals(fixture.zoneId(), ((Number) alarm.get("zone_id")).longValue());
        assertEquals(DeviceConnectivityService.OFFLINE_ALARM_SOURCE, alarm.get("source_type"));
        assertEquals("HIGH", alarm.get("severity"));
        assertEquals("PENDING", alarm.get("status"));
    }

    private record SiteFixture(long siteId, long zoneId) {
    }
}
