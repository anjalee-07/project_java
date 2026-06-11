/* ===========================================================================
   editor.js — glue between the Monaco editor, the OT client, and the WebSocket.
   Handles the lobby, live document sync, presence avatars, peer cursors, and the
   "Run" -> Judge0 -> output console flow.
   =========================================================================== */
(function () {
    "use strict";

    var TextOperation = OT.TextOperation;
    var BaseClient = OT.Client;

    // Supported languages — id must match the server's Languages registry; `monaco`
    // is the Monaco language id for highlighting; `run` marks what Judge0 executes.
    var LANGUAGES = [
        { id: "javascript", label: "JavaScript", monaco: "javascript", run: true },
        { id: "typescript", label: "TypeScript", monaco: "typescript", run: true },
        { id: "python",     label: "Python",     monaco: "python",     run: true },
        { id: "java",       label: "Java",       monaco: "java",       run: true },
        { id: "cpp",        label: "C++",        monaco: "cpp",        run: true },
        { id: "c",          label: "C",          monaco: "cpp",        run: true },
        { id: "csharp",     label: "C#",         monaco: "csharp",     run: true },
        { id: "go",         label: "Go",         monaco: "go",         run: true },
        { id: "rust",       label: "Rust",       monaco: "rust",       run: true },
        { id: "ruby",       label: "Ruby",       monaco: "ruby",       run: true },
        { id: "php",        label: "PHP",        monaco: "php",        run: true },
        { id: "sql",        label: "SQL",        monaco: "sql",        run: true },
        { id: "html",       label: "HTML",       monaco: "html",       run: false },
        { id: "css",        label: "CSS",        monaco: "css",        run: false }
    ];
    var LANG_BY_ID = {};
    LANGUAGES.forEach(function (l) { LANG_BY_ID[l.id] = l; });

    // ---- DOM ----------------------------------------------------------------
    var lobby = document.getElementById("lobby");
    var workspace = document.getElementById("workspace");
    var joinForm = document.getElementById("join-form");
    var nameInput = document.getElementById("name-input");
    var roomInput = document.getElementById("room-input");
    var roomsList = document.getElementById("rooms-list");
    var statusLiveText = document.getElementById("live-stat-text");
    var langSelect = document.getElementById("lang-select");
    var langSwitcher = document.getElementById("lang-switcher");
    var statusLang = document.getElementById("status-lang");
    var presenceEl = document.getElementById("presence");
    var roomPill = document.getElementById("room-pill");
    var connStatus = document.getElementById("conn-status");
    var statusRev = document.getElementById("status-rev");
    var statusSync = document.getElementById("status-sync");
    var statusPeers = document.getElementById("status-peers");
    var toastEl = document.getElementById("toast");
    var runBtn = document.getElementById("run-btn");
    var consolePanel = document.getElementById("console-panel");
    var consoleBody = document.getElementById("console-body");
    var consoleMeta = document.getElementById("console-meta");
    var consoleStatus = document.getElementById("console-status");
    var consoleClose = document.getElementById("console-close");

    // ---- session state ------------------------------------------------------
    var monacoApi = null;     // the global `monaco` once the AMD module loads
    var editor = null;        // Monaco editor instance
    var model = null;         // its text model
    var ws = null;
    var client = null;        // OT client state machine
    var shadow = "";          // mirror of the document the OT engine reasons about
    var applyingRemote = false;
    var myClientId = null;
    var myColor = null;
    var currentLangId = "javascript";
    var peers = {};           // clientId -> { name, color, anchor, head, widget, node }
    var selectionTimer = null;

    // =========================================================================
    //  LOBBY
    // =========================================================================
    function initLobby() {
        populateLanguageOptions(langSelect);
        populateLanguageOptions(langSwitcher);

        var params = new URLSearchParams(location.search);
        var presetRoom = params.get("room");
        if (presetRoom) roomInput.value = presetRoom;
        var savedName = localStorage.getItem("cn-name");
        if (savedName) nameInput.value = savedName;
        var savedLang = localStorage.getItem("cn-lang");
        if (savedLang && LANG_BY_ID[savedLang]) langSelect.value = savedLang;

        joinForm.addEventListener("submit", function (e) {
            e.preventDefault();
            var name = nameInput.value.trim();
            var room = slugify(roomInput.value.trim());
            if (!name || !room) return;
            var lang = langSelect.value;
            localStorage.setItem("cn-name", name);
            localStorage.setItem("cn-lang", lang);
            history.replaceState(null, "", "?room=" + encodeURIComponent(room));
            enterRoom(room, name, lang);
        });

        refreshRooms();
        setInterval(refreshRooms, 4000);
    }

    function populateLanguageOptions(select) {
        LANGUAGES.forEach(function (l) {
            var opt = document.createElement("option");
            opt.value = l.id;
            opt.textContent = l.label;
            select.appendChild(opt);
        });
    }

    function refreshRooms() {
        fetch("/api/rooms").then(function (r) { return r.json(); }).then(function (rooms) {
            updateLiveStat(rooms);
            if (!rooms.length) {
                roomsList.innerHTML = '<li class="rooms-empty">No live rooms yet — start one above.</li>';
                return;
            }
            roomsList.innerHTML = "";
            rooms.forEach(function (room) {
                var li = document.createElement("li");
                li.className = "room-row";
                li.innerHTML = '<span class="r-name"></span><span class="r-meta"></span>';
                li.querySelector(".r-name").textContent = room.name;
                li.querySelector(".r-meta").textContent =
                    room.peers + (room.peers === 1 ? " person" : " people");
                li.addEventListener("click", function () { roomInput.value = room.id; nameInput.focus(); });
                roomsList.appendChild(li);
            });
        }).catch(function () {
            if (statusLiveText) statusLiveText.textContent = "Be the first to start a room.";
        });
    }

    function updateLiveStat(rooms) {
        if (!statusLiveText) return;
        var people = rooms.reduce(function (sum, r) { return sum + r.peers; }, 0);
        if (!rooms.length) {
            statusLiveText.textContent = "Be the first to start a room.";
        } else {
            statusLiveText.textContent = people + (people === 1 ? " person" : " people")
                + " collaborating across " + rooms.length + (rooms.length === 1 ? " room" : " rooms")
                + " right now";
        }
    }

    function slugify(s) {
        return s.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-+|-+$/g, "") || "lobby";
    }

    // =========================================================================
    //  ENTER ROOM  (load Monaco, then connect)
    // =========================================================================
    function enterRoom(room, name, lang) {
        lobby.classList.add("hidden");
        workspace.classList.remove("hidden");
        roomPill.textContent = room;
        document.title = "CodeNuance · " + room;
        currentLangId = LANG_BY_ID[lang] ? lang : "javascript";

        require(["vs/editor/editor.main"], function () {
            monacoApi = window.monaco;
            defineTheme();

            editor = monacoApi.editor.create(document.getElementById("editor"), {
                value: "",
                language: (LANG_BY_ID[currentLangId] || LANG_BY_ID.javascript).monaco,
                theme: "codenuance",
                automaticLayout: true,
                fontFamily: '"JetBrains Mono", ui-monospace, monospace',
                fontSize: 14.5,
                lineHeight: 25,
                minimap: { enabled: false },
                scrollBeyondLastLine: false,
                padding: { top: 14, bottom: 14 },
                renderLineHighlight: "line",
                smoothScrolling: true,
                cursorBlinking: "smooth",
                tabSize: 2,
                fixedOverflowWidgets: true,
                scrollbar: { verticalScrollbarSize: 10, horizontalScrollbarSize: 10 }
            });
            model = editor.getModel();
            model.setEOL(monacoApi.editor.EndOfLineSequence.LF);

            wireButtons(room);
            wireLanguageSwitcher();
            wireRun();
            connect(room, name, currentLangId);
        });
    }

    // A burgundy/buttermilk Monaco theme so the editor keeps CodeNuance's identity.
    function defineTheme() {
        monacoApi.editor.defineTheme("codenuance", {
            base: "vs-dark",
            inherit: true,
            rules: [
                { token: "", foreground: "f3e9d2" },
                { token: "comment", foreground: "9a8b76", fontStyle: "italic" },
                { token: "keyword", foreground: "c1dbe8", fontStyle: "bold" },
                { token: "keyword.control", foreground: "c1dbe8" },
                { token: "string", foreground: "cfe3b8" },
                { token: "number", foreground: "f0b07a" },
                { token: "type", foreground: "fff1b5" },
                { token: "type.identifier", foreground: "fff1b5" },
                { token: "function", foreground: "fff1b5" },
                { token: "identifier", foreground: "f3e9d2" },
                { token: "operator", foreground: "c1dbe8" },
                { token: "delimiter", foreground: "cdbfa6" },
                { token: "tag", foreground: "c1dbe8" },
                { token: "attribute.name", foreground: "fff1b5" },
                { token: "attribute.value", foreground: "cfe3b8" }
            ],
            colors: {
                "editor.background": "#332624",
                "editor.foreground": "#f3e9d2",
                "editorLineNumber.foreground": "#7c6c5b",
                "editorLineNumber.activeForeground": "#fff1b5",
                "editorCursor.foreground": "#fff1b5",
                "editor.selectionBackground": "#c1dbe83a",
                "editor.inactiveSelectionBackground": "#c1dbe824",
                "editor.lineHighlightBackground": "#ffffff08",
                "editorWidget.background": "#2c211f",
                "editorWidget.border": "#ffffff14",
                "editorSuggestWidget.background": "#2c211f",
                "editorIndentGuide.background1": "#ffffff10",
                "editorGutter.background": "#00000000",
                "scrollbarSlider.background": "#fff1b522",
                "scrollbarSlider.hoverBackground": "#fff1b53a"
            }
        });
    }

    // When a collaborator picks a language we just tell the server; the broadcast
    // it sends back (handled in onLanguage) is what actually flips every editor,
    // keeping the room's single source of truth on the server.
    function wireLanguageSwitcher() {
        langSwitcher.addEventListener("change", function () {
            sendJSON({ type: "language", language: langSwitcher.value });
        });
    }

    function wireButtons(room) {
        document.getElementById("share-btn").addEventListener("click", function () {
            var url = location.origin + "/?room=" + encodeURIComponent(room);
            navigator.clipboard.writeText(url).then(function () {
                toast("Invite link copied — share it!");
            }, function () {
                toast(url);
            });
        });
        document.getElementById("leave-btn").addEventListener("click", function () {
            if (ws) ws.close();
            location.href = "/";
        });
    }

    // =========================================================================
    //  RUN CODE  (Judge0 via the backend) -> output console
    // =========================================================================
    function wireRun() {
        runBtn.addEventListener("click", runCode);
        consoleClose.addEventListener("click", function () {
            consolePanel.classList.add("hidden");
        });
    }

    function runCode() {
        if (!editor) return;
        var lang = LANG_BY_ID[currentLangId] || LANG_BY_ID.javascript;
        if (!lang.run) { toast(lang.label + " isn't executable here."); return; }

        showConsole();
        setConsoleStatus("running", "Running…");
        consoleMeta.textContent = "";
        consoleBody.textContent = "";
        runBtn.disabled = true;
        runBtn.classList.add("is-running");

        fetch("/api/run", {
            method: "POST",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ language: currentLangId, source: editor.getValue(), stdin: "" })
        }).then(function (r) { return r.json(); })
          .then(renderRunResult)
          .catch(function () {
              setConsoleStatus("error", "Error");
              consoleBody.textContent = "Couldn't reach the server.";
          })
          .finally(function () {
              runBtn.disabled = !lang.run;
              runBtn.classList.remove("is-running");
          });
    }

    function renderRunResult(res) {
        if (!res || res.clientError) {
            setConsoleStatus("warn", "Note");
            consoleMeta.textContent = "";
            consoleBody.textContent = (res && res.message) || "Nothing to show.";
            return;
        }
        var status = (res.status && res.status.description) || "Done";
        var ok = res.status && res.status.id === 3; // 3 = Accepted
        setConsoleStatus(ok ? "ok" : "error", status);

        var parts = [];
        if (res.compile_output) parts.push("— Compilation —\n" + String(res.compile_output).replace(/\s+$/, ""));
        if (res.stdout)         parts.push(String(res.stdout).replace(/\n$/, ""));
        if (res.stderr)         parts.push("— Runtime error —\n" + String(res.stderr).replace(/\s+$/, ""));
        if (!parts.length && res.message) parts.push(String(res.message));
        if (!parts.length)      parts.push(ok ? "(no output)" : status);
        consoleBody.textContent = parts.join("\n\n");

        var meta = [];
        if (res.time != null)   meta.push(res.time + "s");
        if (res.memory != null) meta.push(res.memory + " KB");
        consoleMeta.textContent = meta.join("  ·  ");
    }

    function showConsole() { consolePanel.classList.remove("hidden"); }

    function setConsoleStatus(state, label) {
        consoleStatus.dataset.state = state;
        consoleStatus.textContent = label;
    }

    // =========================================================================
    //  WEBSOCKET + OT WIRING
    // =========================================================================
    function connect(room, name, lang) {
        var proto = location.protocol === "https:" ? "wss" : "ws";
        var url = proto + "://" + location.host + "/ws/collab/" + encodeURIComponent(room) +
            "?name=" + encodeURIComponent(name) + "&lang=" + encodeURIComponent(lang || "javascript");
        ws = new WebSocket(url);
        setConn("connecting", "connecting…");

        ws.onopen = function () { setConn("open", "live"); };
        ws.onclose = function () { setConn("closed", "disconnected"); };
        ws.onerror = function () { setConn("closed", "error"); };
        ws.onmessage = function (evt) { handleMessage(JSON.parse(evt.data)); };
    }

    function handleMessage(msg) {
        switch (msg.type) {
            case "init": onInit(msg); break;
            case "ack": onAck(); break;
            case "op": onRemoteOp(msg); break;
            case "selection": onRemoteSelection(msg); break;
            case "peers": onPeers(msg); break;
            case "peer-left": onPeerLeft(msg); break;
            case "language": onLanguage(msg); break;
            case "resync": onResync(msg); break;
        }
    }

    function onInit(msg) {
        myClientId = msg.clientId;
        myColor = msg.color;

        // Seed the document without generating an operation.
        applyingRemote = true;
        editor.setValue(msg.document);
        applyingRemote = false;
        shadow = msg.document;

        // Build the OT client bound to this editor + socket.
        client = makeClient(msg.revision);
        statusRev.textContent = "rev " + msg.revision;

        applyLanguage(msg.language);
        msg.peers.forEach(addOrUpdatePeer);
        renderPresence();
        attachEditorListeners();
    }

    function onLanguage(msg) {
        applyLanguage(msg.language);
        var peer = peers[msg.clientId];
        var who = peer ? peer.name : "Someone";
        if (msg.clientId !== myClientId) {
            toast(who + " switched to " + (LANG_BY_ID[msg.language] || {}).label);
        }
    }

    // Single place that flips the editor's language and keeps the picker, footer
    // label, and Run button in sync with the room's current language.
    function applyLanguage(langId) {
        var lang = LANG_BY_ID[langId] || LANG_BY_ID.javascript;
        currentLangId = lang.id;
        if (model && monacoApi) monacoApi.editor.setModelLanguage(model, lang.monaco);
        if (langSwitcher) langSwitcher.value = lang.id;
        if (statusLang) statusLang.textContent = lang.label;
        if (runBtn) {
            runBtn.disabled = !lang.run;
            runBtn.title = lang.run ? "Run the code (Judge0)" : lang.label + " isn't executable";
        }
    }

    function makeClient(revision) {
        var c = new BaseClient(revision);
        c.sendOperation = function (rev, operation) {
            sendJSON({ type: "op", revision: rev, operation: operation.toJSON() });
            markSync(false);
        };
        c.applyOperation = function (operation) {
            applyOpToEditor(operation);
        };
        return c;
    }

    // ---- Monaco model -> operations ----------------------------------------
    function attachEditorListeners() {
        editor.onDidChangeModelContent(function () {
            if (applyingRemote) { shadow = model.getValue(); return; }
            var next = model.getValue();
            if (next === shadow) return;
            var op = diffToOperation(shadow, next);
            shadow = next;
            client.applyClient(op);
            statusRev.textContent = "rev " + client.revision;
            queueSelection();
        });

        editor.onDidChangeCursorSelection(function () {
            if (applyingRemote) return;
            queueSelection();
        });
    }

    // Minimal correct operation from old -> new via common prefix/suffix.
    function diffToOperation(oldStr, newStr) {
        var op = new TextOperation();
        var start = 0;
        var minLen = Math.min(oldStr.length, newStr.length);
        while (start < minLen && oldStr.charCodeAt(start) === newStr.charCodeAt(start)) start++;
        var endOld = oldStr.length, endNew = newStr.length;
        while (endOld > start && endNew > start &&
               oldStr.charCodeAt(endOld - 1) === newStr.charCodeAt(endNew - 1)) {
            endOld--; endNew--;
        }
        op.retain(start);
        if (endOld > start) op.delete(endOld - start);
        if (endNew > start) op.insert(newStr.slice(start, endNew));
        op.retain(oldStr.length - endOld);
        return op;
    }

    // ---- operations -> Monaco model ----------------------------------------
    function applyOpToEditor(operation) {
        applyingRemote = true;
        var index = 0;
        var edits = [];
        operation.ops.forEach(function (o) {
            if (typeof o === "number" && o > 0) {
                index += o;
            } else if (typeof o === "string") {
                var p = model.getPositionAt(index);
                edits.push({
                    range: new monacoApi.Range(p.lineNumber, p.column, p.lineNumber, p.column),
                    text: o,
                    forceMoveMarkers: true
                });
            } else { // delete (negative)
                var from = model.getPositionAt(index);
                var to = model.getPositionAt(index - o);
                edits.push({
                    range: new monacoApi.Range(from.lineNumber, from.column, to.lineNumber, to.column),
                    text: "",
                    forceMoveMarkers: true
                });
                index -= o;
            }
        });
        // All ranges are in current-document coordinates; Monaco applies them
        // atomically and keeps the local cursor put.
        model.applyEdits(edits);
        shadow = model.getValue();
        applyingRemote = false;
    }

    // ---- server messages ----------------------------------------------------
    function onAck() {
        client.serverAck();
        statusRev.textContent = "rev " + client.revision;
        if (client.isSynchronized()) markSync(true);
    }

    function onRemoteOp(msg) {
        var op = TextOperation.fromJSON(msg.operation);
        var caret = caretAfter(op);
        client.applyServer(op);
        statusRev.textContent = "rev " + client.revision;
        if (client.isSynchronized()) markSync(true);

        var peer = peers[msg.clientId];
        if (peer && caret >= 0) {
            peer.anchor = caret;
            peer.head = caret;
            drawPeerCursor(msg.clientId);
        }
    }

    function caretAfter(operation) {
        var newIndex = 0, caret = -1;
        operation.ops.forEach(function (o) {
            if (typeof o === "number" && o > 0) { newIndex += o; }
            else if (typeof o === "string") { newIndex += o.length; caret = newIndex; }
            else { caret = newIndex; } // delete: cursor sits at the deletion point
        });
        return caret;
    }

    function onRemoteSelection(msg) {
        var peer = peers[msg.clientId];
        if (!peer) return;
        peer.anchor = msg.anchor;
        peer.head = msg.head;
        drawPeerCursor(msg.clientId);
    }

    function onPeers(msg) {
        var seen = {};
        msg.peers.forEach(function (p) { seen[p.clientId] = true; addOrUpdatePeer(p); });
        Object.keys(peers).forEach(function (id) {
            if (!seen[id] && id !== myClientId) removePeer(id);
        });
        renderPresence();
    }

    function onPeerLeft(msg) { removePeer(msg.clientId); renderPresence(); }

    function onResync(msg) {
        applyingRemote = true;
        editor.setValue(msg.document);
        applyingRemote = false;
        shadow = msg.document;
        client = makeClient(msg.revision);
        statusRev.textContent = "rev " + msg.revision;
        markSync(true);
        toast("Resynced with the room");
    }

    // =========================================================================
    //  PRESENCE + PEER CURSORS  (Monaco content widgets)
    // =========================================================================
    function addOrUpdatePeer(p) {
        if (p.clientId === myClientId) return;
        var existing = peers[p.clientId] || {};
        peers[p.clientId] = {
            name: p.name,
            color: p.color,
            anchor: p.anchor !== undefined ? p.anchor : existing.anchor,
            head: p.head !== undefined ? p.head : existing.head,
            widget: existing.widget || null,
            node: existing.node || null,
            pos: existing.pos || null,
            flagTimer: existing.flagTimer || null
        };
        if (peers[p.clientId].head >= 0) drawPeerCursor(p.clientId);
    }

    function removePeer(id) {
        var peer = peers[id];
        if (peer && peer.widget && editor) editor.removeContentWidget(peer.widget);
        delete peers[id];
    }

    function drawPeerCursor(id) {
        if (!editor || !model || !monacoApi) return;
        var peer = peers[id];
        if (!peer || peer.head == null || peer.head < 0) return;

        var offset = Math.min(peer.head, model.getValueLength());
        peer.pos = model.getPositionAt(offset);

        if (!peer.widget) {
            var node = document.createElement("div");
            node.className = "mco-peer show-flag";
            node.style.setProperty("--c", peer.color);
            var flag = document.createElement("span");
            flag.className = "mco-flag";
            flag.textContent = peer.name;
            var caret = document.createElement("span");
            caret.className = "mco-caret";
            node.appendChild(flag);
            node.appendChild(caret);
            peer.node = node;
            peer.widget = {
                getId: function () { return "peer-cursor-" + id; },
                getDomNode: function () { return node; },
                getPosition: function () {
                    return {
                        position: peer.pos,
                        preference: [monacoApi.editor.ContentWidgetPositionPreference.EXACT]
                    };
                }
            };
            editor.addContentWidget(peer.widget);
        } else {
            peer.node.querySelector(".mco-flag").textContent = peer.name;
            editor.layoutContentWidget(peer.widget);
        }

        // Let the name flag fade after a moment, like Google Docs.
        peer.node.classList.add("show-flag");
        clearTimeout(peer.flagTimer);
        peer.flagTimer = setTimeout(function () {
            if (peer.node) peer.node.classList.remove("show-flag");
        }, 1800);
    }

    function renderPresence() {
        presenceEl.innerHTML = "";
        addAvatar(localName(), myColor, true);
        Object.keys(peers).forEach(function (id) {
            addAvatar(peers[id].name, peers[id].color, false);
        });
        var total = 1 + Object.keys(peers).length;
        statusPeers.textContent = total === 1 ? "just you" : total + " here";
    }

    function addAvatar(name, color, isMe) {
        var el = document.createElement("div");
        el.className = "avatar";
        el.style.background = color || "#C1DBE8";
        el.title = name + (isMe ? " (you)" : "");
        el.textContent = initials(name);
        presenceEl.appendChild(el);
    }

    // =========================================================================
    //  SELECTION REPORTING
    // =========================================================================
    function queueSelection() {
        if (selectionTimer) return;
        selectionTimer = setTimeout(function () {
            selectionTimer = null;
            if (!ws || ws.readyState !== WebSocket.OPEN || !editor || !model) return;
            var sel = editor.getSelection();
            if (!sel) return;
            var anchor = model.getOffsetAt(sel.getSelectionStart());
            var head = model.getOffsetAt(sel.getPosition());
            sendJSON({ type: "selection", anchor: anchor, head: head });
        }, 60);
    }

    // =========================================================================
    //  HELPERS
    // =========================================================================
    function sendJSON(obj) {
        if (ws && ws.readyState === WebSocket.OPEN) ws.send(JSON.stringify(obj));
    }

    function setConn(state, label) {
        connStatus.dataset.state = state;
        connStatus.textContent = label;
    }

    function markSync(synced) {
        statusSync.textContent = synced ? "synced" : "syncing…";
        statusSync.classList.toggle("dirty", !synced);
    }

    function localName() { return localStorage.getItem("cn-name") || "You"; }

    function initials(name) {
        var parts = name.trim().split(/\s+/);
        if (parts.length === 1) return parts[0].slice(0, 2).toUpperCase();
        return (parts[0][0] + parts[parts.length - 1][0]).toUpperCase();
    }

    function toast(text) {
        toastEl.textContent = text;
        toastEl.classList.remove("hidden");
        requestAnimationFrame(function () { toastEl.classList.add("show"); });
        clearTimeout(toast._t);
        toast._t = setTimeout(function () {
            toastEl.classList.remove("show");
            setTimeout(function () { toastEl.classList.add("hidden"); }, 250);
        }, 2200);
    }

    // ---- boot ---------------------------------------------------------------
    initLobby();
})();
