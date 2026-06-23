package com.codenuance.persistence;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Turns on Spring's scheduler so {@link SnapshotScheduler} can run its periodic
 * auto-save. Only active when persistence is enabled, so the single-node default
 * starts no background threads.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "codenuance.persistence.enabled", havingValue = "true")
public class PersistenceConfig {
}
