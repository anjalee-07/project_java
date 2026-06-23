package com.codenuance.persistence;

import com.codenuance.session.Room;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

/**
 * PostgreSQL-backed {@link SnapshotStore}, active under the {@code distributed}
 * profile when {@code codenuance.persistence.enabled=true}. Upserts one row per
 * room so the latest snapshot can be recovered after a crash or restart.
 */
@Component
@ConditionalOnProperty(name = "codenuance.persistence.enabled", havingValue = "true")
public class JpaSnapshotStore implements SnapshotStore {

    private final RoomSnapshotRepository repository;

    public JpaSnapshotStore(RoomSnapshotRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(Room.Snapshot snapshot) {
        RoomSnapshotEntity entity = repository.findById(snapshot.id())
                .map(existing -> {
                    existing.update(snapshot.name(), snapshot.language(), snapshot.contents(), snapshot.revision());
                    return existing;
                })
                .orElseGet(() -> new RoomSnapshotEntity(
                        snapshot.id(), snapshot.name(), snapshot.language(),
                        snapshot.contents(), snapshot.revision()));
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Room.Snapshot> load(String roomId) {
        return repository.findById(roomId)
                .map(e -> new Room.Snapshot(e.getRoomId(), e.getName(), e.getLanguage(),
                        e.getContents(), e.getRevision()));
    }
}
