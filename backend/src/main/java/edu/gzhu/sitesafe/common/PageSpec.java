package edu.gzhu.sitesafe.common;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Shared one-based pagination normalization and response contract.
 */
public record PageSpec(int page, int pageSize, long offset) {
    public static PageSpec of(int page, int pageSize) {
        int safePage = Math.max(1, page);
        int safeSize = Math.max(1, Math.min(pageSize, 100));
        return new PageSpec(safePage, safeSize, (long) (safePage - 1) * safeSize);
    }

    public Map<String, Object> result(List<?> items, long total) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("items", items);
        result.put("total", total);
        result.put("page", page);
        result.put("pageSize", pageSize);
        return result;
    }
}
