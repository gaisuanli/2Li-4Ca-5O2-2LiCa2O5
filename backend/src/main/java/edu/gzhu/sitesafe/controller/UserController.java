package edu.gzhu.sitesafe.controller;

import edu.gzhu.sitesafe.common.ApiResponse;
import edu.gzhu.sitesafe.common.AppException;
import edu.gzhu.sitesafe.common.PageSpec;
import edu.gzhu.sitesafe.security.SecurityUtil;
import edu.gzhu.sitesafe.security.SessionService;
import edu.gzhu.sitesafe.service.AuditService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.simple.SimpleJdbcInsert;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/users")
@PreAuthorize("hasRole('ADMIN')")
public class UserController {
    private static final Set<String> ROLES = Set.of("ADMIN", "SUPERVISOR", "DEVICE_MANAGER");

    private final JdbcTemplate jdbc;
    private final PasswordEncoder passwordEncoder;
    private final AuditService audit;
    private final SessionService sessions;

    public UserController(JdbcTemplate jdbc, PasswordEncoder passwordEncoder,
                          AuditService audit, SessionService sessions) {
        this.jdbc = jdbc;
        this.passwordEncoder = passwordEncoder;
        this.audit = audit;
        this.sessions = sessions;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> list(@RequestParam(required = false) String keyword,
                                                 @RequestParam(defaultValue = "1") int page,
                                                 @RequestParam(defaultValue = "20") int pageSize) {
        StringBuilder where = new StringBuilder(" where 1=1 ");
        List<Object> parameters = new ArrayList<>();
        if (keyword != null && !keyword.isBlank()) {
            where.append("and (lower(username) like ? or lower(display_name) like ?) ");
            String term = "%" + keyword.trim().toLowerCase() + "%";
            parameters.add(term);
            parameters.add(term);
        }
        List<Map<String, Object>> matching = jdbc.queryForList(
                "select id,username,display_name as displayName,role,site_scope as siteScope,enabled,created_at as createdAt " +
                        "from app_user" + where + "order by id",
                parameters.toArray());
        Set<Long> allowedSites = SecurityUtil.currentUser().siteIds();
        List<Map<String, Object>> visible = matching.stream()
                .filter(user -> isWithinScope(allowedSites,
                        parseSiteScope(String.valueOf(user.get("siteScope")))))
                .toList();
        PageSpec paging = PageSpec.of(page, pageSize);
        int from = (int) Math.min(paging.offset(), visible.size());
        int to = Math.min(from + paging.pageSize(), visible.size());
        return ApiResponse.ok(paging.result(visible.subList(from, to), visible.size()));
    }

    @PostMapping
    public ApiResponse<Map<String, Object>> create(@Valid @RequestBody CreateRequest request) {
        String role = normalizeRole(request.role());
        String siteScope = normalizeSiteScope(request.siteScope());
        validatePassword(request.password());
        String username = request.username().trim();
        Long existing = jdbc.queryForObject("select count(*) from app_user where lower(username)=lower(?)",
                Long.class, username);
        if (existing != null && existing > 0) {
            throw new AppException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }

        Number key;
        try {
            key = new SimpleJdbcInsert(jdbc)
                    .withTableName("app_user")
                    .usingGeneratedKeyColumns("id")
                    .usingColumns("username", "password_hash", "display_name", "role", "site_scope", "enabled")
                    .executeAndReturnKey(new MapSqlParameterSource()
                            .addValue("username", username)
                            .addValue("password_hash", passwordEncoder.encode(request.password()))
                            .addValue("display_name", request.displayName().trim())
                            .addValue("role", role)
                            .addValue("site_scope", siteScope)
                            .addValue("enabled", true));
        } catch (DuplicateKeyException ex) {
            throw new AppException(HttpStatus.CONFLICT, "USERNAME_EXISTS", "用户名已存在");
        }
        long id = key.longValue();
        audit.record("USER_CREATE", "APP_USER", id, "新增用户 " + username + "，角色 " + role);
        return ApiResponse.ok(find(id));
    }

    @PatchMapping("/{id}/enabled")
    public ApiResponse<Void> enabled(@PathVariable long id, @Valid @RequestBody EnableRequest request) {
        Map<String, Object> target = findManageable(id);
        if (!request.enabled() && SecurityUtil.currentUser().id() == id) {
            throw new AppException(HttpStatus.CONFLICT, "CANNOT_DISABLE_SELF", "不能停用当前登录账号");
        }
        jdbc.update("update app_user set enabled=? where id=?", request.enabled(), id);
        audit.record("USER_ENABLE_CHANGE", "APP_USER", id,
                target.get("username") + " 启用状态改为 " + request.enabled());
        if (!request.enabled()) {
            sessions.removeByUserId(id);
        }
        return ApiResponse.okMessage("用户启用状态已更新");
    }

    @PostMapping("/{id}/reset-password")
    public ApiResponse<Void> resetPassword(@PathVariable long id,
                                           @Valid @RequestBody ResetPasswordRequest request) {
        Map<String, Object> target = findManageable(id);
        validatePassword(request.password());
        jdbc.update("update app_user set password_hash=? where id=?",
                passwordEncoder.encode(request.password()), id);
        audit.record("USER_PASSWORD_RESET", "APP_USER", id,
                "重置用户 " + target.get("username") + " 的密码");
        sessions.removeByUserId(id);
        return ApiResponse.okMessage("用户密码已重置");
    }

    private Map<String, Object> find(long id) {
        List<Map<String, Object>> users = jdbc.queryForList(
                "select id,username,display_name as displayName,role,site_scope as siteScope,enabled,created_at as createdAt " +
                        "from app_user where id=?", id);
        if (users.isEmpty()) {
            throw new AppException(HttpStatus.NOT_FOUND, "USER_NOT_FOUND", "用户不存在");
        }
        return users.get(0);
    }

    private Map<String, Object> findManageable(long id) {
        Map<String, Object> user = find(id);
        if (!isWithinScope(SecurityUtil.currentUser().siteIds(),
                parseSiteScope(String.valueOf(user.get("siteScope"))))) {
            throw new AppException(HttpStatus.FORBIDDEN, "SITE_SCOPE_DENIED", "无权管理该用户的工地范围");
        }
        return user;
    }

    private boolean isWithinScope(Set<Long> allowedSites, Set<Long> targetSites) {
        return !targetSites.isEmpty()
                && targetSites.stream().allMatch(id -> id > 0)
                && allowedSites.containsAll(targetSites);
    }

    private String normalizeRole(String role) {
        String normalized = role.trim().toUpperCase();
        if (!ROLES.contains(normalized)) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_ROLE", "用户角色无效");
        }
        return normalized;
    }

    private String normalizeSiteScope(String value) {
        LinkedHashSet<Long> ids;
        try {
            List<Long> parsed = Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .map(Long::parseLong)
                    .toList();
            if (parsed.stream().anyMatch(id -> id <= 0)) {
                throw new NumberFormatException("site id must be positive");
            }
            ids = parsed.stream()
                    .sorted()
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (NumberFormatException ex) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SITE_SCOPE", "工地权限范围格式无效");
        }
        if (ids.isEmpty()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SITE_SCOPE", "工地权限范围不能为空");
        }
        String placeholders = String.join(",", ids.stream().map(id -> "?").toList());
        Long count = jdbc.queryForObject("select count(*) from site where id in (" + placeholders + ")",
                Long.class, ids.toArray());
        if (count == null || count != ids.size()) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_SITE_SCOPE", "工地权限范围包含不存在的工地");
        }
        if (!SecurityUtil.currentUser().siteIds().containsAll(ids)) {
            throw new AppException(HttpStatus.FORBIDDEN, "SITE_SCOPE_DENIED", "不能授予当前管理员范围外的工地权限");
        }
        return ids.stream().map(String::valueOf).collect(Collectors.joining(","));
    }

    private Set<Long> parseSiteScope(String value) {
        try {
            return Arrays.stream(value.split(","))
                    .map(String::trim)
                    .filter(item -> !item.isBlank())
                    .map(Long::parseLong)
                    .collect(Collectors.toCollection(LinkedHashSet::new));
        } catch (NumberFormatException ex) {
            return Set.of();
        }
    }

    private void validatePassword(String password) {
        if (password.length() < 8 || password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new AppException(HttpStatus.BAD_REQUEST, "INVALID_PASSWORD",
                    "密码至少 8 位且 UTF-8 编码后不能超过 72 字节");
        }
    }

    public record CreateRequest(
            @NotBlank(message = "用户名不能为空")
            @Pattern(regexp = "[A-Za-z0-9_.-]{3,50}", message = "用户名只能包含字母、数字、点、下划线或连字符，长度 3 到 50 位") String username,
            @NotBlank(message = "密码不能为空") @Size(min = 8, max = 72, message = "密码长度必须为 8 到 72 位") String password,
            @NotBlank(message = "显示名称不能为空") @Size(max = 100, message = "显示名称不能超过 100 个字符") String displayName,
            @NotBlank(message = "用户角色不能为空") String role,
            @NotBlank(message = "工地权限范围不能为空") String siteScope
    ) {}

    public record EnableRequest(@NotNull(message = "启用状态不能为空") Boolean enabled) {}

    public record ResetPasswordRequest(
            @NotBlank(message = "密码不能为空") @Size(min = 8, max = 72, message = "密码长度必须为 8 到 72 位") String password
    ) {}
}
