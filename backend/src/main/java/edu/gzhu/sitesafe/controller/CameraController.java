package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.common.PageSpec;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.config.IntegrationProperties;
import edu.gzhu.sitesafe.service.AuditService;
import edu.gzhu.sitesafe.service.IntegrationEndpointPolicy;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/cameras")
public class CameraController {
    private final JdbcTemplate jdbc;
    private final IntegrationProperties integrationProperties;
    private final IntegrationEndpointPolicy endpointPolicy;
    private final AuditService audit;

    public CameraController(JdbcTemplate jdbc, IntegrationProperties integrationProperties,
                            IntegrationEndpointPolicy endpointPolicy, AuditService audit) {
        this.jdbc = jdbc;
        this.integrationProperties = integrationProperties;
        this.endpointPolicy = endpointPolicy;
        this.audit = audit;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(defaultValue = "1") long siteId,
                                                 @RequestParam(required = false) Long zoneId,
                                                 @RequestParam(required = false) Boolean online,
                                                 @RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        SecurityUtil.requireSite(siteId);
        StringBuilder where = new StringBuilder(" where c.site_id=? ");
        List<Object> parameters = new ArrayList<>();
        parameters.add(siteId);
        if (zoneId != null) {
            where.append("and c.zone_id=? ");
            parameters.add(zoneId);
        }
        if (online != null) {
            where.append("and c.online=? ");
            parameters.add(online);
        }
        if (keyword != null && !keyword.isBlank()) {
            where.append("and (lower(c.code) like ? or lower(c.name) like ?) ");
            String term = "%" + keyword.trim().toLowerCase() + "%";
            parameters.add(term);
            parameters.add(term);
        }
        long total = jdbc.queryForObject("select count(*) from camera c" + where,
                Long.class, parameters.toArray());
        PageSpec paging = PageSpec.of(page, pageSize);
        parameters.add(paging.pageSize());
        parameters.add(paging.offset());
        var cameras = jdbc.queryForList(
                "select c.id,c.code,c.name,c.zone_id as zoneId,z.name as zoneName,c.online,c.stream_url as streamUrl,c.last_frame_at as lastFrameAt "
                        + "from camera c join zone z on z.id=c.zone_id" + where
                        + "order by c.code,c.id limit ? offset ?",
                parameters.toArray());
        cameras.forEach(camera -> {
            camera.put("playbackStatus", camera.get("streamUrl") == null ? "NOT_CONFIGURED" : (Boolean.TRUE.equals(camera.get("online")) ? "READY" : "OFFLINE"));
            camera.put("streamProtocol", streamProtocol(camera.get("streamUrl")));
        });
        return ApiResponse.ok(paging.result(cameras, total));
    }

    @PutMapping("/{id}/stream")
    @PreAuthorize("hasAnyRole('ADMIN','SUPERVISOR','DEVICE_MANAGER')")
    public ApiResponse<Map<String, Object>> configureStream(@PathVariable long id,
                                                            @Valid @RequestBody StreamRequest request) {
        SecurityUtil.requireSite(request.siteId());
        List<Map<String, Object>> rows = jdbc.queryForList("select id,site_id as siteId,code from camera where id=?", id);
        if (rows.isEmpty()) throw new AppException(HttpStatus.NOT_FOUND, "CAMERA_NOT_FOUND", "摄像头不存在");
        Map<String, Object> camera = rows.get(0);
        if (((Number) camera.get("siteId")).longValue() != request.siteId()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "CAMERA_SITE_MISMATCH", "摄像头不属于指定工地");
        }
        String streamUrl = request.streamUrl() == null || request.streamUrl().isBlank()
                ? null : endpointPolicy.validateVideoUrl(request.streamUrl().trim(),
                integrationProperties.getVideo().getAllowedHosts()).toString();
        jdbc.update("update camera set stream_url=? where id=?", streamUrl, id);
        String detail = streamUrl == null ? "清除视频流配置" : "配置视频流协议=" + streamProtocol(streamUrl);
        audit.record("CAMERA_STREAM_CONFIGURE", "CAMERA", id, detail);
        Map<String, Object> updated = jdbc.queryForMap(
                "select id,code,name,site_id as siteId,zone_id as zoneId,online,stream_url as streamUrl,last_frame_at as lastFrameAt from camera where id=?",
                id);
        updated.put("playbackStatus", streamUrl == null ? "NOT_CONFIGURED" : (Boolean.TRUE.equals(updated.get("online")) ? "READY" : "OFFLINE"));
        updated.put("streamProtocol", streamProtocol(streamUrl));
        return ApiResponse.ok(updated);
    }

    private String streamProtocol(Object streamUrl) {
        if (streamUrl == null || String.valueOf(streamUrl).isBlank()) return null;
        int separator = String.valueOf(streamUrl).indexOf(':');
        return separator > 0 ? String.valueOf(streamUrl).substring(0, separator).toUpperCase() : "UNKNOWN";
    }

    public record StreamRequest(
            @NotNull(message = "工地不能为空") Long siteId,
            @Size(max = 500, message = "视频流地址不能超过 500 个字符") String streamUrl) {}
}
