package edu.gzhu.sitesafe.realtime;

import org.springframework.stereotype.Component;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.support.DefaultHandshakeHandler;

import java.util.List;

/**
 * 自定义握手处理器：回显 Sec-WebSocket-Protocol 子协议。
 *
 * <p>按 RFC 6455，服务端必须在响应中回显 Sec-WebSocket-Protocol，
 * 浏览器才会接受连接；否则浏览器会报 "WebSocket connection failed"。
 *
 * <p>Spring 默认的 {@link DefaultHandshakeHandler} 只回显
 * {@link #getSupportedProtocols()} 中声明的协议。本类重写
 * {@link #selectProtocol(List, WebSocketHandler)}，把客户端请求的
 * bearer.&lt;token&gt; 协议视为受支持并回显，从而让浏览器接受连接。
 *
 * <p>Token 本身由 {@link TokenHandshakeInterceptor} 在握手前提取并存入
 * attributes；本类只负责协议回显。
 */
@Component
public class EchoProtocolHandshakeHandler extends DefaultHandshakeHandler {

    @Override
    protected String selectProtocol(List<String> requestedProtocols, WebSocketHandler webSocketHandler) {
        if (requestedProtocols != null) {
            for (String protocol : requestedProtocols) {
                if (protocol != null && protocol.startsWith(TokenHandshakeInterceptor.BEARER_PREFIX)) {
                    // 回显完整的 bearer.<token> 协议名，让浏览器接受连接
                    return protocol;
                }
            }
        }
        // 没有子协议需求，返回 null（不回显 Sec-WebSocket-Protocol）
        return null;
    }
}
