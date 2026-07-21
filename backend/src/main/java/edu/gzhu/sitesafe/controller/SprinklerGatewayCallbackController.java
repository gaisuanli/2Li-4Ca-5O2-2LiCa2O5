package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.service.SprinklerGatewayClient;
import edu.gzhu.sitesafe.service.SprinklerTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/integration-callbacks/sprinkler")
public class SprinklerGatewayCallbackController {
    private final SprinklerGatewayClient gateway;
    private final SprinklerTaskService tasks;

    public SprinklerGatewayCallbackController(SprinklerGatewayClient gateway, SprinklerTaskService tasks) {
        this.gateway = gateway;
        this.tasks = tasks;
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> callback(
            @RequestHeader(value = "X-Gateway-Token", required = false) String token,
            @Valid @RequestBody CallbackRequest request) {
        gateway.verifyCallbackToken(token);
        return ApiResponse.ok(tasks.acknowledgeFromGateway(
                request.commandId(), request.success(), request.failureReason()));
    }

    public record CallbackRequest(
            @NotBlank(message = "commandId 不能为空") @Size(max = 64) String commandId,
            @NotNull(message = "回执结果不能为空") Boolean success,
            @Size(max = 500) String failureReason) {}
}
