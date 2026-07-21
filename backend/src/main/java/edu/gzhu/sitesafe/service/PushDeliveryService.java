package edu.gzhu.sitesafe.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.common.PageSpec;
import edu.gzhu.sitesafe.config.PushProperties;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.UserSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class PushDeliveryService {
    private static final Set<String> TYPES = Set.of("LOG", "WEBHOOK");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Z][A-Z0-9_]{1,99}");

    private final JdbcTemplate jdbc;
    private final AuditService audit;
    private final ReportWorkflowService reports;
    private final PushProperties properties;
    private final ObjectMapper objectMapper;

    public PushDeliveryService(JdbcTemplate jdbc, AuditService audit, ReportWorkflowService reports,
                               PushProperties properties, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.audit = audit;
        this.reports = reports;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> channels(long siteId, int page, int pageSize) {
        SecurityUtil.requireSite(siteId);
        PageSpec paging = PageSpec.of(page, pageSize);
        Long total = jdbc.queryForObject("select count(*) from push_channel where site_id=?", Long.class, siteId);
        List<Map<String, Object>> items = jdbc.queryForList(channelSelect()
                        + " where p.site_id=? order by p.updated_at desc,p.id desc limit ? offset ?",
                siteId, paging.pageSize(), paging.offset());
        items.forEach(this::decorateChannel);
        return paging.result(items, total == null ? 0L : total);
    }

    @Transactional
    public Map<String, Object> createChannel(long siteId, String name, String requestedType,
                                             String endpointUrl, String credentialEnvName) {
        SecurityUtil.requireSite(siteId);
        String type = normalizeType(requestedType);
        String endpoint = null;
        String credential = null;
        if ("WEBHOOK".equals(type)) {
            endpoint = validateEndpointSyntax(endpointUrl);
            credential = normalizeCredentialName(credentialEnvName);
        }
        UserSession user = SecurityUtil.currentUser();
        LocalDateTime now = LocalDateTime.now();
        long id = new SimpleJdbcInsert(jdbc)
                .withTableName("push_channel")
                .usingGeneratedKeyColumns("id")
                .usingColumns("site_id", "name", "type", "endpoint_url", "credential_env_name", "enabled",
                        "created_by", "created_at", "updated_at")
                .executeAndReturnKey(new MapSqlParameterSource()
                        .addValue("site_id", siteId)
                        .addValue("name", required(name, "渠道名称", 120))
                        .addValue("type", type)
                        .addValue("endpoint_url", endpoint)
                        .addValue("credential_env_name", credential)
                        .addValue("enabled", true)
                        .addValue("created_by", user.id())
                        .addValue("created_at", Timestamp.valueOf(now))
                        .addValue("updated_at", Timestamp.valueOf(now)))
                .longValue();
        audit.record("PUSH_CHANNEL_CREATE", "PUSH_CHANNEL", id, "创建 " + type + " 推送渠道");
        return findChannel(id);
    }

    @Transactional
    public Map<String, Object> setEnabled(long id, boolean enabled) {
        findChannelForUpdate(id);
        jdbc.update("update push_channel set enabled=?,updated_at=? where id=?",
                enabled, Timestamp.valueOf(LocalDateTime.now()), id);
        audit.record("PUSH_CHANNEL_ENABLE_CHANGE", "PUSH_CHANNEL", id, "启用状态改为 " + enabled);
        return findChannel(id);
    }

    public Map<String, Object> deliveries(long reportId, int page, int pageSize) {
        reports.findReport(reportId);
        PageSpec paging = PageSpec.of(page, pageSize);
        Long total = jdbc.queryForObject("select count(*) from push_delivery where report_id=?", Long.class, reportId);
        List<Map<String, Object>> items = jdbc.queryForList(deliverySelect()
                        + " where d.report_id=? order by d.created_at desc,d.id desc limit ? offset ?",
                reportId, paging.pageSize(), paging.offset());
        return paging.result(items, total == null ? 0L : total);
    }

    public Map<String, Object> deliver(long reportId, long channelId) {
        Map<String, Object> report = reports.findReport(reportId);
        if (!"APPROVED".equals(report.get("status"))) {
            throw new AppException(HttpStatus.CONFLICT, "REPORT_NOT_APPROVED", "报告必须经人工审核通过后才能推送");
        }
        Map<String, Object> channel = findChannel(channelId);
        long reportSite = ((Number) report.get("siteId")).longValue();
        long channelSite = ((Number) channel.get("siteId")).longValue();
        if (reportSite != channelSite) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PUSH_CHANNEL_SITE_MISMATCH", "推送渠道不属于报告工地");
        }
        if (!Boolean.TRUE.equals(channel.get("enabled"))) {
            throw new AppException(HttpStatus.CONFLICT, "PUSH_CHANNEL_DISABLED", "推送渠道已停用");
        }
        UserSession user = SecurityUtil.currentUser();
        LocalDateTime now = LocalDateTime.now();
        long deliveryId = new SimpleJdbcInsert(jdbc)
                .withTableName("push_delivery")
                .usingGeneratedKeyColumns("id")
                .usingColumns("report_id", "channel_id", "status", "attempt_count", "created_by", "created_at")
                .executeAndReturnKey(new MapSqlParameterSource()
                        .addValue("report_id", reportId)
                        .addValue("channel_id", channelId)
                        .addValue("status", "PENDING")
                        .addValue("attempt_count", 0)
                        .addValue("created_by", user.id())
                        .addValue("created_at", Timestamp.valueOf(now)))
                .longValue();

        DeliveryResult result = "LOG".equals(channel.get("type"))
                ? new DeliveryResult("SENT", null, null)
                : sendWebhook(report, channel);
        jdbc.update("update push_delivery set status=?,attempt_count=1,http_status=?,error_message=?,completed_at=? where id=?",
                result.status(), result.httpStatus(), result.errorMessage(), Timestamp.valueOf(LocalDateTime.now()), deliveryId);
        audit.record("PUSH_DELIVERY_" + result.status(), "SAFETY_REPORT", reportId,
                "渠道=" + channel.get("name") + "；deliveryId=" + deliveryId);
        return findDelivery(deliveryId);
    }

    public Map<String, Object> runtimeStatus() {
        List<String> allowed = properties.getAllowedEndpoints() == null ? List.of()
                : properties.getAllowedEndpoints().stream().filter(item -> item != null && !item.isBlank()).toList();
        return Map.of(
                "webhookEnabled", properties.isWebhookEnabled(),
                "allowedEndpointCount", allowed.size(),
                "allowHttpLoopback", properties.isAllowHttpLoopback());
    }

    private DeliveryResult sendWebhook(Map<String, Object> report, Map<String, Object> channel) {
        if (!properties.isWebhookEnabled()) {
            return failed("WEBHOOK_DISABLED", "Webhook 推送未在服务端启用");
        }
        URI endpoint;
        try {
            endpoint = approvedEndpoint(String.valueOf(channel.get("endpointUrl")));
        } catch (AppException ex) {
            return failed(ex.code(), ex.getMessage());
        }
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", "safety.report.approved");
        payload.put("siteId", report.get("siteId"));
        payload.put("reportCode", report.get("code"));
        payload.put("title", report.get("title"));
        payload.put("content", report.get("content"));
        payload.put("reviewedAt", report.get("reviewedAt"));
        byte[] body;
        try {
            body = objectMapper.writeValueAsBytes(payload);
        } catch (JsonProcessingException ex) {
            return failed("PUSH_PAYLOAD_ERROR", "无法生成推送载荷");
        }
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(properties.effectiveRequestTimeoutMs()))
                .header("Content-Type", "application/json")
                .header("X-SiteSafe-Event", "safety.report.approved")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        Object envName = channel.get("credentialEnvName");
        if (envName != null) {
            String token = System.getenv(String.valueOf(envName));
            if (token == null || token.isBlank()) {
                return failed("PUSH_CREDENTIAL_MISSING", "渠道凭据环境变量未配置");
            }
            request.header("Authorization", "Bearer " + token.trim());
        }
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(properties.effectiveConnectTimeoutMs()))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
            HttpResponse<Void> response = client.send(request.build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return new DeliveryResult("SENT", response.statusCode(), null);
            }
            return new DeliveryResult("FAILED", response.statusCode(), "Webhook 返回非成功状态");
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return failed("PUSH_INTERRUPTED", "Webhook 推送被中断");
        } catch (Exception ex) {
            return failed("PUSH_UNREACHABLE", "Webhook 端点不可达");
        }
    }

    private URI approvedEndpoint(String endpointUrl) {
        String normalized = normalizeUrl(endpointUrl);
        List<String> allowed = properties.getAllowedEndpoints() == null ? List.of()
                : properties.getAllowedEndpoints().stream().map(this::normalizeUrl).toList();
        if (!allowed.contains(normalized)) {
            throw new AppException(HttpStatus.CONFLICT, "PUSH_ENDPOINT_NOT_ALLOWED", "Webhook 地址不在服务端白名单中");
        }
        URI uri = parseUri(normalized);
        if ("https".equalsIgnoreCase(uri.getScheme())) return uri;
        if ("http".equalsIgnoreCase(uri.getScheme()) && properties.isAllowHttpLoopback() && isLoopback(uri.getHost())) {
            return uri;
        }
        throw new AppException(HttpStatus.CONFLICT, "PUSH_ENDPOINT_INSECURE", "Webhook 地址必须使用 HTTPS");
    }

    private String validateEndpointSyntax(String value) {
        if (value == null || value.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "PUSH_ENDPOINT_REQUIRED", "Webhook 地址不能为空");
        }
        if (value.trim().length() > 500) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR", "Webhook 地址不能超过 500 个字符");
        }
        URI uri = parseUri(value.trim());
        if (!Set.of("https", "http").contains(uri.getScheme().toLowerCase(Locale.ROOT))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_PUSH_ENDPOINT", "Webhook 地址格式无效");
        }
        return normalizeUrl(value);
    }

    private URI parseUri(String value) {
        try {
            return new URI(value);
        } catch (URISyntaxException ex) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_PUSH_ENDPOINT", "Webhook 地址格式无效");
        }
    }

    private String normalizeUrl(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isLoopback(String host) {
        try {
            InetAddress address = InetAddress.getByName(host);
            return address.isLoopbackAddress();
        } catch (Exception ex) {
            return false;
        }
    }

    private Map<String, Object> findChannel(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(channelSelect() + " where p.id=?", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "PUSH_CHANNEL_NOT_FOUND", "推送渠道不存在");
        Map<String, Object> row = rows.get(0);
        SecurityUtil.requireSite(((Number) row.get("siteId")).longValue());
        decorateChannel(row);
        return row;
    }

    private Map<String, Object> findChannelForUpdate(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select id,site_id as siteId from push_channel where id=? for update", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "PUSH_CHANNEL_NOT_FOUND", "推送渠道不存在");
        Map<String, Object> row = rows.get(0);
        SecurityUtil.requireSite(((Number) row.get("siteId")).longValue());
        return row;
    }

    private Map<String, Object> findDelivery(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList(deliverySelect() + " where d.id=?", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "PUSH_DELIVERY_NOT_FOUND", "推送记录不存在");
        SecurityUtil.requireSite(((Number) rows.get(0).get("siteId")).longValue());
        return rows.get(0);
    }

    private void decorateChannel(Map<String, Object> channel) {
        Object envName = channel.get("credentialEnvName");
        channel.put("credentialConfigured", envName == null
                || (System.getenv(String.valueOf(envName)) != null && !System.getenv(String.valueOf(envName)).isBlank()));
        channel.put("runtimeReady", "LOG".equals(channel.get("type"))
                || (properties.isWebhookEnabled() && approvedWithoutThrow(String.valueOf(channel.get("endpointUrl")))));
    }

    private boolean approvedWithoutThrow(String endpoint) {
        try {
            approvedEndpoint(endpoint);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }

    private String channelSelect() {
        return "select p.id,p.site_id as siteId,p.name,p.type,p.endpoint_url as endpointUrl,"
                + "p.credential_env_name as credentialEnvName,p.enabled,p.created_by as createdBy,"
                + "u.display_name as createdByName,p.created_at as createdAt,p.updated_at as updatedAt "
                + "from push_channel p join app_user u on u.id=p.created_by";
    }

    private String deliverySelect() {
        return "select d.id,d.report_id as reportId,r.site_id as siteId,d.channel_id as channelId,"
                + "p.name as channelName,p.type as channelType,d.status,d.attempt_count as attemptCount,"
                + "d.http_status as httpStatus,d.error_message as errorMessage,d.created_by as createdBy,"
                + "u.display_name as createdByName,d.created_at as createdAt,d.completed_at as completedAt "
                + "from push_delivery d join safety_report r on r.id=d.report_id "
                + "join push_channel p on p.id=d.channel_id join app_user u on u.id=d.created_by";
    }

    private String normalizeType(String value) {
        String type = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_PUSH_CHANNEL_TYPE", "推送渠道类型无效");
        }
        return type;
    }

    private String normalizeCredentialName(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.trim();
        if (!ENV_NAME.matcher(normalized).matches()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_CREDENTIAL_ENV_NAME", "凭据环境变量名称格式无效");
        }
        return normalized;
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

    private DeliveryResult failed(String code, String message) {
        return new DeliveryResult("FAILED", null, code + "：" + message);
    }

    private record DeliveryResult(String status, Integer httpStatus, String errorMessage) {}
}
