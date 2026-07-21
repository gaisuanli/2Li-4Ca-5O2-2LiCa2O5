package edu.gzhu.sitesafe.controller;

import com.fasterxml.jackson.databind.JsonNode;
import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.service.AiAgentService;
import edu.gzhu.sitesafe.service.AiAgentUserProviderConfigService;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/agent")
public class AiAgentController {
    private final AiAgentService service;
    private final AiAgentUserProviderConfigService providerConfigs;

    public AiAgentController(AiAgentService service,
                             AiAgentUserProviderConfigService providerConfigs) {
        this.service = service;
        this.providerConfigs = providerConfigs;
    }

    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> config() {
        return ApiResponse.ok(service.config());
    }

    @GetMapping("/provider-config")
    public ApiResponse<Map<String, Object>> providerConfig() {
        return ApiResponse.ok(providerConfigs.current());
    }

    @PutMapping("/provider-config")
    public ApiResponse<Map<String, Object>> saveProviderConfig(@RequestBody JsonNode body) {
        requireObjectWithOnly(body, Set.of("baseUrl", "model", "apiKey"));
        JsonNode baseUrl = body.get("baseUrl");
        JsonNode model = body.get("model");
        JsonNode apiKey = body.get("apiKey");
        if (baseUrl == null || !baseUrl.isTextual()) throw invalidRequest("baseUrl 必须是字符串");
        if (model == null || !model.isTextual()) throw invalidRequest("model 必须是字符串");
        if (apiKey != null && !apiKey.isNull() && !apiKey.isTextual()) {
            throw invalidRequest("apiKey 必须是字符串");
        }
        return ApiResponse.ok(providerConfigs.save(baseUrl.asText(), model.asText(),
                apiKey == null || apiKey.isNull() ? null : apiKey.asText()));
    }

    @DeleteMapping("/provider-config")
    public ApiResponse<Void> deleteProviderConfig() {
        providerConfigs.delete();
        return ApiResponse.okMessage("个人 AI Agent 服务商配置已清除");
    }

    @GetMapping("/conversations")
    public ApiResponse<Map<String, Object>> conversations(@RequestParam long siteId,
                                                          @RequestParam(defaultValue = "1") int page,
                                                          @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.conversations(siteId, page, pageSize));
    }

    @PostMapping("/conversations")
    public ApiResponse<Map<String, Object>> create(@RequestBody JsonNode body) {
        requireObjectWithOnly(body, Set.of("siteId", "title"));
        JsonNode site = body.get("siteId");
        if (site == null || !site.isIntegralNumber() || !site.canConvertToLong() || site.asLong() <= 0) {
            throw invalidRequest("siteId 必须是正整数");
        }
        JsonNode titleNode = body.get("title");
        if (titleNode != null && !titleNode.isNull() && !titleNode.isTextual()) {
            throw invalidRequest("title 必须是字符串");
        }
        return ApiResponse.ok(service.create(site.asLong(),
                titleNode == null || titleNode.isNull() ? null : titleNode.asText()));
    }

    @GetMapping("/conversations/{id}/messages")
    public ApiResponse<Map<String, Object>> messages(@PathVariable long id,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "50") int pageSize) {
        return ApiResponse.ok(service.messages(id, page, pageSize));
    }

    @PostMapping("/conversations/{id}/messages")
    public ApiResponse<Map<String, Object>> send(@PathVariable long id, @RequestBody JsonNode body) {
        requireObjectWithOnly(body, Set.of("content"));
        JsonNode content = body.get("content");
        if (content == null || !content.isTextual()) {
            throw invalidRequest("content 必须是字符串");
        }
        return ApiResponse.ok(service.send(id, content.asText()));
    }

    private void requireObjectWithOnly(JsonNode body, Set<String> allowedFields) {
        if (body == null || !body.isObject()) throw invalidRequest("请求体必须是 JSON 对象");
        Set<String> extras = new HashSet<>();
        body.fieldNames().forEachRemaining(name -> {
            if (!allowedFields.contains(name)) extras.add(name);
        });
        if (!extras.isEmpty()) {
            throw invalidRequest("请求体包含不支持的字段");
        }
    }

    private AppException invalidRequest(String message) {
        return new AppException(HttpStatus.BAD_REQUEST, "INVALID_AGENT_REQUEST", message);
    }
}
