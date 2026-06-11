package com.codenuance.session;

import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Owns the lifecycle of every {@link Room} in the process.
 *
 * <p>Rooms are kept in a {@link ConcurrentHashMap} so any number of WebSocket
 * threads can look one up without contention. {@code computeIfAbsent} makes room
 * creation atomic — two people hitting the same fresh room id at once still share
 * a single document.
 *
 * <p>State is in-memory by design so the app runs with zero external services.
 * The seam for durability is deliberately narrow: swapping this map for a Redis
 * client (Lettuce/Jedis) keyed by room id is all that stands between this and a
 * horizontally scalable deployment.
 */
@Component
public class RoomManager {

    private final Map<String, Room> rooms = new ConcurrentHashMap<>();
    private final AtomicInteger colorCursor = new AtomicInteger();

    /** Presence accent colours derived from the project palette. */
    private static final String[] PEER_COLORS = {
            "#C1DBE8", // pastel blue
            "#FFF1B5", // buttermilk
            "#E8B7A0", // dusty clay
            "#A7C4A0", // sage
            "#D8A7C1", // mauve
            "#9FB7CE", // slate blue
    };

    /**
     * Returns the room, creating it on first use. The {@code requestedLanguage}
     * only takes effect when the room is created — once a room exists, joiners
     * adopt whatever language it is already set to.
     */
    public Room getOrCreate(String roomId, String requestedLanguage) {
        String language = Languages.normalize(requestedLanguage);
        return rooms.computeIfAbsent(roomId, id ->
                new Room(id, prettyName(id), language, Languages.starterFor(language)));
    }

    public Room get(String roomId) {
        return rooms.get(roomId);
    }

    public Collection<Room> allRooms() {
        return rooms.values();
    }

    /** Hands out palette colours round-robin so peers stay visually distinct. */
    public String nextColor() {
        int i = Math.floorMod(colorCursor.getAndIncrement(), PEER_COLORS.length);
        return PEER_COLORS[i];
    }

    /** Drops a room once the last collaborator leaves, freeing its memory. */
    public void removeIfEmpty(String roomId) {
        rooms.computeIfPresent(roomId, (id, room) -> room.getPeerCount() == 0 ? null : room);
    }

    private static String prettyName(String id) {
        if (id == null || id.isBlank()) {
            return "Untitled room";
        }
        String cleaned = id.replace('-', ' ').replace('_', ' ').trim();
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }
}
