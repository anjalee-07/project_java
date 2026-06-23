package com.codenuance.ot;

import com.codenuance.session.Peer;
import com.codenuance.session.Room;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conflict-resolution tests that go beyond the pairwise transform law in
 * {@link TextOperationTest}: they simulate several users editing the same document
 * concurrently and assert the whole system converges.
 *
 * <p>{@link #multipleClientsConvergeThroughTheServer()} runs a faithful in-memory
 * model of the real wire protocol — each client runs the same three-state OT
 * machine the browser does ({@code Synchronized → AwaitingConfirm →
 * AwaitingWithBuffer}), edits are interleaved randomly with the network, and after
 * the dust settles every client's document must equal the server's.
 *
 * <p>{@link #concurrentEditsThroughRoomLoseNoCharacters()} drives a real
 * {@link Room} from many threads to exercise the actual locking + transform path.
 */
class ConcurrentEditingTest {

    @Test
    void multipleClientsConvergeThroughTheServer() {
        // Many seeds, varying client counts, lots of interleaved edits.
        for (long seed = 0; seed < 60; seed++) {
            int clients = 2 + (int) (seed % 4); // 2..5 clients
            runSimulation(seed, clients, 250);
        }
    }

    private void runSimulation(long seed, int numClients, int edits) {
        Random rnd = new Random(seed);
        ServerDocument server = new ServerDocument("hello world\n");
        Deque<ServerMsg> serverInbox = new ArrayDeque<>();

        List<SimClient> clients = new ArrayList<>();
        for (int i = 0; i < numClients; i++) {
            clients.add(new SimClient(i, server.getContents(), server.getRevision(), serverInbox));
        }

        // Randomly interleave local edits, server processing, and message delivery.
        for (int step = 0; step < edits; step++) {
            switch (rnd.nextInt(3)) {
                case 0 -> {
                    SimClient c = clients.get(rnd.nextInt(numClients));
                    c.localEdit(randomOperation(rnd, c.local));
                }
                case 1 -> {
                    if (!serverInbox.isEmpty()) {
                        processServer(serverInbox.poll(), server, clients);
                    }
                }
                default -> deliverToSomeClient(rnd, clients);
            }
        }

        // Drain everything until the system is quiescent and synchronized.
        int guard = 0;
        while (guard++ < 1_000_000) {
            if (!serverInbox.isEmpty()) {
                processServer(serverInbox.poll(), server, clients);
                continue;
            }
            boolean delivered = false;
            for (SimClient c : clients) {
                if (!c.inbox.isEmpty()) {
                    c.deliver(c.inbox.poll());
                    delivered = true;
                    break;
                }
            }
            if (delivered) {
                continue;
            }
            if (clients.stream().allMatch(SimClient::synced)) {
                break;
            }
            throw new IllegalStateException("seed " + seed + ": stuck with unsynced clients and no messages");
        }

        // Convergence: every client and the server agree on the exact document.
        String authoritative = server.getContents();
        for (SimClient c : clients) {
            assertEquals(authoritative, c.local,
                    "seed " + seed + ": client " + c.index + " diverged from the server");
            assertEquals(server.getRevision(), c.revision,
                    "seed " + seed + ": client " + c.index + " revision drifted");
        }
    }

    private void deliverToSomeClient(Random rnd, List<SimClient> clients) {
        List<SimClient> ready = new ArrayList<>();
        for (SimClient c : clients) {
            if (!c.inbox.isEmpty()) {
                ready.add(c);
            }
        }
        if (!ready.isEmpty()) {
            ready.get(rnd.nextInt(ready.size())).deliver(null);
        }
    }

    /** Server: apply the client's op, ack the author, broadcast the transform to the rest. */
    private void processServer(ServerMsg msg, ServerDocument server, List<SimClient> clients) {
        TextOperation transformed = server.receiveOperation(msg.baseRevision, msg.op);
        for (SimClient c : clients) {
            if (c.index == msg.author) {
                c.inbox.add(ClientMsg.ack());
            } else {
                c.inbox.add(ClientMsg.op(transformed));
            }
        }
    }

    @Test
    void concurrentEditsThroughRoomLoseNoCharacters() throws Exception {
        Room room = new Room("r", "Room", "javascript", "");
        room.addPeer(new Peer("seed", "s0", "seed", "#fff"));

        int threads = 16;
        int perThread = 40;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            final char ch = (char) ('A' + t);
            futures.add(pool.submit(() -> {
                start.await();
                for (int k = 0; k < perThread; k++) {
                    // Base each op on an atomic (revision, contents) view; the server
                    // transforms it against everything concurrent before applying.
                    Room.Snapshot snap = room.snapshot();
                    TextOperation op = new TextOperation()
                            .insert(String.valueOf(ch))
                            .retain(snap.contents().length());
                    room.applyOperation("t" + ch, k, snap.revision(), op);
                }
                return null;
            }));
        }

        start.countDown();
        for (Future<?> f : futures) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();

        String doc = room.snapshot().contents();
        assertEquals(threads * perThread, doc.length(), "every inserted character must survive");
        for (int t = 0; t < threads; t++) {
            char ch = (char) ('A' + t);
            long count = doc.chars().filter(c -> c == ch).count();
            assertEquals(perThread, count, "all of thread " + ch + "'s inserts must be present");
        }
        assertEquals(threads * perThread, room.snapshot().revision(),
                "each accepted op advances the revision exactly once");
    }

    // ===================================================================
    //  In-test model of a browser OT client (mirrors static/js/ot.js)
    // ===================================================================
    private static final class SimClient {
        final int index;
        final Deque<ServerMsg> serverInbox;
        final Deque<ClientMsg> inbox = new ArrayDeque<>();

        String local;
        int revision;

        // 0 = synchronized, 1 = awaiting confirm, 2 = awaiting with buffer
        int stateType = 0;
        TextOperation outstanding;
        TextOperation buffer;

        SimClient(int index, String doc, int revision, Deque<ServerMsg> serverInbox) {
            this.index = index;
            this.local = doc;
            this.revision = revision;
            this.serverInbox = serverInbox;
        }

        boolean synced() {
            return stateType == 0;
        }

        /** The user typed: apply locally, then drive the state machine. */
        void localEdit(TextOperation op) {
            local = op.apply(local);
            switch (stateType) {
                case 0 -> {
                    send(revision, op);
                    outstanding = op;
                    stateType = 1;
                }
                case 1 -> {
                    buffer = op;
                    stateType = 2;
                }
                default -> buffer = TextOperation.compose(buffer, op);
            }
        }

        void deliver(ClientMsg m) {
            ClientMsg msg = (m != null) ? m : inbox.poll();
            if (msg == null) {
                return;
            }
            if (msg.ack) {
                serverAck();
            } else {
                applyServer(msg.op);
            }
        }

        private void applyServer(TextOperation op) {
            revision++;
            switch (stateType) {
                case 0 -> local = op.apply(local);
                case 1 -> {
                    TextOperation[] p = TextOperation.transform(outstanding, op);
                    local = p[1].apply(local);
                    outstanding = p[0];
                }
                default -> {
                    TextOperation[] p1 = TextOperation.transform(outstanding, op);
                    TextOperation[] p2 = TextOperation.transform(buffer, p1[1]);
                    local = p2[1].apply(local);
                    outstanding = p1[0];
                    buffer = p2[0];
                }
            }
        }

        private void serverAck() {
            revision++;
            switch (stateType) {
                case 1 -> {
                    outstanding = null;
                    stateType = 0;
                }
                case 2 -> {
                    send(revision, buffer);
                    outstanding = buffer;
                    buffer = null;
                    stateType = 1;
                }
                default -> throw new IllegalStateException("ack while synchronized");
            }
        }

        private void send(int rev, TextOperation op) {
            serverInbox.add(new ServerMsg(index, rev, op));
        }
    }

    private record ServerMsg(int author, int baseRevision, TextOperation op) {
    }

    private static final class ClientMsg {
        final boolean ack;
        final TextOperation op;

        private ClientMsg(boolean ack, TextOperation op) {
            this.ack = ack;
            this.op = op;
        }

        static ClientMsg ack() {
            return new ClientMsg(true, null);
        }

        static ClientMsg op(TextOperation op) {
            return new ClientMsg(false, op);
        }
    }

    // ----- Random op builder (valid against the given document) ------------
    private static TextOperation randomOperation(Random rnd, String doc) {
        TextOperation op = new TextOperation();
        int i = 0;
        int n = doc.length();
        while (i < n) {
            int remaining = n - i;
            int chunk = 1 + rnd.nextInt(remaining);
            switch (rnd.nextInt(3)) {
                case 0 -> {
                    op.retain(chunk);
                    i += chunk;
                }
                case 1 -> op.insert(randomString(rnd, 1 + rnd.nextInt(3)));
                default -> {
                    op.delete(chunk);
                    i += chunk;
                }
            }
        }
        if (rnd.nextBoolean()) {
            op.insert(randomString(rnd, 1 + rnd.nextInt(3)));
        }
        return op;
    }

    private static String randomString(Random rnd, int len) {
        StringBuilder sb = new StringBuilder();
        String alphabet = "abc \n";
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rnd.nextInt(alphabet.length())));
        }
        return sb.toString();
    }
}
