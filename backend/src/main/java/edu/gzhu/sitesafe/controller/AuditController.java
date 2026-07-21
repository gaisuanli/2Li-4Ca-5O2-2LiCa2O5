package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.service.AuditService;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/audit-logs")
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) {
        this.audit = audit;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ApiResponse<Map<String, Object>> list(@RequestParam long siteId,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(audit.list(siteId, page, pageSize));
    }
}
