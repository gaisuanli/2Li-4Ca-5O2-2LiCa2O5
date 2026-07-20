package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.service.KnowledgeDocumentService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/knowledge-documents")
@PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
public class KnowledgeDocumentController {
    private final KnowledgeDocumentService documents;

    public KnowledgeDocumentController(KnowledgeDocumentService documents) {
        this.documents = documents;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam long siteId,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "10") int pageSize) {
        return ApiResponse.ok(documents.list(siteId, keyword, status, page, pageSize));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody DocumentRequest request) {
        return ApiResponse.ok(documents.create(request.siteId(), request.title(), request.category(),
                request.sourceReference(), request.content()));
    }

    @PutMapping("/{id}")
    public ApiResponse<Map<String, Object>> update(@PathVariable long id,
                                                   @Valid @RequestBody DocumentRequest request) {
        return ApiResponse.ok(documents.update(id, request.title(), request.category(),
                request.sourceReference(), request.content()));
    }

    @PostMapping("/{id}/submit")
    public ApiResponse<Map<String, Object>> submit(@PathVariable long id) {
        return ApiResponse.ok(documents.submit(id));
    }

    @PostMapping("/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> review(@PathVariable long id,
                                                   @Valid @RequestBody ReviewRequest request) {
        return ApiResponse.ok(documents.review(id, request.action(), request.note()));
    }

    @PostMapping("/{id}/archive")
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> archive(@PathVariable long id) {
        return ApiResponse.ok(documents.archive(id));
    }

    public record DocumentRequest(
            @NotNull(message = "工地不能为空") Long siteId,
            @NotBlank(message = "知识标题不能为空") @Size(max = 160) String title,
            @NotBlank(message = "知识分类不能为空") @Size(max = 80) String category,
            @Size(max = 500) String sourceReference,
            @NotBlank(message = "知识正文不能为空") @Size(max = 30000) String content) {}

    public record ReviewRequest(
            @NotBlank(message = "审核操作不能为空") String action,
            @Size(max = 500) String note) {}
}
