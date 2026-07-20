package edu.gzhu.sitesafe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.integrations")
public class IntegrationProperties {
    private final Video video = new Video();
    private final VisionAi visionAi = new VisionAi();
    private final SprinklerGateway sprinklerGateway = new SprinklerGateway();

    public Video getVideo() { return video; }
    public VisionAi getVisionAi() { return visionAi; }
    public SprinklerGateway getSprinklerGateway() { return sprinklerGateway; }

    public static class Video {
        private List<String> allowedHosts = new ArrayList<>(List.of("127.0.0.1", "localhost"));
        private int connectTimeoutMs = 3000;

        public List<String> getAllowedHosts() { return allowedHosts; }
        public void setAllowedHosts(List<String> allowedHosts) { this.allowedHosts = allowedHosts; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int effectiveConnectTimeoutMs() { return clamp(connectTimeoutMs, 100, 30000); }
    }

    public static class VisionAi {
        private boolean enabled = false;
        private String baseUrl = "http://127.0.0.1:5001";
        private String apiKey = "";
        private List<String> allowedBaseUrls = new ArrayList<>(List.of("http://127.0.0.1:5001"));
        private boolean allowHttpLoopback = true;
        private int connectTimeoutMs = 3000;
        private int requestTimeoutMs = 30000;

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public List<String> getAllowedBaseUrls() { return allowedBaseUrls; }
        public void setAllowedBaseUrls(List<String> allowedBaseUrls) { this.allowedBaseUrls = allowedBaseUrls; }
        public boolean isAllowHttpLoopback() { return allowHttpLoopback; }
        public void setAllowHttpLoopback(boolean allowHttpLoopback) { this.allowHttpLoopback = allowHttpLoopback; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
        public int effectiveConnectTimeoutMs() { return clamp(connectTimeoutMs, 100, 30000); }
        public int effectiveRequestTimeoutMs() { return clamp(requestTimeoutMs, 500, 120000); }
    }

    public static class SprinklerGateway {
        public enum Mode { DEMO, HTTP, DISABLED }

        private Mode mode = Mode.DEMO;
        private String baseUrl = "";
        private String apiKey = "";
        private String callbackToken = "";
        private List<String> allowedBaseUrls = new ArrayList<>();
        private boolean allowHttpLoopback = false;
        private int connectTimeoutMs = 3000;
        private int requestTimeoutMs = 10000;

        public Mode getMode() { return mode; }
        public void setMode(Mode mode) { this.mode = mode; }
        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getCallbackToken() { return callbackToken; }
        public void setCallbackToken(String callbackToken) { this.callbackToken = callbackToken; }
        public List<String> getAllowedBaseUrls() { return allowedBaseUrls; }
        public void setAllowedBaseUrls(List<String> allowedBaseUrls) { this.allowedBaseUrls = allowedBaseUrls; }
        public boolean isAllowHttpLoopback() { return allowHttpLoopback; }
        public void setAllowHttpLoopback(boolean allowHttpLoopback) { this.allowHttpLoopback = allowHttpLoopback; }
        public int getConnectTimeoutMs() { return connectTimeoutMs; }
        public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
        public int getRequestTimeoutMs() { return requestTimeoutMs; }
        public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }
        public int effectiveConnectTimeoutMs() { return clamp(connectTimeoutMs, 100, 30000); }
        public int effectiveRequestTimeoutMs() { return clamp(requestTimeoutMs, 500, 60000); }
    }

    private static int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
