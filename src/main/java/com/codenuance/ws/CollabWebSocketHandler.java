package com.codenuance.ws;

import com.codenuance.messaging.MessageBus;
import com.codenuance.ot.TextOperation;
import com.codenuance.session.Languages;
import com.codenuance.session.Peer;
import com.codenuance.session.Room;
import com.codenuance.session.RoomManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bridges raw WebSocket frames to the {@link RoomManager} / OT engine.
 *
 * <p>The wire protocol is small JSON messages. From the client:
 * <ul>
 *   <li>{@code {type:"op", revision, operation:[...]}} — an edit composed against {@code revision}</li>
 *   <li>{@code {type:"selection", anchor, head}} — cursor/selection movement</li>
 * </ul>
 * From the server: {@code init}, {@code ack}, {@code op}, {@code selection},
 * {@code peers}, {@code peer-left}, and {@code resync}. This mirrors the
 * ShareDB/ot.js handshake so the browser can run a standard client-side OT state
 * machine.
 *
 * <p>The room id is taken from the connection path: {@code /ws/collab/{roomId}}.
 * A single instance handles every connection (Spring registers it as a singleton),
 * so all shared state here is concurrent.
 */
public class CollabWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(CollabWebSocketHandler.class);

    private static final String ATTR_ROOM = "roomId";
    private static final String ATTR_CLIENT = "clientId";

    private final RoomManager rooms;
    private final MessageBus bus;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicLong clientSeq = new AtomicLong();

    /** sessionId -> live socket, so a {@link Peer} can be resolved back to its connection. */
    private final Map<String, WebSocketSession> sessions = new ConcurrentHashMap<>();

    public CollabWebSocketHandler(RoomManager rooms, MessageBus bus) {
        this.rooms = rooms;
        this.bus = bus;
        // Deliver messages that arrive from other server instances to our local sockets.
        this.bus.onRemoteMessage(this::relayFromRemote);
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        sessions.put(session.getId(), session);

        String roomId = roomIdFromPath(session);
        // A stable per-browser key keeps identity (and the colored cursor) across
        // reconnects; fall back to a fresh id for first-time/legacy clients.
        String clientKey = queryParam(session, "clientKey");
        String clientId = (clientKey != null && !clientKey.isBlank())
                ? clientKey.trim() : "c" + clientSeq.incrementAndGet();
        String name = displayNameFromQuery(session, clientId);
        String color = rooms.nextColor();
        String requestedLang = queryParam(session, "lang");
        int since = parseInt(queryParam(session, "since"), -1);

        Room room = rooms.getOrCreate(roomId, requestedLang);
        Room.JoinState join = room.join(new Peer(clientId, session.getId(), name, color), since);

        session.getAttributes().put(ATTR_ROOM, roomId);
        session.getAttributes().put(ATTR_CLIENT, clientId);

        if (join.isResume()) {
            // Reconnect: replay only the operations the client missed.
            ObjectNode replay = json.createObjectNode();
            replay.put("type", "replay");
            replay.put("clientId", clientId);
            replay.put("color", color);
            replay.put("revision", join.revision());
            replay.put("language", join.language());
            ArrayNode opsArr = json.createArrayNode();
            for (Room.OpMeta meta : join.replay()) {
                ObjectNode entry = json.createObjectNode();
                entry.put("clientId", meta.authorId());
                entry.put("opId", meta.opId());
                entry.put("revision", meta.revision());
                entry.set("operation", operationToJson(meta.operation()));
                opsArr.add(entry);
            }
            replay.set("ops", opsArr);
            replay.set("peers", peersJson(room));
            send(session, replay);
            log.info("peer {} resumed room {} (replayed {} op(s) since rev {})",
                    clientId, roomId, join.replay().size(), since);
        } else {
            // First join (or history too old to replay): send the full document.
            ObjectNode init = json.createObjectNode();
            init.put("type", "init");
            init.put("clientId", clientId);
            init.put("name", name);
            init.put("color", color);
            init.put("revision", join.revision());
            init.put("document", join.contents());
            init.put("language", join.language());
            init.put("roomName", join.name());
            init.set("peers", peersJson(room));
            send(session, init);
            log.info("peer {} joined room {} ({} present)", clientId, roomId, room.getPeerCount());
        }

        // Tell everyone the roster changed.
        broadcastPeers(room);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String roomId = (String) session.getAttributes().get(ATTR_ROOM);
        String clientId = (String) session.getAttributes().get(ATTR_CLIENT);
        Room room = rooms.get(roomId);
        if (room == null) {
            return;
        }

        JsonNode msg = json.readTree(message.getPayload());
        switch (msg.path("type").asText()) {
            case "op" -> handleOperation(session, room, clientId, msg);
            case "selection" -> handleSelection(session, room, clientId, msg);
            case "language" -> handleLanguage(room, clientId, msg);
            default -> log.debug("ignoring unknown message type '{}'", msg.path("type").asText());
        }
    }

    private void handleOperation(WebSocketSession session, Room room, String clientId, JsonNode msg) {
        int revision = msg.path("revision").asInt();
        long opId = msg.path("opId").asLong(-1);
        TextOperation incoming = parseOperation(msg.path("operation"));

        Room.ApplyResult result;
        try {
            result = room.applyOperation(clientId, opId, revision, incoming);
        } catch (RuntimeException ex) {
            // The client based its edit on an impossible revision — ask it to resync.
            log.warn("rejecting op from {} in room {}: {}", clientId, room.getId(), ex.getMessage());
            ObjectNode err = json.createObjectNode();
            err.put("type", "resync");
            err.put("revision", room.getRevision());
            err.put("document", room.getContents());
            send(session, err);
            return;
        }

        // Acknowledge the author so its OT state machine can advance...
        ObjectNode ack = json.createObjectNode();
        ack.put("type", "ack");
        send(session, ack);

        // A duplicate (resent after reconnect) was already applied and broadcast.
        if (result.duplicate()) {
            return;
        }

        // ...and broadcast the transformed operation to everyone else.
        ObjectNode out = json.createObjectNode();
        out.put("type", "op");
        out.put("clientId", clientId);
        out.put("revision", result.revision());
        out.set("operation", operationToJson(result.operation()));
        broadcastExcept(room, session.getId(), out);
        publishRemote(room.getId(), out);
    }

    private void handleSelection(WebSocketSession session, Room room, String clientId, JsonNode msg) {
        int anchor = msg.path("anchor").asInt(-1);
        int head = msg.path("head").asInt(-1);
        Peer peer = room.getPeer(session.getId());
        if (peer != null) {
            peer.setSelection(anchor, head);
        }
        ObjectNode out = json.createObjectNode();
        out.put("type", "selection");
        out.put("clientId", clientId);
        out.put("anchor", anchor);
        out.put("head", head);
        broadcastExcept(room, session.getId(), out);
        publishRemote(room.getId(), out);
    }

    private void handleLanguage(Room room, String clientId, JsonNode msg) {
        String requested = msg.path("language").asText(null);
        if (!Languages.isSupported(requested)) {
            return;
        }
        room.setLanguage(requested);
        // Broadcast to everyone (including the author) so all editors — and their
        // language pickers — switch syntax modes in lockstep.
        ObjectNode out = json.createObjectNode();
        out.put("type", "language");
        out.put("clientId", clientId);
        out.put("language", room.getLanguage());
        broadcastAll(room, out);
        publishRemote(room.getId(), out);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessions.remove(session.getId());
        String roomId = (String) session.getAttributes().get(ATTR_ROOM);
        String clientId = (String) session.getAttributes().get(ATTR_CLIENT);
        if (roomId == null) {
            return;
        }
        Room room = rooms.get(roomId);
        if (room == null) {
            return;
        }
        room.removePeer(session.getId());

        ObjectNode left = json.createObjectNode();
        left.put("type", "peer-left");
        left.put("clientId", clientId);
        broadcastExcept(room, session.getId(), left);
        publishRemote(roomId, left);
        broadcastPeers(room);

        rooms.removeIfEmpty(roomId);
        log.info("peer {} left room {} ({} remaining)", clientId, roomId, room.getPeerCount());
    }

    // ----- Operation <-> JSON --------------------------------------------

    private TextOperation parseOperation(JsonNode arr) {
        TextOperation op = new TextOperation();
        if (arr == null || !arr.isArray()) {
            return op;
        }
        for (JsonNode component : arr) {
            if (component.isTextual()) {
                op.insert(component.asText());
            } else if (component.isNumber()) {
                int n = component.asInt();
                if (n > 0) {
                    op.retain(n);
                } else if (n < 0) {
                    op.delete(-n);
                }
            }
        }
        return op;
    }

    private ArrayNode operationToJson(TextOperation op) {
        ArrayNode arr = json.createArrayNode();
        for (Object component : op.getOps()) {
            if (component instanceof String s) {
                arr.add(s);
            } else {
                arr.add((Integer) component);
            }
        }
        return arr;
    }

    // ----- Presence broadcasting -----------------------------------------

    private ArrayNode peersJson(Room room) {
        ArrayNode arr = json.createArrayNode();
        for (Peer p : room.getPeers()) {
            ObjectNode node = json.createObjectNode();
            node.put("clientId", p.getClientId());
            node.put("name", p.getName());
            node.put("color", p.getColor());
            node.put("anchor", p.getAnchor());
            node.put("head", p.getHead());
            arr.add(node);
        }
        return arr;
    }

    private void broadcastPeers(Room room) {
        ObjectNode out = json.createObjectNode();
        out.put("type", "peers");
        out.set("peers", peersJson(room));
        for (WebSocketSession s : sessionsOf(room)) {
            send(s, out);
        }
        publishRemote(room.getId(), out);
    }

    // ----- Cross-instance fan-out (Redis Pub/Sub) ------------------------

    /** Publish an already-built broadcast to other server instances via the bus. */
    private void publishRemote(String roomId, ObjectNode payload) {
        try {
            bus.publish(roomId, json.writeValueAsString(payload));
        } catch (JsonProcessingException e) {
            log.warn("failed to publish room {} message: {}", roomId, e.getMessage());
        }
    }

    /** Deliver a message originating on another instance to our local sockets. */
    private void relayFromRemote(String roomId, String messageJson) {
        Room room = rooms.get(roomId);
        if (room == null) {
            return; // no local peers for this room on this instance
        }
        TextMessage frame = new TextMessage(messageJson);
        for (WebSocketSession s : sessionsOf(room)) {
            try {
                if (s.isOpen()) {
                    s.sendMessage(frame);
                }
            } catch (IOException e) {
                log.warn("failed to relay to session {}: {}", s.getId(), e.getMessage());
            }
        }
    }

    private void broadcastExcept(Room room, String exceptSessionId, ObjectNode payload) {
        for (WebSocketSession s : sessionsOf(room)) {
            if (!s.getId().equals(exceptSessionId)) {
                send(s, payload);
            }
        }
    }

    private void broadcastAll(Room room, ObjectNode payload) {
        for (WebSocketSession s : sessionsOf(room)) {
            send(s, payload);
        }
    }

    private List<WebSocketSession> sessionsOf(Room room) {
        List<WebSocketSession> result = new ArrayList<>();
        for (Peer p : room.getPeers()) {
            WebSocketSession s = sessions.get(p.getSessionId());
            if (s != null && s.isOpen()) {
                result.add(s);
            }
        }
        return result;
    }

    private void send(WebSocketSession session, JsonNode payload) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage(json.writeValueAsString(payload)));
            }
        } catch (IOException e) {
            log.warn("failed to send to session {}: {}", session.getId(), e.getMessage());
        }
    }

    // ----- Connection metadata helpers -----------------------------------

    private static String roomIdFromPath(WebSocketSession session) {
        String path = session.getUri() != null ? session.getUri().getPath() : "";
        int idx = path.lastIndexOf('/');
        String id = idx >= 0 && idx < path.length() - 1 ? path.substring(idx + 1) : "lobby";
        return id.isBlank() ? "lobby" : id;
    }

    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static String displayNameFromQuery(WebSocketSession session, String fallback) {
        String name = queryParam(session, "name");
        return name != null && !name.isBlank() ? name.trim() : "Guest " + fallback;
    }

    /** Reads a single decoded query-string parameter, or {@code null} if absent. */
    private static String queryParam(WebSocketSession session, String key) {
        String query = session.getUri() != null ? session.getUri().getQuery() : null;
        if (query == null) {
            return null;
        }
        String prefix = key + "=";
        for (String part : query.split("&")) {
            if (part.startsWith(prefix)) {
                return URLDecoder.decode(part.substring(prefix.length()), StandardCharsets.UTF_8);
            }
        }
        return null;
    }
}
