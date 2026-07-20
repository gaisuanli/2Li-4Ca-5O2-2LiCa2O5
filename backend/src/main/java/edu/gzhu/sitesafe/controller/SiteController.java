package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.UserSession;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class SiteController {
    private final JdbcTemplate jdbc;

    public SiteController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/sites")
    public ApiResponse<List<Map<String, Object>>> sites() {
        UserSession user = SecurityUtil.currentUser();
        if (user.siteIds().isEmpty()) return ApiResponse.ok(List.of());
        String placeholders = String.join(",", user.siteIds().stream().map(id -> "?").toList());
        return ApiResponse.ok(jdbc.queryForList("select id, code, name, address, status, updated_at as updatedAt from site where id in (" + placeholders + ") order by id", user.siteIds().toArray()));
    }

    @GetMapping("/sites/{siteId}/zones")
    public ApiResponse<List<Map<String, Object>>> zones(@PathVariable long siteId) {
        SecurityUtil.requireSite(siteId);
        return ApiResponse.ok(jdbc.queryForList("select z.id, z.code, z.name, z.status, z.map_x as mapX, z.map_y as mapY, z.map_width as mapWidth, z.map_height as mapHeight, count(d.id) as deviceCount, sum(case when d.connection_status='ONLINE' then 1 else 0 end) as onlineCount from zone z left join device d on d.zone_id=z.id where z.site_id=? group by z.id,z.code,z.name,z.status,z.map_x,z.map_y,z.map_width,z.map_height order by z.id", siteId));
    }

    @GetMapping("/zones/{zoneId}/devices")
    public ApiResponse<List<Map<String, Object>>> zoneDevices(@PathVariable long zoneId) {
        List<Long> siteIds = jdbc.query("select site_id from zone where id=?",
                (resultSet, rowNumber) -> resultSet.getLong(1), zoneId);
        if (siteIds.isEmpty()) return ApiResponse.ok(List.of());
        SecurityUtil.requireSite(siteIds.get(0));
        return ApiResponse.ok(jdbc.queryForList("select id, code, name, type, location, enabled, connection_status as connectionStatus, last_reported_at as lastReportedAt from device where zone_id=? order by type,code", zoneId));
    }
}
