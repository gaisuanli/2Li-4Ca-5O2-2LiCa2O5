package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.realtime.RealtimeHub;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.UserSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Service
public class AlarmService {
    private static final Set<String> STATUSES = Set.of("PENDING", "PROCESSING", "RESOLVED", "CLOSED");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH");
    private static final Set<String> SOURCES = Set.of(
            "DEVICE_RULE", "ENVIRONMENT_RULE", "AI_RISK", "SYSTEM", "SYSTEM_DEVICE_OFFLINE");
    private static final Map<String, Transition> TRANSITIONS = Map.of(
            "CONFIRM", new Transition("PENDING", "PROCESSING"),
            "RESOLVE", new Transition("PROCESSING", "RESOLVED"),
            "CLOSE", new Transition("RESOLVED", "CLOSED")
    );
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final RealtimeHub realtime;

    public AlarmService(JdbcTemplate jdbc, AuditService audit, RealtimeHub realtime) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.realtime = realtime;
    }

    public Map<String, Object> list(long siteId, String status, String severity, String source, String keyword,
                                    Long zoneId, LocalDateTime from, LocalDateTime to, int page, int pageSize) {
        QueryParts query = buildQuery(siteId, status, severity, source, keyword, zoneId, from, to);
        long total = jdbc.queryForObject("select count(*) from alarm a" + query.where(), Long.class, query.parameters().toArray());
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        long offset = (long) (safePage - 1) * safeSize;
        List<Object> params = new ArrayList<>(query.parameters());
        params.add(safeSize); params.add(offset);
        var items = jdbc.queryForList(alarmSelect() + query.where()
                + alarmOrder() + " limit ? offset ?", params.toArray());
        return Map.of("items", items, "total", total, "page", safePage, "pageSize", safeSize);
    }

    public byte[] exportCsv(long siteId, String status, String severity, String source, String keyword,
                            Long zoneId, LocalDateTime from, LocalDateTime to) {
        QueryParts query = buildQuery(siteId, status, severity, source, keyword, zoneId, from, to);
        List<Map<String, Object>> rows = jdbc.queryForList(alarmSelect() + query.where() + alarmOrder(),
                query.parameters().toArray());
        StringBuilder csv = new StringBuilder("\uFEFF编号,标题,来源,等级,状态,区域,设备,重复次数,首次发生,最近发生\r\n");
        for (Map<String, Object> row : rows) {
            appendCsvRow(csv, Arrays.asList(
                    row.get("code"), row.get("title"), row.get("sourceType"), row.get("severity"), row.get("status"),
                    row.get("zoneName"), row.get("deviceName"), row.get("occurrences"),
                    row.get("firstOccurredAt"), row.get("lastOccurredAt")
            ));
        }
        return csv.toString().getBytes(StandardCharsets.UTF_8);
    }

    private QueryParts buildQuery(long siteId, String status, String severity, String source, String keyword,
                                  Long zoneId, LocalDateTime from, LocalDateTime to) {
        SecurityUtil.requireSite(siteId);
        String normalizedStatus = normalizeFilter(status, STATUSES, "INVALID_ALARM_STATUS", "告警状态无效");
        String normalizedSeverity = normalizeFilter(severity, SEVERITIES, "INVALID_SEVERITY", "告警等级无效");
        String normalizedSource = normalizeFilter(source, SOURCES, "INVALID_ALARM_SOURCE", "告警来源无效");
        if (from != null && to != null && from.isAfter(to)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "开始时间不能晚于结束时间");
        }
        if (zoneId != null) {
            Long count = jdbc.queryForObject("select count(*) from zone where id=? and site_id=?", Long.class, zoneId, siteId);
            if (count == null || count == 0) {
                throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_ZONE", "区域不属于当前工地");
            }
        }

        StringBuilder where = new StringBuilder(" where a.site_id=? ");
        List<Object> params = new ArrayList<>();
        params.add(siteId);
        if (normalizedStatus != null) { where.append("and a.status=? "); params.add(normalizedStatus); }
        if (normalizedSeverity != null) { where.append("and a.severity=? "); params.add(normalizedSeverity); }
        if ("SYSTEM".equals(normalizedSource)) {
            where.append("and a.source_type like 'SYSTEM%' ");
        } else if (normalizedSource != null) {
            where.append("and a.source_type=? ");
            params.add(normalizedSource);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append("and (lower(a.code) like ? or lower(a.title) like ?) ");
            String term = "%" + keyword.trim().toLowerCase() + "%";
            params.add(term);
            params.add(term);
        }
        if (zoneId != null) { where.append("and a.zone_id=? "); params.add(zoneId); }
        if (from != null) { where.append("and a.last_occurred_at>=? "); params.add(Timestamp.valueOf(from)); }
        if (to != null) { where.append("and a.last_occurred_at<=? "); params.add(Timestamp.valueOf(to)); }
        return new QueryParts(where.toString(), params);
    }

    private String normalizeFilter(String value, Set<String> allowed, String code, String message) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim().toUpperCase();
        if (!allowed.contains(normalized)) throw new AppException(HttpStatus.BAD_REQUEST, code, message);
        return normalized;
    }

    private String alarmSelect() {
        return "select a.id,a.code,a.title,a.description,a.source_type as sourceType,a.severity,a.status,a.occurrences,"
                + "a.first_occurred_at as firstOccurredAt,a.last_occurred_at as lastOccurredAt,a.device_id as deviceId,"
                + "d.code as deviceCode,d.name as deviceName,z.id as zoneId,z.name as zoneName "
                + "from alarm a left join device d on d.id=a.device_id join zone z on z.id=a.zone_id";
    }

    private String alarmOrder() {
        return " order by case a.severity when 'HIGH' then 1 when 'MEDIUM' then 2 else 3 end,"
                + "a.last_occurred_at desc,a.id desc";
    }

    private void appendCsvRow(StringBuilder csv, List<Object> values) {
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) csv.append(',');
            csv.append(csvCell(values.get(index)));
        }
        csv.append("\r\n");
    }

    private String csvCell(Object value) {
        String text = value == null ? "" : String.valueOf(value);
        if (!text.isEmpty() && "=+-@".indexOf(text.charAt(0)) >= 0) text = "'" + text;
        return "\"" + text.replace("\"", "\"\"") + "\"";
    }

    private record QueryParts(String where, List<Object> parameters) {}

    public Map<String, Object> detail(long id) {
        var rows = jdbc.queryForList("select a.id,a.code,a.site_id as siteId,a.zone_id as zoneId,z.name as zoneName,a.device_id as deviceId,d.code as deviceCode,d.name as deviceName,a.rule_id as ruleId,r.name as ruleName,a.source_type as sourceType,a.severity,a.title,a.description,a.status,a.occurrences,a.first_occurred_at as firstOccurredAt,a.last_occurred_at as lastOccurredAt from alarm a join zone z on z.id=a.zone_id left join device d on d.id=a.device_id left join alarm_rule r on r.id=a.rule_id where a.id=?", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "ALARM_NOT_FOUND", "告警不存在");
        Map<String, Object> alarm = new LinkedHashMap<>(rows.get(0));
        SecurityUtil.requireSite(((Number) alarm.get("siteId")).longValue());
        alarm.put("actions", jdbc.queryForList("select aa.id,aa.action,aa.from_status as fromStatus,aa.to_status as toStatus,aa.note,aa.created_at as createdAt,u.display_name as operatorName from alarm_action aa join app_user u on u.id=aa.operator_id where aa.alarm_id=? order by aa.created_at", id));
        return alarm;
    }

    @Transactional
    public Map<String, Object> transition(long id, String action, String note) {
        Map<String, Object> alarm = detail(id);
        String normalized = action == null ? "" : action.toUpperCase();
        Transition transition = TRANSITIONS.get(normalized);
        if (transition == null) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_ALARM_ACTION", "不支持的告警操作");
        String current = String.valueOf(alarm.get("status"));
        if (!transition.from.equals(current)) {
            throw new AppException(HttpStatus.CONFLICT, "INVALID_ALARM_TRANSITION", "告警当前状态为 " + current + "，不能执行 " + normalized);
        }
        UserSession user = SecurityUtil.currentUser();
        LocalDateTime now = LocalDateTime.now();
        int changed = jdbc.update("update alarm set status=? where id=? and status=?", transition.to, id, transition.from);
        if (changed != 1) throw new AppException(HttpStatus.CONFLICT, "ALARM_STATE_CHANGED", "告警状态已被其他操作更新，请刷新后重试");
        jdbc.update("insert into alarm_action(alarm_id,operator_id,action,from_status,to_status,note,created_at) values(?,?,?,?,?,?,?)",
                id, user.id(), normalized, transition.from, transition.to, note, Timestamp.valueOf(now));
        audit.record("ALARM_" + normalized, "ALARM", id, note == null ? "" : note);
        realtime.publish("alarm.status.changed", Map.of("alarmId", id, "siteId", alarm.get("siteId"), "fromStatus", transition.from, "toStatus", transition.to));
        return detail(id);
    }

    private record Transition(String from, String to) {}
}
