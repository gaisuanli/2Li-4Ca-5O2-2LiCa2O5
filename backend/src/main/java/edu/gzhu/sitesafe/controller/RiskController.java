package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.realtime.RealtimeHub;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.UserSession;
import edu.gzhu.sitesafe.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/risks")
public class RiskController {
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final RealtimeHub realtime;

    public RiskController(JdbcTemplate jdbc, AuditService audit, RealtimeHub realtime) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.realtime = realtime;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") long siteId,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        SecurityUtil.requireSite(siteId);
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        long offset = (long) (safePage - 1) * safeSize;
        String sql = "select r.id,r.risk_type as riskType,r.confidence,r.model_version as modelVersion,r.occurred_at as occurredAt,r.evidence_url as evidenceUrl,r.status,r.review_note as reviewNote,r.reviewed_at as reviewedAt,c.code as cameraCode,c.name as cameraName,z.id as zoneId,z.name as zoneName,u.display_name as reviewerName from ai_risk r join camera c on c.id=r.camera_id join zone z on z.id=r.zone_id left join app_user u on u.id=r.reviewed_by where r.site_id=?";
        long total;
        List<Map<String, Object>> items;
        if (status != null && !status.isBlank()) {
            total = jdbc.queryForObject("select count(*) from ai_risk where site_id=? and status=?", Long.class, siteId, status);
            items = jdbc.queryForList(sql + " and r.status=? order by r.occurred_at desc,r.id desc limit ? offset ?", siteId, status, safeSize, offset);
        } else {
            total = jdbc.queryForObject("select count(*) from ai_risk where site_id=?", Long.class, siteId);
            items = jdbc.queryForList(sql + " order by r.occurred_at desc,r.id desc limit ? offset ?", siteId, safeSize, offset);
        }
        return ApiResponse.ok(Map.of("items", items, "total", total, "page", safePage, "pageSize", safeSize));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVICE_MANAGER')")
    @Transactional
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateRequest request) {
        var cameras = jdbc.queryForList(
                "select id,code,name,site_id,zone_id from camera where code=?",
                request.cameraCode());
        if (cameras.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "CAMERA_NOT_FOUND", "摄像头不存在");
        }
        Map<String, Object> camera = cameras.get(0);
        long siteId = ((Number) camera.get("site_id")).longValue();
        SecurityUtil.requireSite(siteId);

        Timestamp occurredAt = Timestamp.valueOf(request.occurredAt()
                .atZoneSameInstant(ZoneId.systemDefault()).toLocalDateTime());
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("camera_id", camera.get("id"))
                .addValue("site_id", siteId)
                .addValue("zone_id", camera.get("zone_id"))
                .addValue("risk_type", request.riskType().trim())
                .addValue("confidence", request.confidence())
                .addValue("model_version", request.modelVersion().trim())
                .addValue("occurred_at", occurredAt)
                .addValue("evidence_url", blankToNull(request.evidenceUrl()))
                .addValue("status", "PENDING_REVIEW");
        Number key = new SimpleJdbcInsert(jdbc)
                .withTableName("ai_risk")
                .usingGeneratedKeyColumns("id")
                .executeAndReturnKey(parameters);
        long id = key.longValue();

        audit.record("RISK_CREATE", "AI_RISK", id,
                request.cameraCode() + " / " + request.riskType().trim());
        realtime.publish("risk.created", Map.of(
                "riskId", id,
                "siteId", siteId,
                "zoneId", camera.get("zone_id"),
                "cameraId", camera.get("id"),
                "cameraCode", camera.get("code"),
                "riskType", request.riskType().trim(),
                "confidence", request.confidence()
        ));
        return ApiResponse.ok(find(id));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    @Transactional
    public ApiResponse<Map<String, Object>> review(@PathVariable long id, @Valid @RequestBody ReviewRequest request) {
        var rows = jdbc.queryForList("select r.*,c.name as camera_name,z.name as zone_name from ai_risk r join camera c on c.id=r.camera_id join zone z on z.id=r.zone_id where r.id=?", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "RISK_NOT_FOUND", "风险事件不存在");
        Map<String, Object> risk = rows.get(0);
        SecurityUtil.requireSite(((Number) risk.get("site_id")).longValue());
        if (!"PENDING_REVIEW".equals(risk.get("status"))) throw new AppException(HttpStatus.CONFLICT, "RISK_ALREADY_REVIEWED", "该风险事件已完成复核");
        String action = request.action().toUpperCase();
        String target = switch (action) {
            case "CONFIRM" -> "CONFIRMED";
            case "FALSE_POSITIVE" -> "FALSE_POSITIVE";
            default -> throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_REVIEW_ACTION", "复核操作无效");
        };
        UserSession user = SecurityUtil.currentUser();
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("update ai_risk set status=?,review_note=?,reviewed_by=?,reviewed_at=? where id=? and status='PENDING_REVIEW'",
                target, request.note(), user.id(), Timestamp.valueOf(now), id);
        Long alarmId = null;
        if ("CONFIRMED".equals(target)) {
            String code = "ALM-AI-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String title = risk.get("zone_name") + "确认 AI 风险：" + risk.get("risk_type");
            String description = "风险事件 " + id + "，摄像头 " + risk.get("camera_name") + "，置信度 " + risk.get("confidence") + "，人工复核确认有效";
            jdbc.update("insert into alarm(code,site_id,zone_id,device_id,rule_id,source_type,severity,title,description,status,first_occurred_at,last_occurred_at,occurrences) values(?,?,?,?,null,'AI_RISK','HIGH',?,?,'PENDING',?,?,1)",
                    code, risk.get("site_id"), risk.get("zone_id"), null, title, description, risk.get("occurred_at"), Timestamp.valueOf(now));
            alarmId = jdbc.queryForObject("select id from alarm where code=?", Long.class, code);
            realtime.publish("alarm.created", Map.of("alarmId", alarmId, "siteId", risk.get("site_id"), "severity", "HIGH", "title", title));
        }
        audit.record("RISK_" + action, "AI_RISK", id, request.note());
        realtime.publish("risk.reviewed", Map.of("riskId", id, "siteId", risk.get("site_id"), "status", target));
        var updated = jdbc.queryForMap("select id,risk_type as riskType,confidence,model_version as modelVersion,occurred_at as occurredAt,status,review_note as reviewNote,reviewed_at as reviewedAt from ai_risk where id=?", id);
        if (alarmId != null) updated.put("alarmId", alarmId);
        return ApiResponse.ok(updated);
    }

    public record ReviewRequest(@NotBlank(message = "复核操作不能为空") String action, String note) {}

    public record CreateRequest(
            @NotBlank(message = "摄像头编号不能为空") String cameraCode,
            @NotBlank(message = "风险类型不能为空") @Size(max = 64, message = "风险类型不能超过 64 个字符") String riskType,
            @NotNull(message = "置信度不能为空")
            @DecimalMin(value = "0.0", message = "置信度必须在 0 到 1 之间")
            @DecimalMax(value = "1.0", message = "置信度必须在 0 到 1 之间") BigDecimal confidence,
            @NotBlank(message = "模型版本不能为空") @Size(max = 64, message = "模型版本不能超过 64 个字符") String modelVersion,
            @NotNull(message = "发生时间不能为空") OffsetDateTime occurredAt,
            @Size(max = 500, message = "证据地址不能超过 500 个字符") String evidenceUrl
    ) {}

    private Map<String, Object> find(long id) {
        return jdbc.queryForMap("select r.id,r.risk_type as riskType,r.confidence,r.model_version as modelVersion,r.occurred_at as occurredAt,r.evidence_url as evidenceUrl,r.status,c.id as cameraId,c.code as cameraCode,c.name as cameraName,r.site_id as siteId,r.zone_id as zoneId,z.name as zoneName from ai_risk r join camera c on c.id=r.camera_id join zone z on z.id=r.zone_id where r.id=?", id);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
