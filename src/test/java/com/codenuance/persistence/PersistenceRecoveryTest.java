package com.codenuance.persistence;

import com.codenuance.ot.TextOperation;
import com.codenuance.session.Room;
import com.codenuance.session.RoomManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Validates the PostgreSQL persistence/recovery path end-to-end against an
 * embedded H2 database (Postgres compatibility mode), without needing a real
 * PostgreSQL. Turns on the persistence beans the same way the {@code distributed}
 * profile does, then proves a room is snapshotted on eviction and restored on the
 * next join.
 */
@SpringBootTest(properties = {
        // The default profile excludes JPA auto-config; re-enable it for this test.
        "spring.autoconfigure.exclude=",
        "codenuance.persistence.enabled=true",
        "codenuance.redis.enabled=false",
        "spring.datasource.url=jdbc:h2:mem:cn;MODE=PostgreSQL;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.hibernate.ddl-auto=create-drop"
})
class PersistenceRecoveryTest {

    @Autowired
    RoomManager rooms;

    @Autowired
    SnapshotStore store;

    @Test
    void persistenceStoreIsTheJpaBacked() {
        assertTrue(store instanceof JpaSnapshotStore,
                "with persistence enabled the JPA store should be wired, not the no-op");
    }

    @Test
    void roomRecoversFromSnapshotAfterEviction() {
        String roomId = "recover-me";

        // Create a room and make an edit.
        Room room = rooms.getOrCreate(roomId, "python");
        Room.Snapshot before = room.snapshot();
        TextOperation edit = new TextOperation()
                .retain(before.contents().length())
                .insert("# recovered\n");
        room.applyOperation("c1", 0, before.revision(), edit);

        String expectedContents = room.snapshot().contents();
        int expectedRevision = room.snapshot().revision();

        // Last peer leaves -> RoomManager persists a final snapshot and evicts it.
        rooms.removeIfEmpty(roomId);
        assertTrue(store.load(roomId).isPresent(), "snapshot should be persisted on eviction");

        // Rejoining recovers the document from PostgreSQL/H2.
        Room recovered = rooms.getOrCreate(roomId, "python");
        assertNotSame(room, recovered, "the room was evicted, so this is a fresh instance");
        assertEquals(expectedContents, recovered.snapshot().contents(),
                "recovered contents must match what was persisted");
        assertEquals("python", recovered.getLanguage(), "language is recovered too");
        assertEquals(expectedRevision, recovered.snapshot().revision(),
                "revision stays monotonic across recovery");
    }
}
