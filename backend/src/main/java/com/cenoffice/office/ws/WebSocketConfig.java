package com.cenoffice.office.ws;

import com.cenoffice.common.config.AppProperties;
import java.util.Map;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.HandshakeInterceptor;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final WsBroadcaster handler;
    private final JwtDecoder decoder;
    private final AppProperties props;

    public WebSocketConfig(WsBroadcaster handler, JwtDecoder decoder, AppProperties props) {
        this.handler = handler;
        this.decoder = decoder;
        this.props = props;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws")
                .addInterceptors(new HandshakeInterceptor() {
                    @Override
                    public boolean beforeHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Map<String, Object> attrs) {
                        // 브라우저 WebSocket 은 헤더를 못 붙이므로 쿼리의 JWT 를 검증한다.
                        String token = ((ServletServerHttpRequest) req).getServletRequest().getParameter("token");
                        if (token == null || token.isBlank()) return false;
                        try {
                            var jwt = decoder.decode(token);
                            attrs.put("user", jwt.getSubject());
                            attrs.put("role", jwt.getClaimAsString("role"));
                            if (jwt.getExpiresAt() != null) attrs.put("exp", jwt.getExpiresAt());
                            return true;
                        } catch (Exception e) {
                            return false;
                        }
                    }

                    @Override
                    public void afterHandshake(ServerHttpRequest req, ServerHttpResponse res, WebSocketHandler h, Exception ex) {}
                })
                .setAllowedOrigins(props.cors().allowedOrigins().toArray(new String[0]));
    }
}
