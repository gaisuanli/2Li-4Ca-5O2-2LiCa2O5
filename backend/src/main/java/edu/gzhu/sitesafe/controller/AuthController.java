package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.SessionService;
import edu.gzhu.sitesafe.security.UserSession;
import edu.gzhu.sitesafe.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final SessionService sessions;
    private final AuditService auditService;

    public AuthController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder, SessionService sessions, AuditService auditService) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.sessions = sessions;
        this.auditService = auditService;
    }

    @PostMapping("/login")
    public ApiResponse<SessionService.LoginSession> login(@Valid @RequestBody LoginRequest request) {
        var users = jdbc.queryForList("select id, username, password_hash, display_name, role, site_scope, enabled from app_user where username = ?", request.username());
        if (users.isEmpty()) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
        }
        Map<String, Object> user = users.get(0);
        if (!Boolean.TRUE.equals(user.get("enabled")) || !passwordEncoder.matches(request.password(), String.valueOf(user.get("password_hash")))) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "用户名或密码错误");
        }
        Set<Long> siteIds = Arrays.stream(String.valueOf(user.get("site_scope")).split(","))
                .map(String::trim).filter(value -> !value.isBlank()).map(Long::parseLong).collect(Collectors.toSet());
        var session = sessions.create(((Number) user.get("id")).longValue(), String.valueOf(user.get("username")),
                String.valueOf(user.get("display_name")), String.valueOf(user.get("role")), siteIds);
        return ApiResponse.ok(session);
    }

    @GetMapping("/me")
    public ApiResponse<UserSession> me() {
        return ApiResponse.ok(SecurityUtil.currentUser());
    }

    @PostMapping("/refresh")
    public ApiResponse<SessionService.LoginSession> refresh(Authentication authentication) {
        String oldToken = authentication == null ? null : String.valueOf(authentication.getCredentials());
        SessionService.LoginSession refreshed = sessions.refresh(oldToken)
                .orElseThrow(() -> new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录状态已失效"));
        auditService.record("TOKEN_REFRESH", "SESSION", null, "用户刷新登录令牌");
        return ApiResponse.ok(refreshed);
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(Authentication authentication) {
        auditService.record("LOGOUT", "SESSION", null, "用户退出登录");
        sessions.remove(authentication == null ? null : String.valueOf(authentication.getCredentials()));
        return ApiResponse.okMessage("已退出登录");
    }

    public record LoginRequest(@NotBlank(message = "请输入用户名") String username,
                               @NotBlank(message = "请输入密码") String password) {}
}
