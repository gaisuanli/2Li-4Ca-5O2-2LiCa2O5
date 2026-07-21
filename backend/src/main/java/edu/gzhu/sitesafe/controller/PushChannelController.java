package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.service.PushDeliveryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class PushChannelController {
    private final PushDeliveryService delivery;

    public PushChannelController(PushDeliveryService delivery) {
        this.delivery = delivery;
    }

    @GetMapping("/push-channels")
    public ApiResponse<Map<String, Object>> channels(@RequestParam long siteId,
                                                     @RequestParam(defaultValue = "1") int page,
                                                     @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(delivery.channels(siteId, page, pageSize));
    }

    @PostMapping("/push-channels")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody ChannelRequest request) {
        return ApiResponse.ok(delivery.createChannel(request.siteId(), request.name(), request.type(),
                request.endpointUrl(), request.credentialEnvName()));
    }

    @PatchMapping("/push-channels/{id}/enabled")
    public ApiResponse<Map<String, Object>> enabled(@PathVariable long id,
                                                    @Valid @RequestBody EnabledRequest request) {
        return ApiResponse.ok(delivery.setEnabled(id, request.enabled()));
    }

    @GetMapping("/reports/{reportId}/deliveries")
    public ApiResponse<Map<String, Object>> deliveries(@PathVariable long reportId,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(delivery.deliveries(reportId, page, pageSize));
    }

    @PostMapping("/reports/{reportId}/deliveries")
    public ApiResponse<Map<String, Object>> deliver(@PathVariable long reportId,
                                                    @Valid @RequestBody DeliveryRequest request) {
        return ApiResponse.ok(delivery.deliver(reportId, request.channelId()));
    }

    @GetMapping("/push-runtime")
    public ApiResponse<Map<String, Object>> runtime() {
        return ApiResponse.ok(delivery.runtimeStatus());
    }

    public record ChannelRequest(
            @NotNull(message = "工地不能为空") Long siteId,
            @NotBlank(message = "渠道名称不能为空") @Size(max = 120) String name,
            @NotBlank(message = "渠道类型不能为空") String type,
            @Size(max = 500) String endpointUrl,
            @Size(max = 100) String credentialEnvName) {}

    public record EnabledRequest(@NotNull(message = "enabled 不能为空") Boolean enabled) {}
    public record DeliveryRequest(@NotNull(message = "推送渠道不能为空") Long channelId) {}
}
