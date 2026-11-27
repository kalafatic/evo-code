/**
 * EVO-Code unified diff viewer — dependency-free, professional code-review rendering.
 *
 * API (backward compatible):
 *   DCodeDiff.render(diffText)            -> element (unified viewer)
 *   DCodeDiff.renderWorkspace(repos)      -> element (file explorer + viewer + stats)
 *
 * Parses unified diffs (git or /dev/null style) into files/hunks/lines, renders line
 * numbers, added/removed gutters, collapsible unchanged regions, per-file stats,
 * file navigation, in-diff search and lightweight syntax highlighting. Never mutates
 * backend data and never fabricates accept/reject actions.
 */
window.DCodeDiff = (function () {
    "use strict";

    var KEYWORDS = ("abstract assert boolean break byte case catch char class const continue default do double else enum " +
        "extends final finally float for goto if implements import instanceof int interface long native new package " +
        "private protected public return short static strictfp super switch synchronized this throw throws transient " +
        "try void volatile while true false null var record sealed permits yield " +
        "function let await async of from export default as fn def lambda not and or pass raise except global nonlocal " +
        "with yield true false None True False import").split(/\s+/);
    var KEYWORD_SET = {};
    KEYWORDS.forEach(function (k) { KEYWORD_SET[k] = true; });

    function esc(text) {
        return String(text == null ? "" : text)
            .replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;")
            .replace(/"/g, "&quot;");
    }

    /** Lightweight, language-agnostic tokenizer → safe HTML. */
    function highlight(text) {
        if (!text) return "";
        var out = "";
        var i = 0;
        var n = text.length;
        while (i < n) {
            var c = text.charAt(i);
            // string literal
            if (c === '"' || c === "'" || c === "`") {
                var quote = c; var j = i + 1; var buf = quote;
                while (j < n) {
                    var ch = text.charAt(j);
                    if (ch === "\\") { buf += ch; if (j + 1 < n) { buf += text.charAt(j + 1); j += 2; continue; } }
                    buf += ch;
                    if (ch === quote) { j++; break; }
                    j++;
                }
                out += '<span class="tok-str">' + esc(buf) + "</span>";
                i = j;
                continue;
            }
            // line/block comment start
            if (c === "/" && text.charAt(i + 1) === "/") {
                out += '<span class="tok-com">' + esc(text.substring(i)) + "</span>";
                break;
            }
            if (c === "#") {
                out += '<span class="tok-com">' + esc(text.substring(i)) + "</span>";
                break;
            }
            // identifier / keyword
            if (/[A-Za-z_$]/.test(c)) {
                var k = i;
                while (k < n && /[A-Za-z0-9_$]/.test(text.charAt(k))) k++;
                var word = text.substring(i, k);
                out += KEYWORD_SET[word] ? '<span class="tok-kw">' + esc(word) + "</span>" : esc(word);
                i = k;
                continue;
            }
            // number
            if (/[0-9]/.test(c)) {
                var m = i;
                while (m < n && /[0-9.]/.test(text.charAt(m))) m++;
                out += '<span class="tok-num">' + esc(text.substring(i, m)) + "</span>";
                i = m;
                continue;
            }
            out += esc(c);
            i++;
        }
        return out;
    }

    /** Parse a unified diff string into files. */
    function parse(diffText) {
        var files = [];
        if (!diffText || !diffText.trim()) return files;
        var lines = diffText.replace(/\r\n/g, "\n").split("\n");
        var current = null;

        function startFile(name) {
            current = { name: name, oldPath: "", newPath: "", added: 0, removed: 0, lines: [], binary: false };
            files.push(current);
        }

        for (var i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (line.indexOf("diff --git ") === 0) {
                var m = line.match(/diff --git a\/(\S+) b\/(\S+)/);
                startFile(m ? m[2] : line.substring(11));
                current.hasGitHeader = true;
                continue;
            }
            if (line.indexOf("--- ") === 0) {
                var oldPath = line.substring(4).trim();
                // Start a new file only when there is no current file, or the current
                // file already recorded an old path (a fresh standalone hunk header).
                // A "--- a/..." line that immediately follows "diff --git" belongs to
                // the file started by that git header.
                if (!current || current.oldPath) {
                    startFile(oldPath);
                }
                current.oldPath = oldPath;
                continue;
            }
            if (line.indexOf("+++ ") === 0) {
                var newPath = line.substring(4).trim();
                if (!current) startFile(newPath);
                current.newPath = newPath;
                if (newPath !== "/dev/null") current.name = newPath.replace(/^b\//, "");
                current.lines.push({ kind: "meta", text: line });
                continue;
            }
            if (!current) continue;
            if (line.indexOf("index ") === 0 || line.indexOf("new file") === 0 || line.indexOf("deleted file") === 0
                || line.indexOf("similarity index") === 0 || line.indexOf("rename ") === 0) {
                current.lines.push({ kind: "meta", text: line });
                if (line.indexOf("deleted file") === 0) current.deleted = true;
                if (line.indexOf("new file") === 0) current.addedFile = true;
                continue;
            }
            if (line.indexOf("Binary files ") === 0 || line === "GIT binary patch") {
                current.binary = true;
                current.lines.push({ kind: "meta", text: line });
                continue;
            }
            if (line.indexOf("@@") === 0) {
                current.lines.push({ kind: "hunk", text: line });
                continue;
            }
            if (line.charAt(0) === "+") {
                current.added++;
                current.lines.push({ kind: "add", text: line.substring(1) });
            } else if (line.charAt(0) === "-") {
                current.removed++;
                current.lines.push({ kind: "del", text: line.substring(1) });
            } else if (line.charAt(0) === "\\") {
                current.lines.push({ kind: "meta", text: line });
            } else {
                // context line (may start with a space)
                current.lines.push({ kind: "ctx", text: line.substring(1) });
            }
        }
        return files;
    }

    function renderFileViewer(file) {
        var view = document.createElement("div");
        view.className = "diff";
        if (file.binary) {
            var b = document.createElement("div");
            b.className = "diff-meta";
            b.textContent = "Binary file — no textual diff.";
            view.appendChild(b);
            return view;
        }
        var oldNo = 0, newNo = 0;
        file.lines.forEach(function (ln) {
            var row = document.createElement("div");
            row.className = "diff-line";
            var oldCell = document.createElement("span");
            oldCell.className = "diff-num";
            var newCell = document.createElement("span");
            newCell.className = "diff-num";
            var sign = document.createElement("span");
            sign.className = "diff-sign";
            var code = document.createElement("span");
            code.className = "diff-code";

            if (ln.kind === "hunk") {
                var hm = ln.text.match(/@@ -(\d+)(?:,\d+)? \+(\d+)(?:,\d+)? @@/);
                if (hm) { oldNo = parseInt(hm[1], 10); newNo = parseInt(hm[2], 10); }
                row.className = "diff-line diff-hunk";
                oldCell.textContent = oldNo > 0 ? String(oldNo - 1) : "";
                newCell.textContent = newNo > 0 ? String(newNo - 1) : "";
                code.textContent = ln.text;
                row.appendChild(oldCell); row.appendChild(newCell); row.appendChild(sign); row.appendChild(code);
                view.appendChild(row);
                return;
            }
            if (ln.kind === "meta") {
                row.className = "diff-line diff-meta";
                code.textContent = ln.text;
                row.appendChild(oldCell); row.appendChild(newCell); row.appendChild(sign); row.appendChild(code);
                view.appendChild(row);
                return;
            }
            if (ln.kind === "add") {
                row.classList.add("diff-add"); oldNo += 0; newNo += 1;
                oldCell.textContent = ""; newCell.textContent = String(newNo); sign.textContent = "+";
            } else if (ln.kind === "del") {
                row.classList.add("diff-del"); oldNo += 1;
                oldCell.textContent = String(oldNo); newCell.textContent = ""; sign.textContent = "-";
            } else {
                oldNo += 1; newNo += 1;
                oldCell.textContent = String(oldNo); newCell.textContent = String(newNo); sign.textContent = " ";
            }
            code.innerHTML = highlight(ln.text);
            row.appendChild(oldCell); row.appendChild(newCell); row.appendChild(sign); row.appendChild(code);
            view.appendChild(row);
        });
        return view;
    }

    /** Backward-compatible single-diff renderer. */
    function render(diffText) {
        var files = parse(diffText);
        var wrap = document.createElement("div");
        wrap.className = "diff-wrap";
        if (!diffText || !diffText.trim()) {
            var note = document.createElement("div");
            note.className = "diff-meta";
            note.textContent = "(no tracked changes)";
            wrap.appendChild(note);
            return wrap;
        }
        // If parsing produced no files, fall back to raw line rendering.
        if (files.length === 0) {
            var container = document.createElement("div");
            container.className = "diff";
            diffText.split("\n").forEach(function (line) {
                var row = document.createElement("div");
                row.className = "diff-line";
                if (line.charAt(0) === "+") row.classList.add("diff-add");
                else if (line.charAt(0) === "-") row.classList.add("diff-del");
                var code = document.createElement("span");
                code.className = "diff-code";
                code.innerHTML = highlight(line.substring(1));
                row.appendChild(code);
                container.appendChild(row);
            });
            wrap.appendChild(container);
            return wrap;
        }
        files.forEach(function (file) {
            var head = document.createElement("div");
            head.className = "diff-file-head";
            head.innerHTML = '<span class="diff-file-name">' + esc(file.name || file.newPath || file.oldPath) + "</span>"
                + '<span class="diff-file-stat"><span class="add">+' + file.added + "</span> "
                + '<span class="del">-' + file.removed + "</span></span>";
            wrap.appendChild(head);
            wrap.appendChild(renderFileViewer(file));
        });
        return wrap;
    }

    /**
     * Professional diff workspace: file explorer (left) + viewer (right) + totals.
     * `repositories` is the backend `repositories[]` array from
     * GET /api/v1/sessions/{id}/changes { name, changedFiles[], diff }.
     */
    function renderWorkspace(repositories) {
        var root = document.createElement("div");
        root.className = "diff-workspace";

        var allFiles = [];
        (repositories || []).forEach(function (repo) {
            parse(repo.diff || "").forEach(function (f) { f.repository = repo.name; allFiles.push(f); });
        });
        // Files reported by changedFiles but absent from the diff string.
        (repositories || []).forEach(function (repo) {
            (repo.changedFiles || []).forEach(function (path) {
                if (!allFiles.some(function (f) { return (f.name === path) || (f.newPath === path) || (f.oldPath === path); })) {
                    allFiles.push({ name: path, repository: repo.name, lines: [], added: 0, removed: 0, untracked: true });
                }
            });
        });

        var totalAdd = allFiles.reduce(function (s, f) { return s + (f.added || 0); }, 0);
        var totalDel = allFiles.reduce(function (s, f) { return s + (f.removed || 0); }, 0);

        var header = document.createElement("div");
        header.className = "diff-workspace-head";
        header.innerHTML = '<span class="diff-title">Code changes</span>'
            + '<span class="diff-totals">' + allFiles.length + " file(s) · "
            + '<span class="add">+' + totalAdd + "</span> "
            + '<span class="del">-' + totalDel + "</span></span>";
        root.appendChild(header);

        var body = document.createElement("div");
        body.className = "diff-workspace-body";
        var explorer = document.createElement("div");
        explorer.className = "diff-explorer";
        var viewer = document.createElement("div");
        viewer.className = "diff-viewer";

        if (allFiles.length === 0) {
            viewer.innerHTML = '<div class="empty-note">No tracked changes for this session.</div>';
            body.appendChild(explorer);
            body.appendChild(viewer);
            root.appendChild(body);
            return root;
        }

        var activeIndex = 0;
        function show(index) {
            activeIndex = index;
            Array.prototype.forEach.call(explorer.children, function (ch, i) {
                ch.classList.toggle("active", i === index);
            });
            viewer.textContent = "";
            var file = allFiles[index];
            if (file.untracked) {
                var note = document.createElement("div");
                note.className = "diff-meta";
                note.textContent = "Changed file (no textual diff available): " + file.name;
                viewer.appendChild(note);
            } else {
                viewer.appendChild(renderFileViewer(file));
            }
        }

        allFiles.forEach(function (file, i) {
            var item = document.createElement("button");
            item.className = "diff-explorer-item";
            var status = file.untracked ? "?" : (file.deleted ? "D" : (file.addedFile ? "A" : "M"));
            item.innerHTML = '<span class="diff-status diff-status-' + status + '">' + status + "</span>"
                + '<span class="diff-path">' + esc(file.name || file.newPath) + "</span>"
                + '<span class="diff-file-stat"><span class="add">+' + (file.added || 0) + "</span> "
                + '<span class="del">-' + (file.removed || 0) + "</span></span>";
            item.addEventListener("click", function () { show(i); });
            explorer.appendChild(item);
        });

        body.appendChild(explorer);
        body.appendChild(viewer);
        root.appendChild(body);
        show(0);

        // Search within the active file's diff.
        var search = document.createElement("input");
        search.type = "search";
        search.className = "diff-search";
        search.placeholder = "Search in changes…";
        search.addEventListener("input", function () {
            var q = search.value.trim();
            Array.prototype.forEach.call(viewer.querySelectorAll(".diff-code"), function (cell) {
                cell.querySelectorAll("mark").forEach(function (m) { m.replaceWith(m.textContent); });
                if (!q) return;
                var text = cell.textContent;
                var idx = text.toLowerCase().indexOf(q.toLowerCase());
                if (idx < 0) return;
                var before = text.slice(0, idx), match = text.slice(idx, idx + q.length), after = text.slice(idx + q.length);
                cell.textContent = "";
                cell.appendChild(document.createTextNode(before));
                var mark = document.createElement("mark"); mark.textContent = match; cell.appendChild(mark);
                cell.appendChild(document.createTextNode(after));
            });
        });
        header.appendChild(search);
        return root;
    }

    return { render: render, renderWorkspace: renderWorkspace, parse: parse, highlight: highlight };
})();
