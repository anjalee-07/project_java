package com.codenuance.ot;

import java.util.ArrayList;
import java.util.List;

/**
 * The authoritative server-side copy of a single document.
 *
 * <p>It implements the classic OT server model (the one used by ShareDB / Wave):
 * every accepted operation is appended to a history. When a client sends an
 * operation, it tags it with the revision it was composed against. If the server
 * has moved on since then, the incoming operation is transformed against every
 * operation that landed in the meantime before being applied. The transformed
 * operation is what gets broadcast to everyone else, guaranteeing convergence.
 *
 * <p>This class is not thread-safe on its own; {@code Room} serialises access.
 */
public class ServerDocument {

    private String contents;
    private final List<TextOperation> history = new ArrayList<>();

    /**
     * Revision number of {@link #contents} before any operation in {@link #history}
     * was applied. Normally 0, but a document recovered from a persisted snapshot
     * starts at the snapshot's revision so revision numbers stay monotonic across
     * restarts (older history is not kept; clients behind it get a full resync).
     */
    private final int baseRevision;

    public ServerDocument(String initialContents) {
        this(initialContents, 0);
    }

    public ServerDocument(String initialContents, int baseRevision) {
        this.contents = initialContents == null ? "" : initialContents;
        this.baseRevision = Math.max(0, baseRevision);
    }

    public String getContents() {
        return contents;
    }

    /** The current revision: the base revision plus every operation applied since. */
    public int getRevision() {
        return baseRevision + history.size();
    }

    /** The earliest revision still reachable via {@link #operationsSince(int)}. */
    public int getBaseRevision() {
        return baseRevision;
    }

    /**
     * Returns the operations applied since {@code revision}, in order, for replaying
     * to a reconnecting client. Returns {@code null} when {@code revision} predates
     * the retained history (the caller should send a full resync instead).
     */
    public List<TextOperation> operationsSince(int revision) {
        if (revision < baseRevision || revision > getRevision()) {
            return null;
        }
        return new ArrayList<>(history.subList(revision - baseRevision, history.size()));
    }

    /**
     * Receive an operation that a client composed against {@code revision}.
     * Transforms it forward against any concurrent operations, applies it, and
     * returns the transformed operation that should be broadcast to other peers.
     *
     * @param revision the server revision the client based its edit on
     * @param operation the client's operation
     * @return the operation as it was actually applied (the one to broadcast)
     */
    public TextOperation receiveOperation(int revision, TextOperation operation) {
        if (revision < baseRevision || revision > getRevision()) {
            throw new IllegalArgumentException(
                    "revision " + revision + " out of range [" + baseRevision + ", " + getRevision() + "]");
        }

        // Transform the incoming operation against everything that happened after
        // the revision it was based on.
        TextOperation transformed = operation;
        for (int i = revision - baseRevision; i < history.size(); i++) {
            TextOperation concurrent = history.get(i);
            TextOperation[] pair = TextOperation.transform(transformed, concurrent);
            transformed = pair[0];
        }

        contents = transformed.apply(contents);
        history.add(transformed);
        return transformed;
    }
}
