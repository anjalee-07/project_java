package com.codenuance.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;

/**
 * The persisted state of one room: enough to fully reconstruct its document after
 * a crash or restart. One row per room, keyed by room id.
 */
@Entity
@Table(name = "room_snapshot")
public class RoomSnapshotEntity {

    @Id
    @Column(name = "room_id", nullable = false, updatable = false)
    private String roomId;

    @Column(name = "name", nullable = false)
    private String name;

    @Column(name = "language", nullable = false)
    private String language;

    @Column(name = "contents", nullable = false, columnDefinition = "text")
    private String contents;

    @Column(name = "revision", nullable = false)
    private int revision;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected RoomSnapshotEntity() {
        // for JPA
    }

    public RoomSnapshotEntity(String roomId, String name, String language, String contents, int revision) {
        this.roomId = roomId;
        this.name = name;
        this.language = language;
        this.contents = contents;
        this.revision = revision;
        this.updatedAt = Instant.now();
    }

    public String getRoomId() {
        return roomId;
    }

    public String getName() {
        return name;
    }

    public String getLanguage() {
        return language;
    }

    public String getContents() {
        return contents;
    }

    public int getRevision() {
        return revision;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void update(String name, String language, String contents, int revision) {
        this.name = name;
        this.language = language;
        this.contents = contents;
        this.revision = revision;
        this.updatedAt = Instant.now();
    }
}
