package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.common.PageSpec;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.service.SprinklerTaskService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api/sprinkler-tasks")
public class SprinklerController {
    private static final Set<String> TASK_STATUSES = Set.of("CREATED", "DISPATCHED", "EXECUTED", "FAILED");
    private final JdbcTemplate jdbc;
    private final SprinklerTaskService tasks;

    public SprinklerController(JdbcTemplate jdbc, SprinklerTaskService tasks) {
        this.jdbc = jdbc;
        this.tasks = tasks;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(
            @RequestParam(defaultValue = "1") long siteId,
            @RequestParam(required = false) Long zoneId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime to,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int pageSize) {
        SecurityUtil.requireSite(siteId);
        validateTimeRange(from, to);
        StringBuilder where = new StringBuilder(" where t.site_id=? ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(siteId);
        if (zoneId != null) {
            where.append("and t.zone_id=? ");
            parameters.add(zoneId);
        }
        if (status != null && !status.isBlank()) {
            String normalizedStatus = status.trim().toUpperCase();
            if (!TASK_STATUSES.contains(normalizedStatus)) {
                throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_TASK_STATUS", "喷淋任务状态无效");
            }
            where.append("and t.status=? ");
            parameters.add(normalizedStatus);
        }
        if (from != null) {
            where.append("and t.planned_at>=? ");
            parameters.add(Timestamp.valueOf(from));
        }
        if (to != null) {
            where.append("and t.planned_at<=? ");
            parameters.add(Timestamp.valueOf(to));
        }
        long total = jdbc.queryForObject("select count(*) from sprinkler_task t" + where,
                Long.class, parameters.toArray());
        PageSpec paging = PageSpec.of(page, pageSize);
        parameters.add(paging.pageSize());
        parameters.add(paging.offset());
        List<Map<String, Object>> items = jdbc.queryForList(
                "select t.id,t.code,t.zone_id as zoneId,z.name as zoneName,t.trigger_type as triggerType,t.reason,t.status,t.planned_at as plannedAt,t.started_at as startedAt,t.ended_at as endedAt,t.command_id as commandId,t.failure_reason as failureReason,u.display_name as createdBy "
                        + "from sprinkler_task t join zone z on z.id=t.zone_id join app_user u on u.id=t.created_by"
                        + where + "order by t.planned_at desc,t.id desc limit ? offset ?",
                parameters.toArray());
        return ApiResponse.ok(paging.result(items, total));
    }

    @PostMapping
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','DEVICE_MANAGER')")
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateRequest request) {
        return ApiResponse.ok(tasks.create(request.siteId(), request.zoneId(), request.reason(), request.plannedAt()));
    }

    @PostMapping("/{id}/dispatch")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','DEVICE_MANAGER')")
    public ApiResponse<Map<String, Object>> dispatch(@PathVariable long id) {
        return ApiResponse.ok(tasks.dispatch(id));
    }

    @PostMapping("/{id}/ack")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','DEVICE_MANAGER')")
    public ApiResponse<Map<String, Object>> ack(@PathVariable long id, @Valid @RequestBody AckRequest request) {
        return ApiResponse.ok(tasks.acknowledge(id, request.success(), request.failureReason()));
    }

    private void validateTimeRange(LocalDateTime from, LocalDateTime to) {
        if (from != null && to != null && from.isAfter(to)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_TIME_RANGE", "开始时间不能晚于结束时间");
        }
    }

    public record CreateRequest(@NotNull(message = "工地不能为空") Long siteId,
                                @NotNull(message = "区域不能为空") Long zoneId,
                                @NotBlank(message = "触发原因不能为空") @Size(max = 500, message = "触发原因不能超过 500 个字符") String reason,
                                LocalDateTime plannedAt) {}
    public record AckRequest(@NotNull(message = "回执结果不能为空") Boolean success,
                             @Size(max = 500, message = "失败原因不能超过 500 个字符") String failureReason) {}
}
