package com.codenuance.messaging;

import java.util.function.BiConsumer;

/**
 * Cross-instance fan-out for room traffic. The single-node default
 * ({@link NoopMessageBus}) does nothing; the {@code distributed} profile swaps in
 * {@link RedisMessageBus}, which relays messages between server instances over
 * Redis Pub/Sub so a room's edits, cursors and presence reach collaborators no
 * matter which instance they connected to.
 *
 * <p>Operational Transformation still requires a single authority per room, so a
 * load balancer should route all connections for a given room id to the same
 * instance (sticky routing). The bus then carries traffic for rooms that happen
 * to be split across instances and lets the cluster scale across many rooms.
 */
public interface MessageBus {

    /** Publish an already-serialized client message for {@code roomId} to other instances. */
    void publish(String roomId, String messageJson);

    /**
     * Register the relay invoked when a message arrives from <em>another</em> instance.
     * The consumer receives {@code (roomId, messageJson)} and should deliver the
     * message to local WebSocket sessions in that room. Messages this instance
     * published itself are filtered out before the relay is called.
     */
    void onRemoteMessage(BiConsumer<String, String> relay);
}
