package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.IntegrationProperties;
import edu.gzhu.sitesafe.security.SecurityUtil;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class IntegrationStatusService {
    private static final Set<String> TYPES = Set.of(
            "VIDEO", "VISION_AI", "SPRINKLER_GATEWAY", "PRODUCTION_MONITORING");

    private final JdbcTemplate jdbc;
    private final IntegrationProperties properties;
    private final IntegrationEndpointPolicy endpointPolicy;
    private final VisionAiIntegrationService visionAi;
    private final SprinklerGatewayClient sprinklerGateway;
    private final PushDeliveryService pushDelivery;
    private final MeterRegistry meterRegistry;

    public IntegrationStatusService(JdbcTemplate jdbc, IntegrationProperties properties,
                                    IntegrationEndpointPolicy endpointPolicy,
                                    VisionAiIntegrationService visionAi,
                                    SprinklerGatewayClient sprinklerGateway,
                                    PushDeliveryService pushDelivery,
                                    MeterRegistry meterRegistry) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.endpointPolicy = endpointPolicy;
        this.visionAi = visionAi;
        this.sprinklerGateway = sprinklerGateway;
        this.pushDelivery = pushDelivery;
        this.meterRegistry = meterRegistry;
    }

    public Map<String, Object> status(long siteId) {
        SecurityUtil.requireSite(siteId);
        List<Map<String, Object>> integrations = new ArrayList<>();
        integrations.add(videoConfiguration(siteId));
        integrations.add(integration("VISION_AI", "视觉 AI", visionAi.configurationStatus()));
        integrations.add(integration("SPRINKLER_GATEWAY", "喷淋网关", sprinklerGateway.configurationStatus()));
        integrations.add(integration("PRODUCTION_MONITORING", "生产监控", monitoringStatus()));
        return Map.of(
                "items", integrations,
                "push", pushDelivery.runtimeStatus(),
                "checkedAt", Instant.now());
    }

    public Map<String, Object> check(long siteId, String requestedType) {
        SecurityUtil.requireSite(siteId);
        String type = requestedType == null ? "" : requestedType.trim().toUpperCase(Locale.ROOT);
        if (!TYPES.contains(type)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_INTEGRATION_TYPE", "集成类型无效");
        }
        Map<String, Object> result = switch (type) {
            case "VIDEO" -> checkVideo(siteId);
            case "VISION_AI" -> visionAi.checkHealth();
            case "SPRINKLER_GATEWAY" -> sprinklerGateway.checkHealth();
            case "PRODUCTION_MONITORING" -> checkMonitoring();
            default -> throw new IllegalStateException("Unexpected integration type");
        };
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("type", type);
        response.putAll(result);
        return response;
    }

    private Map<String, Object> videoConfiguration(long siteId) {
        Long total = jdbc.queryForObject("select count(*) from camera where site_id=?", Long.class, siteId);
        Long configured = jdbc.queryForObject(
                "select count(*) from camera where site_id=? and stream_url is not null and trim(stream_url)<>''",
                Long.class, siteId);
        Long ready = jdbc.queryForObject(
                "select count(*) from camera where site_id=? and online=true and stream_url is not null and trim(stream_url)<>''",
                Long.class, siteId);
        String state = configured == null || configured == 0 ? "NOT_CONFIGURED"
                : ready != null && ready > 0 ? "CONFIGURED" : "DEGRADED";
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("state", state);
        details.put("totalChannels", total == null ? 0 : total);
        details.put("configuredChannels", configured == null ? 0 : configured);
        details.put("onlineConfiguredChannels", ready == null ? 0 : ready);
        details.put("allowedHosts", properties.getVideo().getAllowedHosts());
        return integration("VIDEO", "真实视频", details);
    }

    private Map<String, Object> checkVideo(long siteId) {
        List<Map<String, Object>> cameras = jdbc.queryForList(
                "select id,code,name,online,stream_url as streamUrl from camera "
                        + "where site_id=? and stream_url is not null and trim(stream_url)<>'' order by id limit 20",
                siteId);
        if (cameras.isEmpty()) {
            return result("NOT_CONFIGURED", "当前工地没有已配置的视频流地址", List.of());
        }
        List<Map<String, Object>> checks = new ArrayList<>();
        int reachable = 0;
        for (Map<String, Object> camera : cameras) {
            Map<String, Object> checked = probeCamera(camera);
            if ("REACHABLE".equals(checked.get("state"))) reachable++;
            checks.add(checked);
        }
        String state = reachable == cameras.size() ? "READY" : reachable > 0 ? "DEGRADED" : "DOWN";
        Map<String, Object> result = result(state,
                reachable + "/" + cameras.size() + " 路传输端点可达；仍需在浏览器确认解码与鉴权", checks);
        result.put("probedChannels", cameras.size());
        result.put("reachableChannels", reachable);
        return result;
    }

    private Map<String, Object> probeCamera(Map<String, Object> camera) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("cameraId", camera.get("id"));
        result.put("cameraCode", camera.get("code"));
        try {
            URI uri = endpointPolicy.validateVideoUrl(String.valueOf(camera.get("streamUrl")),
                    properties.getVideo().getAllowedHosts());
            result.put("protocol", uri.getScheme().toUpperCase(Locale.ROOT));
            if (Set.of("http", "https").contains(uri.getScheme().toLowerCase(Locale.ROOT))) {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofMillis(properties.getVideo().effectiveConnectTimeoutMs()))
                        .followRedirects(HttpClient.Redirect.NEVER)
                        .build();
                HttpRequest request = HttpRequest.newBuilder(uri)
                        .timeout(Duration.ofMillis(properties.getVideo().effectiveConnectTimeoutMs()))
                        .method("HEAD", HttpRequest.BodyPublishers.noBody()).build();
                HttpResponse<Void> response = client.send(request, HttpResponse.BodyHandlers.discarding());
                result.put("httpStatus", response.statusCode());
                if (response.statusCode() < 500) {
                    result.put("state", "REACHABLE");
                    result.put("message", "HTTP 传输端点已响应");
                } else {
                    result.put("state", "UNREACHABLE");
                    result.put("message", "HTTP 传输端点返回服务端错误");
                }
            } else {
                int port = uri.getPort() > 0 ? uri.getPort() : "rtsp".equalsIgnoreCase(uri.getScheme()) ? 554 : 1935;
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(uri.getHost(), port),
                            properties.getVideo().effectiveConnectTimeoutMs());
                }
                result.put("state", "REACHABLE");
                result.put("message", "媒体传输端口可达");
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            result.put("state", "UNREACHABLE");
            result.put("message", "探测被中断");
        } catch (AppException ex) {
            result.put("state", "MISCONFIGURED");
            result.put("message", ex.getMessage());
        } catch (Exception ex) {
            result.put("state", "UNREACHABLE");
            result.put("message", "传输端点不可达");
        }
        return result;
    }

    private Map<String, Object> monitoringStatus() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", "CONFIGURED");
        result.put("healthEndpoint", "/actuator/health");
        result.put("metricsEndpoint", "/actuator/prometheus");
        result.put("metricsEndpointRequiresAdmin", true);
        return result;
    }

    private Map<String, Object> checkMonitoring() {
        Integer database = jdbc.queryForObject("select 1", Integer.class);
        long meters = meterRegistry.getMeters().size();
        Map<String, Object> result = result(database != null && database == 1 ? "READY" : "DOWN",
                "数据库与指标注册表检查完成", List.of());
        result.put("database", database != null && database == 1 ? "UP" : "DOWN");
        result.put("registeredMeters", meters);
        result.put("healthEndpoint", "/actuator/health");
        result.put("metricsEndpoint", "/actuator/prometheus");
        return result;
    }

    private Map<String, Object> integration(String type, String name, Map<String, Object> details) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("type", type);
        result.put("name", name);
        result.putAll(details);
        return result;
    }

    private Map<String, Object> result(String state, String message, List<Map<String, Object>> checks) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("state", state);
        result.put("message", message);
        result.put("checks", checks);
        result.put("checkedAt", Instant.now());
        return result;
    }
}
