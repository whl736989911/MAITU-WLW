package com.fireplatform.alarm;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.*;

/** Raw WS only, no SockJS/STOMP/broker or additional listener. */
@Configuration @EnableWebSocket
public class AlarmChangeChannelWebConfig implements WebSocketConfigurer {
    private final AlarmChangeChannelHandler handler;private final AlarmChangeChannelHandshake handshake;private final com.fireplatform.configuration.AlarmChangeChannelPolicy policy;
    public AlarmChangeChannelWebConfig(AlarmChangeChannelHandler handler,AlarmChangeChannelHandshake handshake,com.fireplatform.configuration.AlarmChangeChannelPolicy policy){this.handler=handler;this.handshake=handshake;this.policy=policy;}
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry){registry.addHandler(handler,"/api/management/alerts","/api/customer/alerts").addInterceptors(handshake).setAllowedOrigins(policy.origins().toArray(String[]::new));}
}
