package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.common.PageSpec;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.service.TelemetryService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/telemetry")
public class TelemetryController {
    private final TelemetryService service;
    private final JdbcTemplate jdbc;

    public TelemetryController(TelemetryService service, JdbcTemplate jdbc) {
        this.service = service;
        this.jdbc = jdbc;
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','DEVICE_MANAGER')")
    public ApiResponse<TelemetryService.IngestResult> ingest(@Valid @RequestBody TelemetryService.TelemetryRequest request) {
        checkDevice(request.deviceCode());
        return ApiResponse.ok(service.ingest(request));
    }

    @GetMapping("/latest")
    public ApiResponse<List<Map<String, Object>>> latest(@RequestParam long deviceId) {
        checkDevice(deviceId);
        return ApiResponse.ok(jdbc.queryForList(
                "select t.metric_code as code,t.metric_value as `value`,t.unit,t.collected_at as collectedAt "
                        + "from telemetry t where t.device_id=? and t.id=(select t2.id from telemetry t2 "
                        + "where t2.device_id=t.device_id and t2.metric_code=t.metric_code "
                        + "order by t2.collected_at desc,t2.id desc limit 1) order by t.metric_code,t.id",
                deviceId));
    }

    @GetMapping("/history")
    public ApiResponse<Map<String, Object>> history(
            @RequestParam long deviceId,
            @RequestParam String metric,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "100") int pageSize) {
        checkDevice(deviceId);
        validateTimeRange(from, to);
        StringBuilder where = new StringBuilder(" where device_id=? and metric_code=? ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(deviceId);
        parameters.add(metric);
        if (from != null) {
            where.append("and collected_at>=? ");
            parameters.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            where.append("and collected_at<=? ");
            parameters.add(Timestamp.valueOf(to));
        }
        long total = jdbc.queryForObject("select count(*) from telemetry" + where,
                Long.class, parameters.toArray());
        PageSpec paging = PageSpec.of(page, pageSize);
        parameters.add(paging.pageSize());
        parameters.add(paging.offset());
        List<Map<String, Object>> items = jdbc.queryForList(
                "select metric_value as `value`,unit,collected_at as collectedAt,message_id as messageId "
                        + "from telemetry" + where + "order by collected_at desc,id desc limit ? offset ?",
                parameters.toArray());
        return ApiResponse.ok(paging.result(items, total));
    }

    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> trend(@RequestParam long deviceId,
                                                        @RequestParam String metric,
                                                        @RequestParam(defaultValue = "200") int limit) {
        checkDevice(deviceId);
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return ApiResponse.ok(jdbc.queryForList(
                "select recent.metric_value as `value`,recent.unit,recent.collected_at as collectedAt "
                        + "from (select id,metric_value,unit,collected_at from telemetry "
                        + "where device_id=? and metric_code=? order by collected_at desc,id desc limit ?) recent "
                        + "order by recent.collected_at asc,recent.id asc",
                deviceId, metric, safeLimit));
    }

    private void validateTimeRange(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "开始时间不能晚于结束时间");
        }
    }

    private void checkDevice(long deviceId) {
        var rows = jdbc.queryForList("select site_id from device where id=?", deviceId);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在");
        SecurityUtil.requireSite(((Number) rows.get(0).get("site_id")).longValue());
    }

    private void checkDevice(String deviceCode) {
        var rows = jdbc.queryForList("select site_id from device where code=?", deviceCode);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "UNKNOWN_DEVICE", "设备编号不存在");
        SecurityUtil.requireSite(((Number) rows.get(0).get("site_id")).longValue());
    }
}
