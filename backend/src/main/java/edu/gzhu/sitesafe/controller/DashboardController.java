package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.security.SecurityUtil;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final JdbcTemplate jdbc;
    private final boolean demoDataEnabled;

    public DashboardController(JdbcTemplate jdbc,
                               @Value("${app.demo-data-enabled:true}") boolean demoDataEnabled) {
        this.jdbc = jdbc;
        this.demoDataEnabled = demoDataEnabled;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> dashboard(@RequestParam(defaultValue = "1") long siteId) {
        SecurityUtil.requireSite(siteId);
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> counts = jdbc.queryForMap("select count(*) as deviceCount, sum(case when connection_status='ONLINE' then 1 else 0 end) as onlineCount from device where site_id=?", siteId);
        long deviceCount = ((Number) counts.get("deviceCount")).longValue();
        long onlineCount = counts.get("onlineCount") == null ? 0 : ((Number) counts.get("onlineCount")).longValue();
        long openAlarms = jdbc.queryForObject("select count(*) from alarm where site_id=? and status <> 'CLOSED'", Long.class, siteId);
        long pendingRisks = jdbc.queryForObject("select count(*) from ai_risk where site_id=? and status='PENDING_REVIEW'", Long.class, siteId);
        LocalDateTime statisticsAt = latestTimestamp(
                jdbc.queryForObject("select max(last_reported_at) from device where site_id=?", Timestamp.class, siteId),
                jdbc.queryForObject("select max(last_occurred_at) from alarm where site_id=?", Timestamp.class, siteId),
                jdbc.queryForObject("select max(occurred_at) from ai_risk where site_id=?", Timestamp.class, siteId)
        );
        result.put("summary", Map.of(
                "deviceCount", deviceCount,
                "onlineCount", onlineCount,
                "onlineRate", deviceCount == 0 ? 0 : Math.round(onlineCount * 1000.0 / deviceCount) / 10.0,
                "openAlarmCount", openAlarms,
                "pendingRiskCount", pendingRisks,
                "statisticsAt", statisticsAt,
                "dataLabel", demoDataEnabled ? "演示数据" : "业务数据"
        ));
        result.put("alarmsBySeverity", jdbc.queryForList("select severity, count(*) as count from alarm where site_id=? and status<>'CLOSED' group by severity order by severity", siteId));
        result.put("latestAlarms", jdbc.queryForList("select a.id,a.code,a.title,a.severity,a.status,a.last_occurred_at as lastOccurredAt,z.name as zoneName from alarm a join zone z on z.id=a.zone_id where a.site_id=? order by a.last_occurred_at desc,a.id desc limit 5", siteId));
        result.put("deviceTypes", jdbc.queryForList("select type, count(*) as count, sum(case when connection_status='ONLINE' then 1 else 0 end) as onlineCount from device where site_id=? group by type order by count(*) desc", siteId));
        result.put("riskTrend", riskTrend(siteId, statisticsAt.toLocalDate()));
        result.put("generatedAt", LocalDateTime.now());
        return ApiResponse.ok(result);
    }

    private LocalDateTime latestTimestamp(Timestamp... candidates) {
        return java.util.Arrays.stream(candidates)
                .filter(Objects::nonNull)
                .max(Comparator.naturalOrder())
                .map(Timestamp::toLocalDateTime)
                .orElseGet(LocalDateTime::now);
    }

    private List<Map<String, Object>> riskTrend(long siteId, LocalDate anchorDate) {
        LocalDate startDate = anchorDate.minusDays(6);
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (int offset = 0; offset < 7; offset++) counts.put(startDate.plusDays(offset), 0L);
        List<LocalDate> dates = jdbc.query("select occurred_at from ai_risk where site_id=? and occurred_at>=? and occurred_at<?",
                (resultSet, rowNumber) -> resultSet.getTimestamp(1).toLocalDateTime().toLocalDate(),
                siteId, Timestamp.valueOf(startDate.atStartOfDay()), Timestamp.valueOf(anchorDate.plusDays(1).atStartOfDay()));
        dates.forEach(date -> counts.computeIfPresent(date, (ignored, value) -> value + 1));
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("MM-dd");
        List<Map<String, Object>> result = new ArrayList<>();
        counts.forEach((date, count) -> result.add(Map.of("date", date.format(formatter), "count", count)));
        return result;
    }
}
