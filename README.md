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

`TextOperationTest` runs a property test that applies **5,000 random pairs of
concurrent edits** and asserts the convergence law holds for every one.

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
│   └── ServerDocument.java    # authoritative doc + operation history
├── session/
│   ├── Room.java              # one document + its peers, ReentrantLock guarded
│   ├── RoomManager.java       # all rooms, ConcurrentHashMap
│   └── Peer.java              # a connected collaborator (name, color, cursor)
├── ws/
│   └── CollabWebSocketHandler.java   # JSON wire protocol <-> OT engine
├── web/
│   └── RoomController.java    # GET /api/rooms for the lobby
└── config/
    └── WebSocketConfig.java   # maps /ws/collab/{room}

src/main/resources/static/     # the immersive front end (HTML/CSS/JS + CodeMirror)
```

## Where to take it next

The persistence seam is deliberately narrow: `RoomManager` keeps state in a
`ConcurrentHashMap` today. Swapping that for **Redis** (Lettuce/Jedis), keyed by
room id, plus persisting each `Room`'s operation history, is what turns this into
a horizontally scalable, durable deployment — the natural next milestone.
