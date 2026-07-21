package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.service.AlarmService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/alarms")
public class AlarmController {
    private final AlarmService service;

    public AlarmController(AlarmService service) {
        this.service = service;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam long siteId,
                                                 @RequestParam(required = false) String status,
                                                 @RequestParam(required = false) String severity,
                                                 @RequestParam(required = false) String source,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(required = false) Long zoneId,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                                 @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        return ApiResponse.ok(service.list(siteId, status, severity, source, keyword, zoneId, from, to, page, pageSize));
    }

    @GetMapping("/export")
    public ResponseEntity<byte[]> export(@RequestParam long siteId,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(required = false) String severity,
                                         @RequestParam(required = false) String source,
                                         @RequestParam(required = false) String keyword,
                                         @RequestParam(required = false) Long zoneId,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
                                         @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to) {
        byte[] csv = service.exportCsv(siteId, status, severity, source, keyword, zoneId, from, to);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=alarms-site-" + siteId + ".csv")
                .contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .body(csv);
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable long id) {
        return ApiResponse.ok(service.detail(id));
    }

    @PostMapping("/{id}/actions")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR')")
    public ApiResponse<Map<String, Object>> transition(@PathVariable long id, @Valid @RequestBody ActionRequest request) {
        return ApiResponse.ok(service.transition(id, request.action(), request.note()));
    }

    public record ActionRequest(@NotBlank(message = "告警操作不能为空") String action, String note) {}
}
