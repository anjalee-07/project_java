# Load testing CodeNuance

A [k6](https://k6.io) WebSocket test that benchmarks the collaboration server
under 50–100 concurrent users.

## Install k6

```powershell
winget install k6.k6        # Windows
# or: choco install k6
# macOS: brew install k6   |   Linux: see https://grafana.com/docs/k6/latest/set-up/install-k6/
```

## Run

Start the server first (single-node is fine for a smoke test, or the full
`docker compose up --build` stack for a distributed run), then:

```powershell
# Defaults: 75 VUs, 60s, 5 rooms, against ws://localhost:8080
k6 run load/ws-load-test.js

# 100 users for 2 minutes
k6 run -e VUS=100 -e DURATION=2m load/ws-load-test.js

# Point at the docker-compose stack (nginx on 8080) or a remote host
k6 run -e TARGET=ws://localhost:8080 -e VUS=100 load/ws-load-test.js
```

## What it measures

| Metric | Meaning |
|--------|---------|
| `op_latency` | round-trip from sending an edit to its server `ack` (p95 threshold 500ms) |
| `ops_acked` | total acknowledged edits — divide by duration for throughput (ops/s) |
| `resyncs` | times a VU fell out of sync and had to resync (should be ~0) |
| `op_errors` | rate of protocol/parse errors (threshold < 1%) |
| `ws_session_duration`, `ws_connecting` | built-in k6 WebSocket timings |

Each virtual user keeps a single operation in flight at a time (the same
`AwaitingConfirm` discipline as the real client) and tracks the document
revision/length from acks and broadcasts so its edits stay valid as others type.

## Reading the result

k6 prints a summary table at the end. The headline numbers to record:

- `op_latency` avg / p95 / max — latency
- `ops_acked` rate (`ops_acked` ÷ test duration) — throughput
- `op_errors` and `resyncs` — failure/instability rate
- `checks` — handshake success rate (should be 100%)
