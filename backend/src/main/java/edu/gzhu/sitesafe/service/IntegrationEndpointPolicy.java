package edu.gzhu.sitesafe.service;

import edu.gzhu.sitesafe.common.AppException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.List;
import java.util.Locale;

@Component
public class IntegrationEndpointPolicy {
    public URI approvedBase(String requested, List<String> allowedBases, boolean allowHttpLoopback,
                            String integrationName) {
        URI uri = parse(normalize(requested), integrationName);
        List<String> allowed = allowedBases == null ? List.of()
                : allowedBases.stream().filter(item -> item != null && !item.isBlank())
                .map(this::normalize).toList();
        if (!allowed.contains(normalize(uri.toString()))) {
            throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRATION_ENDPOINT_NOT_ALLOWED",
                    integrationName + "地址未加入服务端白名单");
        }
        if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) {
            throw invalid(integrationName);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if ("https".equals(scheme)) return uri;
        if ("http".equals(scheme) && allowHttpLoopback && isLoopback(uri.getHost())) return uri;
        throw new AppException(HttpStatus.SERVICE_UNAVAILABLE, "INTEGRATION_ENDPOINT_INSECURE",
                integrationName + "地址必须使用 HTTPS");
    }

    public URI resolve(URI base, String relativePath) {
        String root = normalize(base.toString());
        String path = relativePath.startsWith("/") ? relativePath : "/" + relativePath;
        return URI.create(root + path);
    }

    public URI validateVideoUrl(String requested, List<String> allowedHosts) {
        URI uri = parse(requested == null ? "" : requested.trim(), "视频流");
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!List.of("https", "http", "rtsp", "rtmp").contains(scheme)
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null) {
            throw invalid("视频流");
        }
        List<String> hosts = allowedHosts == null ? List.of()
                : allowedHosts.stream().filter(item -> item != null && !item.isBlank())
                .map(item -> item.trim().toLowerCase(Locale.ROOT)).toList();
        if (!hosts.contains(uri.getHost().toLowerCase(Locale.ROOT))) {
            throw new AppException(HttpStatus.BAD_REQUEST, "VIDEO_HOST_NOT_ALLOWED", "视频流主机未加入服务端白名单");
        }
        return uri;
    }

    public boolean isLoopback(String host) {
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (Exception ex) {
            return false;
        }
    }

    private URI parse(String value, String integrationName) {
        try {
            return new URI(value);
        } catch (URISyntaxException ex) {
            throw invalid(integrationName);
        }
    }

    private AppException invalid(String integrationName) {
        return new AppException(HttpStatus.BAD_REQUEST, "INVALID_INTEGRATION_ENDPOINT",
                integrationName + "地址格式无效");
    }

    private String normalize(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        while (normalized.endsWith("/") && normalized.length() > 1) {
            normalized = normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }
}
