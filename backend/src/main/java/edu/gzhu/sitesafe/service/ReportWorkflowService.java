package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.common.PageSpec;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.UserSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ReportWorkflowService {
    private static final Set<String> REPORT_STATUSES = Set.of(
            "DRAFT", "PENDING_REVIEW", "APPROVED", "REJECTED");
    private static final Set<String> TEMPLATE_FIELDS = Set.of(
            "siteName", "generatedAt", "deviceTotal", "onlineDeviceTotal",
            "activeAlarmTotal", "highAlarmTotal", "pendingRiskTotal", "pendingSprinklerTotal");
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{([A-Za-z][A-Za-z0-9]*)}}?");
    private static final DateTimeFormatter DISPLAY_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public ReportWorkflowService(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    public Map<String, Object> templates(long siteId, Boolean enabled, int page, int pageSize) {
        SecurityUtil.requireSite(siteId);
        String where = " where t.site_id=? " + (enabled == null ? "" : "and t.enabled=? ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(siteId);
        if (enabled != null) parameters.add(enabled);
        Long total = jdbc.queryForObject("select count(*) from report_template t" + where,
                Long.class, parameters.toArray());
        PageSpec paging = PageSpec.of(page, pageSize);
        parameters.add(paging.pageSize());
        parameters.add(paging.offset());
        List<Map<String, Object>> items = jdbc.queryForList(templateSelect() + where
                + "order by t.updated_at desc,t.id desc limit ? offset ?", parameters.toArray());
        return paging.result(items, total == null ? 0L : total);
    }

    @Transactional
    public Map<String, Object> createTemplate(long siteId, String name, String description, String bodyTemplate) {
        SecurityUtil.requireSite(siteId);
        String body = normalizeTemplate(bodyTemplate);
        UserSession user = SecurityUtil.currentUser();
        LocalDateTime now = LocalDateTime.now();
        long id = new SimpleJdbcInsert(jdbc)
                .withTableName("report_template")
                .usingGeneratedKeyColumns("id")
                .usingColumns("site_id", "name", "description", "body_template", "enabled", "created_by",
                        "updated_by", "created_at", "updated_at")
                .executeAndReturnKey(new MapSqlParameterSource()
                        .addValue("site_id", siteId)
                        .addValue("name", required(name, "模板名称", 120))
                        .addValue("description", optional(description, 500))
                        .addValue("body_template", body)
                        .addValue("enabled", true)
                        .addValue("created_by", user.id())
                        .addValue("updated_by", user.id())
                        .addValue("created_at", Timestamp.valueOf(now))
                        .addValue("updated_at", Timestamp.valueOf(now)))
                .longValue();
        audit.record("REPORT_TEMPLATE_CREATE", "REPORT_TEMPLATE", id, "创建报告模板");
        return findTemplate(id);
    }

    @Transactional
    public Map<String, Object> updateTemplate(long id, String name, String description, String bodyTemplate) {
        Map<String, Object> current = findTemplateForUpdate(id);
        UserSession user = SecurityUtil.currentUser();
        jdbc.update("update report_template set name=?,description=?,body_template=?,updated_by=?,updated_at=? where id=?",
                required(name, "模板名称", 120), optional(description, 500), normalizeTemplate(bodyTemplate),
                user.id(), Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record("REPORT_TEMPLATE_UPDATE", "REPORT_TEMPLATE", id, "更新报告模板");
        return findTemplate(id);
    }

    @Transactional
    public Map<String, Object> setTemplateEnabled(long id, boolean enabled) {
        findTemplateForUpdate(id);
        UserSession user = SecurityUtil.currentUser();
        jdbc.update("update report_template set enabled=?,updated_by=?,updated_at=? where id=?",
                enabled, user.id(), Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record("REPORT_TEMPLATE_ENABLE_CHANGE", "REPORT_TEMPLATE", id, "启用状态改为 " + enabled);
        return findTemplate(id);
    }

    public Map<String, Object> reports(long siteId, String requestedStatus, int page, int pageSize) {
        SecurityUtil.requireSite(siteId);
        StringBuilder where = new StringBuilder(" where r.site_id=? ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(siteId);
        if (requestedStatus != null && !requestedStatus.isBlank()) {
            String status = requestedStatus.trim().toUpperCase(Locale.ROOT);
            if (!REPORT_STATUSES.contains(status)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_REPORT_STATUS", "报告状态无效");
            }
            where.append("and r.status=? ");
            parameters.add(status);
        }
        Long total = jdbc.queryForObject("select count(*) from safety_report r" + where,
                Long.class, parameters.toArray());
        PageSpec paging = PageSpec.of(page, pageSize);
        parameters.add(paging.pageSize());
        parameters.add(paging.offset());
        List<Map<String, Object>> items = jdbc.queryForList(reportSelect() + where
                + "order by r.updated_at desc,r.id desc limit ? offset ?", parameters.toArray());
        return paging.result(items, total == null ? 0L : total);
    }

    @Transactional
    public Map<String, Object> generate(long siteId, long templateId, String requestedTitle) {
        SecurityUtil.requireSite(siteId);
        Map<String, Object> template = findTemplate(templateId);
        if (((Number) template.get("siteId")).longValue() != siteId) {
            throw new AppException(HttpStatus.BAD_REQUEST, "TEMPLATE_SITE_MISMATCH", "报告模板不属于指定工地");
        }
        if (!Boolean.TRUE.equals(template.get("enabled"))) {
            throw new AppException(HttpStatus.CONFLICT, "REPORT_TEMPLATE_DISABLED", "报告模板已停用");
        }
        Map<String, String> values = snapshot(siteId);
        String content = render(String.valueOf(template.get("bodyTemplate")), values);
        String defaultTitle = values.get("siteName") + "安全运行报告 · "
                + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"));
        String title = requestedTitle == null || requestedTitle.isBlank()
                ? defaultTitle : required(requestedTitle, "报告标题", 160);
        UserSession user = SecurityUtil.currentUser();
        LocalDateTime now = LocalDateTime.now();
        String code = "RPT-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase(Locale.ROOT);
        long id = new SimpleJdbcInsert(jdbc)
                .withTableName("safety_report")
                .usingGeneratedKeyColumns("id")
                .usingColumns("code", "site_id", "template_id", "title", "content", "status",
                        "created_by", "created_at", "updated_at")
                .executeAndReturnKey(new MapSqlParameterSource()
                        .addValue("code", code)
                        .addValue("site_id", siteId)
                        .addValue("template_id", templateId)
                        .addValue("title", title)
                        .addValue("content", content)
                        .addValue("status", "DRAFT")
                        .addValue("created_by", user.id())
                        .addValue("created_at", Timestamp.valueOf(now))
                        .addValue("updated_at", Timestamp.valueOf(now)))
                .longValue();
        audit.record("REPORT_GENERATE", "SAFETY_REPORT", id, "从模板生成报告草稿");
        return findReport(id);
    }

    @Transactional
    public Map<String, Object> updateReport(long id, String title, String content) {
        Map<String, Object> report = findReportForUpdate(id);
        requireReportStatus(report, Set.of("DRAFT", "REJECTED"), "报告当前状态不能编辑");
        jdbc.update("update safety_report set title=?,content=?,status='DRAFT',reviewed_by=null,"
                        + "review_note=null,reviewed_at=null,updated_at=? where id=?",
                required(title, "报告标题", 160), required(content, "报告正文", 50000),
                Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record("REPORT_UPDATE", "SAFETY_REPORT", id, "更新报告草稿");
        return findReport(id);
    }

    @Transactional
    public Map<String, Object> submit(long id) {
        Map<String, Object> report = findReportForUpdate(id);
        requireReportStatus(report, Set.of("DRAFT"), "只有草稿可以提交审核");
        jdbc.update("update safety_report set status='PENDING_REVIEW',updated_at=? where id=?",
                Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record("REPORT_SUBMIT", "SAFETY_REPORT", id, "提交报告人工审核");
        return findReport(id);
    }

    @Transactional
    public Map<String, Object> review(long id, String requestedAction, String requestedNote) {
        Map<String, Object> report = findReportForUpdate(id);
        requireReportStatus(report, Set.of("PENDING_REVIEW"), "报告不在待审核状态");
        String action = requestedAction == null ? "" : requestedAction.trim().toUpperCase(Locale.ROOT);
        if (!Set.of("APPROVE", "REJECT").contains(action)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_ACTION", "审核操作无效");
        }
        String note = optional(requestedNote, 500);
        if ("REJECT".equals(action) && note == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "REVIEW_NOTE_REQUIRED", "驳回时必须填写审核意见");
        }
        String status = "APPROVE".equals(action) ? "APPROVED" : "REJECTED";
        UserSession user = SecurityUtil.currentUser();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("update safety_report set status=?,reviewed_by=?,review_note=?,reviewed_at=?,updated_at=? where id=?",
                status, user.id(), note, Timestamp.valueOf(now), Timestamp.valueOf(now), id);
        audit.record("REPORT_" + action, "SAFETY_REPORT", id,
                status + (note == null ? "" : "；" + note));
        return findReport(id);
    }

    public Map<String, Object> findReport(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(reportSelect() + " where r.id=?", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "报告不存在");
        Map<String, Object> row = rows.get(0);
        SecurityUtil.requireSite(((Number) row.get("siteId")).longValue());
        return row;
    }

    private Map<String, Object> findReportForUpdate(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,site_id as siteId,status from safety_report where id=? for update", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "REPORT_NOT_FOUND", "报告不存在");
        Map<String, Object> row = rows.get(0);
        SecurityUtil.requireSite(((Number) row.get("siteId")).longValue());
        return row;
    }

    private Map<String, Object> findTemplate(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(templateSelect() + " where t.id=?", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "REPORT_TEMPLATE_NOT_FOUND", "报告模板不存在");
        Map<String, Object> row = rows.get(0);
        SecurityUtil.requireSite(((Number) row.get("siteId")).longValue());
        return row;
    }

    private Map<String, Object> findTemplateForUpdate(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,site_id as siteId from report_template where id=? for update", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "REPORT_TEMPLATE_NOT_FOUND", "报告模板不存在");
        Map<String, Object> row = rows.get(0);
        SecurityUtil.requireSite(((Number) row.get("siteId")).longValue());
        return row;
    }

    private Map<String, String> snapshot(long siteId) {
        String siteName = jdbc.queryForObject("select name from site where id=?", String.class, siteId);
        Long devices = jdbc.queryForObject("select count(*) from device where site_id=?", Long.class, siteId);
        Long online = jdbc.queryForObject("select count(*) from device where site_id=? and enabled=true and connection_status='ONLINE'", Long.class, siteId);
        Long activeAlarms = jdbc.queryForObject("select count(*) from alarm where site_id=? and status in ('PENDING','PROCESSING')", Long.class, siteId);
        Long highAlarms = jdbc.queryForObject("select count(*) from alarm where site_id=? and status in ('PENDING','PROCESSING') and severity='HIGH'", Long.class, siteId);
        Long pendingRisks = jdbc.queryForObject("select count(*) from ai_risk where site_id=? and status='PENDING_REVIEW'", Long.class, siteId);
        Long pendingSprinklers = jdbc.queryForObject("select count(*) from sprinkler_task where site_id=? and status in ('CREATED','DISPATCHED')", Long.class, siteId);
        Map<String, String> values = new LinkedHashMap<>();
        values.put("siteName", siteName == null ? "当前工地" : siteName);
        values.put("generatedAt", LocalDateTime.now().format(DISPLAY_TIME));
        values.put("deviceTotal", String.valueOf(devices == null ? 0 : devices));
        values.put("onlineDeviceTotal", String.valueOf(online == null ? 0 : online));
        values.put("activeAlarmTotal", String.valueOf(activeAlarms == null ? 0 : activeAlarms));
        values.put("highAlarmTotal", String.valueOf(highAlarms == null ? 0 : highAlarms));
        values.put("pendingRiskTotal", String.valueOf(pendingRisks == null ? 0 : pendingRisks));
        values.put("pendingSprinklerTotal", String.valueOf(pendingSprinklers == null ? 0 : pendingSprinklers));
        return values;
    }

    private String render(String template, Map<String, String> values) {
        String rendered = template;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            rendered = rendered.replace("{{" + entry.getKey() + "}}", entry.getValue());
        }
        return rendered;
    }

    private String normalizeTemplate(String value) {
        String body = required(value, "模板正文", 50000);
        Matcher matcher = PLACEHOLDER.matcher(body);
        while (matcher.find()) {
            if (!TEMPLATE_FIELDS.contains(matcher.group(1))) {
                throw new AppException(HttpStatus.BAD_REQUEST, "UNKNOWN_TEMPLATE_FIELD",
                        "模板包含不支持的字段：" + matcher.group(1));
            }
        }
        return body;
    }

    private void requireReportStatus(Map<String, Object> report, Set<String> allowed, String message) {
        if (!allowed.contains(String.valueOf(report.get("status")))) {
            throw new AppException(HttpStatus.CONFLICT, "REPORT_STATE_CONFLICT", message);
        }
    }

    private String templateSelect() {
        return "select t.id,t.site_id as siteId,t.name,t.description,t.body_template as bodyTemplate,t.enabled,"
                + "t.created_by as createdBy,c.display_name as createdByName,t.updated_by as updatedBy,"
                + "u.display_name as updatedByName,t.created_at as createdAt,t.updated_at as updatedAt "
                + "from report_template t join app_user c on c.id=t.created_by join app_user u on u.id=t.updated_by";
    }

    private String reportSelect() {
        return "select r.id,r.code,r.site_id as siteId,r.template_id as templateId,t.name as templateName,"
                + "r.title,r.content,r.status,r.created_by as createdBy,c.display_name as createdByName,"
                + "r.reviewed_by as reviewedBy,v.display_name as reviewedByName,r.review_note as reviewNote,"
                + "r.created_at as createdAt,r.updated_at as updatedAt,r.reviewed_at as reviewedAt,"
                + "(select count(*) from push_delivery d where d.report_id=r.id) as deliveryCount "
                + "from safety_report r left join report_template t on t.id=r.template_id "
                + "join app_user c on c.id=r.created_by left join app_user v on v.id=r.reviewed_by";
    }

    private String required(String value, String label, int maximum) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", label + "不能为空");
        }
        if (normalized.length() > maximum) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", label + "不能超过 " + maximum + " 个字符");
        }
        return normalized;
    }

    private String optional(String value, int maximum) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (normalized.length() > maximum) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "字段不能超过 " + maximum + " 个字符");
        }
        return normalized;
    }
}
