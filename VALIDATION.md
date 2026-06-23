# Validation guide

How to verify CodeNuance's distributed features end-to-end. Two layers:

1. **Automated tests** — run anywhere, no external services.
2. **Distributed stack** — needs Docker; validates Postgres recovery, Redis
   fan-out across instances, reconnection, and load.

---

## 1. Automated tests (no Docker needed)

```powershell
.\mvnw.cmd test
```

What they cover:

| Test | Validates |
|------|-----------|
| `TextOperationTest` | OT apply/compose/transform; 5,000-pair convergence law |
| `ConcurrentEditingTest` — multi-client sim | 2–5 clients × 60 seeds running the real 3-state OT machine all converge with the server |
| `ConcurrentEditingTest` — `Room` concurrency | 16 threads × 40 edits through the real lock/transform path lose no characters |
| `PersistenceRecoveryTest` | PostgreSQL persistence path against embedded **H2**: snapshot-on-evict → recover-on-rejoin (contents, language, revision) |

Single-node smoke test (no DB/Redis):

```powershell
.\mvnw.cmd spring-boot:run
# open http://localhost:8080, GET /api/rooms returns []
```

---

## 2. Distributed stack (Docker)

### Install Docker Desktop (Windows 11)

```powershell
winget install -e --id Docker.DockerDesktop
```

Reboot if prompted, launch **Docker Desktop**, wait for *"Engine running"*, then:

```powershell
docker version          # client AND server versions print
docker compose version
```

### Bring it up

From the project root:

```powershell
docker compose up --build
```

Ready when `app1` and `app2` both log `Started CodeNuanceApplication`. The stack is
Postgres + Redis + two app instances (`SPRING_PROFILES_ACTIVE=distributed`) behind
an nginx load balancer that pins each room to one instance.

### Checks

**a) Distributed editing** — open <http://localhost:8080> in two tabs, join the
same room, type. Edits + colored cursors sync.

**b) Crash recovery (Postgres):**

```powershell
docker compose restart app1 app2
```

Reopen the room — text is restored from the snapshot.

**c) Reconnection** — stop/restart an app (or toggle network) while a room is open;
the client shows "reconnecting…", then replays missed ops with no lost edits.

**d) Confirm data is in Postgres:**

```powershell
docker compose exec postgres psql -U codenuance -d codenuance -c "select room_id, revision, length(contents) from room_snapshot;"
```

**e) Confirm Redis Pub/Sub traffic** (watch while editing in a browser):

```powershell
docker compose exec redis redis-cli psubscribe "codenuance:room:*"
```

### Tear down

```powershell
docker compose down       # stop & remove
docker compose down -v    # also wipe the Postgres volume
```

---

## 3. Load test (50–100 users)

```powershell
winget install -e --id k6.k6
k6 run -e VUS=100 -e DURATION=2m load/ws-load-test.js
```

Record from the summary:

- `op_latency` avg / p95 / max — latency
- `ops_acked` rate — throughput (ops/s)
- `resyncs` — should be ~0
- `op_errors` — should be < 1%
- `checks` — handshake success (should be 100%)

See [`load/README.md`](load/README.md) for options.

---

## Status snapshot

| Feature | Automated | Needs Docker |
|---------|-----------|--------------|
| OT convergence / conflict resolution | ✅ | — |
| Multi-room | ✅ | — |
| Presence & cursors | (manual, single-node) | cross-instance: ✅ |
| Postgres recovery | ✅ (H2) | full Postgres: manual |
| Redis Pub/Sub | — | ✅ |
| Reconnection / replay | server: ✅ (sim) | browser UX: manual |
| Load test | — | ✅ |
