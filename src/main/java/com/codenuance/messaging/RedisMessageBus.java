package com.codenuance.messaging;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.function.BiConsumer;

/**
 * Redis Pub/Sub implementation of {@link MessageBus}. Each room maps to a channel
 * ({@code <prefix><roomId>}); messages are wrapped with this instance's id so a
 * node ignores its own echo. Active under the {@code distributed} profile when
 * {@code codenuance.redis.enabled=true}.
 */
@Component
@ConditionalOnProperty(name = "codenuance.redis.enabled", havingValue = "true")
public class RedisMessageBus implements MessageBus {

    private static final Logger log = LoggerFactory.getLogger(RedisMessageBus.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper json = new ObjectMapper();
    private final String channelPrefix;
    private final String instanceId;

    private volatile BiConsumer<String, String> relay;

    public RedisMessageBus(StringRedisTemplate redis,
                           RedisMessageListenerContainer container,
                           org.springframework.core.env.Environment env) {
        this.redis = redis;
        this.channelPrefix = env.getProperty("codenuance.redis.channel-prefix", "codenuance:room:");
        this.instanceId = env.getProperty("codenuance.instance-id", "node-" + Long.toHexString(System.nanoTime()));

        container.addMessageListener(this::onMessage, new PatternTopic(channelPrefix + "*"));
        log.info("Redis message bus active (instance={}, channelPrefix={})", instanceId, channelPrefix);
    }

    @Override
    public void publish(String roomId, String messageJson) {
        try {
            ObjectNode envelope = json.createObjectNode();
            envelope.put("origin", instanceId);
            envelope.put("payload", messageJson);
            redis.convertAndSend(channelPrefix + roomId, json.writeValueAsString(envelope));
        } catch (RuntimeException | com.fasterxml.jackson.core.JsonProcessingException ex) {
            log.warn("failed to publish to room {}: {}", roomId, ex.getMessage());
        }
    }

    @Override
    public void onRemoteMessage(BiConsumer<String, String> relay) {
        this.relay = relay;
    }

    private void onMessage(Message message, byte[] pattern) {
        BiConsumer<String, String> r = relay;
        if (r == null) {
            return;
        }
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            JsonNode envelope = json.readTree(new String(message.getBody(), StandardCharsets.UTF_8));
            if (instanceId.equals(envelope.path("origin").asText())) {
                return; // our own message echoed back — ignore
            }
            String roomId = channel.startsWith(channelPrefix) ? channel.substring(channelPrefix.length()) : channel;
            r.accept(roomId, envelope.path("payload").asText());
        } catch (Exception ex) {
            log.warn("failed to relay redis message: {}", ex.getMessage());
        }
    }
}
