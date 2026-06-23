# CodeNuance

A real-time collaborative code editor — a stripped-down Google Docs for code.
Many people type in the same file at once; a Java **Operational Transformation
(OT)** engine merges every keystroke so no edit is ever lost and everyone
converges on the same document.

The interface is themed on the **dotnuance "Buttermilk · Pastel Blue · Old
Burgundy"** palette: a warm, grainy, film-photo feel with a deep burgundy editor
canvas, buttermilk text, and pastel-blue accents for focus states and live peer
cursors.

```
Buttermilk   #FFF1B5      Pastel Blue  #C1DBE8      Old Burgundy  #43302E
```

## What it does

- **Live multi-user editing** over WebSockets — open the room link in two tabs
  and watch edits sync instantly.
- **Operational Transformation** merges concurrent edits without locking. Two
  people typing in the same spot both keep their work.
- **Presence**: avatars for everyone in the room plus colored, labelled remote
  cursors that follow each collaborator.
- **14 languages, synced**: JavaScript, TypeScript, Python, Java, C++, C, C#,
  Go, Rust, Ruby, PHP, SQL, HTML, CSS. Pick one when you create a room (it seeds
  a matching starter snippet); switch it live from the editor and every
  collaborator's syntax highlighting flips in lockstep. The server is the single
  source of truth (see `Languages.java` + the `language` WebSocket message).
- **Shareable rooms**: every room is a URL (`/?room=sunset-loft`). "Copy invite
  link" puts it on your clipboard.
- **Resilient**: if a client ever bases an edit on an impossible revision the
  server tells it to resync, so sessions self-heal.
- **Reconnection without data loss**: a dropped client auto-reconnects with
  exponential backoff, the server replays exactly the operations it missed, and
  any edit that was in flight is resent and de-duplicated — no keystroke is lost.
- **Distributed-ready** (opt-in `distributed` profile): room snapshots persist to
  **PostgreSQL** for crash recovery, and **Redis Pub/Sub** fans edits, cursors and
  presence out across multiple server instances. Runs single-node with zero
  external services by default.

## The interesting part — the OT engine

The heart of the project is `com.codenuance.ot`:

- **`TextOperation`** — an edit is a sequence of `retain(n)` / `insert(s)` /
  `delete(n)` components (the Etherpad/ShareDB model). It implements the two
  operations that make collaboration possible:
  - `compose(a, b)` — squash two sequential edits into one.
  - `transform(a, b) -> [a', b']` — the core OT law. Given two edits made
    against the *same* document, produce adjusted versions such that
    `compose(a, b') == compose(b, a')`. That convergence guarantee is what lets
    two people edit simultaneously.
- **`ServerDocument`** — the authoritative copy. It keeps a history of applied
  operations; an incoming edit tagged with an older revision is transformed
  forward against everything that happened since before being applied.

On the browser side, `static/js/ot.js` mirrors the same engine and runs the
classic three-state client machine (`Synchronized → AwaitingConfirm →
AwaitingWithBuffer`) so you can keep typing while an edit is still in flight.

### Concurrency

- `RoomManager` holds rooms in a `ConcurrentHashMap`; `computeIfAbsent` makes
  room creation race-free.
- Each `Room` guards its document with a `ReentrantLock`, giving the total
  ordering OT needs, while presence lives in a lock-free map.

### Tests

- `TextOperationTest` runs a property test that applies **5,000 random pairs of
  concurrent edits** and asserts the convergence law holds for every one.
- `ConcurrentEditingTest` goes further:
  - **Multi-client convergence** — an in-test model runs the same three-state OT
    machine the browser does for 2–5 clients across 60 seeds, interleaving random
    edits with the network, then asserts every client and the server end on an
    identical document.
  - **Real `Room` concurrency** — 16 threads hammer a live `Room` with 640 edits
    through the actual lock + transform path; the test asserts every inserted
    character survives and the revision advances exactly once per accepted op.

## Reconnection & recovery

- **Stable identity** — each browser tab gets a `clientKey`; on a reconnect the
  server recognises it, keeps the user's colour/cursor, and replays only the ops
  missed since the client's last revision (`Room.join` returns a `JoinState` with
  the replay buffer, falling back to a full document send if history is too old).
- **No lost / no duplicated edits** — every op carries a per-author `opId`. A
  resent in-flight op is de-duplicated server-side (`Room.applyOperation`), so a
  drop mid-keystroke neither loses nor doubles the edit.
- **Durable documents** — under the `distributed` profile, `SnapshotScheduler`
  auto-saves changed rooms to PostgreSQL every few seconds (and on the last peer
  leaving); `RoomManager` recovers a room from its snapshot the next time someone
  joins, so a crash or restart doesn't lose work.

## Distributed mode

Single-node is the default and needs nothing external. To run the horizontally
scalable stack — PostgreSQL snapshots + Redis Pub/Sub across two app instances
behind a sticky load balancer:

```powershell
docker compose up --build
```

Then open <http://localhost:8080>. Architecture:

- **`distributed` Spring profile** flips on JPA persistence and the Redis message
  bus (the default profile excludes their auto-configuration entirely).
- **PostgreSQL** holds one `room_snapshot` row per room.
- **Redis Pub/Sub** (`MessageBus` → `RedisMessageBus`) relays ops/cursors/presence
  between instances; each node ignores its own echo via an instance id.
- **nginx** hashes on the request path so all clients of a room land on one
  instance — OT needs a single authority per document — while Redis carries that
  room's traffic to any client that connected elsewhere.

> Note: per-instance room listing (`/api/rooms`) reflects one backend; a
> Redis-backed room registry would aggregate it. The OT authority model assumes
> sticky-by-room routing, which the bundled nginx config provides.

## Load testing

A [k6](https://k6.io) WebSocket test benchmarks 50–100 concurrent collaborators
and records op latency, throughput, resyncs and error rate. See
[`load/README.md`](load/README.md):

```powershell
k6 run -e VUS=100 -e DURATION=2m load/ws-load-test.js
```

## Run it

Requires JDK 17+. The Maven Wrapper downloads Maven on first use, so you don't
need Maven installed.

```powershell
# Windows (PowerShell)
.\mvnw.cmd spring-boot:run
```

```bash
# macOS / Linux
./mvnw spring-boot:run
```

Then open <http://localhost:8080>, enter a name and a room, and open the same
room link in a second tab (or send it to a friend) to see the live sync.

To run the tests:

```powershell
.\mvnw.cmd test
```

To build a runnable jar:

```powershell
.\mvnw.cmd package
java -jar target\codenuance-1.0.0.jar
```

## Project layout

```
src/main/java/com/codenuance/
├── ot/
│   ├── TextOperation.java     # retain/insert/delete ops; apply, compose, transform
│   └── ServerDocument.java    # authoritative doc + history (+ base revision, replay)
├── session/
│   ├── Room.java              # one document + peers + op log; lock-guarded join/apply
│   ├── RoomManager.java       # all rooms; recovers from snapshots on first join
│   └── Peer.java              # a connected collaborator (name, color, cursor)
├── persistence/              # distributed profile: PostgreSQL snapshots
│   ├── SnapshotStore.java     # seam — NoopSnapshotStore (default) / JpaSnapshotStore
│   ├── RoomSnapshotEntity.java, RoomSnapshotRepository.java
│   └── SnapshotScheduler.java # periodic auto-save of changed rooms
├── messaging/                # distributed profile: Redis Pub/Sub
│   ├── MessageBus.java        # seam — NoopMessageBus (default) / RedisMessageBus
│   └── RedisConfig.java       # listener container
├── ws/
│   └── CollabWebSocketHandler.java   # JSON wire protocol <-> OT engine + bus
├── web/
│   └── RoomController.java    # GET /api/rooms for the lobby
└── config/
    └── WebSocketConfig.java   # maps /ws/collab/{room}

src/main/resources/
├── application.properties              # single-node default (no external services)
└── application-distributed.properties  # Postgres + Redis (used by docker-compose)

src/main/resources/static/     # the immersive front end (HTML/CSS/JS + Monaco)
load/                          # k6 WebSocket load test
Dockerfile, docker-compose.yml, nginx.conf   # distributed deployment
```

## Where to take it next

The distributed foundation is in place (Postgres snapshots, Redis fan-out,
reconnection replay, load harness). Natural follow-ups: a **Redis-backed room
registry** so `/api/rooms` aggregates across instances, **multiple documents per
room**, and persisting the full **operation log** (not just snapshots) so replay
survives a server restart, not only a network drop.
