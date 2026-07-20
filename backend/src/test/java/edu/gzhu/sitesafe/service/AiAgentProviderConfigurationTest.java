package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.AiAgentProperties;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AiAgentProviderConfigurationTest {
    @Test
    void compatibleModeRejectsBaseUrlOutsideServerWhitelist() {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setMode(AiAgentProperties.Mode.OPENAI_COMPATIBLE);
        properties.setModel("test-model");
        properties.setApiKey("server-secret");
        properties.setBaseUrl("https://unapproved.example/v1");
        properties.setAllowedBaseUrls(List.of("https://approved.example/v1"));

        AiAgentProvider.ProviderStatus status = provider(properties).status();
        assertEquals(AiAgentProperties.Mode.OPENAI_COMPATIBLE, status.mode());
        assertFalse(status.available());
    }

    @Test
    void disabledModeIsUnavailableAndRejectsCallsBeforeUsingAnyContext() {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setMode(AiAgentProperties.Mode.DISABLED);
        AiAgentProvider provider = provider(properties);

        assertFalse(provider.status().available());
        AppException error = assertThrows(AppException.class,
                () -> provider.answer(1L, "question", "context", List.of(), null, null));
        assertEquals("AI_AGENT_DISABLED", error.code());
    }

    @Test
    void remoteHttpIsRejectedEvenWhenItAppearsInTheServerWhitelist() {
        AiAgentProperties properties = compatible("http://api.example.test/v1");
        assertFalse(provider(properties).status().available());

        properties = compatible("http://127.0.0.2:8080/v1");
        assertFalse(provider(properties).status().available());
    }

    @Test
    void exactLocalLoopbackHttpHostsRemainAvailableForLocalAdaptersAndTests() {
        for (String baseUrl : List.of(
                "http://localhost:8080/v1",
                "http://127.0.0.1:8080/v1",
                "http://[::1]:8080/v1")) {
            AiAgentProvider.ProviderStatus status = provider(compatible(baseUrl)).status();
            org.junit.jupiter.api.Assertions.assertTrue(status.available(), baseUrl);
        }
    }

    private AiAgentProperties compatible(String baseUrl) {
        AiAgentProperties properties = new AiAgentProperties();
        properties.setMode(AiAgentProperties.Mode.OPENAI_COMPATIBLE);
        properties.setModel("test-model");
        properties.setApiKey("server-secret");
        properties.setBaseUrl(baseUrl);
        properties.setAllowedBaseUrls(List.of(baseUrl));
        return properties;
    }

    private AiAgentProvider provider(AiAgentProperties properties) {
        return new AiAgentProvider(properties, new AiAgentAdmissionService(properties),
                new com.fasterxml.jackson.databind.ObjectMapper());
    }
}
