package com.codenuance.session;

/**
 * A single connected collaborator inside a room. One WebSocket session maps to
 * one peer. Carries the presence info other clients render: a display name, an
 * accent colour drawn from the app palette, and the peer's latest cursor/selection.
 */
public class Peer {

    private final String clientId;
    private final String sessionId;
    private final String name;
    private final String color;

    /** Cursor anchor offset into the document, or -1 when unknown. */
    private volatile int anchor = -1;
    /** Cursor head offset into the document, or -1 when unknown. */
    private volatile int head = -1;

    public Peer(String clientId, String sessionId, String name, String color) {
        this.clientId = clientId;
        this.sessionId = sessionId;
        this.name = name;
        this.color = color;
    }

    public String getClientId() {
        return clientId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getName() {
        return name;
    }

    public String getColor() {
        return color;
    }

    public int getAnchor() {
        return anchor;
    }

    public int getHead() {
        return head;
    }

    public void setSelection(int anchor, int head) {
        this.anchor = anchor;
        this.head = head;
    }
}
