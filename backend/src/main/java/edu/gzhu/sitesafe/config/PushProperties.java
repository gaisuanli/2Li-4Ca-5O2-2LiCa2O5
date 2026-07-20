package edu.gzhu.sitesafe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.push")
public class PushProperties {
    private boolean webhookEnabled = false;
    private boolean allowHttpLoopback = false;
    private List<String> allowedEndpoints = new ArrayList<>();
    private int connectTimeoutMs = 3000;
    private int requestTimeoutMs = 10000;

    public boolean isWebhookEnabled() { return webhookEnabled; }
    public void setWebhookEnabled(boolean webhookEnabled) { this.webhookEnabled = webhookEnabled; }
    public boolean isAllowHttpLoopback() { return allowHttpLoopback; }
    public void setAllowHttpLoopback(boolean allowHttpLoopback) { this.allowHttpLoopback = allowHttpLoopback; }
    public List<String> getAllowedEndpoints() { return allowedEndpoints; }
    public void setAllowedEndpoints(List<String> allowedEndpoints) { this.allowedEndpoints = allowedEndpoints; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getRequestTimeoutMs() { return requestTimeoutMs; }
    public void setRequestTimeoutMs(int requestTimeoutMs) { this.requestTimeoutMs = requestTimeoutMs; }

    public int effectiveConnectTimeoutMs() { return clamp(connectTimeoutMs, 100, 30000); }
    public int effectiveRequestTimeoutMs() { return clamp(requestTimeoutMs, 500, 60000); }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
