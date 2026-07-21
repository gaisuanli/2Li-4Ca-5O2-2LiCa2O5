package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.realtime.RealtimeHub;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class TelemetryService {
    private static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    private static final Duration MAX_TELEMETRY_AGE = Duration.ofDays(30);
    private static final Map<String, Map<String, Range>> DEVICE_METRIC_RANGES = Map.of(
            "TOWER_CRANE", Map.ofEntries(
                    Map.entry("weight", new Range(0, 300)),
                    Map.entry("windSpeed", new Range(0, 80)),
                    Map.entry("rotation", new Range(-360, 360)),
                    Map.entry("height", new Range(-20, 1000)),
                    Map.entry("amplitude", new Range(0, 200)),
                    Map.entry("moment", new Range(0, 10000)),
                    Map.entry("obliquity", new Range(-30, 30))
            ),
            "ENVIRONMENT", Map.ofEntries(
                    Map.entry("pm25", new Range(0, 2000)),
                    Map.entry("pm10", new Range(0, 3000)),
                    Map.entry("noise", new Range(0, 180)),
                    Map.entry("temperature", new Range(-60, 100)),
                    Map.entry("humidity", new Range(0, 100))
            ),
            "ELEVATOR", Map.ofEntries(
                    Map.entry("load", new Range(0, 10000)),
                    Map.entry("floor", new Range(-10, 300)),
                    Map.entry("speed", new Range(0, 20)),
                    Map.entry("direction", new Range(0, 3)),
                    Map.entry("doorStatus", new Range(0, 1)),
                    Map.entry("limitStatus", new Range(0, 1))
            ),
            "FORMWORK", Map.ofEntries(
                    Map.entry("axialForce", new Range(-100000, 100000)),
                    Map.entry("displacement", new Range(-1000, 1000)),
                    Map.entry("settlement", new Range(-1000, 1000)),
                    Map.entry("xAngle", new Range(-30, 30)),
                    Map.entry("yAngle", new Range(-30, 30)),
                    Map.entry("pressure", new Range(0, 10000))
            ),
            "FOUNDATION_PIT", Map.ofEntries(
                    Map.entry("horizontalDisplacement", new Range(-1000, 1000)),
                    Map.entry("settlement", new Range(-1000, 1000)),
                    Map.entry("axialForce", new Range(-100000, 100000)),
                    Map.entry("xAngle", new Range(-30, 30)),
                    Map.entry("yAngle", new Range(-30, 30)),
                    Map.entry("waterLevel", new Range(-1000, 1000)),
                    Map.entry("earthPressure", new Range(0, 10000)),
                    Map.entry("strain", new Range(-100000, 100000)),
                    Map.entry("settlementRate", new Range(-1000, 1000))
            )
    );
    private static final Set<String> KNOWN_METRICS = DEVICE_METRIC_RANGES.values().stream()
            .flatMap(ranges -> ranges.keySet().stream())
            .collect(Collectors.toUnmodifiableSet());

    private final JdbcTemplate jdbc;
    private final RealtimeHub realtime;
    private final DeviceConnectivityService connectivity;

    public TelemetryService(JdbcTemplate jdbc, RealtimeHub realtime, DeviceConnectivityService connectivity) {
        this.jdbc = jdbc;
        this.realtime = realtime;
        this.connectivity = connectivity;
    }

    @Transactional
    public IngestResult ingest(TelemetryRequest request) {
        if (!"1.0".equals(request.protocolVersion())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_PROTOCOL", "仅支持协议版本 1.0");
        }
        if (!"telemetry".equals(request.messageType())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_MESSAGE_TYPE", "messageType 必须为 telemetry");
        }
        validateCollectedAt(request.collectedAt());
        var devices = jdbc.queryForList("select id,code,name,type,site_id,zone_id,enabled from device where code=?", request.deviceCode());
        if (devices.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "UNKNOWN_DEVICE", "设备编号不存在");
        Map<String, Object> device = devices.get(0);
        if (!Boolean.TRUE.equals(device.get("enabled"))) throw new AppException(HttpStatus.CONFLICT, "DEVICE_DISABLED", "设备已停用，拒绝接收数据");

        long deviceId = ((Number) device.get("id")).longValue();
        long siteId = ((Number) device.get("site_id")).longValue();
        long zoneId = ((Number) device.get("zone_id")).longValue();
        Timestamp collectedAt = Timestamp.from(request.collectedAt().toInstant());
        int inserted = 0;
        int duplicates = 0;
        List<Long> createdAlarmIds = new ArrayList<>();

        String deviceType = String.valueOf(device.get("type"));
        for (Metric metric : request.metrics()) {
            validateMetric(deviceType, metric);
            try {
                jdbc.update("insert into telemetry(message_id,device_id,metric_code,metric_value,unit,collected_at) values(?,?,?,?,?,?)",
                        request.messageId(), deviceId, metric.code(), metric.value(), metric.unit(), collectedAt);
                inserted++;
                evaluateRules(device, siteId, zoneId, metric, collectedAt, createdAlarmIds);
            } catch (DuplicateKeyException ex) {
                duplicates++;
            }
        }
        connectivity.recordHeartbeat(deviceId, siteId, zoneId, collectedAt);
        Map<String, Object> event = Map.of("siteId", siteId, "zoneId", zoneId, "deviceId", deviceId,
                "deviceCode", request.deviceCode(), "messageId", request.messageId(), "collectedAt", request.collectedAt());
        realtime.publish("telemetry.updated", event);
        return new IngestResult(request.messageId(), inserted, duplicates, createdAlarmIds);
    }

    private void validateMetric(String deviceType, Metric metric) {
        if (!KNOWN_METRICS.contains(metric.code())) {
            throw new AppException(HttpStatus.BAD_REQUEST, "UNKNOWN_METRIC", "未定义的指标：" + metric.code());
        }
        Map<String, Range> ranges = DEVICE_METRIC_RANGES.get(deviceType);
        if (ranges == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_DEVICE_TELEMETRY",
                    "设备类型 " + deviceType + " 不支持遥测上报");
        }
        Range range = ranges.get(metric.code());
        if (range == null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "METRIC_DEVICE_TYPE_MISMATCH",
                    "指标 " + metric.code() + " 不适用于设备类型 " + deviceType);
        }
        double value = metric.value().doubleValue();
        if (!Double.isFinite(value) || value < range.min || value > range.max) {
            throw new AppException(HttpStatus.BAD_REQUEST, "METRIC_OUT_OF_RANGE", metric.code() + " 超出允许范围");
        }
    }

    private void validateCollectedAt(OffsetDateTime collectedAt) {
        Instant now = Instant.now();
        Instant instant = collectedAt.toInstant();
        if (instant.isAfter(now.plus(MAX_FUTURE_SKEW)) || instant.isBefore(now.minus(MAX_TELEMETRY_AGE))) {
            throw new AppException(HttpStatus.BAD_REQUEST, "TELEMETRY_TIME_INVALID",
                    "采集时间不能早于当前时间 30 天或晚于当前时间 5 分钟");
        }
    }

    private void evaluateRules(Map<String, Object> device, long siteId, long zoneId, Metric metric, Timestamp collectedAt, List<Long> createdAlarmIds) {
        var rules = jdbc.queryForList("select id,name,source_type,metric_code,operator,threshold_value,severity,scope_type,scope_id,suppression_seconds from alarm_rule where enabled=true and metric_code=?", metric.code());
        for (Map<String, Object> rule : rules) {
            if (!scopeMatches(rule, device, siteId)) continue;
            BigDecimal threshold = (BigDecimal) rule.get("threshold_value");
            if (!compare(metric.value(), threshold, String.valueOf(rule.get("operator")))) continue;
            long ruleId = ((Number) rule.get("id")).longValue();
            int suppression = ((Number) rule.get("suppression_seconds")).intValue();
            Timestamp windowStart = Timestamp.from(collectedAt.toInstant().minusSeconds(suppression));
            var existing = jdbc.queryForList("select id from alarm where device_id=? and rule_id=? and status<>'CLOSED' and last_occurred_at>=? order by last_occurred_at desc limit 1", device.get("id"), ruleId, windowStart);
            String description = metric.code() + " 读数 " + metric.value().stripTrailingZeros().toPlainString() + " " + (metric.unit() == null ? "" : metric.unit())
                    + "，触发规则阈值 " + threshold.stripTrailingZeros().toPlainString();
            if (!existing.isEmpty()) {
                long alarmId = ((Number) existing.get(0).get("id")).longValue();
                jdbc.update("update alarm set last_occurred_at=?,occurrences=occurrences+1,description=? where id=?", collectedAt, description, alarmId);
                realtime.publish("alarm.updated", Map.of("alarmId", alarmId, "siteId", siteId, "occurrencesDelta", 1));
                continue;
            }
            String code = "ALM-" + collectedAt.toLocalDateTime().toLocalDate().toString().replace("-", "") + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
            String title = device.get("name") + "触发" + rule.get("name");
            jdbc.update("insert into alarm(code,site_id,zone_id,device_id,rule_id,source_type,severity,title,description,status,first_occurred_at,last_occurred_at,occurrences) values(?,?,?,?,?,?,?,?,?,'PENDING',?,?,1)",
                    code, siteId, zoneId, device.get("id"), ruleId, rule.get("source_type"), rule.get("severity"), title, description, collectedAt, collectedAt);
            Long alarmId = jdbc.queryForObject("select id from alarm where code=?", Long.class, code);
            createdAlarmIds.add(alarmId);
            realtime.publish("alarm.created", Map.of("alarmId", alarmId, "code", code, "siteId", siteId, "severity", rule.get("severity"), "title", title));
        }
    }

    private boolean scopeMatches(Map<String, Object> rule, Map<String, Object> device, long siteId) {
        String type = String.valueOf(rule.get("scope_type"));
        Object scopeId = rule.get("scope_id");
        return switch (type) {
            case "SITE" -> scopeId != null && ((Number) scopeId).longValue() == siteId;
            case "DEVICE" -> scopeId != null && ((Number) scopeId).longValue() == ((Number) device.get("id")).longValue();
            case "TYPE" -> scopeId != null
                    && ((Number) scopeId).longValue() == siteId
                    && typeRuleMatchesDevice(rule, device);
            default -> false;
        };
    }

    private boolean typeRuleMatchesDevice(Map<String, Object> rule, Map<String, Object> device) {
        String metricCode = String.valueOf(rule.get("metric_code"));
        return DeviceMetricCatalog.supports(String.valueOf(device.get("type")), metricCode);
    }

    private boolean compare(BigDecimal value, BigDecimal threshold, String operator) {
        int comparison = value.compareTo(threshold);
        return switch (operator) {
            case ">" -> comparison > 0;
            case ">=" -> comparison >= 0;
            case "<" -> comparison < 0;
            case "<=" -> comparison <= 0;
            case "=" -> comparison == 0;
            default -> false;
        };
    }

    public record TelemetryRequest(
            @NotBlank(message = "protocolVersion 不能为空") String protocolVersion,
            @NotBlank(message = "messageId 不能为空") String messageId,
            @NotBlank(message = "deviceCode 不能为空") String deviceCode,
            @NotBlank(message = "messageType 不能为空") String messageType,
            @NotNull(message = "collectedAt 不能为空") OffsetDateTime collectedAt,
            @NotEmpty(message = "metrics 不能为空") List<@Valid Metric> metrics
    ) {}

    public record Metric(@NotBlank(message = "指标编码不能为空") String code,
                         @NotNull(message = "指标值不能为空") BigDecimal value,
                         String unit) {}
    public record IngestResult(String messageId, int insertedMetrics, int duplicateMetrics, List<Long> createdAlarmIds) {}
    private record Range(double min, double max) {}
}
