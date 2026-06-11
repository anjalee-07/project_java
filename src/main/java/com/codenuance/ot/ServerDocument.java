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

    public ServerDocument(String initialContents) {
        this.contents = initialContents == null ? "" : initialContents;
    }

    public String getContents() {
        return contents;
    }

    /** The current revision equals the number of operations applied so far. */
    public int getRevision() {
        return history.size();
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
        if (revision < 0 || revision > history.size()) {
            throw new IllegalArgumentException(
                    "revision " + revision + " out of range [0, " + history.size() + "]");
        }

        // Transform the incoming operation against everything that happened after
        // the revision it was based on.
        TextOperation transformed = operation;
        for (int i = revision; i < history.size(); i++) {
            TextOperation concurrent = history.get(i);
            TextOperation[] pair = TextOperation.transform(transformed, concurrent);
            transformed = pair[0];
        }

        contents = transformed.apply(contents);
        history.add(transformed);
        return transformed;
    }
}
