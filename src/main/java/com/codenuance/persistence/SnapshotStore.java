package com.codenuance.persistence;

import com.codenuance.session.Room;

import java.util.Optional;

/**
 * Durability seam for room documents. The single-node default ({@link NoopSnapshotStore})
 * keeps everything in memory; the {@code distributed} profile swaps in
 * {@link JpaSnapshotStore} to persist snapshots to PostgreSQL for crash recovery.
 *
 * <p>Implementations must be safe to call from many WebSocket threads at once.
 */
public interface SnapshotStore {

    /** Persist (insert or update) the room's current state. */
    void save(Room.Snapshot snapshot);

    /** Load a previously persisted snapshot for {@code roomId}, if one exists. */
    Optional<Room.Snapshot> load(String roomId);
}
