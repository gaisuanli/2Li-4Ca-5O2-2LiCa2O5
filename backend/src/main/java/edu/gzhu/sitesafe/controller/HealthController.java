package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Map;

@RestController
@RequestMapping("/api/health")
public class HealthController {
    private final JdbcTemplate jdbc;

    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> health() {
        Integer database = jdbc.queryForObject("select 1", Integer.class);
        return ApiResponse.ok(Map.of(
                "status", "UP",
                "database", database != null && database == 1 ? "UP" : "DOWN",
                "service", "building-safety-api",
                "checkedAt", Instant.now()
        ));
    }
}
