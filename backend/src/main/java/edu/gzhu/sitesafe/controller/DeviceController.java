package edu.gzhu.sitesafe.controller;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.realtime.RealtimeHub;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/devices")
public class DeviceController {
    private static final Set<String> DEVICE_TYPES = Set.of(
            "TOWER_CRANE", "ELEVATOR", "FORMWORK", "FOUNDATION_PIT",
            "ENVIRONMENT", "SPRINKLER", "CAMERA"
    );
    private static final Set<String> CONNECTION_STATUSES = Set.of("ONLINE", "OFFLINE");
    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final RealtimeHub realtime;
    private final ObjectMapper objectMapper;

    public DeviceController(JdbcTemplate jdbc, AuditService audit, RealtimeHub realtime, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.realtime = realtime;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") long siteId,
                                                 @RequestParam(required = false) Long zoneId,
                                                 @RequestParam(required = false) String type,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        SecurityUtil.requireSite(siteId);
        StringBuilder where = new StringBuilder(" where d.site_id=? ");
        List<Object> params = new ArrayList<>();
        params.add(siteId);
        if (zoneId != null) { where.append("and d.zone_id=? "); params.add(zoneId); }
        if (type != null && !type.isBlank()) { where.append("and d.type=? "); params.add(type); }
        if (status != null && !status.isBlank()) { where.append("and d.connection_status=? "); params.add(status); }
        if (keyword != null && !keyword.isBlank()) { where.append("and (lower(d.code) like ? or lower(d.name) like ?) "); String term = "%" + keyword.toLowerCase() + "%"; params.add(term); params.add(term); }
        long total = jdbc.queryForObject("select count(*) from device d" + where, Long.class, params.toArray());
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        long offset = (long) (safePage - 1) * safeSize;
        params.add(safeSize); params.add(offset);
        var items = jdbc.queryForList("select d.id,d.code,d.name,d.type,d.site_id as siteId,d.zone_id as zoneId,z.name as zoneName,d.location,d.enabled,d.connection_status as connectionStatus,d.last_reported_at as lastReportedAt,d.config_json as configJson from device d join zone z on z.id=d.zone_id" + where + "order by d.type,d.code,d.id limit ? offset ?", params.toArray());
        return ApiResponse.ok(Map.of("items", items, "total", total, "page", safePage, "pageSize", safeSize));
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        var rows = jdbc.queryForList("select d.id,d.code,d.name,d.type,d.site_id as siteId,s.name as siteName,d.zone_id as zoneId,z.name as zoneName,d.location,d.enabled,d.connection_status as connectionStatus,d.last_reported_at as lastReportedAt,d.config_json as configJson from device d join site s on s.id=d.site_id join zone z on z.id=d.zone_id where d.id=?", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在");
        SecurityUtil.requireSite(((Number) rows.get(0).get("siteId")).longValue());
        return ApiResponse.ok(rows.get(0));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVICE_MANAGER')")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody DeviceRequest request) {
        validateRequest(request);
        String configJson = normalizeConfigJson(request.configJson());
        try {
            jdbc.update("insert into device(code,name,type,site_id,zone_id,location,enabled,connection_status,config_json) values(?,?,?,?,?,?,?,?,?)",
                    request.code(), request.name(), request.type(), request.siteId(), request.zoneId(), request.location(), true, "OFFLINE", configJson);
        } catch (DuplicateKeyException ex) {
            throw new AppException(HttpStatus.CONFLICT, "DEVICE_CODE_EXISTS", "设备编号已存在");
        }
        Long id = jdbc.queryForObject("select id from device where code=?", Long.class, request.code());
        audit.record("DEVICE_CREATE", "DEVICE", id, "新增设备 " + request.code());
        return detail(id);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAnyRole('ADMIN','DEVICE_MANAGER')")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id, @Valid @RequestBody DeviceRequest request) {
        Map<String, Object> existing = detail(id).data();
        validateRequest(request);
        String configJson = normalizeConfigJson(request.configJson());
        try {
            int changed = jdbc.update("update device set code=?,name=?,type=?,site_id=?,zone_id=?,location=?,config_json=? where id=?",
                    request.code(), request.name(), request.type(), request.siteId(), request.zoneId(), request.location(), configJson, id);
            if (changed == 0) throw new AppException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在");
        } catch (DuplicateKeyException ex) {
            throw new AppException(HttpStatus.CONFLICT, "DEVICE_CODE_EXISTS", "设备编号已存在");
        }
        audit.record("DEVICE_UPDATE", "DEVICE", id, "更新设备 " + request.code());
        realtime.publish("device.updated", Map.of(
                "deviceId", id,
                "siteId", request.siteId(),
                "previousSiteId", existing.get("siteId")
        ));
        return detail(id);
    }

    @PatchMapping("/{id}/enabled")
    @PreAuthorize("hasAnyRole('ADMIN','DEVICE_MANAGER')")
    public ApiResponse<Void> enabled(@PathVariable long id, @Valid @RequestBody EnableRequest request) {
        Map<String, Object> device = detail(id).data();
        jdbc.update("update device set enabled=? where id=?", request.enabled(), id);
        audit.record("DEVICE_ENABLE_CHANGE", "DEVICE", id, "启用状态改为 " + request.enabled());
        realtime.publish("device.status.changed", Map.of("deviceId", id, "enabled", request.enabled(), "siteId", device.get("siteId")));
        return ApiResponse.okMessage("设备启用状态已更新");
    }

    @PatchMapping("/{id}/connection")
    @PreAuthorize("hasAnyRole('ADMIN','DEVICE_MANAGER')")
    public ApiResponse<Void> connection(@PathVariable long id, @Valid @RequestBody ConnectionRequest request) {
        if (!CONNECTION_STATUSES.contains(request.status())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_CONNECTION_STATUS", "连接状态仅支持 ONLINE 或 OFFLINE");
        }
        Map<String, Object> device = detail(id).data();
        jdbc.update("update device set connection_status=?,last_reported_at=? where id=?", request.status(), Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record("DEVICE_CONNECTION_CHANGE", "DEVICE", id, "连接状态改为 " + request.status());
        realtime.publish("device.status.changed", Map.of("deviceId", id, "connectionStatus", request.status(), "siteId", device.get("siteId")));
        return ApiResponse.okMessage("设备连接状态已更新");
    }

    private void validateRequest(DeviceRequest request) {
        SecurityUtil.requireSite(request.siteId());
        if (!DEVICE_TYPES.contains(request.type())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_TYPE", "设备类型无效");
        }
        List<Long> zoneSites = jdbc.query("select site_id from zone where id=?",
                (resultSet, rowNumber) -> resultSet.getLong(1), request.zoneId());
        if (zoneSites.isEmpty() || zoneSites.get(0).longValue() != request.siteId()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "ZONE_SITE_MISMATCH", "区域不存在或不属于指定工地");
        }
    }

    private String normalizeConfigJson(String configJson) {
        String value = configJson == null || configJson.isBlank() ? "{}" : configJson.trim();
        try {
            JsonNode node = objectMapper.readTree(value);
            if (node == null || !node.isObject()) {
                throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_CONFIG", "扩展参数必须是 JSON 对象");
            }
            return node.toString();
        } catch (JsonProcessingException ex) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_DEVICE_CONFIG", "扩展参数不是有效的 JSON");
        }
    }

    public record DeviceRequest(@NotBlank(message = "设备编号不能为空") String code,
                                @NotBlank(message = "设备名称不能为空") String name,
                                @NotBlank(message = "设备类型不能为空") String type,
                                @NotNull(message = "工地不能为空") Long siteId,
                                @NotNull(message = "区域不能为空") Long zoneId,
                                String location,
                                String configJson) {}
    public record EnableRequest(@NotNull(message = "启用状态不能为空") Boolean enabled) {}
    public record ConnectionRequest(@NotBlank(message = "连接状态不能为空") String status) {}
}
