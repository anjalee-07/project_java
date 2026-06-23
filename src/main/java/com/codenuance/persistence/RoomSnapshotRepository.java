package com.codenuance.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data repository over {@link RoomSnapshotEntity}. Only loaded under the
 * {@code distributed} profile (JPA auto-config is excluded otherwise).
 */
public interface RoomSnapshotRepository extends JpaRepository<RoomSnapshotEntity, String> {
}
