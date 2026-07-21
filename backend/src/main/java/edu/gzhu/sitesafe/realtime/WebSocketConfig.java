package edu.gzhu.sitesafe.realtime;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final RealtimeHub hub;
    private final TokenHandshakeInterceptor tokenInterceptor;
    private final EchoProtocolHandshakeHandler handshakeHandler;

    public WebSocketConfig(RealtimeHub hub,
                           TokenHandshakeInterceptor tokenInterceptor,
                           EchoProtocolHandshakeHandler handshakeHandler) {
        this.hub = hub;
        this.tokenInterceptor = tokenInterceptor;
        this.handshakeHandler = handshakeHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(hub, "/ws/events")
                .addInterceptors(tokenInterceptor)
                .setHandshakeHandler(handshakeHandler)
                .setAllowedOriginPatterns("http://localhost:*", "http://127.0.0.1:*");
    }
}
