/* ===========================================================================
   demo.js — the small "two people typing at once" animation in the hero
   panel. Purely decorative: it has no connection to the real editor or OT
   engine, it just shows what the product feels like before you join a room.
   =========================================================================== */
(function () {
    "use strict";

    var el = document.getElementById("demo-code");
    if (!el) return;

    // Two "collaborators" typing into the same file, each with their own caret
    // color drawn from the palette (pastel blue / buttermilk).
    var SCRIPT = [
        { author: "a", text: "function mergeEdits(local, remote) {" },
        { author: "b", text: "  return transform(local, remote);" },
        { author: "a", text: "}" },
        { author: "a", text: "" },
        { author: "b", text: "room.on(\"edit\", op => {" },
        { author: "a", text: "  doc.apply(op);" },
        { author: "a", text: "  broadcast(op);" },
        { author: "b", text: "});" }
    ];

    var TYPE_MS = 28;
    var LINE_PAUSE_MS = 260;
    var END_PAUSE_MS = 1800;
    var RESTART_PAUSE_MS = 500;

    function highlight(text) {
        var escaped = text
            .replace(/&/g, "&amp;")
            .replace(/</g, "&lt;")
            .replace(/>/g, "&gt;");
        return escaped
            .replace(/(&quot;[^&]*&quot;|"[^"]*")/g, '<span class="tok-str">$1</span>')
            .replace(/\b(function|return|const|let)\b/g, '<span class="tok-kw">$1</span>')
            .replace(/\b(mergeEdits|transform|apply|broadcast|on)\b(?=\()/g, '<span class="tok-fn">$1</span>');
    }

    function render(lines, partial, caretAuthor) {
        var html = lines.map(highlight).join("\n");
        if (partial !== undefined) {
            if (html.length) html += "\n";
            html += highlight(partial);
            html += '<span class="demo-caret caret-' + caretAuthor + '"></span>';
        }
        el.innerHTML = html;
    }

    function run() {
        var done = [];
        var i = 0;

        function typeLine() {
            if (i >= SCRIPT.length) {
                setTimeout(function () {
                    done = [];
                    i = 0;
                    render(done);
                    setTimeout(typeLine, RESTART_PAUSE_MS);
                }, END_PAUSE_MS);
                return;
            }
            var line = SCRIPT[i];
            var pos = 0;

            function tick() {
                pos++;
                render(done, line.text.slice(0, pos), line.author);
                if (pos < line.text.length) {
                    setTimeout(tick, TYPE_MS);
                } else {
                    done.push(line.text);
                    i++;
                    setTimeout(typeLine, LINE_PAUSE_MS);
                }
            }

            if (line.text.length === 0) {
                done.push("");
                render(done);
                i++;
                setTimeout(typeLine, LINE_PAUSE_MS);
            } else {
                tick();
            }
        }

        typeLine();
    }

    run();
})();
