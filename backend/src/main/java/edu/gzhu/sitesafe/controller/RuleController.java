package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.common.PageSpec;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.service.AuditService;
import edu.gzhu.sitesafe.service.DeviceMetricCatalog;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/rules")
public class RuleController {
    private static final Set<String> SOURCE_TYPES = Set.of("DEVICE_RULE", "ENVIRONMENT_RULE");
    private final JdbcTemplate jdbc;
    private final AuditService audit;

    public RuleController(JdbcTemplate jdbc, AuditService audit) {
        this.jdbc = jdbc;
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ApiResponse<Map<String, Object>> list(@RequestParam long siteId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Boolean enabled,
                                                 @RequestParam(required = false) String sourceType,
                                                 @RequestParam(required = false) String metricCode,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        SecurityUtil.requireSite(siteId);
        StringBuilder where = new StringBuilder(
                " where ((scope_type in ('SITE','TYPE') and scope_id=?)"
                        + " or (scope_type='DEVICE' and exists (select 1 from device d where d.id=alarm_rule.scope_id and d.site_id=?))) ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(siteId);
        parameters.add(siteId);
        if (keyword != null && !keyword.isBlank()) {
            where.append("and lower(name) like ? ");
            parameters.add("%" + keyword.trim().toLowerCase() + "%");
        }
        if (enabled != null) {
            where.append("and enabled=? ");
            parameters.add(enabled);
        }
        if (sourceType != null && !sourceType.isBlank()) {
            where.append("and source_type=? ");
            parameters.add(sourceType.trim());
        }
        if (metricCode != null && !metricCode.isBlank()) {
            where.append("and metric_code=? ");
            parameters.add(metricCode.trim());
        }

        long total = jdbc.queryForObject("select count(*) from alarm_rule" + where,
                Long.class, parameters.toArray());
        PageSpec paging = PageSpec.of(page, pageSize);
        parameters.add(paging.pageSize());
        parameters.add(paging.offset());
        List<Map<String, Object>> items = jdbc.queryForList(
                ruleSelect() + where + "order by updated_at desc,id desc limit ? offset ?",
                parameters.toArray());
        items.forEach(this::decorateTargetDeviceType);
        return ApiResponse.ok(paging.result(items, total));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody RuleRequest request) {
        Long scopeId = validateAndResolveScope(request);
        jdbc.update("insert into alarm_rule(name,source_type,metric_code,operator,threshold_value,severity,scope_type,scope_id,enabled,suppression_seconds,updated_at) values(?,?,?,?,?,?,?,?,?,?,?)",
                request.name(), request.sourceType(), request.metricCode(), request.operator(), request.thresholdValue(), request.severity(), request.scopeType(), scopeId, true, request.suppressionSeconds(), Timestamp.valueOf(LocalDateTime.now()));
        Long id = jdbc.queryForObject("select max(id) from alarm_rule", Long.class);
        audit.record("RULE_CREATE", "ALARM_RULE", id, request.name());
        return ApiResponse.ok(find(id));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @Valid @RequestBody RuleRequest request) {
        requireRuleAccess(id);
        Long scopeId = validateAndResolveScope(request);
        int changed = jdbc.update("update alarm_rule set name=?,source_type=?,metric_code=?,operator=?,threshold_value=?,severity=?,scope_type=?,scope_id=?,suppression_seconds=?,updated_at=? where id=?",
                request.name(), request.sourceType(), request.metricCode(), request.operator(), request.thresholdValue(), request.severity(), request.scopeType(), scopeId, request.suppressionSeconds(), Timestamp.valueOf(LocalDateTime.now()), id);
        if (changed == 0) throw new AppException(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "规则不存在");
        audit.record("RULE_UPDATE", "ALARM_RULE", id, request.name());
        return ApiResponse.ok(find(id));
    }

    @PatchMapping("/{id}/enabled")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ApiResponse<Void> enabled(@PathVariable long id, @RequestBody Map<String, Boolean> body) {
        Boolean enabled = body.get("enabled");
        if (enabled == null) throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "enabled 不能为空");
        requireRuleAccess(id);
        int changed = jdbc.update("update alarm_rule set enabled=?,updated_at=? where id=?", enabled, Timestamp.valueOf(LocalDateTime.now()), id);
        if (changed == 0) throw new AppException(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "规则不存在");
        audit.record("RULE_ENABLE_CHANGE", "ALARM_RULE", id, "启用状态改为 " + enabled);
        return ApiResponse.okMessage("规则状态已更新");
    }

    private Map<String, Object> find(long id) {
        var rows = jdbc.queryForList(ruleSelect() + " where id=?", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "规则不存在");
        return decorateTargetDeviceType(rows.get(0));
    }

    private Long validateAndResolveScope(RuleRequest request) {
        if (!SOURCE_TYPES.contains(request.sourceType())) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SOURCE_TYPE", "规则来源类型无效");
        if (!DeviceMetricCatalog.isKnownMetric(request.metricCode())) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_METRIC", "规则指标编码无效");
        String expectedSourceType = DeviceMetricCatalog.sourceTypeFor(request.metricCode());
        if (!expectedSourceType.equals(request.sourceType())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "RULE_METRIC_SOURCE_MISMATCH", "指标与规则来源类型不匹配");
        }
        if (!List.of(">", ">=", "<", "<=", "=").contains(request.operator())) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_OPERATOR", "规则运算符无效");
        if (!List.of("LOW", "MEDIUM", "HIGH").contains(request.severity())) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SEVERITY", "告警等级无效");
        if (!List.of("SITE", "DEVICE", "TYPE").contains(request.scopeType())) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "规则范围无效");
        Long resolvedScopeId = request.scopeId();
        if ("SITE".equals(request.scopeType())) {
            if (request.scopeId() == null) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "工地范围规则必须指定工地");
            List<Long> sites = jdbc.query("select id from site where id=?", (rs, row) -> rs.getLong(1), request.scopeId());
            if (sites.isEmpty()) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "规则指定的工地不存在");
            SecurityUtil.requireSite(request.scopeId());
        } else if ("DEVICE".equals(request.scopeType())) {
            if (request.scopeId() == null) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "设备范围规则必须指定设备");
            List<Map<String, Object>> devices = jdbc.queryForList("select site_id,type from device where id=?", request.scopeId());
            if (devices.isEmpty()) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "规则指定的设备不存在");
            SecurityUtil.requireSite(((Number) devices.get(0).get("site_id")).longValue());
            if (!DeviceMetricCatalog.supports(String.valueOf(devices.get(0).get("type")), request.metricCode())) {
                throw new AppException(HttpStatus.BAD_REQUEST, "RULE_METRIC_DEVICE_MISMATCH", "指标不适用于规则指定的设备");
            }
        } else {
            if (DeviceMetricCatalog.uniqueDeviceTypeFor(request.metricCode()).isEmpty()) {
                throw new AppException(HttpStatus.BAD_REQUEST, "RULE_METRIC_TYPE_AMBIGUOUS",
                        "该指标适用于多个设备类型，请改用设备范围规则");
            }
            if (resolvedScopeId == null) {
                Set<Long> siteIds = SecurityUtil.currentUser().siteIds();
                if (siteIds.size() != 1) {
                    throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "设备类型范围规则必须指定工地");
                }
                resolvedScopeId = siteIds.iterator().next();
            }
            List<Long> sites = jdbc.query("select id from site where id=?", (rs, row) -> rs.getLong(1), resolvedScopeId);
            if (sites.isEmpty()) throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SCOPE", "规则指定的工地不存在");
            SecurityUtil.requireSite(resolvedScopeId);
        }
        return resolvedScopeId;
    }

    private void requireRuleAccess(long id) {
        var rules = jdbc.queryForList("select scope_type,scope_id from alarm_rule where id=?", id);
        if (rules.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "RULE_NOT_FOUND", "规则不存在");
        Map<String, Object> rule = rules.get(0);
        Object scopeId = rule.get("scope_id");
        if (scopeId == null) throw new AppException(HttpStatus.FORBIDDEN, "SITE_SCOPE_DENIED", "无权访问该工地数据");
        if ("DEVICE".equals(String.valueOf(rule.get("scope_type")))) {
            List<Long> siteIds = jdbc.query("select site_id from device where id=?", (rs, row) -> rs.getLong(1), scopeId);
            if (siteIds.isEmpty()) throw new AppException(HttpStatus.FORBIDDEN, "SITE_SCOPE_DENIED", "无权访问该工地数据");
            SecurityUtil.requireSite(siteIds.get(0));
            return;
        }
        SecurityUtil.requireSite(((Number) scopeId).longValue());
    }

    private String ruleSelect() {
        return "select id,name,source_type as sourceType,metric_code as metricCode,operator,threshold_value as thresholdValue,severity,scope_type as scopeType,scope_id as scopeId,"
                + "enabled,suppression_seconds as suppressionSeconds,updated_at as updatedAt from alarm_rule";
    }

    private Map<String, Object> decorateTargetDeviceType(Map<String, Object> rule) {
        if ("TYPE".equals(String.valueOf(rule.get("scopeType")))) {
            DeviceMetricCatalog.uniqueDeviceTypeFor(String.valueOf(rule.get("metricCode")))
                    .ifPresent(type -> rule.put("targetDeviceType", type));
        }
        return rule;
    }

    public record RuleRequest(@NotBlank(message = "规则名称不能为空") String name,
                              @NotBlank(message = "来源类型不能为空") String sourceType,
                              @NotBlank(message = "指标编码不能为空") String metricCode,
                              @NotBlank(message = "运算符不能为空") String operator,
                              @NotNull(message = "阈值不能为空") BigDecimal thresholdValue,
                              @NotBlank(message = "告警等级不能为空") String severity,
                              @NotBlank(message = "适用范围不能为空") String scopeType,
                              Long scopeId,
                              @NotNull(message = "抑制时间不能为空") @DecimalMin(value = "30", message = "抑制时间不能少于 30 秒") Integer suppressionSeconds) {}
}
