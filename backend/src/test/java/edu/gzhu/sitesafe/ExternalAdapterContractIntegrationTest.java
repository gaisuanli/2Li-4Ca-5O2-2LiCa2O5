package edu.gzhu.sitesafe;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sitesafe-adapter-contract;MODE=MySQL;DATABASE_TO_LOWER=FALSE;DATABASE_TO_UPPER=FALSE;CASE_INSENSITIVE_IDENTIFIERS=TRUE;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.sql.init.mode=always",
        "app.tcp.enabled=false",
        "app.sprinkler.minimum-interval-seconds=0"
})
@AutoConfigureMockMvc
class ExternalAdapterContractIntegrationTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final AtomicReference<String> receivedSprinklerCommand = new AtomicReference<>();
    private static HttpServer adapter;

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @DynamicPropertySource
    static void adapterProperties(DynamicPropertyRegistry registry) {
        startAdapter();
        String baseUrl = "http://127.0.0.1:" + adapter.getAddress().getPort();
        registry.add("app.integrations.video.allowed-hosts", () -> "127.0.0.1");
        registry.add("app.integrations.vision-ai.enabled", () -> "true");
        registry.add("app.integrations.vision-ai.base-url", () -> baseUrl);
        registry.add("app.integrations.vision-ai.allowed-base-urls", () -> baseUrl);
        registry.add("app.integrations.vision-ai.allow-http-loopback", () -> "true");
        registry.add("app.integrations.sprinkler-gateway.mode", () -> "HTTP");
        registry.add("app.integrations.sprinkler-gateway.base-url", () -> baseUrl);
        registry.add("app.integrations.sprinkler-gateway.allowed-base-urls", () -> baseUrl);
        registry.add("app.integrations.sprinkler-gateway.allow-http-loopback", () -> "true");
        registry.add("app.integrations.sprinkler-gateway.callback-token", () -> "contract-callback-token-2026");
    }

    @AfterAll
    static void stopAdapter() {
        if (adapter != null) adapter.stop(0);
    }

    @Test
    void videoVisionAndSprinklerNetworkContractsAreAcceptedWithoutClaimingHardware() throws Exception {
        String admin = login();
        String baseUrl = "http://127.0.0.1:" + adapter.getAddress().getPort();

        mockMvc.perform(put("/api/cameras/1/stream")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1, "streamUrl", baseUrl + "/live.m3u8"))))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/integrations/VIDEO/check")
                        .param("siteId", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READY"))
                .andExpect(jsonPath("$.data.checks[0].state").value("REACHABLE"));

        mockMvc.perform(post("/api/integrations/VISION_AI/check")
                        .param("siteId", "1")
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("READY"))
                .andExpect(jsonPath("$.data.adapterMode").value("CONTRACT_TEST"));
        mockMvc.perform(post("/api/integrations/vision-ai/infer")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1, "cameraId", 1, "imageBase64", "aW1hZ2U="))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.acceptedRiskCount").value(1))
                .andExpect(jsonPath("$.data.riskIds", hasSize(1)))
                .andExpect(jsonPath("$.data.reviewRequired").value(true));

        long taskId = dataId(mockMvc.perform(post("/api/sprinkler-tasks")
                        .header("Authorization", bearer(admin))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("siteId", 1, "zoneId", 2, "reason", "网关契约验收"))))
                .andExpect(status().isOk()).andReturn());
        MvcResult dispatched = mockMvc.perform(post("/api/sprinkler-tasks/{id}/dispatch", taskId)
                        .header("Authorization", bearer(admin)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DISPATCHED"))
                .andReturn();
        String commandId = objectMapper.readTree(dispatched.getResponse().getContentAsString())
                .path("data").path("commandId").asText();
        org.junit.jupiter.api.Assertions.assertEquals(commandId, receivedSprinklerCommand.get());

        mockMvc.perform(post("/api/integration-callbacks/sprinkler")
                        .header("X-Gateway-Token", "contract-callback-token-2026")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("commandId", commandId, "success", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXECUTED"));
    }

    private static synchronized void startAdapter() {
        if (adapter != null) return;
        try {
            adapter = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            adapter.createContext("/health", exchange -> jsonResponse(exchange, 200,
                    "{\"status\":\"UP\",\"mode\":\"CONTRACT_TEST\",\"modelFilePresent\":true,\"notice\":\"network contract only\"}"));
            adapter.createContext("/infer", exchange -> jsonResponse(exchange, 200,
                    "{\"mode\":\"CONTRACT_TEST\",\"modelVersion\":\"contract-v1\",\"detections\":[{\"riskType\":\"未佩戴安全帽\",\"confidence\":0.92}]}"));
            adapter.createContext("/live.m3u8", exchange -> {
                exchange.sendResponseHeaders(200, -1);
                exchange.close();
            });
            adapter.createContext("/commands/sprinkler", exchange -> {
                JsonNode body = JSON.readTree(exchange.getRequestBody());
                receivedSprinklerCommand.set(body.path("platformCommandId").asText());
                jsonResponse(exchange, 200, "{\"accepted\":true,\"commandId\":\"gateway-contract-1\"}");
            });
            adapter.start();
        } catch (IOException ex) {
            throw new IllegalStateException("Could not start adapter contract server", ex);
        }
    }

    private static void jsonResponse(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private long dataId(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
    }

    private String login() throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("username", "admin", "password", "Admin@123"))))
                .andExpect(status().isOk()).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString()).path("data").path("token").asText();
    }

    private String bearer(String token) { return "Bearer " + token; }
    private String json(Object value) throws Exception { return objectMapper.writeValueAsString(value); }
}
