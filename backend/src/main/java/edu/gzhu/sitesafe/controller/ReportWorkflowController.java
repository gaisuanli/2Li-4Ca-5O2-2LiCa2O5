package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.service.ReportWorkflowService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class ReportWorkflowController {
    private final ReportWorkflowService reports;

    public ReportWorkflowController(ReportWorkflowService reports) {
        this.reports = reports;
    }

    @GetMapping("/report-templates")
    public ApiResponse<Map<String, Object>> templates(@RequestParam long siteId,
                                                      @RequestParam(required = false) Boolean enabled,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(reports.templates(siteId, enabled, page, pageSize));
    }

    @PostMapping("/report-templates")
    public ApiResponse<Map<String, Object>> createTemplate(@Valid @RequestBody TemplateRequest request) {
        return ApiResponse.ok(reports.createTemplate(request.siteId(), request.name(),
                request.description(), request.bodyTemplate()));
    }

    @PutMapping("/report-templates/{id}")
    public ApiResponse<Map<String, Object>> updateTemplate(@PathVariable long id,
                                                           @Valid @RequestBody TemplateRequest request) {
        return ApiResponse.ok(reports.updateTemplate(id, request.name(), request.description(), request.bodyTemplate()));
    }

    @PatchMapping("/report-templates/{id}/enabled")
    public ApiResponse<Map<String, Object>> templateEnabled(@PathVariable long id,
                                                            @Valid @RequestBody EnabledRequest request) {
        return ApiResponse.ok(reports.setTemplateEnabled(id, request.enabled()));
    }

    @GetMapping("/reports")
    public ApiResponse<Map<String, Object>> reportList(@RequestParam long siteId,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "1") int page,
                                                       @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(reports.reports(siteId, status, page, pageSize));
    }

    @PostMapping("/reports/generate")
    public ApiResponse<Map<String, Object>> generate(@Valid @RequestBody GenerateRequest request) {
        return ApiResponse.ok(reports.generate(request.siteId(), request.templateId(), request.title()));
    }

    @PutMapping("/reports/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id,
                                                   @Valid @RequestBody ReportRequest request) {
        return ApiResponse.ok(reports.updateReport(id, request.title(), request.content()));
    }

    @PostMapping("/reports/{id}/submit")
    public ApiResponse<Map<String, Object>> submit(@PathVariable long id) {
        return ApiResponse.ok(reports.submit(id));
    }

    @PostMapping("/reports/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> review(@PathVariable long id,
                                                   @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(reports.review(id, request.action(), request.note()));
    }

    public record TemplateRequest(
            @NotNull(message = "工地不能为空") Long siteId,
            @NotBlank(message = "模板名称不能为空") @Size(max = 120) String name,
            @Size(max = 500) String description,
            @NotBlank(message = "模板正文不能为空") @Size(max = 50000) String bodyTemplate) {}

    public record EnabledRequest(@NotNull(message = "enabled 不能为空") Boolean enabled) {}

    public record GenerateRequest(
            @NotNull(message = "工地不能为空") Long siteId,
            @NotNull(message = "报告模板不能为空") Long templateId,
            @Size(max = 160) String title) {}

    public record ReportRequest(
            @NotBlank(message = "报告标题不能为空") @Size(max = 160) String title,
            @NotBlank(message = "报告正文不能为空") @Size(max = 50000) String content) {}

    public record ReviewRequest(
            @NotBlank(message = "审核操作不能为空") String action,
            @Size(max = 500) String note) {}
}
