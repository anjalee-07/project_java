package com.codenuance.web;

import com.codenuance.session.Room;
import com.codenuance.session.RoomManager;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Tiny REST surface used by the landing page to show which rooms are live.
 */
@RestController
@RequestMapping("/api/rooms")
public class RoomController {

    private final RoomManager rooms;

    public RoomController(RoomManager rooms) {
        this.rooms = rooms;
    }

    @GetMapping
    public List<Map<String, Object>> activeRooms() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Room room : rooms.allRooms()) {
            if (room.getPeerCount() == 0) {
                continue;
            }
            out.add(Map.of(
                    "id", room.getId(),
                    "name", room.getName(),
                    "language", room.getLanguage(),
                    "peers", room.getPeerCount(),
                    "revision", room.getRevision()));
        }
        out.sort(Comparator.comparingInt((Map<String, Object> m) -> (int) m.get("peers")).reversed());
        return out;
    }
}
