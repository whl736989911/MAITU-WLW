package com.fireplatform.alarm;

import java.util.List;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

@Component
public class AlarmChangeChannelHandler extends AbstractWebSocketHandler implements SubProtocolCapable {
    private final AlarmChangeChannelRuntime runtime;
    public AlarmChangeChannelHandler(AlarmChangeChannelRuntime runtime){this.runtime=runtime;}
    @Override public List<String> getSubProtocols(){return List.of(AlarmChangeChannelHandshake.PROTOCOL);}
    @Override public void afterConnectionEstablished(WebSocketSession session){runtime.open(session);}
    @Override protected void handleTextMessage(WebSocketSession session,TextMessage message){runtime.protocol(session,message.asBytes().length);}
    @Override protected void handleBinaryMessage(WebSocketSession session,BinaryMessage message){runtime.protocol(session,message.getPayloadLength());}
    @Override public void handleTransportError(WebSocketSession session,Throwable exception){runtime.failed(session);}
    @Override public void afterConnectionClosed(WebSocketSession session,CloseStatus status){runtime.closed(session);}
}
