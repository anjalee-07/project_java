package com.codenuance.config;

import com.codenuance.messaging.MessageBus;
import com.codenuance.session.RoomManager;
import com.codenuance.ws.CollabWebSocketHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * Wires the collaboration handler onto {@code /ws/collab/**}. The trailing path
 * segment is the room id, e.g. {@code /ws/collab/sunset-loft}.
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final RoomManager roomManager;
    private final MessageBus messageBus;

    public WebSocketConfig(RoomManager roomManager, MessageBus messageBus) {
        this.roomManager = roomManager;
        this.messageBus = messageBus;
    }

    @Bean
    public CollabWebSocketHandler collabWebSocketHandler() {
        return new CollabWebSocketHandler(roomManager, messageBus);
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(collabWebSocketHandler(), "/ws/collab/**")
                .setAllowedOriginPatterns("*");
    }
}
