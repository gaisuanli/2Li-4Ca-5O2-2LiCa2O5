package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.common.TraceContext;
import edu.gzhu.sitesafe.realtime.RealtimeHub;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.UserSession;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SprinklerTaskService {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final RealtimeHub realtime;
    private final SprinklerGatewayClient gateway;
    private final long minimumIntervalSeconds;
    private final long dispatchTimeoutSeconds;
    private final boolean timeoutScanEnabled;

    public SprinklerTaskService(JdbcTemplate jdbc,
                                AuditService audit,
                                RealtimeHub realtime,
                                SprinklerGatewayClient gateway,
                                @Value("${app.sprinkler.minimum-interval-seconds:300}") long minimumIntervalSeconds,
                                @Value("${app.sprinkler.dispatch-timeout-seconds:120}") long dispatchTimeoutSeconds,
                                @Value("${app.sprinkler.timeout-scan-enabled:true}") boolean timeoutScanEnabled) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.realtime = realtime;
        this.gateway = gateway;
        this.minimumIntervalSeconds = Math.max(0, minimumIntervalSeconds);
        this.dispatchTimeoutSeconds = Math.max(1, dispatchTimeoutSeconds);
        this.timeoutScanEnabled = timeoutScanEnabled;
    }

    @Transactional
    public Map<String, Object> create(long siteId, long zoneId, String reason, LocalDateTime requestedPlannedAt) {
        SecurityUtil.requireSite(siteId);
        requireZoneForUpdate(siteId, zoneId);
        requireAvailableDevice(siteId, zoneId, false);

        LocalDateTime plannedAt = requestedPlannedAt == null ? LocalDateTime.now() : requestedPlannedAt;
        requireMinimumInterval(siteId, zoneId, plannedAt);

        UserSession user = SecurityUtil.currentUser();
        String code = "SPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        jdbc.update("insert into sprinkler_task(code,site_id,zone_id,trigger_type,reason,status,planned_at,created_by) values(?,?,?,?,?,'CREATED',?,?)",
                code, siteId, zoneId, "MANUAL", reason.trim(), Timestamp.valueOf(plannedAt), user.id());
        Long id = jdbc.queryForObject("select id from sprinkler_task where code=?", Long.class, code);
        audit.record("SPRINKLER_TASK_CREATE", "SPRINKLER_TASK", id, reason.trim());
        return find(id);
    }

    @Transactional
    public Map<String, Object> dispatch(long id) {
        Map<String, Object> task = find(id);
        long siteId = number(task, "siteId");
        long zoneId = number(task, "zoneId");
        SecurityUtil.requireSite(siteId);

        String currentStatus = String.valueOf(task.get("status"));
        if ("DISPATCHED".equals(currentStatus)) return task;
        if ("EXECUTED".equals(currentStatus) || "FAILED".equals(currentStatus)) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_ALREADY_FINALIZED", "喷淋任务已结束，不能再次下发");
        }
        if (!"CREATED".equals(currentStatus)) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_NOT_DISPATCHABLE", "喷淋任务当前状态不能下发");
        }

        requireAvailableDevice(siteId, zoneId, true);
        LocalDateTime now = LocalDateTime.now();
        String commandId = "CMD-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        SprinklerGatewayClient.DispatchReceipt receipt = gateway.dispatch(
                id, siteId, zoneId, commandId, String.valueOf(task.get("reason")));
        int changed = jdbc.update(
                "update sprinkler_task set status='DISPATCHED',started_at=?,command_id=?,failure_reason=null where id=? and status='CREATED'",
                Timestamp.valueOf(now), commandId, id);
        if (changed == 0) {
            Map<String, Object> concurrent = find(id);
            if ("DISPATCHED".equals(concurrent.get("status"))) return concurrent;
            throw new AppException(HttpStatus.CONFLICT, "TASK_STATE_CHANGED", "喷淋任务状态已变化，请刷新后重试");
        }

        audit.record("SPRINKLER_TASK_DISPATCH", "SPRINKLER_TASK", id,
                "网关模式=" + receipt.mode() + "；commandId=" + commandId
                        + (commandId.equals(receipt.externalCommandId()) ? "" : "；externalCommandId=" + receipt.externalCommandId()));
        realtime.publish("sprinkler.task.changed", Map.of(
                "taskId", id, "siteId", siteId, "status", "DISPATCHED", "commandId", commandId));
        return find(id);
    }

    @Transactional
    public Map<String, Object> acknowledge(long id, boolean success, String requestedFailureReason) {
        return acknowledgeInternal(id, success, requestedFailureReason, false);
    }

    @Transactional
    public Map<String, Object> acknowledgeFromGateway(String commandId, boolean success, String requestedFailureReason) {
        List<Long> ids = jdbc.query("select id from sprinkler_task where command_id=?",
                (resultSet, rowNumber) -> resultSet.getLong(1), commandId.trim());
        if (ids.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "SPRINKLER_COMMAND_NOT_FOUND", "喷淋指令不存在");
        }
        return acknowledgeInternal(ids.get(0), success, requestedFailureReason, true);
    }

    private Map<String, Object> acknowledgeInternal(long id, boolean success,
                                                     String requestedFailureReason, boolean gatewayCallback) {
        String failureReason = normalizeFailureReason(success, requestedFailureReason);
        String target = success ? "EXECUTED" : "FAILED";
        Map<String, Object> task = find(id);
        long siteId = number(task, "siteId");
        if (!gatewayCallback) SecurityUtil.requireSite(siteId);

        String currentStatus = String.valueOf(task.get("status"));
        if (target.equals(currentStatus)) return task;
        if ("EXECUTED".equals(currentStatus) || "FAILED".equals(currentStatus)) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_ACK_CONFLICT", "任务已有相反结果，不能覆盖最终状态");
        }
        if ("CREATED".equals(currentStatus)) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_NOT_DISPATCHED", "任务尚未下发，不能记录设备回执");
        }
        if (!"DISPATCHED".equals(currentStatus)) {
            throw new AppException(HttpStatus.CONFLICT, "TASK_NOT_ACKNOWLEDGEABLE", "喷淋任务当前状态不能接收回执");
        }

        LocalDateTime now = LocalDateTime.now();
        int changed = jdbc.update(
                "update sprinkler_task set status=?,ended_at=?,failure_reason=? where id=? and status='DISPATCHED'",
                target, Timestamp.valueOf(now), failureReason, id);
        if (changed == 0) {
            Map<String, Object> concurrent = find(id);
            if (target.equals(concurrent.get("status"))) return concurrent;
            throw new AppException(HttpStatus.CONFLICT, "TASK_ACK_CONFLICT", "任务回执结果已被其他请求写入");
        }

        String detail = target + (failureReason == null ? "" : "：" + failureReason);
        if (gatewayCallback) {
            jdbc.update("insert into audit_log(user_id,username,action,object_type,object_id,detail,trace_id,created_at) values(null,'sprinkler-gateway','SPRINKLER_GATEWAY_ACK','SPRINKLER_TASK',?,?,?,?)",
                    String.valueOf(id), detail, TraceContext.currentId(), Timestamp.valueOf(now));
        } else {
            audit.record("SPRINKLER_TASK_ACK", "SPRINKLER_TASK", id, detail);
        }
        realtime.publish("sprinkler.task.changed", Map.of(
                "taskId", id, "siteId", siteId, "status", target));
        return find(id);
    }

    @Scheduled(fixedDelayString = "${app.sprinkler.timeout-scan-ms:30000}")
    @Transactional
    public void scanTimedOutTasks() {
        if (timeoutScanEnabled) expireTimedOutTasksInternal(LocalDateTime.now());
    }

    @Transactional
    public int expireTimedOutTasks(LocalDateTime now) {
        return expireTimedOutTasksInternal(now);
    }

    public Map<String, Object> find(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select t.id,t.code,t.site_id as siteId,t.zone_id as zoneId,z.name as zoneName,t.trigger_type as triggerType,t.reason,t.status,t.planned_at as plannedAt,t.started_at as startedAt,t.ended_at as endedAt,t.command_id as commandId,t.failure_reason as failureReason "
                        + "from sprinkler_task t join zone z on z.id=t.zone_id where t.id=?",
                id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "TASK_NOT_FOUND", "喷淋任务不存在");
        return rows.get(0);
    }

    private int expireTimedOutTasksInternal(LocalDateTime now) {
        LocalDateTime cutoff = now.minusSeconds(dispatchTimeoutSeconds);
        List<Map<String, Object>> candidates = jdbc.queryForList(
                "select id,site_id as siteId from sprinkler_task where status='DISPATCHED' and started_at is not null and started_at<=? order by id",
                Timestamp.valueOf(cutoff));
        int expired = 0;
        String reason = "演示设备回执超时（超过 " + dispatchTimeoutSeconds + " 秒）";
        for (Map<String, Object> candidate : candidates) {
            long id = number(candidate, "id");
            long siteId = number(candidate, "siteId");
            int changed = jdbc.update(
                    "update sprinkler_task set status='FAILED',ended_at=?,failure_reason=? where id=? and status='DISPATCHED' and started_at<=?",
                    Timestamp.valueOf(now), reason, id, Timestamp.valueOf(cutoff));
            if (changed != 1) continue;
            expired += 1;
            jdbc.update("insert into audit_log(user_id,username,action,object_type,object_id,detail,trace_id,created_at) values(null,'system','SPRINKLER_TASK_TIMEOUT','SPRINKLER_TASK',?,?,null,?)",
                    String.valueOf(id), reason, Timestamp.valueOf(now));
            realtime.publish("sprinkler.task.changed", Map.of(
                    "taskId", id, "siteId", siteId, "status", "FAILED", "reason", reason));
        }
        return expired;
    }

    private void requireZoneForUpdate(long siteId, long zoneId) {
        List<Long> zoneSites = jdbc.query("select site_id from zone where id=? for update",
                (resultSet, rowNumber) -> resultSet.getLong(1), zoneId);
        if (zoneSites.isEmpty() || zoneSites.get(0) != siteId) {
            throw new AppException(HttpStatus.BAD_REQUEST, "ZONE_SITE_MISMATCH", "区域不存在或不属于指定工地");
        }
    }

    private Map<String, Object> requireAvailableDevice(long siteId, long zoneId, boolean dispatching) {
        List<Map<String, Object>> devices = jdbc.queryForList(
                "select id,code,name,site_id as siteId,zone_id as zoneId,enabled,connection_status as connectionStatus "
                        + "from device where site_id=? and zone_id=? and type='SPRINKLER' order by id",
                siteId, zoneId);
        if (devices.isEmpty()) {
            String code = dispatching ? "SPRINKLER_BINDING_INVALID" : "NO_SPRINKLER";
            String message = dispatching ? "任务区域不再绑定喷淋设备，不能下发" : "该区域未关联喷淋设备";
            throw new AppException(HttpStatus.CONFLICT, code, message);
        }
        return devices.stream()
                .filter(device -> Boolean.TRUE.equals(device.get("enabled")))
                .filter(device -> "ONLINE".equals(device.get("connectionStatus")))
                .findFirst()
                .orElseThrow(() -> new AppException(HttpStatus.CONFLICT, "SPRINKLER_UNAVAILABLE", "喷淋设备已停用或离线，不能执行任务"));
    }

    private void requireMinimumInterval(long siteId, long zoneId, LocalDateTime plannedAt) {
        if (minimumIntervalSeconds == 0) return;
        LocalDateTime from = plannedAt.minusSeconds(minimumIntervalSeconds);
        LocalDateTime to = plannedAt.plusSeconds(minimumIntervalSeconds);
        Long conflicts = jdbc.queryForObject(
                "select count(*) from sprinkler_task where site_id=? and zone_id=? and planned_at>? and planned_at<?",
                Long.class, siteId, zoneId, Timestamp.valueOf(from), Timestamp.valueOf(to));
        if (conflicts != null && conflicts > 0) {
            throw new AppException(HttpStatus.CONFLICT, "SPRINKLER_INTERVAL_CONFLICT",
                    "同一区域喷淋任务间隔不能少于 " + minimumIntervalSeconds + " 秒");
        }
    }

    private String normalizeFailureReason(boolean success, String failureReason) {
        if (success) return null;
        String normalized = failureReason == null ? "" : failureReason.trim();
        if (normalized.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "FAILURE_REASON_REQUIRED", "失败回执必须填写失败原因");
        }
        if (normalized.length() > 500) {
            throw new AppException(HttpStatus.BAD_REQUEST, "FAILURE_REASON_TOO_LONG", "失败原因不能超过 500 个字符");
        }
        return normalized;
    }

    private long number(Map<String, Object> row, String key) {
        return ((Number) row.get(key)).longValue();
    }
}
