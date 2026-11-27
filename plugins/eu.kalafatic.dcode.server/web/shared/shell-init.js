/*
 * EVO-Code shell integration — wires the shared EVOShell foundation onto the
 * Darwin Code application shell. Layout-only: it never re-implements backend or
 * application logic, only the resizable shell, command palette and shortcuts.
 */
(function () {
    "use strict";

    function onReady(fn) {
        if (document.readyState === "loading") {
            document.addEventListener("DOMContentLoaded", fn);
        } else {
            fn();
        }
    }

    onReady(function () {
        var left = document.getElementById("left-panel");
        var work = document.getElementById("work-area");
        var right = document.getElementById("right-panel");
        var main = document.getElementById("main");
        var bottom = document.getElementById("bottom-panel");

        if (!left || !work || !right || !main || !bottom) {
            return; // shell missing — do not touch anything.
        }

        // Draggable splitters (persisted, double-click to reset).
        if (left.nextElementSibling === work || work.previousElementSibling === left) {
            EVOShell.split(left, work, {
                direction: "horizontal", side: "before",
                storageKey: "dcode.left.width", defaultSize: 280, min: 180, max: 600
            });
        }
        if (work.nextElementSibling === right || right.previousElementSibling === work) {
            EVOShell.split(work, right, {
                direction: "horizontal", side: "after",
                storageKey: "dcode.right.width", defaultSize: 320, min: 200, max: 800
            });
        }
        if (main.nextElementSibling === bottom) {
            EVOShell.split(main, bottom, {
                direction: "vertical", side: "after",
                storageKey: "dcode.bottom.height", defaultSize: 220, min: 80, max: 600
            });
        }

        // Collapse toggles.
        function togglePanel(panel, key) {
            var hidden = panel.style.display === "none";
            panel.style.display = hidden ? "" : "none";
            try { localStorage.setItem("evo.shell." + key, hidden ? "shown" : "hidden"); } catch (e) {}
            // Let the adjacent splitter re-flow naturally.
        }

        // Command palette entries (safe DOM-level actions only).
        EVOShell.commands({ label: "Toggle theme", keywords: ["dark", "light", "theme"],
            action: function () {
                EVOShell.themeToggle("dcode.theme");
                var btn = document.getElementById("btn-theme");
                if (btn) btn.textContent = document.documentElement.getAttribute("data-theme") === "light" ? "theme ☀" : "theme";
            } });
        EVOShell.commands({ label: "Toggle left sidebar", keywords: ["sidebar", "navigation"],
            shortcut: "Ctrl+B", action: function () { togglePanel(left, "dcode.left"); } });
        EVOShell.commands({ label: "Toggle context panel", keywords: ["context", "inspector"],
            action: function () { togglePanel(right, "dcode.right"); } });
        EVOShell.commands({ label: "Toggle bottom panel", keywords: ["console", "terminal", "logs"],
            shortcut: "Ctrl+J", action: function () { togglePanel(bottom, "dcode.bottom"); } });
        EVOShell.commands({ label: "Clear console", keywords: ["clear"],
            action: function () {
                var c = document.getElementById("panel-console");
                if (c) c.textContent = "";
            } });

        // Global shortcuts (non-typing only; Ctrl+K is handled by the shell).
        EVOShell.onShortcut("Ctrl+b", function () { togglePanel(left, "dcode.left"); });
        EVOShell.onShortcut("Ctrl+j", function () { togglePanel(bottom, "dcode.bottom"); });
    });
})();
