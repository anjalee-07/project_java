/* ===========================================================================
   ot.js — browser-side Operational Transformation.
   A faithful companion to the Java engine: the same retain / insert / delete
   model and the same transform law, plus the client state machine that lets a
   browser keep typing while edits are still in flight to the server.
   Exposes window.OT = { TextOperation, Client }.
   =========================================================================== */
(function (global) {
    "use strict";

    function isRetain(o) { return typeof o === "number" && o > 0; }
    function isDelete(o) { return typeof o === "number" && o < 0; }
    function isInsert(o) { return typeof o === "string"; }

    /** An operation is an array of components: +n retain, -n delete, "str" insert. */
    function TextOperation() {
        this.ops = [];
        this.baseLength = 0;
        this.targetLength = 0;
    }

    TextOperation.prototype.retain = function (n) {
        if (n <= 0) return this;
        this.baseLength += n;
        this.targetLength += n;
        var last = this.ops[this.ops.length - 1];
        if (isRetain(last)) this.ops[this.ops.length - 1] += n;
        else this.ops.push(n);
        return this;
    };

    TextOperation.prototype.insert = function (s) {
        if (!s) return this;
        this.targetLength += s.length;
        var ops = this.ops;
        var last = ops[ops.length - 1];
        if (isInsert(last)) ops[ops.length - 1] = last + s;
        else if (isDelete(last)) {
            if (isInsert(ops[ops.length - 2])) ops[ops.length - 2] += s;
            else ops.splice(ops.length - 1, 0, s);
        } else ops.push(s);
        return this;
    };

    TextOperation.prototype.delete = function (n) {
        if (n === 0) return this;
        if (n > 0) n = -n;
        this.baseLength -= n;
        var last = this.ops[this.ops.length - 1];
        if (isDelete(last)) this.ops[this.ops.length - 1] += n;
        else this.ops.push(n);
        return this;
    };

    TextOperation.prototype.apply = function (doc) {
        if (doc.length !== this.baseLength) {
            throw new Error("operation base length mismatch");
        }
        var out = [], cursor = 0, ops = this.ops;
        for (var i = 0; i < ops.length; i++) {
            var op = ops[i];
            if (isRetain(op)) { out.push(doc.slice(cursor, cursor + op)); cursor += op; }
            else if (isInsert(op)) { out.push(op); }
            else { cursor -= op; }
        }
        return out.join("");
    };

    TextOperation.prototype.toJSON = function () { return this.ops; };

    TextOperation.fromJSON = function (arr) {
        var o = new TextOperation();
        for (var i = 0; i < arr.length; i++) {
            var op = arr[i];
            if (isRetain(op)) o.retain(op);
            else if (isDelete(op)) o.delete(op);
            else o.insert(op);
        }
        return o;
    };

    /** compose(a, b): one operation equivalent to a then b. */
    TextOperation.compose = function (a, b) {
        if (a.targetLength !== b.baseLength) throw new Error("compose length mismatch");
        var op = new TextOperation();
        var ops1 = a.ops, ops2 = b.ops, i1 = 0, i2 = 0;
        var o1 = ops1[i1++], o2 = ops2[i2++];
        while (true) {
            if (o1 === undefined && o2 === undefined) break;
            if (isDelete(o1)) { op.delete(o1); o1 = ops1[i1++]; continue; }
            if (isInsert(o2)) { op.insert(o2); o2 = ops2[i2++]; continue; }
            if (isRetain(o1) && isRetain(o2)) {
                if (o1 > o2) { op.retain(o2); o1 -= o2; o2 = ops2[i2++]; }
                else if (o1 < o2) { op.retain(o1); o2 -= o1; o1 = ops1[i1++]; }
                else { op.retain(o1); o1 = ops1[i1++]; o2 = ops2[i2++]; }
            } else if (isInsert(o1) && isDelete(o2)) {
                if (o1.length > -o2) { o1 = o1.slice(-o2); o2 = ops2[i2++]; }
                else if (o1.length < -o2) { o2 = o2 + o1.length; o1 = ops1[i1++]; }
                else { o1 = ops1[i1++]; o2 = ops2[i2++]; }
            } else if (isInsert(o1) && isRetain(o2)) {
                if (o1.length > o2) { op.insert(o1.slice(0, o2)); o1 = o1.slice(o2); o2 = ops2[i2++]; }
                else if (o1.length < o2) { op.insert(o1); o2 -= o1.length; o1 = ops1[i1++]; }
                else { op.insert(o1); o1 = ops1[i1++]; o2 = ops2[i2++]; }
            } else if (isRetain(o1) && isDelete(o2)) {
                if (o1 > -o2) { op.delete(o2); o1 += o2; o2 = ops2[i2++]; }
                else if (o1 < -o2) { op.delete(-o1); o2 = o2 + o1; o1 = ops1[i1++]; }
                else { op.delete(o2); o1 = ops1[i1++]; o2 = ops2[i2++]; }
            } else {
                throw new Error("compose: unreachable");
            }
        }
        return op;
    };

    /** transform(a, b) -> [a', b'] with compose(a, b') == compose(b, a'). */
    TextOperation.transform = function (a, b) {
        if (a.baseLength !== b.baseLength) throw new Error("transform base mismatch");
        var aP = new TextOperation(), bP = new TextOperation();
        var ops1 = a.ops, ops2 = b.ops, i1 = 0, i2 = 0;
        var o1 = ops1[i1++], o2 = ops2[i2++];
        while (true) {
            if (o1 === undefined && o2 === undefined) break;
            if (isInsert(o1)) { aP.insert(o1); bP.retain(o1.length); o1 = ops1[i1++]; continue; }
            if (isInsert(o2)) { aP.retain(o2.length); bP.insert(o2); o2 = ops2[i2++]; continue; }
            var minl;
            if (isRetain(o1) && isRetain(o2)) {
                if (o1 > o2) { minl = o2; o1 -= o2; o2 = ops2[i2++]; }
                else if (o1 < o2) { minl = o1; o2 -= o1; o1 = ops1[i1++]; }
                else { minl = o1; o1 = ops1[i1++]; o2 = ops2[i2++]; }
                aP.retain(minl); bP.retain(minl);
            } else if (isDelete(o1) && isDelete(o2)) {
                if (-o1 > -o2) { o1 -= o2; o2 = ops2[i2++]; }
                else if (-o1 < -o2) { o2 -= o1; o1 = ops1[i1++]; }
                else { o1 = ops1[i1++]; o2 = ops2[i2++]; }
            } else if (isDelete(o1) && isRetain(o2)) {
                if (-o1 > o2) { minl = o2; o1 += o2; o2 = ops2[i2++]; }
                else if (-o1 < o2) { minl = -o1; o2 += o1; o1 = ops1[i1++]; }
                else { minl = o2; o1 = ops1[i1++]; o2 = ops2[i2++]; }
                aP.delete(minl);
            } else if (isRetain(o1) && isDelete(o2)) {
                if (o1 > -o2) { minl = -o2; o1 += o2; o2 = ops2[i2++]; }
                else if (o1 < -o2) { minl = o1; o2 += o1; o1 = ops1[i1++]; }
                else { minl = o1; o1 = ops1[i1++]; o2 = ops2[i2++]; }
                bP.delete(minl);
            } else {
                throw new Error("transform: unreachable");
            }
        }
        return [aP, bP];
    };

    // ----- Client state machine ----------------------------------------------
    // Three states: Synchronized, AwaitingConfirm, AwaitingWithBuffer. Only one
    // operation is ever "in flight"; further local edits accumulate in a buffer
    // until the server acknowledges the outstanding one.

    function Client(revision) {
        this.revision = revision;
        this.state = synchronized;
    }
    Client.prototype.setState = function (s) { this.state = s; };
    Client.prototype.applyClient = function (op) { this.setState(this.state.applyClient(this, op)); };
    Client.prototype.applyServer = function (op) { this.revision++; this.setState(this.state.applyServer(this, op)); };
    Client.prototype.serverAck = function () { this.revision++; this.setState(this.state.serverAck(this)); };
    Client.prototype.isSynchronized = function () { return this.state === synchronized; };
    // Subclasses override:
    Client.prototype.sendOperation = function (revision, operation) {};
    Client.prototype.applyOperation = function (operation) {};

    var synchronized = {
        applyClient: function (client, op) {
            client.sendOperation(client.revision, op);
            return new AwaitingConfirm(op);
        },
        applyServer: function (client, op) { client.applyOperation(op); return synchronized; },
        serverAck: function () { throw new Error("unexpected ack in synchronized state"); }
    };

    function AwaitingConfirm(outstanding) { this.outstanding = outstanding; }
    AwaitingConfirm.prototype.applyClient = function (client, op) {
        return new AwaitingWithBuffer(this.outstanding, op);
    };
    AwaitingConfirm.prototype.applyServer = function (client, op) {
        var pair = TextOperation.transform(this.outstanding, op);
        client.applyOperation(pair[1]);
        return new AwaitingConfirm(pair[0]);
    };
    AwaitingConfirm.prototype.serverAck = function () { return synchronized; };

    function AwaitingWithBuffer(outstanding, buffer) {
        this.outstanding = outstanding;
        this.buffer = buffer;
    }
    AwaitingWithBuffer.prototype.applyClient = function (client, op) {
        return new AwaitingWithBuffer(this.outstanding, TextOperation.compose(this.buffer, op));
    };
    AwaitingWithBuffer.prototype.applyServer = function (client, op) {
        var pair1 = TextOperation.transform(this.outstanding, op);
        var pair2 = TextOperation.transform(this.buffer, pair1[1]);
        client.applyOperation(pair2[1]);
        return new AwaitingWithBuffer(pair1[0], pair2[0]);
    };
    AwaitingWithBuffer.prototype.serverAck = function (client) {
        client.sendOperation(client.revision, this.buffer);
        return new AwaitingConfirm(this.buffer);
    };

    global.OT = { TextOperation: TextOperation, Client: Client };
})(window);
