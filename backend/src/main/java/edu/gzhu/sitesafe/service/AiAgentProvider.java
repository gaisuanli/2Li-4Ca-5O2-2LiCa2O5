package edu.gzhu.sitesafe.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.AiAgentProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class AiAgentProvider {
    private static final Logger log = LoggerFactory.getLogger(AiAgentProvider.class);
    private static final String GENERIC_PROVIDER_ERROR = "AI 服务暂时不可用，请稍后重试";

    private final AiAgentProperties properties;
    private final AiAgentAdmissionService admission;
    private final ObjectMapper objectMapper;

    public AiAgentProvider(AiAgentProperties properties,
                           AiAgentAdmissionService admission,
                           ObjectMapper objectMapper) {
        this.properties = properties;
        this.admission = admission;
        this.objectMapper = objectMapper;
    }

    public ProviderStatus status() {
        return status(globalConfiguration());
    }

    public ProviderStatus status(ProviderConfiguration configuration) {
        AiAgentProperties.Mode mode = configuration.mode();
        if (mode == AiAgentProperties.Mode.DEMO) {
            return new ProviderStatus(mode, safeModel(configuration.model(), "demo-site-summary"), true);
        }
        if (mode == AiAgentProperties.Mode.DISABLED) {
            return new ProviderStatus(mode, safeModel(configuration.model(), "disabled"), false);
        }
        boolean available = compatibleEndpoint(configuration).isPresent()
                && configuration.apiKey() != null
                && !configuration.apiKey().isBlank()
                && validModel(configuration.model())
                && approvedModel(configuration.model());
        return new ProviderStatus(mode, safeModel(configuration.model(), "unconfigured"), available);
    }

    public ProviderConfiguration globalConfiguration() {
        return new ProviderConfiguration(properties.getMode(), properties.getModel(),
                properties.getBaseUrl(), properties.getApiKey());
    }

    public ProviderConfiguration userConfiguration(String model, String baseUrl, String apiKey) {
        String normalizedModel = normalizeUserModel(model);
        String normalizedBaseUrl = normalizeApprovedBaseUrl(baseUrl);
        return new ProviderConfiguration(AiAgentProperties.Mode.OPENAI_COMPATIBLE,
                normalizedModel, normalizedBaseUrl, apiKey == null ? "" : apiKey);
    }

    public List<String> approvedModels() {
        if (properties.getApprovedModels() == null) return List.of();
        return properties.getApprovedModels().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(this::validModel)
                .distinct()
                .toList();
    }

    public boolean customModelAllowed() {
        return properties.getApprovedModels() == null
                || properties.getApprovedModels().stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .noneMatch(item -> !item.isEmpty());
    }

    public List<String> approvedBaseUrls() {
        if (properties.getAllowedBaseUrls() == null) return List.of();
        return properties.getAllowedBaseUrls().stream()
                .map(this::normalizeBaseUrl)
                .filter(Objects::nonNull)
                .distinct()
                .collect(Collectors.toUnmodifiableList());
    }

    public ProviderReply answer(long userId,
                                String question,
                                String siteContext,
                                List<HistoryMessage> history,
                                AiAgentSiteSnapshotService.SiteSnapshot snapshot,
                                AiAgentSiteSnapshotService snapshots) {
        return answer(userId, question, siteContext, history, snapshot, snapshots, globalConfiguration());
    }

    public ProviderReply answer(long userId,
                                String question,
                                String siteContext,
                                List<HistoryMessage> history,
                                AiAgentSiteSnapshotService.SiteSnapshot snapshot,
                                AiAgentSiteSnapshotService snapshots,
                                ProviderConfiguration configuration) {
        ProviderStatus status = status(configuration);
        if (status.mode() == AiAgentProperties.Mode.DISABLED) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AI_AGENT_DISABLED", "AI Agent 当前已停用");
        }
        if (!status.available()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AI_AGENT_UNAVAILABLE",
                    "AI Agent 服务端配置不可用，请联系管理员");
        }
        if (status.mode() == AiAgentProperties.Mode.DEMO) {
            return new ProviderReply(snapshots.demoAnswer(snapshot, question), status.mode().name(), status.model());
        }
        return admission.execute(userId,
                () -> compatibleAnswer(question, siteContext, history, status, configuration));
    }

    private ProviderReply compatibleAnswer(String question, String siteContext,
                                           List<HistoryMessage> history, ProviderStatus status,
                                           ProviderConfiguration configuration) {
        URI endpoint = compatibleEndpoint(configuration).orElseThrow(() ->
                new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AI_AGENT_UNAVAILABLE",
                        "AI Agent 服务端配置不可用，请联系管理员"));
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.effectiveConnectTimeoutMs()))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.effectiveReadTimeoutMs()));
        RestClient client = RestClient.builder().requestFactory(requestFactory).build();

        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of(
                "role", "system",
                "content", "你是建筑施工安全平台的辅助问答 Agent。只能依据提供的工地摘要和会话上下文回答；"
                        + "数据不足时要明确说明，不得虚构传感器数据、事故结论或已经执行的操作。\n\n" + siteContext));
        for (HistoryMessage item : history) {
            String role = "ASSISTANT".equals(item.role()) ? "assistant" : "user";
            messages.add(Map.of("role", role, "content", item.content()));
        }
        messages.add(Map.of("role", "user", "content", question));
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("model", status.model());
        request.put("messages", messages);
        request.put("temperature", 0.2);

        try {
            JsonNode response = client.post()
                    .uri(endpoint)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer " + configuration.apiKey().trim())
                    .contentType(MediaType.APPLICATION_JSON)
                    .accept(MediaType.APPLICATION_JSON)
                    .body(request)
                    .exchange((httpRequest, httpResponse) -> {
                        int statusCode = httpResponse.getStatusCode().value();
                        if (!httpResponse.getStatusCode().is2xxSuccessful()) {
                            // Do not read or retain provider error bodies.
                            throw new ProviderHttpStatusException(statusCode);
                        }
                        int byteLimit = properties.effectiveMaxResponseBytes();
                        long contentLength = httpResponse.getHeaders().getContentLength();
                        if (contentLength > byteLimit) throw new ProviderResponseTooLargeException();
                        byte[] boundedBody = readBounded(httpResponse.getBody(), byteLimit);
                        return objectMapper.readTree(boundedBody);
                    });
            JsonNode content = response == null ? null : response.path("choices").path(0).path("message").path("content");
            if (content == null || !content.isTextual() || content.asText().isBlank()
                    || content.asText().length() > properties.effectiveMaxResponseChars()) {
                log.warn("AI compatible provider returned an invalid response envelope");
                throw providerError();
            }
            return new ProviderReply(content.asText().trim(), status.mode().name(), status.model());
        } catch (ProviderHttpStatusException ex) {
            log.warn("AI compatible provider returned HTTP status {}", ex.statusCode());
            throw providerError();
        } catch (ProviderResponseTooLargeException ex) {
            log.warn("AI compatible provider response exceeded the configured byte limit");
            throw providerError();
        } catch (ResourceAccessException ex) {
            if (isTimeout(ex)) {
                log.warn("AI compatible provider request timed out");
                throw timeoutError();
            }
            log.warn("AI compatible provider could not be reached ({}, causes={})",
                    ex.getClass().getSimpleName(), causeTypeNames(ex));
            throw providerError();
        } catch (AppException ex) {
            throw ex;
        } catch (Exception ex) {
            if (isTimeout(ex)) {
                log.warn("AI compatible provider request timed out");
                throw timeoutError();
            }
            log.warn("AI compatible provider response could not be processed ({})", ex.getClass().getSimpleName());
            throw providerError();
        }
    }

    private java.util.Optional<URI> compatibleEndpoint(ProviderConfiguration configuration) {
        String normalizedBase = normalizeBaseUrl(configuration.baseUrl());
        if (normalizedBase == null) return java.util.Optional.empty();
        boolean allowed = properties.getAllowedBaseUrls() != null
                && properties.getAllowedBaseUrls().stream()
                .map(this::normalizeBaseUrl)
                .filter(Objects::nonNull)
                .anyMatch(normalizedBase::equals);
        if (!allowed) return java.util.Optional.empty();
        try {
            return java.util.Optional.of(new URI(normalizedBase + "/chat/completions"));
        } catch (URISyntaxException ex) {
            return java.util.Optional.empty();
        }
    }

    private String normalizeBaseUrl(String raw) {
        if (raw == null || raw.isBlank()) return null;
        try {
            URI uri = new URI(raw.trim());
            if (!uri.isAbsolute() || uri.getHost() == null || uri.getUserInfo() != null
                    || uri.getQuery() != null || uri.getFragment() != null) {
                return null;
            }
            String scheme = uri.getScheme().toLowerCase();
            String host = uri.getHost().toLowerCase();
            if (!"https".equals(scheme) && !("http".equals(scheme) && isExactLoopbackHost(host))) return null;
            String path = uri.getPath() == null ? "" : uri.getPath().replaceAll("/+$", "");
            URI normalized = new URI(scheme, null, host,
                    uri.getPort(), path, null, null);
            return normalized.toString();
        } catch (URISyntaxException ex) {
            return null;
        }
    }

    private boolean isExactLoopbackHost(String host) {
        String unwrapped = host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
        return "localhost".equals(unwrapped) || "127.0.0.1".equals(unwrapped) || "::1".equals(unwrapped);
    }

    private byte[] readBounded(InputStream input, int byteLimit) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(byteLimit, 8192));
        byte[] buffer = new byte[4096];
        int total = 0;
        int read;
        while ((read = input.read(buffer)) != -1) {
            if (read > byteLimit - total) throw new ProviderResponseTooLargeException();
            output.write(buffer, 0, read);
            total += read;
        }
        return output.toByteArray();
    }

    private boolean validModel(String model) {
        return model != null && model.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}");
    }

    private String normalizeUserModel(String model) {
        String normalized = model == null ? "" : model.trim();
        if (!validModel(normalized)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_PROVIDER_MODEL",
                    "模型标识须为 1～100 个字母、数字或 . _ : / - 字符");
        }
        if (!approvedModel(normalized)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "AGENT_PROVIDER_MODEL_NOT_APPROVED",
                    "所选模型不在管理员允许列表中");
        }
        return normalized;
    }

    private boolean approvedModel(String model) {
        return customModelAllowed() || approvedModels().contains(model);
    }

    private String normalizeApprovedBaseUrl(String baseUrl) {
        String normalized = normalizeBaseUrl(baseUrl);
        if (normalized == null || !approvedBaseUrls().contains(normalized)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "AGENT_PROVIDER_BASE_URL_NOT_APPROVED",
                    "服务商 Base URL 无效或不在管理员允许列表中");
        }
        return normalized;
    }

    private String safeModel(String model, String fallback) {
        return validModel(model) ? model.trim() : fallback;
    }

    private boolean hasCause(Throwable error, Class<? extends Throwable> type) {
        Throwable current = error;
        while (current != null) {
            if (type.isInstance(current)) return true;
            current = current.getCause();
        }
        return false;
    }

    private boolean isTimeout(Throwable error) {
        return hasCause(error, java.net.SocketTimeoutException.class)
                || hasCause(error, HttpTimeoutException.class)
                || hasCause(error, java.io.InterruptedIOException.class)
                || hasCause(error, java.util.concurrent.TimeoutException.class);
    }

    private String causeTypeNames(Throwable error) {
        List<String> names = new ArrayList<>();
        Throwable current = error;
        while (current != null && names.size() < 8) {
            names.add(current.getClass().getSimpleName());
            current = current.getCause();
        }
        return String.join("->", names);
    }

    private AppException providerError() {
        return new AppException(HttpStatus.BAD_GATEWAY, "AI_AGENT_PROVIDER_ERROR", GENERIC_PROVIDER_ERROR);
    }

    private AppException timeoutError() {
        return new AppException(HttpStatus.GATEWAY_TIMEOUT, "AI_AGENT_PROVIDER_TIMEOUT",
                "AI 服务响应超时，请稍后重试");
    }

    public record ProviderStatus(AiAgentProperties.Mode mode, String model, boolean available) {}
    public record ProviderReply(String content, String mode, String model) {}
    public record HistoryMessage(String role, String content) {}

    public static final class ProviderConfiguration {
        private final AiAgentProperties.Mode mode;
        private final String model;
        private final String baseUrl;
        private final String apiKey;

        public ProviderConfiguration(AiAgentProperties.Mode mode, String model, String baseUrl, String apiKey) {
            this.mode = mode;
            this.model = model;
            this.baseUrl = baseUrl;
            this.apiKey = apiKey;
        }

        public AiAgentProperties.Mode mode() { return mode; }
        public String model() { return model; }
        public String baseUrl() { return baseUrl; }
        public String apiKey() { return apiKey; }

        @Override
        public String toString() {
            return "ProviderConfiguration[mode=" + mode + ", model=" + model
                    + ", baseUrl=" + baseUrl + ", credentialConfigured="
                    + (apiKey != null && !apiKey.isBlank()) + "]";
        }
    }

    private static final class ProviderHttpStatusException extends RuntimeException {
        private final int statusCode;
        private ProviderHttpStatusException(int statusCode) { this.statusCode = statusCode; }
        private int statusCode() { return statusCode; }
    }

    private static final class ProviderResponseTooLargeException extends RuntimeException {}
}
