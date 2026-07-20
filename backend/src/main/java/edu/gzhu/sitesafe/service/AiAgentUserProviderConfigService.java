package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.AiAgentProperties;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.UserSession;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class AiAgentUserProviderConfigService {
    private static final int MAX_API_KEY_CHARS = 1024;

    private final JdbcTemplate jdbc;
    private final AiAgentProperties properties;
    private final AiAgentProvider provider;
    private final AiAgentCredentialCipher credentialCipher;
    private final AuditService audit;

    public AiAgentUserProviderConfigService(JdbcTemplate jdbc,
                                            AiAgentProperties properties,
                                            AiAgentProvider provider,
                                            AiAgentCredentialCipher credentialCipher,
                                            AuditService audit) {
        this.jdbc = jdbc;
        this.properties = properties;
        this.provider = provider;
        this.credentialCipher = credentialCipher;
        this.audit = audit;
    }

    public Map<String, Object> current() {
        UserSession user = SecurityUtil.currentUser();
        ProviderRow row = find(user.id());
        AiAgentProvider.ProviderStatus effective = effectiveStatus(user.id());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("configured", row != null);
        result.put("baseUrl", row == null ? defaultBaseUrl() : row.baseUrl());
        result.put("model", row == null ? defaultModel() : row.model());
        result.put("apiKeyConfigured", row != null && hasEncryptedCredential(row));
        result.put("credentialStorageAvailable", credentialCipher.available());
        result.put("userConfigEnabled", properties.isUserConfigEnabled());
        result.put("approvedBaseUrls", provider.approvedBaseUrls());
        result.put("approvedModels", provider.approvedModels());
        result.put("customModelAllowed", provider.customModelAllowed());
        result.put("effectiveMode", effective.mode().name());
        result.put("effectiveModel", effective.model());
        result.put("available", effective.available());
        return result;
    }

    public AiAgentProvider.ProviderStatus effectiveStatus(long userId) {
        if (properties.getMode() == AiAgentProperties.Mode.DISABLED || !properties.isUserConfigEnabled()) {
            return provider.status();
        }
        ProviderRow row = find(userId);
        if (row == null) return provider.status();
        String configuredMarker = hasEncryptedCredential(row) && credentialCipher.available()
                ? "configured" : "";
        return provider.status(new AiAgentProvider.ProviderConfiguration(
                AiAgentProperties.Mode.OPENAI_COMPATIBLE, row.model(), row.baseUrl(), configuredMarker));
    }

    public AiAgentProvider.ProviderConfiguration resolveForRequest(long userId) {
        if (properties.getMode() == AiAgentProperties.Mode.DISABLED || !properties.isUserConfigEnabled()) {
            return provider.globalConfiguration();
        }
        ProviderRow row = find(userId);
        if (row == null) return provider.globalConfiguration();
        String apiKey = hasEncryptedCredential(row)
                ? credentialCipher.decrypt(userId, row.encryptedApiKey()) : "";
        return new AiAgentProvider.ProviderConfiguration(
                AiAgentProperties.Mode.OPENAI_COMPATIBLE, row.model(), row.baseUrl(), apiKey);
    }

    @Transactional
    public Map<String, Object> save(String requestedBaseUrl, String requestedModel, String requestedApiKey) {
        requireUserConfigurationEnabled();
        credentialCipher.requireAvailable();
        UserSession user = SecurityUtil.currentUser();
        // Serialize first-write and update for one user without relying on a
        // database-specific upsert syntax.
        jdbc.queryForObject("select id from app_user where id=? for update", Long.class, user.id());
        ProviderRow existing = find(user.id());
        AiAgentProvider.ProviderConfiguration validated = provider.userConfiguration(
                requestedModel, requestedBaseUrl, "");
        String encryptedApiKey = existing == null ? null : existing.encryptedApiKey();
        String normalizedApiKey = normalizeApiKey(requestedApiKey);
        if (normalizedApiKey == null
                && (existing == null || !hasEncryptedCredential(existing))) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_PROVIDER_API_KEY",
                    "首次保存个人服务商配置时必须填写 API Key");
        }
        if (normalizedApiKey != null) {
            encryptedApiKey = credentialCipher.encrypt(user.id(), normalizedApiKey);
        }

        LocalDateTime now = LocalDateTime.now();
        if (existing == null) {
            jdbc.update("insert into ai_agent_provider_config(user_id,base_url,model,encrypted_api_key,created_at,updated_at) "
                            + "values(?,?,?,?,?,?)",
                    user.id(), validated.baseUrl(), validated.model(), encryptedApiKey,
                    Timestamp.valueOf(now), Timestamp.valueOf(now));
        } else {
            jdbc.update("update ai_agent_provider_config set base_url=?,model=?,encrypted_api_key=?,updated_at=? "
                            + "where user_id=?",
                    validated.baseUrl(), validated.model(), encryptedApiKey, Timestamp.valueOf(now), user.id());
        }
        boolean configured = encryptedApiKey != null && !encryptedApiKey.isBlank();
        audit.record("AI_AGENT_PROVIDER_CONFIG_SAVE", "AI_AGENT_PROVIDER_CONFIG", user.id(),
                "baseUrl=" + validated.baseUrl() + "；model=" + validated.model()
                        + "；credentialConfigured=" + configured);
        return current();
    }

    @Transactional
    public void delete() {
        UserSession user = SecurityUtil.currentUser();
        int deleted = jdbc.update("delete from ai_agent_provider_config where user_id=?", user.id());
        audit.record("AI_AGENT_PROVIDER_CONFIG_DELETE", "AI_AGENT_PROVIDER_CONFIG", user.id(),
                "清除个人 AI Agent 服务商配置；existed=" + (deleted > 0));
    }

    private ProviderRow find(long userId) {
        List<ProviderRow> rows = jdbc.query(
                "select user_id,base_url,model,encrypted_api_key from ai_agent_provider_config where user_id=?",
                (rs, rowNum) -> new ProviderRow(
                        rs.getLong("user_id"), rs.getString("base_url"), rs.getString("model"),
                        rs.getString("encrypted_api_key")), userId);
        return rows.isEmpty() ? null : rows.get(0);
    }

    private String normalizeApiKey(String apiKey) {
        if (apiKey == null || apiKey.isBlank()) return null;
        String normalized = apiKey.trim();
        if (normalized.length() > MAX_API_KEY_CHARS || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_PROVIDER_API_KEY",
                    "API Key 长度不能超过 1024 个字符且不能包含控制字符");
        }
        return normalized;
    }

    private void requireUserConfigurationEnabled() {
        if (!properties.isUserConfigEnabled()) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "AI_AGENT_USER_CONFIG_DISABLED",
                    "管理员已停用个人 AI Agent 服务商配置");
        }
    }

    private boolean hasEncryptedCredential(ProviderRow row) {
        return row.encryptedApiKey() != null && !row.encryptedApiKey().isBlank();
    }

    private String defaultBaseUrl() {
        String configured = properties.getBaseUrl();
        if (configured != null && provider.approvedBaseUrls().contains(configured.replaceAll("/+$", ""))) {
            return configured.replaceAll("/+$", "");
        }
        return provider.approvedBaseUrls().isEmpty() ? "" : provider.approvedBaseUrls().get(0);
    }

    private String defaultModel() {
        String configured = properties.getModel();
        if (configured != null && configured.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}")) {
            List<String> approved = provider.approvedModels();
            if (provider.customModelAllowed() || approved.contains(configured)) return configured;
        }
        return provider.approvedModels().isEmpty() ? "" : provider.approvedModels().get(0);
    }

    private record ProviderRow(long userId, String baseUrl, String model, String encryptedApiKey) {}
}
