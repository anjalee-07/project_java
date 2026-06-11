package com.codenuance.session;

import com.codenuance.ot.ServerDocument;
import com.codenuance.ot.TextOperation;

import java.util.Collection;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A collaboration room: one shared document plus the people editing it.
 *
 * <p>Concurrency model — many WebSocket threads may touch a room at once, so the
 * mutable document is guarded by a {@link ReentrantLock}. Operations against the
 * {@link ServerDocument} must be totally ordered (each one is transformed against
 * the ones before it), and the lock gives us exactly that serialization point
 * while presence data lives in a lock-free {@link ConcurrentHashMap}.
 */
public class Room {

    private final String id;
    private final String name;
    private volatile String language;
    private final ServerDocument document;

    /** sessionId -> Peer. Lock-free for fast presence reads/writes. */
    private final Map<String, Peer> peers = new ConcurrentHashMap<>();

    /** Serialises all mutations of {@link #document}. */
    private final ReentrantLock lock = new ReentrantLock();

    public Room(String id, String name, String language, String initialContents) {
        this.id = id;
        this.name = name;
        this.language = language;
        this.document = new ServerDocument(initialContents);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getLanguage() {
        return language;
    }

    public void setLanguage(String language) {
        this.language = Languages.normalize(language);
    }

    public Collection<Peer> getPeers() {
        return peers.values();
    }

    public int getPeerCount() {
        return peers.size();
    }

    public void addPeer(Peer peer) {
        peers.put(peer.getSessionId(), peer);
    }

    public Peer removePeer(String sessionId) {
        return peers.remove(sessionId);
    }

    public Peer getPeer(String sessionId) {
        return peers.get(sessionId);
    }

    public String getContents() {
        lock.lock();
        try {
            return document.getContents();
        } finally {
            lock.unlock();
        }
    }

    public int getRevision() {
        lock.lock();
        try {
            return document.getRevision();
        } finally {
            lock.unlock();
        }
    }

    /**
     * Apply a client operation under the room lock. Returns the result holding
     * the transformed operation to broadcast and the new revision number.
     */
    public ApplyResult applyOperation(int revision, TextOperation operation) {
        lock.lock();
        try {
            TextOperation broadcast = document.receiveOperation(revision, operation);
            return new ApplyResult(broadcast, document.getRevision());
        } finally {
            lock.unlock();
        }
    }

    public record ApplyResult(TextOperation operation, int revision) {
    }
}
