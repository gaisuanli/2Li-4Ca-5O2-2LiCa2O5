package edu.gzhu.sitesafe.security;

import java.time.Instant;
import java.util.Set;

public record UserSession(
        long id,
        String username,
        String displayName,
        String role,
        Set<Long> siteIds,
        Instant expiresAt
) {}
