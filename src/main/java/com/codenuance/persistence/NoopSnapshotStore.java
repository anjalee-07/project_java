package com.codenuance.persistence;

import com.codenuance.session.Room;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * Default {@link SnapshotStore}: does nothing. Active whenever persistence is off
 * (the single-node default), so {@code RoomManager} always has a store to call
 * without null checks. Rooms live only in memory and are lost on restart.
 */
@Component
@ConditionalOnProperty(name = "codenuance.persistence.enabled", havingValue = "false", matchIfMissing = true)
public class NoopSnapshotStore implements SnapshotStore {

    @Override
    public void save(Room.Snapshot snapshot) {
        // no-op: in-memory only
    }

    @Override
    public Optional<Room.Snapshot> load(String roomId) {
        return Optional.empty();
    }
}
