package com.codenuance.persistence;

import com.codenuance.session.Room;
import com.codenuance.session.RoomManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Periodically persists every live room to the {@link SnapshotStore} so documents
 * survive a crash. Only active when persistence is enabled (distributed profile).
 *
 * <p>To avoid redundant writes, a room is only saved when its revision has advanced
 * since the last snapshot — an idle room costs nothing.
 */
@Component
@ConditionalOnProperty(name = "codenuance.persistence.enabled", havingValue = "true")
public class SnapshotScheduler {

    private static final Logger log = LoggerFactory.getLogger(SnapshotScheduler.class);

    private final RoomManager rooms;
    private final SnapshotStore store;

    /** roomId -> last revision we persisted, so unchanged rooms are skipped. */
    private final Map<String, Integer> lastSavedRevision = new ConcurrentHashMap<>();

    public SnapshotScheduler(RoomManager rooms, SnapshotStore store) {
        this.rooms = rooms;
        this.store = store;
    }

    @Scheduled(fixedRateString = "${codenuance.persistence.snapshot-interval-ms:5000}")
    public void persistDirtyRooms() {
        int saved = 0;
        for (Room room : rooms.allRooms()) {
            Room.Snapshot snapshot = room.snapshot();
            Integer last = lastSavedRevision.get(snapshot.id());
            if (last != null && last == snapshot.revision()) {
                continue; // unchanged since last snapshot
            }
            try {
                store.save(snapshot);
                lastSavedRevision.put(snapshot.id(), snapshot.revision());
                saved++;
            } catch (RuntimeException ex) {
                log.warn("failed to snapshot room {}: {}", snapshot.id(), ex.getMessage());
            }
        }
        if (saved > 0) {
            log.debug("persisted {} room snapshot(s)", saved);
        }
    }
}
