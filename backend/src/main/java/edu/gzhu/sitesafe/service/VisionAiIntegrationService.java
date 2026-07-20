package edu.gzhu.sitesafe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.IntegrationProperties;
import edu.gzhu.sitesafe.realtime.RealtimeHub;
import edu.gzhu.sitesafe.security.SecurityUtil;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class VisionAiIntegrationService {
    private static final int MAX_IMAGE_BYTES = 8 * 1024 * 1024;

    private final JdbcTemplate jdbc;
    private final IntegrationProperties properties;
    private final IntegrationEndpointPolicy endpointPolicy;
    private final ObjectMapper objectMapper;
    private final AuditService audit;
    private final RealtimeHub realtime;

    public VisionAiIntegrationService(JdbcTemplate jdbc, IntegrationProperties properties,
                                      IntegrationEndpointPolicy endpointPolicy, ObjectMapper objectMapper,
                                      AuditService audit, RealtimeHub realtime) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.endpointPolicy = endpointPolicy;
        this.objectMapper = objectMapper;
        this.audit = audit;
        this.realtime = realtime;
    }

    public Map<String, Object> configurationStatus() {
        IntegrationProperties.VisionAi config = properties.getVisionAi();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("enabled", config.isEnabled());
        result.put("baseUrl", config.getBaseUrl());
        result.put("credentialConfigured", config.getApiKey() != null && !config.getApiKey().isBlank());
        result.put("state", config.isEnabled() ? "CONFIGURED" : "NOT_CONFIGURED");
        return result;
    }

    public Map<String, Object> checkHealth() {
        IntegrationProperties.VisionAi config = properties.getVisionAi();
        if (!config.isEnabled()) {
            return status("NOT_CONFIGURED", null, "视觉 AI 适配器未启用");
        }
        try {
            URI endpoint = endpointPolicy.resolve(approvedBase(), "/health");
            HttpResponse<InputStream> response = client(config.effectiveConnectTimeoutMs())
                    .send(request(endpoint, "", config.effectiveRequestTimeoutMs()).GET().build(),
                            HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(65537);
            }
            if (body.length > 65536) return status("DEGRADED", response.statusCode(), "健康检查响应过大");
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return status("DOWN", response.statusCode(), "健康检查返回非成功状态");
            }
            JsonNode payload = objectMapper.readTree(body);
            Map<String, Object> result = status("READY", response.statusCode(), "视觉 AI 服务可达");
            result.put("adapterMode", payload.path("mode").asText("UNKNOWN"));
            result.put("modelFilePresent", payload.path("modelFilePresent").asBoolean(false));
            result.put("notice", payload.path("notice").asText(""));
            return result;
        } catch (AppException ex) {
            return status("MISCONFIGURED", null, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return status("DOWN", null, "健康检查被中断");
        } catch (Exception ex) {
            return status("DOWN", null, "视觉 AI 服务不可达");
        }
    }

    @Transactional
    public Map<String, Object> infer(long siteId, long cameraId, String imageBase64) {
        SecurityUtil.requireSite(siteId);
        IntegrationProperties.VisionAi config = properties.getVisionAi();
        if (!config.isEnabled()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "VISION_AI_DISABLED", "视觉 AI 适配器未启用");
        }
        validateImage(imageBase64);
        List<Map<String, Object>> cameras = jdbc.queryForList(
                "select id,code,site_id as siteId,zone_id as zoneId from camera where id=?", cameraId);
        if (cameras.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "CAMERA_NOT_FOUND", "摄像头不存在");
        }
        Map<String, Object> camera = cameras.get(0);
        if (((Number) camera.get("siteId")).longValue() != siteId) {
            throw new AppException(HttpStatus.BAD_REQUEST, "CAMERA_SITE_MISMATCH", "摄像头不属于指定工地");
        }
        JsonNode response = invokeInfer(imageBase64);
        JsonNode detections = response.path("detections");
        if (!detections.isArray()) {
            throw new AppException(HttpStatus.BAD_GATEWAY, "VISION_AI_INVALID_RESPONSE", "视觉 AI 返回格式无效");
        }
        String modelVersion = clean(response.path("modelVersion").asText("unknown"), 64, "unknown");
        LocalDateTime now = LocalDateTime.now();
        List<Long> riskIds = new ArrayList<>();
        for (JsonNode detection : detections) {
            String riskType = clean(detection.path("riskType").asText(""), 64, null);
            double confidence = detection.path("confidence").asDouble(-1);
            if (riskType == null || !Double.isFinite(confidence) || confidence < 0 || confidence > 1) continue;
            long riskId = new SimpleJdbcInsert(jdbc)
                    .withTableName("ai_risk")
                    .usingGeneratedKeyColumns("id")
                    .usingColumns("camera_id", "site_id", "zone_id", "risk_type", "confidence",
                            "model_version", "occurred_at", "status")
                    .executeAndReturnKey(new MapSqlParameterSource()
                            .addValue("camera_id", cameraId)
                            .addValue("site_id", siteId)
                            .addValue("zone_id", camera.get("zoneId"))
                            .addValue("risk_type", riskType)
                            .addValue("confidence", BigDecimal.valueOf(confidence))
                            .addValue("model_version", modelVersion)
                            .addValue("occurred_at", Timestamp.valueOf(now))
                            .addValue("status", "PENDING_REVIEW"))
                    .longValue();
            riskIds.add(riskId);
            audit.record("VISION_AI_RISK_INGEST", "AI_RISK", riskId,
                    "camera=" + camera.get("code") + "；model=" + modelVersion);
            realtime.publish("risk.created", Map.of(
                    "riskId", riskId, "siteId", siteId, "cameraId", cameraId,
                    "riskType", riskType, "confidence", confidence));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", response.path("mode").asText("UNKNOWN"));
        result.put("modelVersion", modelVersion);
        result.put("detectionCount", detections.size());
        result.put("acceptedRiskCount", riskIds.size());
        result.put("riskIds", riskIds);
        result.put("reviewRequired", true);
        return result;
    }

    private JsonNode invokeInfer(String imageBase64) {
        IntegrationProperties.VisionAi config = properties.getVisionAi();
        URI endpoint = endpointPolicy.resolve(approvedBase(), "/infer");
        try {
            byte[] payload = objectMapper.writeValueAsBytes(Map.of("imageBase64", imageBase64));
            HttpRequest request = request(endpoint, config.getApiKey(), config.effectiveRequestTimeoutMs())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            HttpResponse<InputStream> response = client(config.effectiveConnectTimeoutMs())
                    .send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(1024 * 1024 + 1);
            }
            if (body.length > 1024 * 1024) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "VISION_AI_INVALID_RESPONSE", "视觉 AI 响应过大");
            }
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "VISION_AI_REQUEST_FAILED", "视觉 AI 推理失败");
            }
            return objectMapper.readTree(body);
        } catch (AppException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "VISION_AI_INTERRUPTED", "视觉 AI 请求被中断");
        } catch (Exception ex) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "VISION_AI_UNREACHABLE", "视觉 AI 服务不可达");
        }
    }

    private HttpRequest.Builder request(URI endpoint, String apiKey, int timeoutMs) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(timeoutMs))
                .header("Accept", "application/json");
        if (apiKey != null && !apiKey.isBlank()) {
            builder.header("Authorization", "Bearer " + apiKey.trim());
        }
        return builder;
    }

    private HttpClient client(int connectTimeoutMs) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectTimeoutMs))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private URI approvedBase() {
        IntegrationProperties.VisionAi config = properties.getVisionAi();
        return endpointPolicy.approvedBase(config.getBaseUrl(), config.getAllowedBaseUrls(),
                config.isAllowHttpLoopback(), "视觉 AI");
    }

    private void validateImage(String imageBase64) {
        if (imageBase64 == null || imageBase64.isBlank()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "IMAGE_REQUIRED", "imageBase64 不能为空");
        }
        try {
            byte[] decoded = Base64.getDecoder().decode(imageBase64);
            if (decoded.length == 0 || decoded.length > MAX_IMAGE_BYTES) {
                throw new AppException(HttpStatus.PAYLOAD_TOO_LARGE, "IMAGE_TOO_LARGE", "图片不能超过 8 MB");
            }
        } catch (IllegalArgumentException ex) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", "imageBase64 格式无效");
        }
    }

    private Map<String, Object> status(String state, Integer httpStatus, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("message", message);
        if (httpStatus != null) result.put("httpStatus", httpStatus);
        result.put("checkedAt", LocalDateTime.now());
        return result;
    }

    private String clean(String value, int maximum, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        return normalized.length() > maximum ? normalized.substring(0, maximum) : normalized;
    }
}
