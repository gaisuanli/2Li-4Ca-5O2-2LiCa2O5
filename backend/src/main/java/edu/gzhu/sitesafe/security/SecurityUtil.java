package edu.gzhu.sitesafe.security;

import edu.gzhu.sitesafe.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

public final class SecurityUtil {
    private SecurityUtil() {}

    public static UserSession currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserSession session)) {
            throw new AppException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "登录状态已失效");
        }
        return session;
    }

    public static void requireSite(long siteId) {
        UserSession user = currentUser();
        if (!user.siteIds().contains(siteId)) {
            throw new AppException(HttpStatus.FORBIDDEN, "SITE_SCOPE_DENIED", "无权访问该工地数据");
        }
    }
}
