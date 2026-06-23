package com.codenuance.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.function.BiConsumer;

/**
 * Default {@link MessageBus}: single-instance, no fan-out. Active whenever Redis is
 * off, so {@code CollabWebSocketHandler} can always publish without null checks.
 */
@Component
@ConditionalOnProperty(name = "codenuance.redis.enabled", havingValue = "false", matchIfMissing = true)
public class NoopMessageBus implements MessageBus {

    @Override
    public void publish(String roomId, String messageJson) {
        // no-op: nothing else to fan out to
    }

    @Override
    public void onRemoteMessage(BiConsumer<String, String> relay) {
        // no remote messages ever arrive
    }
}
