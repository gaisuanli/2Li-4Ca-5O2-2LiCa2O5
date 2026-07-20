package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.PageSpec;
import edu.gzhu.sitesafe.common.TraceContext;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.UserSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class AuditService {
    private final JdbcTemplate jdbc;

    public AuditService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void record(String action, String objectType, Object objectId, String detail) {
        UserSession user = SecurityUtil.currentUser();
        jdbc.update("insert into audit_log(user_id, username, action, object_type, object_id, detail, trace_id, created_at) values(?,?,?,?,?,?,?,?)",
                user.id(), user.username(), action, objectType, objectId == null ? null : objectId.toString(), detail,
                TraceContext.currentId(), Timestamp.valueOf(LocalDateTime.now()));
    }

    public Map<String, Object> list(long siteId, int page, int pageSize) {
        SecurityUtil.requireSite(siteId);
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,user_id as userId,username,action,object_type as objectType,object_id as objectId,detail,trace_id as traceId,created_at as createdAt "
                        + "from audit_log order by created_at desc,id desc");
        Map<String, Set<Long>> scopeCache = new HashMap<>();
        List<Map<String, Object>> visible = new ArrayList<>();
        for (Map<String, Object> row : rows) {
            String key = row.get("objectType") + ":" + row.get("objectId") + ":" + row.get("userId");
            Set<Long> scopes = scopeCache.computeIfAbsent(key, ignored -> resolveSites(row));
            if (!scopes.contains(siteId)) continue;
            row.put("siteScope", scopes.stream().sorted().map(String::valueOf).collect(Collectors.joining(",")));
            row.remove("userId");
            visible.add(row);
        }
        PageSpec paging = PageSpec.of(page, pageSize);
        int from = (int) Math.min(paging.offset(), visible.size());
        int to = Math.min(from + paging.pageSize(), visible.size());
        return paging.result(visible.subList(from, to), visible.size());
    }

    private Set<Long> resolveSites(Map<String, Object> row) {
        Long objectId = parseId(row.get("objectId"));
        String objectType = String.valueOf(row.get("objectType"));
        if (objectId != null) {
            Set<Long> resolved = switch (objectType) {
                case "DEVICE" -> singleSite("select site_id from device where id=?", objectId);
                case "ALARM" -> singleSite("select site_id from alarm where id=?", objectId);
                case "AI_RISK" -> singleSite("select site_id from ai_risk where id=?", objectId);
                case "SPRINKLER_TASK" -> singleSite("select site_id from sprinkler_task where id=?", objectId);
                case "AI_AGENT_CONVERSATION" -> singleSite(
                        "select site_id from ai_agent_conversation where id=?", objectId);
                case "KNOWLEDGE_DOCUMENT" -> singleSite(
                        "select site_id from knowledge_document where id=?", objectId);
                case "REPORT_TEMPLATE" -> singleSite(
                        "select site_id from report_template where id=?", objectId);
                case "SAFETY_REPORT" -> singleSite(
                        "select site_id from safety_report where id=?", objectId);
                case "PUSH_CHANNEL" -> singleSite(
                        "select site_id from push_channel where id=?", objectId);
                case "ALARM_RULE" -> singleSite(
                        "select case when ar.scope_type='DEVICE' then d.site_id else ar.scope_id end "
                                + "from alarm_rule ar left join device d on d.id=ar.scope_id and ar.scope_type='DEVICE' where ar.id=?",
                        objectId);
                case "APP_USER" -> userSites(objectId);
                case "AI_AGENT_PROVIDER_CONFIG" -> userSites(objectId);
                default -> Set.of();
            };
            if (!resolved.isEmpty()) return resolved;
        }
        Long actorId = row.get("userId") instanceof Number number ? number.longValue() : null;
        return actorId == null ? Set.of() : userSites(actorId);
    }

    private Set<Long> singleSite(String sql, long id) {
        List<Long> ids = jdbc.query(sql, (rs, rowNum) -> rs.getObject(1) == null ? null : rs.getLong(1), id);
        return ids.isEmpty() || ids.get(0) == null ? Set.of() : Set.of(ids.get(0));
    }

    private Set<Long> userSites(long userId) {
        List<String> scopes = jdbc.query("select site_scope from app_user where id=?", (rs, rowNum) -> rs.getString(1), userId);
        if (scopes.isEmpty() || scopes.get(0) == null) return Set.of();
        try {
            return Arrays.stream(scopes.get(0).split(","))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .map(Long::parseLong)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (NumberFormatException ex) {
            return Set.of();
        }
    }

    private Long parseId(Object value) {
        if (value == null) return null;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
