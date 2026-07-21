package edu.gzhu.sitesafe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@ConfigurationProperties(prefix = "app.ai-agent")
public class AiAgentProperties {
    public enum Mode { DEMO, OPENAI_COMPATIBLE, DISABLED }

    private Mode mode = Mode.DEMO;
    private String model = "demo-site-summary";
    private String baseUrl = "https://api.openai.com/v1";
    private String apiKey = "";
    private List<String> allowedBaseUrls = new ArrayList<>(List.of("https://api.openai.com/v1"));
    private boolean userConfigEnabled = true;
    private String credentialEncryptionKey = "";
    private List<String> approvedModels = new ArrayList<>();
    private int connectTimeoutMs = 5000;
    private int readTimeoutMs = 60000;
    private int maxContentChars = 8000;
    private int maxResponseChars = 16000;
    private int maxHistoryMessages = 20;
    private int conversationLockStripes = 256;
    private int conversationLockWaitMs = 5000;
    private int maxConcurrentRequests = 8;
    private int perUserRequestsPerMinute = 20;
    private int bulkheadWaitMs = 100;

    public Mode getMode() { return mode; }
    public void setMode(Mode mode) { this.mode = mode; }
    public String getModel() { return model; }
    public void setModel(String model) { this.model = model; }
    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }
    public List<String> getAllowedBaseUrls() { return allowedBaseUrls; }
    public void setAllowedBaseUrls(List<String> allowedBaseUrls) { this.allowedBaseUrls = allowedBaseUrls; }
    public boolean isUserConfigEnabled() { return userConfigEnabled; }
    public void setUserConfigEnabled(boolean userConfigEnabled) { this.userConfigEnabled = userConfigEnabled; }
    public String getCredentialEncryptionKey() { return credentialEncryptionKey; }
    public void setCredentialEncryptionKey(String credentialEncryptionKey) { this.credentialEncryptionKey = credentialEncryptionKey; }
    public List<String> getApprovedModels() { return approvedModels; }
    public void setApprovedModels(List<String> approvedModels) { this.approvedModels = approvedModels; }
    public int getConnectTimeoutMs() { return connectTimeoutMs; }
    public void setConnectTimeoutMs(int connectTimeoutMs) { this.connectTimeoutMs = connectTimeoutMs; }
    public int getReadTimeoutMs() { return readTimeoutMs; }
    public void setReadTimeoutMs(int readTimeoutMs) { this.readTimeoutMs = readTimeoutMs; }
    public int getMaxContentChars() { return maxContentChars; }
    public void setMaxContentChars(int maxContentChars) { this.maxContentChars = maxContentChars; }
    public int getMaxResponseChars() { return maxResponseChars; }
    public void setMaxResponseChars(int maxResponseChars) { this.maxResponseChars = maxResponseChars; }
    public int getMaxHistoryMessages() { return maxHistoryMessages; }
    public void setMaxHistoryMessages(int maxHistoryMessages) { this.maxHistoryMessages = maxHistoryMessages; }
    public int getConversationLockStripes() { return conversationLockStripes; }
    public void setConversationLockStripes(int conversationLockStripes) { this.conversationLockStripes = conversationLockStripes; }
    public int getConversationLockWaitMs() { return conversationLockWaitMs; }
    public void setConversationLockWaitMs(int conversationLockWaitMs) { this.conversationLockWaitMs = conversationLockWaitMs; }
    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int maxConcurrentRequests) { this.maxConcurrentRequests = maxConcurrentRequests; }
    public int getPerUserRequestsPerMinute() { return perUserRequestsPerMinute; }
    public void setPerUserRequestsPerMinute(int perUserRequestsPerMinute) { this.perUserRequestsPerMinute = perUserRequestsPerMinute; }
    public int getBulkheadWaitMs() { return bulkheadWaitMs; }
    public void setBulkheadWaitMs(int bulkheadWaitMs) { this.bulkheadWaitMs = bulkheadWaitMs; }

    public int effectiveConnectTimeoutMs() { return clamp(connectTimeoutMs, 100, 30000); }
    public int effectiveReadTimeoutMs() { return clamp(readTimeoutMs, 500, 180000); }
    public int effectiveMaxContentChars() { return clamp(maxContentChars, 100, 16000); }
    public int effectiveMaxResponseChars() { return clamp(maxResponseChars, 100, 32000); }
    public int effectiveMaxHistoryMessages() { return clamp(maxHistoryMessages, 0, 100); }
    public int effectiveConversationLockStripes() { return clamp(conversationLockStripes, 16, 4096); }
    public int effectiveConversationLockWaitMs() { return clamp(conversationLockWaitMs, 0, 120000); }
    public int effectiveMaxConcurrentRequests() { return clamp(maxConcurrentRequests, 1, 128); }
    public int effectivePerUserRequestsPerMinute() { return clamp(perUserRequestsPerMinute, 1, 1000); }
    public int effectiveBulkheadWaitMs() { return clamp(bulkheadWaitMs, 0, 5000); }
    public int effectiveMaxResponseBytes() {
        long bytes = (long) effectiveMaxResponseChars() * 4L + 4096L;
        return (int) Math.min(bytes, 132096L);
    }

    private int clamp(int value, int minimum, int maximum) {
        return Math.max(minimum, Math.min(value, maximum));
    }
}
