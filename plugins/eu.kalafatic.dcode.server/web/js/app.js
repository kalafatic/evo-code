/**
 * DCode Web application shell: navigation, tabs, modals, SSE wiring, connection state
 * model and the structured console. The backend is authoritative; this module only
 * presents it.
 */
window.DCodeApp = (function () {
    const state = {
        view: 'workspace',
        tabs: new Map(),   // tabId -> {title, render}
        activeTab: null,
        workspace: null,
        session: null,     // currently OPEN session (detail view)
        activeSession: null, // session used for tool executions
        seenEventIds: new Set(),
        connection: 'DISCONNECTED', // DISCONNECTED|CONNECTING|CONNECTED|INITIALIZING|READY|DEGRADED|ERROR
        identity: null,
        diagnostic: ''
    };

    // ---------- context menu (keyboard-accessible) ----------
    const menuState = { items: [], index: -1, trigger: null };

    // ---------- structured console ----------
    const consoleEntries = [];
    let consoleCategory = 'ALL';
    let consolePaused = false;
    let consoleAutoscroll = true;
    let consoleRepeatKey = null;
    let consoleRepeatCount = 0;
    let consoleRepeatElement = null;
    const consolePane = () => document.getElementById('panel-console');
    const CONSOLE_CATEGORIES = ['ALL', 'SYSTEM', 'INFO', 'SUCCESS', 'WARNING', 'ERROR',
        'DEBUG', 'NETWORK', 'HARNESS', 'RUNTIME', 'TOOL', 'TASK', 'INFERENCE', 'EVENT'];

    function init() {
        applyTheme();
        wireNavigation();
        wireBottomTabs();
        wireConsoleControls();
        wireChat();
        renderView('workspace');
        applyAuth();
        const build = document.getElementById('frontend-build');
        if (build) { build.textContent = window.DCODE_FRONTEND_BUILD; }
        const sbBuild = document.getElementById('sb-build');
        if (sbBuild) { sbBuild.textContent = window.DCODE_FRONTEND_BUILD; }
        console.log('EVO-Code frontend build ' + window.DCODE_FRONTEND_BUILD
            + ' (' + location.origin + ')');
        probeBackend();
    }

    // ---------- connection state model ----------

    function setConnectionState(newState, identity, diagnostic) {
        state.connection = newState;
        state.identity = identity || null;
        state.diagnostic = diagnostic || '';
        const map = {
            DISCONNECTED: ['conn conn-disconnected', '● disconnected'],
            CONNECTING: ['conn conn-reconnecting', '● connecting'],
            CONNECTED: ['conn conn-connected', '● connected'],
            INITIALIZING: ['conn conn-reconnecting', '● initializing'],
            READY: ['conn conn-connected', '● ready'],
            DEGRADED: ['conn conn-connected', '● degraded'],
            ERROR: ['conn conn-disconnected', '● error']
        };
        const entry = map[newState] || map.DISCONNECTED;
        const el = document.getElementById('connection-status');
        el.className = entry[0];
        el.textContent = entry[1];
        el.title = diagnostic || newState;
        const sb = document.getElementById('sb-connection');
        if (sb) { sb.textContent = entry[1].replace('\u25CF ', ''); }
        if (identity) {
            const sbApp = document.getElementById('sb-application');
            const sbVer = document.getElementById('sb-version');
            if (sbApp) { sbApp.textContent = identity.application || 'DCODE'; }
            if (sbVer) { sbVer.textContent = 'v' + (identity.version || '—'); }
        }
        renderConnectionBanner();
    }

    function renderConnectionBanner() {
        let banner = document.getElementById('conn-banner');
        if (state.connection === 'ERROR' || state.connection === 'DEGRADED') {
            if (!banner) {
                banner = document.createElement('div');
                banner.id = 'conn-banner';
                document.body.insertBefore(banner, document.body.firstChild);
            }
            banner.className = state.connection === 'ERROR'
                ? 'conn-banner conn-banner-error' : 'conn-banner conn-banner-warning';
            banner.textContent = state.diagnostic
                || (state.connection === 'ERROR' ? 'Backend connection failed.'
                    : 'Backend reports degraded readiness.');
        } else if (banner) {
            banner.remove();
        }
    }

    function probeBackend() {
        setConnectionState('CONNECTING', null, '');
        logConsole('network', 'Connecting to ' + DCodeApi.base + '…');
        DCodeApi.get('/api/v1/health').then((health) => {
            const identity = {
                application: health.application || health.name || '',
                version: health.version || '',
                ready: !!(health.ready),
                status: health.status || '',
                stages: health.stages || {}
            };
            if (identity.application !== 'DCODE') {
                setConnectionState('ERROR', identity,
                    'The endpoint is not an EVO-Code server (it reports: '
                    + (identity.application || 'unrecognized HTTP service')
                    + '). Correct the server URL.');
                logConsole('error', 'Connection failed — the endpoint does not '
                    + 'provide the EVO-Code API.');
                return;
            }
            if (identity.ready) {
                setConnectionState('READY', identity, '');
                logConsole('success', 'EVO-Code backend ready: ' + identity.application
                    + ' ' + identity.version);
                logConsole('info', 'Loading workspaces and runtimes.');
                renderView(state.view);
                refreshRuntimeChip();
                // Make the chat immediately discoverable: the first time the backend
                // becomes ready with no tab open, show the Chat tab.
                if (!state.activeTab) {
                    openChat();
                }
                return;
            }
            if (identity.status === 'UP') {
                setConnectionState('INITIALIZING', identity,
                    'EVO-Code backend is starting up.');
                logConsole('info', 'EVO-Code backend is starting up (ready=false).');
            } else {
                setConnectionState('DEGRADED', identity,
                    'EVO-Code backend reports degraded readiness.');
                logConsole('warning', 'EVO-Code backend reports degraded readiness: '
                    + identity.status);
            }
        }).catch((e) => {
            let diagnostic;
            if (e.status === 404) {
                diagnostic = 'The endpoint does not provide the EVO-Code API '
                    + '(/api/v1/health is missing). Is this an EVO-Code server?';
            } else if (e.status === 401 || e.status === 403) {
                diagnostic = 'Authentication required — configure the server token.';
            } else {
                diagnostic = 'Unreachable: ' + e.message;
            }
            setConnectionState('ERROR', null, diagnostic);
            reportError('backend connection', e, diagnostic);
        });
    }

    /** User-triggered connection recheck (no automatic retry loop). */
    function recheckConnection() {
        probeBackend();
    }

    function applyAuth() {
        const btn = document.getElementById('btn-auth');
        btn.onclick = () => openModal('auth');
        if (DCodeApi.hasToken()) {
            btn.textContent = 'auth ✓';
        }
        const connBtn = document.getElementById('btn-conn');
        connBtn.onclick = () => openModal('connection-details');
    }

    // Theme toggle: UPM design standard light/dark pair. Default light
    // (matching the application default); persisted locally per browser.
    function applyTheme() {
        const saved = (function () {
            try { return localStorage.getItem('dcode.theme'); } catch (e) { return null; }
        })();
        const theme = saved === 'light' || saved === 'dark' ? saved : 'light';
        document.documentElement.dataset.theme = theme;
        const btn = document.getElementById('btn-theme');
        if (btn) {
            btn.onclick = () => {
                const next = document.documentElement.dataset.theme === 'dark'
                    ? 'light' : 'dark';
                document.documentElement.dataset.theme = next;
                try { localStorage.setItem('dcode.theme', next); } catch (e) { /* ignore */ }
            };
        }
    }

    function setConnectionStatus(status, detail) {
        if (status === 'connected') {
            if (state.connection === 'CONNECTING' || state.connection === 'DISCONNECTED') {
                setConnectionState('CONNECTED', null, detail || status);
            }
        } else if (status === 'reconnecting') {
            setConnectionState('CONNECTING', null, detail || status);
        } else if (status === 'disconnected') {
            setConnectionState('ERROR', null, detail || status);
        }
    }

    function wireNavigation() {
        document.querySelectorAll('.nav-btn').forEach((btn) => {
            btn.onclick = () => {
                document.querySelectorAll('.nav-btn').forEach((b) => b.classList.remove('active'));
                btn.classList.add('active');
                renderView(btn.dataset.view);
            };
        });
    }

    function wireBottomTabs() {
        document.querySelectorAll('.bottom-tab').forEach((tab) => {
            tab.onclick = () => {
                document.querySelectorAll('.bottom-tab').forEach((t) => t.classList.remove('active'));
                document.querySelectorAll('.bottom-pane').forEach((p) => p.classList.remove('active'));
                tab.classList.add('active');
                document.getElementById('panel-' + tab.dataset.panel).classList.add('active');
            };
        });
    }

    function wireConsoleControls() {
        const bar = document.getElementById('console-controls');
        if (!bar) {
            return;
        }
        CONSOLE_CATEGORIES.forEach((category) => {
            const chip = document.createElement('button');
            chip.className = 'console-chip' + (category === consoleCategory ? ' active' : '');
            chip.textContent = category;
            chip.onclick = () => {
                consoleCategory = category;
                bar.querySelectorAll('.console-chip').forEach((c) =>
                    c.classList.remove('active'));
                chip.classList.add('active');
                renderConsole();
            };
            bar.appendChild(chip);
        });
        const pause = document.createElement('button');
        pause.className = 'console-chip';
        pause.textContent = 'Pause';
        pause.onclick = () => {
            consolePaused = !consolePaused;
            pause.textContent = consolePaused ? 'Resume' : 'Pause';
        };
        bar.appendChild(pause);
        const autoscroll = document.createElement('button');
        autoscroll.className = 'console-chip active';
        autoscroll.textContent = 'Auto-scroll';
        autoscroll.onclick = () => {
            consoleAutoscroll = !consoleAutoscroll;
            autoscroll.classList.toggle('active', consoleAutoscroll);
        };
        bar.appendChild(autoscroll);
        const clear = document.createElement('button');
        clear.className = 'console-chip';
        clear.textContent = 'Clear';
        clear.title = 'Clear the visible console (entries are kept for export)';
        clear.onclick = () => {
            consolePane().innerHTML = '';
        };
        bar.appendChild(clear);
        const copy = document.createElement('button');
        copy.className = 'console-chip';
        copy.textContent = 'Copy';
        copy.onclick = () => {
            const text = consoleEntries.map((entry) => entry.line).join('\n');
            navigator.clipboard.writeText(text).then(() =>
                logConsole('info', 'Console copied to clipboard'));
        };
        bar.appendChild(copy);
        const exportBtn = document.createElement('button');
        exportBtn.className = 'console-chip';
        exportBtn.textContent = 'Export…';
        exportBtn.onclick = () => {
            const blob = new Blob([consoleEntries.map((entry) => entry.line).join('\n')],
                { type: 'text/plain' });
            const link = document.createElement('a');
            link.href = URL.createObjectURL(blob);
            link.download = 'dcode-console.txt';
            link.click();
        };
        bar.appendChild(exportBtn);
    }

    function renderConsole() {
        const pane = consolePane();
        if (!pane || consolePaused) {
            return;
        }
        pane.innerHTML = '';
        for (const entry of consoleEntries) {
            if (consoleCategory !== 'ALL' && entry.category !== consoleCategory) {
                continue;
            }
            pane.appendChild(entry.element.cloneNode(true));
        }
        while (pane.children.length > 500) {
            pane.removeChild(pane.firstChild);
        }
        if (consoleAutoscroll) {
            pane.scrollTop = pane.scrollHeight;
        }
    }

    /** Appends one structured console entry. */
    function logConsole(kind, message) {
        const category = normalizeCategory(kind);
        const line = document.createElement('div');
        line.className = 'log-line log-' + category.toLowerCase();
        const ts = document.createElement('span');
        ts.className = 'ts';
        ts.textContent = new Date().toISOString().slice(11, 19) + ' ';
        line.appendChild(ts);
        const tag = document.createElement('span');
        tag.className = 'console-tag console-tag-' + category.toLowerCase();
        tag.textContent = category + ' ';
        line.appendChild(tag);
        line.appendChild(document.createTextNode(message || ''));
        const entry = { category, message: message || '', line: ts.textContent + category
            + ' ' + (message || ''), element: line };
        consoleEntries.push(entry);
        while (consoleEntries.length > 2000) {
            consoleEntries.shift();
        }
        // error dedup: identical errors within a short window are aggregated
        if (category === 'ERROR') {
            const key = message || '';
            if (key === consoleRepeatKey) {
                consoleRepeatCount++;
                line.appendChild(document.createTextNode('  (×' + consoleRepeatCount
                    + ' — repeated)'));
            } else {
                consoleRepeatKey = key;
                consoleRepeatCount = 1;
            }
        }
        renderConsole();
    }

    function normalizeCategory(kind) {
        const normalized = String(kind || 'info').toUpperCase();
        return CONSOLE_CATEGORIES.indexOf(normalized) >= 0 ? normalized : 'INFO';
    }

    /** Structured, human-readable API failure report (deduplicated). */
    function reportError(context, error, overrideDiagnostic) {
        let status = error && error.status ? 'HTTP ' + error.status : 'n/a';
        let cause;
        if (status === 'HTTP 404' || status === 'HTTP 405') {
            cause = 'client/server API contract mismatch or wrong endpoint '
                + '(is this an EVO-Code server?)';
        } else if (status === 'HTTP 401' || status === 'HTTP 403') {
            cause = 'authentication required';
        } else if (status.startsWith('HTTP 5')) {
            cause = 'backend temporarily unavailable';
        } else {
            cause = 'backend unreachable or request failed';
        }
        const headline = context + ' failed.'
            + (overrideDiagnostic ? ' ' + overrideDiagnostic : '');
        logConsole('error', headline);
        logConsole('debug', '    Server: ' + DCodeApi.base);
        logConsole('debug', '    Operation: ' + context);
        logConsole('debug', '    Status: ' + status);
        logConsole('debug', '    Possible cause: ' + cause);
        logConsole('debug', '    Actions: Retry · Connection details · Diagnostics');
        logConsole('debug', '    Detail: ' + (error && error.message ? error.message
            : String(error)));
    }

    function renderView(view) {
        state.view = view;
        const content = document.getElementById('left-content');
        if (view === 'workspace') {
            DCodeExplorer.render(content);
        } else if (view === 'sessions') {
            DCodeSessions.render(content);
        } else if (view === 'runtimes') {
            DCodeRuntimes.renderRuntimes(content);
        } else if (view === 'models') {
            DCodeRuntimes.renderModels(content);
        } else if (view === 'modelmanager') {
            DCodeModelManager.render(content);
        } else if (view === 'tools') {
            DCodeTooling.render(content);
        } else if (view === 'activity') {
            renderActivity(content);
        }
        refreshRuntimeChip();
    }

    /** Opens (or focuses) the prominent Chat tab in the main work area. */
    function openChat() {
        const id = 'chat';
        if (!state.tabs.has(id)) {
            state.tabs.set(id, {
                title: '💬 Chat',
                render: () => window.DCodeChat.render()
            });
        }
        state.activeTab = id;
        renderTabs();
    }

    /** Wires the top-bar Chat button and the left-nav Chat entry. */
    function wireChat() {
        const btn = document.getElementById('btn-chat');
        if (btn) {
            btn.onclick = () => openChat();
        }
        const nav = document.getElementById('nav-chat');
        if (nav) {
            nav.onclick = () => {
                document.querySelectorAll('.nav-btn').forEach((b) => b.classList.remove('active'));
                nav.classList.add('active');
                openChat();
            };
        }
        const placeholder = document.getElementById('placeholder-chat');
        if (placeholder) {
            placeholder.onclick = () => openChat();
        }
    }

    /** Top-bar indicator of the default runtime (shared state, server-authoritative). */
    function refreshRuntimeChip() {
        if (state.connection !== 'READY') {
            return;
        }
        DCodeApi.get('/api/v1/runtimes').then((runtimes) => {
            const el = document.getElementById('top-runtime');
            if (!runtimes || !runtimes.length) {
                el.textContent = 'no runtimes';
                return;
            }
            const def = runtimes.find((runtime) => runtime.default) || null;
            const reachable = runtimes.filter((runtime) =>
                runtime.status && runtime.status.reachable).length;
            el.textContent = 'runtime: '
                + (def ? def.name + ' ★' : '(none)')
                + ' · ' + reachable + '/' + runtimes.length + ' reachable';
        }).catch(() => {});
    }

    function renderActivity(container) {
        container.innerHTML = '';
        const head = document.createElement('div');
        head.className = 'section-title';
        head.textContent = 'Tool activity (live)';
        container.appendChild(head);
        const list = document.createElement('div');
        list.id = 'activity-list';
        list.className = 'flow';
        list.style.padding = '8px';
        container.appendChild(list);
        const note = document.createElement('div');
        note.className = 'empty-note';
        note.textContent = 'Tool calls appear here as they execute.';
        list.appendChild(note);
    }

    function appendActivity(toolCall) {
        const list = document.getElementById('activity-list');
        if (!list) {
            return;
        }
        const empty = list.querySelector('.empty-note');
        if (empty) {
            empty.remove();
        }
        const card = document.createElement('div');
        card.className = 'tool-call' + (toolCall.errorMessage ? ' failed' : '');
        const head = document.createElement('div');
        head.className = 'tool-call-head';
        head.innerHTML = '<span class="name">' + escapeHtml(toolCall.toolName) + '</span>'
            + '<span class="badge">' + toolCall.state + '</span>'
            + '<span class="meta">' + (toolCall.startedAt || '').slice(11, 19) + '</span>';
        card.appendChild(head);
        const body = document.createElement('div');
        body.className = 'tool-call-body';
        if (toolCall.errorMessage) {
            const error = document.createElement('div');
            error.className = 'log-error';
            error.textContent = toolCall.errorMessage;
            body.appendChild(error);
        }
        head.onclick = () => card.classList.toggle('open');
        list.insertBefore(card, list.firstChild);
        if (list.children.length > 100) {
            list.removeChild(list.lastChild);
        }
    }

    function openWorkspace(workspace) {
        state.workspace = workspace;
        document.getElementById('top-workspace').textContent = 'workspace: ' + workspace.name;
    }

    function openSession(session) {
        state.session = session;
        state.activeSession = session;
        document.getElementById('top-session').textContent = 'session: '
            + (session.title || session.id);
        setConnectionStatus('reconnecting', 'connecting to session');
        DCodeSse.connect(session.id, onSessionEvent, setConnectionStatus);
        openTab('session:' + session.id, 'Session — ' + (session.title || session.id),
            () => DCodeSessions.renderDetail(session));
    }

    function onSessionEvent(type, dataJson) {
        logEvent(type, dataJson);
        let data = {};
        try {
            data = JSON.parse(dataJson);
        } catch (e) { /* opaque payload */ }
        if (type === 'SESSION_STATE_CHANGED') {
            refreshSessionDetail();
        } else if (type === 'MESSAGE_APPENDED') {
            refreshSessionMessages();
        } else if (type === 'TOOL_CALL_COMPLETED') {
            if (state.session && data.callId) {
                refreshSessionMessages();
            }
        }
    }

    function refreshSessionDetail() {
        if (!state.session) {
            return;
        }
        DCodeApi.get('/api/v1/sessions/' + state.session.id).then((fresh) => {
            state.session = fresh;
            state.activeSession = fresh;
            const tab = state.tabs.get('session:' + fresh.id);
            if (tab && state.activeTab === 'session:' + fresh.id) {
                renderTab('session:' + fresh.id);
            }
        }).catch(() => { /* session may be gone */ });
    }

    function refreshSessionMessages() {
        if (!state.session) {
            return;
        }
        const container = document.getElementById('session-messages-' + state.session.id);
        if (!container) {
            return;
        }
        DCodeApi.get('/api/v1/sessions/' + state.session.id + '/messages').then((messages) => {
            container.innerHTML = '';
            for (const message of messages) {
                DCodeSessions.appendMessage(container, message);
            }
        }).catch(() => { /* best effort */ });
    }

    function onToolResult(toolName, response) {
        const call = response.toolCall || {};
        logConsole('tool', toolName + ' → ' + (call.state || '?'));
        appendActivity(call);
        const result = response.result;
        if (result && !result.success) {
            logConsole('error', toolName + ': ' + (result.error || 'failed'));
            return;
        }
        logConsole('success', toolName + ' executed');
        openTab('tool:' + toolName + ':' + Date.now(), 'Result — ' + toolName,
            () => renderToolResult(toolName, response));
    }

    function renderToolResult(toolName, response) {
        const container = document.createElement('div');
        container.className = 'flow';
        const head = document.createElement('div');
        head.className = 'section-title';
        head.textContent = toolName + ' — structured result';
        container.appendChild(head);
        const pre = document.createElement('pre');
        pre.className = 'result';
        pre.textContent = JSON.stringify(response.result, null, 2);
        container.appendChild(pre);
        const rawHead = document.createElement('div');
        rawHead.className = 'section-title';
        rawHead.textContent = 'Raw output';
        container.appendChild(rawHead);
        const raw = document.createElement('pre');
        raw.className = 'result';
        raw.textContent = response.output || '';
        container.appendChild(raw);
        return container;
    }

    function logEvent(type, dataJson) {
        logConsole('event', type + ' ' + (dataJson || '{}'));
    }

    function openTab(id, title, render) {
        state.tabs.set(id, { title, render });
        state.activeTab = id;
        renderTabs();
    }

    function closeTab(id) {
        state.tabs.delete(id);
        if (state.activeTab === id) {
            state.activeTab = state.tabs.size > 0
                ? state.tabs.keys().next().value : null;
        }
        renderTabs();
    }

    function renderTabs() {
        const bar = document.getElementById('tabs-bar');
        bar.innerHTML = '';
        for (const [id, tab] of state.tabs) {
            const el = document.createElement('div');
            el.className = 'tab' + (id === state.activeTab ? ' active' : '');
            const label = document.createElement('span');
            label.textContent = tab.title;
            el.appendChild(label);
            const close = document.createElement('span');
            close.className = 'tab-close';
            close.textContent = '✕';
            close.onclick = (event) => {
                event.stopPropagation();
                closeTab(id);
            };
            el.appendChild(close);
            el.onclick = () => {
                state.activeTab = id;
                renderTabs();
            };
            bar.appendChild(el);
        }
        renderTab(state.activeTab);
    }

    function renderTab(id) {
        const content = document.getElementById('tab-content');
        const tab = state.tabs.get(id);
        if (!tab) {
            content.innerHTML = '<div class="placeholder"><div class="placeholder-text">'
                + 'No tab open.</div></div>';
            return;
        }
        content.innerHTML = '';
        Promise.resolve(tab.render()).then((rendered) => {
            if (rendered instanceof Node) {
                content.appendChild(rendered);
            }
        });
    }

    function showContextMenu(event, items) {
        const menu = document.getElementById('context-menu');
        menu.innerHTML = '';
        menu.setAttribute('role', 'menu');
        menuState.items = [];
        items.forEach((item) => {
            if (item === '-') {
                const sep = document.createElement('div');
                sep.className = 'menu-sep';
                sep.setAttribute('role', 'separator');
                menu.appendChild(sep);
                return;
            }
            const el = document.createElement('div');
            el.className = 'menu-item' + (item.danger ? ' danger' : '');
            el.textContent = item.label;
            el.setAttribute('role', 'menuitem');
            el.setAttribute('tabindex', '-1');
            el.onclick = () => {
                hideContextMenu();
                item.action();
            };
            menu.appendChild(el);
            menuState.items.push(el);
        });
        menu.classList.remove('hidden');
        menu.style.left = Math.min(event.clientX, window.innerWidth - 220) + 'px';
        menu.style.top = Math.min(event.clientY, window.innerHeight - 200) + 'px';
        event.preventDefault();
        menuState.index = menuState.items.length > 0 ? 0 : -1;
        menuState.trigger = event.target && typeof event.target.focus === 'function'
            ? event.target : null;
        if (menuState.index >= 0) {
            focusMenuItem(menuState.index);
        }
    }

    function hideContextMenu() {
        const menu = document.getElementById('context-menu');
        menu.classList.add('hidden');
        menu.removeAttribute('role');
        menuState.items.forEach((item) => item.classList.remove('focused'));
        menuState.items = [];
        menuState.index = -1;
        menuState.trigger = null;
    }

    function focusMenuItem(index) {
        const item = menuState.items[index];
        if (!item) {
            return;
        }
        menuState.items.forEach((candidate) => candidate.classList.remove('focused'));
        menuState.index = index;
        item.classList.add('focused');
        item.focus();
    }

    function moveMenuFocus(delta) {
        if (menuState.items.length === 0) {
            return;
        }
        const next = (menuState.index + delta + menuState.items.length)
            % menuState.items.length;
        focusMenuItem(next);
    }

    document.addEventListener('click', hideContextMenu);

    document.addEventListener('keydown', (event) => {
        if (menuState.items.length === 0) {
            return;
        }
        if (event.key === 'ArrowDown') {
            event.preventDefault();
            moveMenuFocus(1);
        } else if (event.key === 'ArrowUp') {
            event.preventDefault();
            moveMenuFocus(-1);
        } else if (event.key === 'Enter') {
            event.preventDefault();
            const item = menuState.items[menuState.index];
            if (item) {
                item.onclick();
            }
        } else if (event.key === 'Escape') {
            event.preventDefault();
            const trigger = menuState.trigger;
            hideContextMenu();
            if (trigger) {
                trigger.focus();
            }
        }
    });

    // ---------- modals ----------

    function openModal(kind, payload) {
        const root = document.getElementById('modal-root');
        const overlay = document.createElement('div');
        overlay.className = 'overlay';
        const modal = document.createElement('div');
        modal.className = 'modal';
        overlay.appendChild(modal);
        overlay.onclick = (event) => {
            if (event.target === overlay) {
                overlay.remove();
            }
        };
        root.appendChild(overlay);
        buildModal(kind, modal, overlay, payload);
    }

    function buildModal(kind, modal, overlay, payload) {
        if (kind === 'auth') {
            modalTitle(modal, 'Server token', overlay);
            const body = modalBody(modal);
            const field = document.createElement('div');
            field.className = 'field';
            field.innerHTML = '<label>Bearer token (leave empty for development mode)</label>'
                + '<input type="password" id="auth-token" placeholder="token">';
            body.appendChild(field);
            modalActions(modal, [{
                label: 'Apply', primary: true,
                action: () => {
                    DCodeApi.setToken(document.getElementById('auth-token').value || null);
                    document.getElementById('btn-auth').textContent = DCodeApi.hasToken() ? 'auth ✓' : 'auth';
                    overlay.remove();
                    probeBackend();
                }
            }]);
            return;
        }
        if (kind === 'new-workspace') {
            modalTitle(modal, 'Create workspace', overlay);
            const body = modalBody(modal);
            body.appendChild(field('Name', 'ws-name'));
            body.appendChild(field('Root path (optional)', 'ws-root'));
            modalActions(modal, [{
                label: 'Create', primary: true,
                action: async () => {
                    try {
                        const created = await DCodeApi.post('/api/v1/workspaces', {
                            name: document.getElementById('ws-name').value,
                            rootPath: document.getElementById('ws-root').value || null
                        });
                        logConsole('success', 'workspace created: ' + created.id);
                        overlay.remove();
                        renderView('workspace');
                    } catch (e) {
                        reportError('create workspace', e);
                    }
                }
            }]);
            return;
        }
        if (kind === 'new-session') {
            modalTitle(modal, 'New session', overlay);
            const body = modalBody(modal);
            body.appendChild(field('Title', 'sess-title'));
            const wsSelect = field('Workspace', 'sess-workspace');
            body.appendChild(wsSelect);
            const select = document.createElement('select');
            select.id = 'sess-workspace-select';
            wsSelect.appendChild(select);
            DCodeApi.get('/api/v1/workspaces').then((workspaces) => {
                for (const workspace of workspaces) {
                    const option = document.createElement('option');
                    option.value = workspace.id;
                    option.textContent = workspace.name;
                    select.appendChild(option);
                }
            });
            // per-session runtime/model selection (same shared backend as the RCP IDE)
            const runtimeField = field('Runtime', 'sess-runtime');
            body.appendChild(runtimeField);
            const runtimeSelect = document.createElement('select');
            runtimeSelect.id = 'sess-runtime-select';
            runtimeField.appendChild(runtimeSelect);
            DCodeRuntimes.populateRuntimeSelect(runtimeSelect, true);
            const modelField = field('Model', 'sess-model');
            body.appendChild(modelField);
            const modelSelect = document.createElement('select');
            modelSelect.id = 'sess-model-select';
            modelField.appendChild(modelSelect);
            DCodeRuntimes.populateModelSelect(modelSelect, null, true);
            runtimeSelect.onchange = () =>
                DCodeRuntimes.populateModelSelect(modelSelect, runtimeSelect.value, true);
            modalActions(modal, [{
                label: 'Create', primary: true,
                action: async () => {
                    try {
                        const created = await DCodeApi.post('/api/v1/sessions', {
                            title: document.getElementById('sess-title').value,
                            workspaceId: select.value,
                            providerId: runtimeSelect.value || null,
                            modelId: modelSelect.value || null
                        });
                        logConsole('success', 'session created: ' + created.id);
                        overlay.remove();
                        renderView('sessions');
                        openSession(created);
                    } catch (e) {
                        reportError('create session', e);
                    }
                }
            }]);
            return;
        }
        if (kind === 'submit-task') {
            const session = payload;
            modalTitle(modal, 'Submit task — ' + (session.title || session.id), overlay);
            const body = modalBody(modal);
            body.appendChild(field('Task instructions', 'task-text', 'textarea'));
            // per-task runtime/model override (this run only; selection is preserved)
            const runtimeField = field('Runtime override (optional)', 'task-runtime');
            body.appendChild(runtimeField);
            const runtimeSelect = document.createElement('select');
            runtimeSelect.id = 'task-runtime-select';
            runtimeField.appendChild(runtimeSelect);
            DCodeRuntimes.populateRuntimeSelect(runtimeSelect, true);
            const modelField = field('Model override (optional)', 'task-model');
            body.appendChild(modelField);
            const modelSelect = document.createElement('select');
            modelSelect.id = 'task-model-select';
            modelField.appendChild(modelSelect);
            DCodeRuntimes.populateModelSelect(modelSelect, null, true);
            runtimeSelect.onchange = () =>
                DCodeRuntimes.populateModelSelect(modelSelect, runtimeSelect.value, true);
            modalActions(modal, [{
                label: 'Submit', primary: true,
                action: async () => {
                    try {
                        await DCodeApi.post('/api/v1/sessions/' + session.id + '/start', {
                            task: document.getElementById('task-text').value,
                            providerId: runtimeSelect.value || null,
                            modelId: modelSelect.value || null
                        });
                        logConsole('task', 'task submitted to ' + session.id);
                        overlay.remove();
                        openSession(session);
                    } catch (e) {
                        reportError('submit task', e);
                    }
                }
            }]);
            return;
        }
        if (kind === 'runtime-edit') {
            DCodeRuntimes.buildRuntimeModal(modal, overlay, payload);
            return;
        }
        if (kind === 'runtime-scan') {
            DCodeRuntimes.buildScanModal(modal, overlay);
            return;
        }
        if (kind === 'java-tool') {
            const tool = payload;
            modalTitle(modal, tool.name, overlay);
            const body = modalBody(modal);
            body.innerHTML += '<div class="muted" style="margin-bottom:10px">' + tool.desc + '</div>';
            tool.args.forEach((arg) => {
                body.appendChild(field(arg.label, 'arg-' + arg.k, 'text'));
            });
            modalActions(modal, [{
                label: 'Run', primary: true,
                action: async () => {
                    const args = {};
                    tool.args.forEach((arg) => {
                        const value = document.getElementById('arg-' + arg.k).value;
                        if (value) {
                            args[arg.k] = value;
                        }
                    });
                    overlay.remove();
                    await runJavaTool(tool.name, args);
                }
            }]);
            return;
        }
        if (kind === 'rename-confirm') {
            const tool = payload;
            modalTitle(modal, 'Rename — confirmation required', overlay);
            const body = modalBody(modal);
            const warning = document.createElement('div');
            warning.className = 'warning-box';
            warning.textContent = 'java_rename MUTATES source files: the target file is '
                + 'renamed and all references are updated by the JDT refactoring. '
                + 'Repositories marked READ_ONLY reject this operation.';
            body.appendChild(warning);
            tool.args.forEach((arg) => {
                body.appendChild(field(arg.label, 'arg-' + arg.k, 'text'));
            });
            modalActions(modal, [{
                label: 'Rename', primary: true, danger: true,
                action: async () => {
                    const args = {};
                    tool.args.forEach((arg) => {
                        const value = document.getElementById('arg-' + arg.k).value;
                        if (value) {
                            args[arg.k] = value;
                        }
                    });
                    overlay.remove();
                    await runJavaTool(tool.name, args);
                }
            }]);
            return;
        }
        if (kind === 'generic-tool') {
            modalTitle(modal, 'Invoke any tool', overlay);
            const body = modalBody(modal);
            body.appendChild(field('Tool name', 'generic-name', 'text'));
            body.appendChild(field('Arguments (JSON)', 'generic-args', 'textarea'));
            const hint = document.createElement('div');
            hint.className = 'muted';
            hint.textContent = 'Example: {"repository": "javarepo"} — sessionId is added automatically.';
            body.appendChild(hint);
            modalActions(modal, [{
                label: 'Execute', primary: true,
                action: async () => {
                    let args = {};
                    try {
                        args = JSON.parse(document.getElementById('generic-args').value || '{}');
                    } catch (e) {
                        logConsole('error', 'invalid JSON arguments');
                        return;
                    }
                    const name = document.getElementById('generic-name').value;
                    overlay.remove();
                    await runJavaTool(name, args);
                }
            }]);
            return;
        }
        if (kind === 'connection-details') {
            modalTitle(modal, 'Connection details', overlay);
            const body = modalBody(modal);
            body.appendChild(kv('Endpoint', DCodeApi.base));
            body.appendChild(kv('State', state.connection));
            body.appendChild(kv('Diagnostic', state.diagnostic || '(none)'));
            if (state.identity) {
                body.appendChild(kv('Backend', state.identity.application
                    + (state.identity.version ? ' ' + state.identity.version : '')));
                body.appendChild(kv('Health', state.identity.status
                    + (state.identity.ready ? ' (ready)' : ' (not ready)')));
                const stages = state.identity.stages;
                const stageText = Object.entries(stages || {})
                    .map(([k, v]) => k + ': ' + v).join(' · ');
                body.appendChild(kv('Stages', stageText || '(none reported)'));
            }
            modalActions(modal, [
                {
                    label: 'Recheck connection', primary: true,
                    action: () => {
                        overlay.remove();
                        recheckConnection();
                    }
                },
                {
                    label: 'Configure endpoint',
                    action: () => {
                        overlay.remove();
                        openModal('auth');
                    }
                }
            ]);
            return;
        }
    }

    function kv(key, value) {
        const row = document.createElement('div');
        row.className = 'kv';
        row.innerHTML = '<span class="k">' + escapeHtml(key) + '</span>'
            + '<span class="v">' + escapeHtml(String(value)) + '</span>';
        return row;
    }

    function modalTitle(modal, text, overlay) {
        const title = document.createElement('div');
        title.className = 'modal-title';
        title.innerHTML = '<span>' + escapeHtml(text) + '</span>'
            + '<span class="close" onclick="this.closest(\'.overlay\').remove()">✕</span>';
        modal.appendChild(title);
    }

    function modalBody(modal) {
        const body = document.createElement('div');
        body.className = 'modal-body';
        modal.appendChild(body);
        return body;
    }

    function modalActions(modal, actions) {
        const bar = document.createElement('div');
        bar.className = 'modal-actions';
        for (const action of actions) {
            const btn = document.createElement('button');
            btn.className = 'btn' + (action.primary ? ' btn-primary' : '')
                + (action.danger ? ' btn-danger' : '');
            btn.textContent = action.label;
            btn.onclick = action.action;
            bar.appendChild(btn);
        }
        modal.appendChild(bar);
    }

    function field(label, id, type) {
        const wrapper = document.createElement('div');
        wrapper.className = 'field';
        wrapper.innerHTML = '<label>' + escapeHtml(label) + '</label>';
        if (type === 'textarea') {
            wrapper.innerHTML += '<textarea id="' + id + '" rows="6"></textarea>';
        } else {
            wrapper.innerHTML += '<input type="text" id="' + id + '">';
        }
        return wrapper;
    }

    function escapeHtml(text) {
        return DCodeExplorer.escapeHtml(text);
    }

    return {
        init,
        openTab,
        openWorkspace,
        openSession,
        openModal,
        showContextMenu,
        logConsole,
        reportError,
        onToolResult,
        setConnectionStatus,
        recheckConnection,
        renderRuntimesNow: () => {
            if (state.view === 'runtimes') {
                DCodeRuntimes.renderRuntimes(document.getElementById('left-content'));
            }
            refreshRuntimeChip();
        },
        get connectionState() { return state.connection; },
        get activeSession() { return state.activeSession; },
        get activeSessionObj() { return state.activeSession; }
    };
})();

document.addEventListener('DOMContentLoaded', () => DCodeApp.init());
