package edu.gzhu.sitesafe.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.IntegrationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SprinklerGatewayClient {
    private final IntegrationProperties properties;
    private final IntegrationEndpointPolicy endpointPolicy;
    private final ObjectMapper objectMapper;

    public SprinklerGatewayClient(IntegrationProperties properties,
                                  IntegrationEndpointPolicy endpointPolicy,
                                  ObjectMapper objectMapper) {
        this.properties = properties;
        this.endpointPolicy = endpointPolicy;
        this.objectMapper = objectMapper;
    }

    public DispatchReceipt dispatch(long taskId, long siteId, long zoneId,
                                    String commandId, String reason) {
        IntegrationProperties.SprinklerGateway config = properties.getSprinklerGateway();
        if (config.getMode() == IntegrationProperties.SprinklerGateway.Mode.DEMO) {
            return new DispatchReceipt("DEMO", commandId);
        }
        if (config.getMode() == IntegrationProperties.SprinklerGateway.Mode.DISABLED) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "SPRINKLER_GATEWAY_DISABLED", "喷淋网关未启用");
        }
        URI endpoint = endpointPolicy.resolve(approvedBase(), "/commands/sprinkler");
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("platformCommandId", commandId);
            payload.put("taskId", taskId);
            payload.put("siteId", siteId);
            payload.put("zoneId", zoneId);
            payload.put("action", "START");
            payload.put("reason", reason);
            payload.put("requestedAt", Instant.now());
            HttpRequest request = request(endpoint)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(objectMapper.writeValueAsBytes(payload)))
                    .build();
            HttpResponse<InputStream> response = client().send(request, HttpResponse.BodyHandlers.ofInputStream());
            byte[] body;
            try (InputStream stream = response.body()) {
                body = stream.readNBytes(65537);
            }
            if (body.length > 65536 || response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "SPRINKLER_GATEWAY_REJECTED", "喷淋网关未接受指令");
            }
            JsonNode responseBody = body.length == 0 ? objectMapper.createObjectNode() : objectMapper.readTree(body);
            boolean accepted = responseBody.path("accepted").asBoolean(false);
            if (!accepted) {
                throw new AppException(HttpStatus.BAD_GATEWAY, "SPRINKLER_GATEWAY_REJECTED", "喷淋网关拒绝指令");
            }
            String externalId = responseBody.path("commandId").asText(commandId).trim();
            if (externalId.isEmpty() || externalId.length() > 64) externalId = commandId;
            return new DispatchReceipt("HTTP", externalId);
        } catch (AppException ex) {
            throw ex;
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "SPRINKLER_GATEWAY_INTERRUPTED", "喷淋网关请求被中断");
        } catch (Exception ex) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "SPRINKLER_GATEWAY_UNREACHABLE", "喷淋网关不可达");
        }
    }

    public Map<String, Object> configurationStatus() {
        IntegrationProperties.SprinklerGateway config = properties.getSprinklerGateway();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", config.getMode().name());
        result.put("baseUrl", config.getBaseUrl());
        result.put("credentialConfigured", config.getApiKey() != null && !config.getApiKey().isBlank());
        result.put("callbackConfigured", config.getCallbackToken() != null && config.getCallbackToken().length() >= 16);
        result.put("state", switch (config.getMode()) {
            case DEMO -> "SIMULATED";
            case HTTP -> "CONFIGURED";
            case DISABLED -> "NOT_CONFIGURED";
        });
        return result;
    }

    public Map<String, Object> checkHealth() {
        IntegrationProperties.SprinklerGateway config = properties.getSprinklerGateway();
        if (config.getMode() == IntegrationProperties.SprinklerGateway.Mode.DEMO) {
            return status("SIMULATED", null, "当前使用演示网关，不代表现场设备已接入");
        }
        if (config.getMode() == IntegrationProperties.SprinklerGateway.Mode.DISABLED) {
            return status("NOT_CONFIGURED", null, "喷淋网关未启用");
        }
        try {
            HttpResponse<Void> response = client().send(request(endpointPolicy.resolve(approvedBase(), "/health"))
                    .GET().build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                return status("READY", response.statusCode(), "喷淋网关可达");
            }
            return status("DOWN", response.statusCode(), "喷淋网关健康检查返回非成功状态");
        } catch (AppException ex) {
            return status("MISCONFIGURED", null, ex.getMessage());
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return status("DOWN", null, "喷淋网关健康检查被中断");
        } catch (Exception ex) {
            return status("DOWN", null, "喷淋网关不可达");
        }
    }

    public void verifyCallbackToken(String providedToken) {
        IntegrationProperties.SprinklerGateway config = properties.getSprinklerGateway();
        String expected = config.getCallbackToken();
        if (config.getMode() != IntegrationProperties.SprinklerGateway.Mode.HTTP
                || expected == null || expected.length() < 16 || providedToken == null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8))) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "INVALID_GATEWAY_CALLBACK_TOKEN", "喷淋网关回调认证失败");
        }
    }

    private URI approvedBase() {
        IntegrationProperties.SprinklerGateway config = properties.getSprinklerGateway();
        return endpointPolicy.approvedBase(config.getBaseUrl(), config.getAllowedBaseUrls(),
                config.isAllowHttpLoopback(), "喷淋网关");
    }

    private HttpRequest.Builder request(URI endpoint) {
        IntegrationProperties.SprinklerGateway config = properties.getSprinklerGateway();
        HttpRequest.Builder builder = HttpRequest.newBuilder(endpoint)
                .timeout(Duration.ofMillis(config.effectiveRequestTimeoutMs()))
                .header("Accept", "application/json");
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.header("Authorization", "Bearer " + config.getApiKey().trim());
        }
        return builder;
    }

    private HttpClient client() {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.getSprinklerGateway().effectiveConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    private Map<String, Object> status(String state, Integer httpStatus, String message) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("message", message);
        result.put("checkedAt", Instant.now());
        if (httpStatus != null) result.put("httpStatus", httpStatus);
        return result;
    }

    public record DispatchReceipt(String mode, String externalCommandId) {}
}
