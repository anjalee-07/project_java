package com.codenuance.session;

import com.codenuance.ot.ServerDocument;
import com.codenuance.ot.TextOperation;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
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

    /**
     * Append-only log of every applied operation with its author and per-author
     * op id — the replay buffer a reconnecting client catches up from. Guarded by
     * {@link #lock}; index {@code i} holds the op at revision {@code baseRevision + 1 + i}.
     */
    private final List<OpMeta> opLog = new ArrayList<>();

    /** Highest op id applied per author, so a resent (duplicate) op is ignored. Guarded by {@link #lock}. */
    private final Map<String, Long> lastOpIdByAuthor = new HashMap<>();

    public Room(String id, String name, String language, String initialContents) {
        this(id, name, language, initialContents, 0);
    }

    /**
     * Restores a room at a given base revision — used when recovering a persisted
     * snapshot so revision numbers stay monotonic across a restart.
     */
    public Room(String id, String name, String language, String initialContents, int baseRevision) {
        this.id = id;
        this.name = name;
        this.language = language;
        this.document = new ServerDocument(initialContents, baseRevision);
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

    /**
     * Atomically admit a peer and capture what it needs to start. If {@code since}
     * is a revision still covered by the replay buffer, the returned {@link JoinState}
     * carries the missed operations (a resume); otherwise the caller should send a
     * full document init. Done under the lock so no live op can slip in before the
     * client's starting point is fixed.
     */
    public JoinState join(Peer peer, int since) {
        lock.lock();
        try {
            peers.put(peer.getSessionId(), peer);
            int rev = document.getRevision();
            int base = document.getBaseRevision();
            List<OpMeta> replay = null;
            if (since >= base && since <= rev) {
                replay = new ArrayList<>(opLog.subList(since - base, opLog.size()));
            }
            return new JoinState(rev, document.getContents(), language, name, replay);
        } finally {
            lock.unlock();
        }
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
     * Apply a client operation under the room lock, recording it in the replay log.
     * {@code opId} is the author's monotonic per-edit id; an op whose id was already
     * applied (a resend after reconnect) is ignored and reported as a duplicate so
     * the caller still acknowledges it without re-broadcasting.
     */
    public ApplyResult applyOperation(String authorId, long opId, int revision, TextOperation operation) {
        lock.lock();
        try {
            Long last = lastOpIdByAuthor.get(authorId);
            if (last != null && opId >= 0 && opId <= last) {
                return new ApplyResult(null, document.getRevision(), true);
            }
            TextOperation broadcast = document.receiveOperation(revision, operation);
            int rev = document.getRevision();
            opLog.add(new OpMeta(rev, authorId, opId, broadcast));
            if (opId >= 0) {
                lastOpIdByAuthor.put(authorId, opId);
            }
            return new ApplyResult(broadcast, rev, false);
        } finally {
            lock.unlock();
        }
    }

    /** An atomic, consistent point-in-time view of the room for persistence. */
    public Snapshot snapshot() {
        lock.lock();
        try {
            return new Snapshot(id, name, language, document.getContents(), document.getRevision());
        } finally {
            lock.unlock();
        }
    }

    public record ApplyResult(TextOperation operation, int revision, boolean duplicate) {
    }

    public record Snapshot(String id, String name, String language, String contents, int revision) {
    }

    /** One applied operation in the replay log: who made it, its id, and the result. */
    public record OpMeta(int revision, String authorId, long opId, TextOperation operation) {
    }

    /**
     * What a joining peer needs to start. When {@link #replay()} is non-null the
     * peer is resuming and should be sent the missed operations; otherwise it gets
     * a full document init.
     */
    public record JoinState(int revision, String contents, String language, String name, List<OpMeta> replay) {
        public boolean isResume() {
            return replay != null;
        }
    }
}
