package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.service.IntegrationStatusService;
import edu.gzhu.sitesafe.service.VisionAiIntegrationService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/integrations")
public class IntegrationController {
    private final IntegrationStatusService integrations;
    private final VisionAiIntegrationService visionAi;

    public IntegrationController(IntegrationStatusService integrations, VisionAiIntegrationService visionAi) {
        this.integrations = integrations;
        this.visionAi = visionAi;
    }

    @GetMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ApiResponse<Map<String, Object>> status(@RequestParam long siteId) {
        return ApiResponse.ok(integrations.status(siteId));
    }

    @PostMapping("/{type}/check")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ApiResponse<Map<String, Object>> check(@PathVariable String type, @RequestParam long siteId) {
        return ApiResponse.ok(integrations.check(siteId, type));
    }

    @PostMapping("/vision-ai/infer")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','DEVICE_MANAGER')")
    public ApiResponse<Map<String, Object>> infer(@Valid @RequestBody InferRequest request) {
        return ApiResponse.ok(visionAi.infer(request.siteId(), request.cameraId(), request.imageBase64()));
    }

    public record InferRequest(
            @NotNull(message = "工地不能为空") Long siteId,
            @NotNull(message = "摄像头不能为空") Long cameraId,
            @NotBlank(message = "图片不能为空") @Size(max = 11200000) String imageBase64) {}
}
