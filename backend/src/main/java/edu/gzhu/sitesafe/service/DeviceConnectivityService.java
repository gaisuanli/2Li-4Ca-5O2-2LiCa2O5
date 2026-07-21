package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.realtime.RealtimeHub;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.util.Map;

/**
 * Owns automatic device connection-state transitions and their system alarm.
 * Manual state changes remain in the device management controller.
 */
@Service
public class DeviceConnectivityService {
    public static final String OFFLINE_ALARM_SOURCE = "SYSTEM_DEVICE_OFFLINE";
    private static final String OFFLINE_EVENT_SOURCE = "HEARTBEAT_TIMEOUT";

    private final JdbcTemplate jdbc;
    private final RealtimeHub realtime;

    public DeviceConnectivityService(JdbcTemplate jdbc, RealtimeHub realtime) {
        this.jdbc = jdbc;
        this.realtime = realtime;
    }

    @Transactional
    public OfflineTransition markOfflineIfTimedOut(OfflineCandidate candidate,
                                                    Timestamp cutoff,
                                                    Timestamp detectedAt) {
        int changed = jdbc.update("""
                update device set connection_status='OFFLINE'
                where id=? and enabled=true and connection_status='ONLINE'
                  and last_reported_at is not null and last_reported_at<=?
                """, candidate.id(), cutoff);
        if (changed == 0) {
            return OfflineTransition.notChanged(candidate.id());
        }

        String alarmCode = offlineAlarmCode(candidate.id());
        String title = candidate.name() + "设备离线";
        String description = "设备 " + candidate.code() + " 最近上报时间为 " + candidate.lastReportedAt()
                + "，超过心跳阈值，系统已自动标记为离线";
        var existing = jdbc.queryForList(
                "select id,status from alarm where code=?", alarmCode);

        long alarmId;
        boolean created;
        String previousAlarmStatus = null;
        String alarmStatus = "PENDING";
        if (existing.isEmpty()) {
            jdbc.update("""
                    insert into alarm(code,site_id,zone_id,device_id,rule_id,source_type,severity,title,
                                      description,status,first_occurred_at,last_occurred_at,occurrences)
                    values(?,?,?,?,null,?,'HIGH',?,?,'PENDING',?,?,1)
                    """, alarmCode, candidate.siteId(), candidate.zoneId(), candidate.id(),
                    OFFLINE_ALARM_SOURCE, title, description, detectedAt, detectedAt);
            alarmId = jdbc.queryForObject("select id from alarm where code=?", Long.class, alarmCode);
            created = true;
        } else {
            Map<String, Object> alarm = existing.get(0);
            alarmId = ((Number) alarm.get("id")).longValue();
            previousAlarmStatus = String.valueOf(alarm.get("status"));
            alarmStatus = ("RESOLVED".equals(previousAlarmStatus) || "CLOSED".equals(previousAlarmStatus))
                    ? "PENDING" : previousAlarmStatus;
            jdbc.update("""
                    update alarm set site_id=?,zone_id=?,device_id=?,source_type=?,severity='HIGH',title=?,
                                     description=?,status=?,last_occurred_at=?,occurrences=occurrences+1
                    where id=?
                    """, candidate.siteId(), candidate.zoneId(), candidate.id(), OFFLINE_ALARM_SOURCE,
                    title, description, alarmStatus, detectedAt, alarmId);
            created = false;
        }

        realtime.publish("device.status.changed", Map.of(
                "deviceId", candidate.id(),
                "deviceCode", candidate.code(),
                "siteId", candidate.siteId(),
                "zoneId", candidate.zoneId(),
                "previousConnectionStatus", "ONLINE",
                "connectionStatus", "OFFLINE",
                "source", OFFLINE_EVENT_SOURCE
        ));
        if (created) {
            realtime.publish("alarm.created", Map.of(
                    "alarmId", alarmId,
                    "code", alarmCode,
                    "siteId", candidate.siteId(),
                    "zoneId", candidate.zoneId(),
                    "deviceId", candidate.id(),
                    "sourceType", OFFLINE_ALARM_SOURCE,
                    "severity", "HIGH",
                    "title", title
            ));
        } else {
            realtime.publish("alarm.updated", Map.of(
                    "alarmId", alarmId,
                    "siteId", candidate.siteId(),
                    "deviceId", candidate.id(),
                    "sourceType", OFFLINE_ALARM_SOURCE,
                    "occurrencesDelta", 1,
                    "status", alarmStatus
            ));
            if (!alarmStatus.equals(previousAlarmStatus)) {
                realtime.publish("alarm.status.changed", Map.of(
                        "alarmId", alarmId,
                        "siteId", candidate.siteId(),
                        "fromStatus", previousAlarmStatus,
                        "toStatus", alarmStatus,
                        "sourceType", OFFLINE_ALARM_SOURCE
                ));
            }
        }
        return new OfflineTransition(candidate.id(), alarmId, true, created);
    }

    @Transactional
    public HeartbeatResult recordHeartbeat(long deviceId, long siteId, long zoneId, Timestamp reportedAt) {
        var currentRows = jdbc.queryForList(
                "select connection_status from device where id=? and enabled=true", deviceId);
        if (currentRows.isEmpty()) {
            return new HeartbeatResult(false, null);
        }
        String previousStatus = String.valueOf(currentRows.get(0).get("connection_status"));
        int restored = jdbc.update("""
                update device set connection_status='ONLINE',
                    last_reported_at=case when last_reported_at is null or last_reported_at<?
                                          then ? else last_reported_at end
                where id=? and enabled=true and connection_status<>'ONLINE'
                """, reportedAt, reportedAt, deviceId);
        if (restored == 0) {
            jdbc.update("""
                    update device set last_reported_at=case when last_reported_at is null or last_reported_at<?
                                                            then ? else last_reported_at end
                    where id=? and enabled=true
                    """, reportedAt, reportedAt, deviceId);
        } else {
            realtime.publish("device.status.changed", Map.of(
                    "deviceId", deviceId,
                    "siteId", siteId,
                    "zoneId", zoneId,
                    "previousConnectionStatus", previousStatus,
                    "connectionStatus", "ONLINE",
                    "source", "TELEMETRY_REPORT"
            ));
        }

        Long resolvedAlarmId = resolveOfflineAlarm(deviceId, siteId, reportedAt);
        return new HeartbeatResult(restored == 1, resolvedAlarmId);
    }

    private Long resolveOfflineAlarm(long deviceId, long siteId, Timestamp reportedAt) {
        var rows = jdbc.queryForList(
                "select id,status from alarm where code=?", offlineAlarmCode(deviceId));
        if (rows.isEmpty()) {
            return null;
        }
        long alarmId = ((Number) rows.get(0).get("id")).longValue();
        String status = String.valueOf(rows.get(0).get("status"));
        if (!"PENDING".equals(status) && !"PROCESSING".equals(status)) {
            return null;
        }
        int changed = jdbc.update("""
                update alarm set status='RESOLVED',
                    description=concat(description, ?)
                where id=? and status=?
                """, "；设备已于 " + reportedAt + " 恢复上报", alarmId, status);
        if (changed == 0) {
            return null;
        }
        realtime.publish("alarm.status.changed", Map.of(
                "alarmId", alarmId,
                "siteId", siteId,
                "fromStatus", status,
                "toStatus", "RESOLVED",
                "sourceType", OFFLINE_ALARM_SOURCE
        ));
        return alarmId;
    }

    public static String offlineAlarmCode(long deviceId) {
        return "SYS-DEVICE-OFFLINE-" + deviceId;
    }

    public record OfflineCandidate(long id, String code, String name, long siteId, long zoneId,
                                   Timestamp lastReportedAt) {
    }

    public record OfflineTransition(long deviceId, Long alarmId, boolean changed, boolean alarmCreated) {
        static OfflineTransition notChanged(long deviceId) {
            return new OfflineTransition(deviceId, null, false, false);
        }
    }

    public record HeartbeatResult(boolean restoredOnline, Long resolvedAlarmId) {
    }
}
