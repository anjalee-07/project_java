package com.codenuance.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

/**
 * Supplies the {@link RedisMessageListenerContainer} that {@link RedisMessageBus}
 * subscribes on. Active only when Redis fan-out is enabled (distributed profile);
 * the connection factory itself is auto-configured by Spring Boot from
 * {@code spring.data.redis.*}.
 */
@Configuration
@ConditionalOnProperty(name = "codenuance.redis.enabled", havingValue = "true")
public class RedisConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        return container;
    }
}
