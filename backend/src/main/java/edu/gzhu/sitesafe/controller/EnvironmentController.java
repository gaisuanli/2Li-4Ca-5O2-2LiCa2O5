package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.security.SecurityUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/environment")
public class EnvironmentController {
    private final JdbcTemplate jdbc;
    private final boolean demoDataEnabled;

    public EnvironmentController(JdbcTemplate jdbc,
                                 @Value("${app.demo-data-enabled:true}") boolean demoDataEnabled) {
        this.jdbc = jdbc;
        this.demoDataEnabled = demoDataEnabled;
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary(@RequestParam(defaultValue = "1") long siteId) {
        SecurityUtil.requireSite(siteId);
        List<Map<String, Object>> stations = jdbc.queryForList("select d.id,d.code,d.name,d.zone_id as zoneId,z.name as zoneName,d.connection_status as connectionStatus,d.last_reported_at as lastReportedAt from device d join zone z on z.id=d.zone_id where d.site_id=? and d.type='ENVIRONMENT' order by d.code", siteId);
        for (Map<String, Object> station : stations) {
            long deviceId = ((Number) station.get("id")).longValue();
            station.put("metrics", jdbc.queryForList(
                    "select t.metric_code as code,t.metric_value as `value`,t.unit,t.collected_at as collectedAt "
                            + "from telemetry t where t.device_id=? and t.id=(select t2.id from telemetry t2 "
                            + "where t2.device_id=t.device_id and t2.metric_code=t.metric_code "
                            + "order by t2.collected_at desc,t2.id desc limit 1) order by t.metric_code,t.id",
                    deviceId));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("stations", stations);
        result.put("exceedances", jdbc.queryForList("select a.id,a.title,a.severity,a.status,a.last_occurred_at as occurredAt,z.name as zoneName from alarm a join zone z on z.id=a.zone_id where a.site_id=? and a.source_type='ENVIRONMENT_RULE' order by a.last_occurred_at desc,a.id desc limit 10", siteId));
        Timestamp latestCollectedAt = jdbc.queryForObject(
                "select max(t.collected_at) from telemetry t join device d on d.id=t.device_id where d.site_id=? and d.type='ENVIRONMENT'",
                Timestamp.class, siteId);
        result.put("statisticsAt", latestCollectedAt == null ? LocalDateTime.now() : latestCollectedAt.toLocalDateTime());
        result.put("dataLabel", demoDataEnabled ? "演示数据" : "业务数据");
        return ApiResponse.ok(result);
    }

    @GetMapping("/trend")
    public ApiResponse<List<Map<String, Object>>> trend(@RequestParam long deviceId,
                                                        @RequestParam String metric,
                                                        @RequestParam(defaultValue = "200") int limit) {
        List<Long> siteIds = jdbc.query("select site_id from device where id=?",
                (resultSet, rowNumber) -> resultSet.getLong(1), deviceId);
        if (siteIds.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "DEVICE_NOT_FOUND", "设备不存在");
        SecurityUtil.requireSite(siteIds.get(0));
        int safeLimit = Math.max(1, Math.min(limit, 500));
        return ApiResponse.ok(jdbc.queryForList(
                "select recent.metric_value as `value`,recent.unit,recent.collected_at as collectedAt "
                        + "from (select id,metric_value,unit,collected_at from telemetry "
                        + "where device_id=? and metric_code=? order by collected_at desc,id desc limit ?) recent "
                        + "order by recent.collected_at asc,recent.id asc",
                deviceId, metric, safeLimit));
    }
}
