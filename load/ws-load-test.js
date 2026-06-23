/* ===========================================================================
   k6 WebSocket load test for CodeNuance.

   Simulates 50–100 concurrent collaborators spread across a handful of rooms.
   Each virtual user connects, waits for `init`, then types (sends an OT insert)
   on an interval, keeping a single op in flight at a time (the same
   AwaitingConfirm discipline the real client uses) and tracking revision/length
   from the server's acks and broadcasts so its operations stay valid.

   Metrics recorded:
     • op_latency      — round-trip time from sending an op to its server ack
     • ops_acked       — throughput counter of acknowledged edits
     • resyncs         — times a VU had to resync (should stay near zero)
     • op_errors       — rate of protocol errors

   Run:
     k6 run load/ws-load-test.js
     k6 run -e TARGET=ws://localhost:8080 -e VUS=100 -e DURATION=60s load/ws-load-test.js
   =========================================================================== */
import ws from "k6/ws";
import { check } from "k6";
import { Trend, Counter, Rate } from "k6/metrics";
import { randomIntBetween } from "https://jslib.k6.io/k6-utils/1.4.0/index.js";

const opLatency = new Trend("op_latency", true);
const opsAcked = new Counter("ops_acked");
const resyncs = new Counter("resyncs");
const opErrors = new Rate("op_errors");

const TARGET = __ENV.TARGET || "ws://localhost:8080";
const VUS = parseInt(__ENV.VUS || "75", 10);
const DURATION = __ENV.DURATION || "60s";
const ROOMS = parseInt(__ENV.ROOMS || "5", 10);
const TYPE_INTERVAL_MS = parseInt(__ENV.TYPE_INTERVAL_MS || "250", 10);

export const options = {
    scenarios: {
        collaborators: {
            executor: "constant-vus",
            vus: VUS,
            duration: DURATION,
        },
    },
    thresholds: {
        op_latency: ["p(95)<500"],   // 95% of edits acked within 500ms
        op_errors: ["rate<0.01"],     // fewer than 1% protocol errors
    },
};

// Net length change of an OT operation array (+inserts, -deletes).
function netLength(ops) {
    let delta = 0;
    for (const o of ops) {
        if (typeof o === "string") delta += o.length;
        else if (o < 0) delta += o; // o is negative
    }
    return delta;
}

export default function () {
    const room = "load-room-" + (__VU % ROOMS);
    const clientKey = "vu-" + __VU + "-" + Date.now();
    const url = `${TARGET}/ws/collab/${room}?name=VU${__VU}&lang=javascript&clientKey=${clientKey}&since=-1`;

    const state = { rev: 0, len: 0, opId: 0, inflight: false, sentAt: 0, ready: false };

    const res = ws.connect(url, {}, function (socket) {
        socket.on("message", function (data) {
            let msg;
            try { msg = JSON.parse(data); } catch (e) { opErrors.add(1); return; }

            switch (msg.type) {
                case "init":
                    state.rev = msg.revision;
                    state.len = (msg.document || "").length;
                    state.ready = true;
                    break;
                case "ack":
                    if (state.inflight) {
                        opLatency.add(Date.now() - state.sentAt);
                        opsAcked.add(1);
                        state.rev += 1;
                        state.len += 1; // we only ever insert one character
                        state.inflight = false;
                    }
                    break;
                case "op":
                    state.rev += 1;
                    state.len += netLength(msg.operation || []);
                    break;
                case "resync":
                    resyncs.add(1);
                    state.rev = msg.revision;
                    state.len = (msg.document || "").length;
                    state.inflight = false;
                    break;
                default:
                    break; // peers / selection / language / replay
            }
        });

        // Type on an interval: one in-flight op at a time, appended at the end.
        socket.setInterval(function () {
            if (!state.ready || state.inflight) return;
            const op = state.len > 0 ? [state.len, "x"] : ["x"];
            state.inflight = true;
            state.sentAt = Date.now();
            socket.send(JSON.stringify({
                type: "op",
                revision: state.rev,
                opId: state.opId++,
                operation: op,
            }));
        }, TYPE_INTERVAL_MS + randomIntBetween(0, 80));

        // Each VU lives for the whole test, then closes.
        socket.setTimeout(function () { socket.close(); }, durationMs(DURATION));
    });

    check(res, { "handshake status is 101": (r) => r && r.status === 101 });
}

function durationMs(d) {
    const m = /^(\d+)(s|m)?$/.exec(d);
    if (!m) return 60000;
    const n = parseInt(m[1], 10);
    return m[2] === "m" ? n * 60000 : n * 1000;
}
