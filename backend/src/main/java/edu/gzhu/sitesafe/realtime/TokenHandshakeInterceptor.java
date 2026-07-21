package edu.gzhu.sitesafe.realtime;

import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.util.List;
import java.util.Map;

/**
 * WebSocket 握手拦截器：从 Sec-WebSocket-Protocol 子协议字段提取 Bearer Token。
 *
 * <p>浏览器端 {@code new WebSocket(url, ['bearer.' + token])} 会把 token 放入
 * Sec-WebSocket-Protocol 请求头，而不是 URL 查询串。这样 token 不会出现在
 * Nginx access.log、浏览器 Referrer 或中间设备的 URL 记录中，安全性更高。
 *
 * <p>本拦截器在握手阶段把 token 和完整的协议名存入 attributes，
 * 供 {@link RealtimeHub} 在连接建立后读取。
 *
 * <p>兼容性：如果客户端仍通过 {@code ?token=xxx} 查询串传 token，
 * 旧路径继续工作（在 RealtimeHub.extractToken 中回退）。
 */
@Component
public class TokenHandshakeInterceptor implements HandshakeInterceptor {

    public static final String TOKEN_ATTRIBUTE = "ws.auth.token";
    public static final String PROTOCOL_ATTRIBUTE = "ws.auth.protocol";
    public static final String BEARER_PREFIX = "bearer.";

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token = extractTokenFromProtocol(request, attributes);
        if (token != null) {
            attributes.put(TOKEN_ATTRIBUTE, token);
            return true;
        }
        // 没有从子协议提取到 token，回退到 query 参数（兼容旧客户端）
        // RealtimeHub.extractToken 会处理 query 参数
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
        // no-op
    }

    /**
     * 从 Sec-WebSocket-Protocol 头中提取 bearer token。
     * 同时把完整的协议名（如 "bearer.abc123"）存入 attributes，
     * 供 HandshakeHandler 在响应中回显。
     */
    private String extractTokenFromProtocol(ServerHttpRequest request,
                                            Map<String, Object> attributes) {
        List<String> protocols = request.getHeaders().get("Sec-WebSocket-Protocol");
        if (protocols == null) {
            return null;
        }
        for (String protocolHeader : protocols) {
            for (String item : protocolHeader.split(",")) {
                String trimmed = item.trim();
                if (trimmed.startsWith(BEARER_PREFIX)) {
                    String token = trimmed.substring(BEARER_PREFIX.length());
                    attributes.put(PROTOCOL_ATTRIBUTE, trimmed);
                    return token;
                }
            }
        }
        return null;
    }
}
