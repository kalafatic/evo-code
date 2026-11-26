/**
 * Workspace / repository / file explorer — professional navigator.
 *
 * Backend-authoritative: every node comes from the tree endpoint; the frontend keeps
 * no project records of its own. Adds a navigator toolbar (refresh, expand/collapse
 * all, live search, filter, sort) while preserving the existing tree, file opening and
 * Java-tool context actions.
 */
window.DCodeExplorer = (function () {
    let root = null;
    const expanded = new Set();

    // Navigator view state (persisted across renders, not restarts).
    let allWorkspaces = [];
    let query = '';
    let filter = 'all'; // all | with-repos | empty
    let sort = 'name'; // name | recent
    let searchTimer = null;

    async function render(container) {
        root = container;
        container.innerHTML = '';
        const loading = document.createElement('div');
        loading.className = 'empty-note';
        loading.style.padding = '10px';
        loading.textContent = 'Loading workspaces…';
        container.appendChild(loading);
        try {
            allWorkspaces = await DCodeApi.get('/api/v1/workspaces');
            paint();
        } catch (e) {
            container.innerHTML = '';
            const error = document.createElement('div');
            error.className = 'warning-box';
            error.textContent = 'Workspaces could not be loaded.';
            container.appendChild(error);
            const detail = document.createElement('div');
            detail.className = 'muted mono';
            detail.style.padding = '6px 10px';
            detail.textContent = e.status ? 'HTTP ' + e.status : e.message;
            container.appendChild(detail);
            const retry = document.createElement('button');
            retry.className = 'btn';
            retry.style.margin = '6px 10px';
            retry.textContent = 'Retry';
            retry.onclick = () => render(container);
            container.appendChild(retry);
            const conn = document.createElement('button');
            conn.className = 'btn';
            conn.textContent = 'Connection details';
            conn.onclick = () => DCodeApp.openModal('connection-details');
            container.appendChild(conn);
            DCodeApp.reportError('list workspaces', e);
        }
    }

    // ---- navigator toolbar ----
    function toolbar() {
        const bar = document.createElement('div');
        bar.className = 'nav-toolbar';

        const search = document.createElement('input');
        search.type = 'search';
        search.className = 'nav-search';
        search.placeholder = 'Search workspaces and repositories…';
        search.value = query;
        search.title = 'Filter the navigator by workspace or repository name';
        search.addEventListener('input', () => {
            clearTimeout(searchTimer);
            searchTimer = setTimeout(() => {
                query = search.value.trim();
                paint();
            }, 180); // debounce: no per-keystroke re-render
        });
        bar.appendChild(search);

        bar.appendChild(select(['all', 'All workspaces', 'with-repos',
            'With repositories', 'empty', 'Empty'], filter, (value) => {
            filter = value;
            paint();
        }, 'Filter'));

        bar.appendChild(select(['name', 'Name (A–Z)', 'recent', 'Recently created'],
            sort, (value) => {
                sort = value;
                paint();
            }, 'Sort'));

        const refresh = iconButton('⟳', 'Refresh', () => render(root));
        const expand = iconButton('⊞', 'Expand all', () => {
            for (const ws of allWorkspaces) { expanded.add('ws:' + ws.id); }
            paint();
        });
        const collapse = iconButton('⊟', 'Collapse all', () => {
            expanded.clear();
            paint();
        });
        bar.appendChild(expand);
        bar.appendChild(collapse);
        bar.appendChild(refresh);
        return bar;
    }

    function select(values, current, onChange, title) {
        // values: [value0, label0, value1, label1, ...]
        const el = document.createElement('select');
        el.className = 'nav-select';
        el.title = title || '';
        for (let i = 0; i < values.length; i += 2) {
            const option = document.createElement('option');
            option.value = values[i];
            option.textContent = values[i + 1];
            el.appendChild(option);
        }
        el.value = current;
        el.onchange = () => onChange(el.value);
        return el;
    }

    function iconButton(glyph, title, action) {
        const button = document.createElement('button');
        button.className = 'nav-icon-btn';
        button.textContent = glyph;
        button.title = title;
        button.onclick = action;
        return button;
    }

    function visibleWorkspaces() {
        const q = query.toLowerCase();
        let list = allWorkspaces.filter((ws) => {
            if (filter === 'with-repos' && (!ws.repositories || !ws.repositories.length)) {
                return false;
            }
            if (filter === 'empty' && ws.repositories && ws.repositories.length) {
                return false;
            }
            if (!q) { return true; }
            if (String(ws.name).toLowerCase().includes(q)) { return true; }
            return (ws.repositories || []).some((repo) =>
                String(repo.name).toLowerCase().includes(q));
        });
        list = list.slice().sort((a, b) => {
            if (sort === 'recent') {
                return String(b.createdAt || '').localeCompare(String(a.createdAt || ''));
            }
            return String(a.name).localeCompare(String(b.name));
        });
        return list;
    }

    async function paint() {
        root.innerHTML = '';
        root.appendChild(toolbar());

        if (!allWorkspaces.length) {
            const empty = document.createElement('div');
            empty.className = 'empty-note';
            empty.style.padding = '10px';
            empty.textContent = 'No workspaces. Create one via the toolbar.';
            root.appendChild(empty);
            const create = document.createElement('button');
            create.className = 'btn';
            create.textContent = 'Create workspace';
            create.onclick = () => DCodeApp.openModal('new-workspace');
            root.appendChild(create);
            return;
        }

        const visible = visibleWorkspaces();
        if (!visible.length) {
            const none = document.createElement('div');
            none.className = 'empty-note';
            none.style.padding = '10px';
            none.textContent = query || filter !== 'all'
                ? 'No matches for the current search/filter.'
                : 'No workspaces.';
            root.appendChild(none);
            if (query || filter !== 'all') {
                const clear = document.createElement('button');
                clear.className = 'btn';
                clear.style.margin = '6px 10px';
                clear.textContent = 'Clear search and filters';
                clear.onclick = () => {
                    query = '';
                    filter = 'all';
                    paint();
                };
                root.appendChild(clear);
            }
            return;
        }

        const ul = document.createElement('ul');
        ul.className = 'tree';
        for (const workspace of visible) {
            ul.appendChild(await workspaceNode(workspace));
        }
        root.appendChild(ul);
    }

    function highlight(text) {
        const safe = escapeHtml(text);
        if (!query) { return safe; }
        try {
            const pattern = new RegExp('(' + query.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
                + ')', 'ig');
            return safe.replace(pattern, '<mark>$1</mark>');
        } catch (e) {
            return safe;
        }
    }

    async function workspaceNode(workspace) {
        const li = document.createElement('li');
        const item = document.createElement('div');
        item.className = 'tree-item';
        const open = expanded.has('ws:' + workspace.id);
        item.innerHTML = '<span class="tree-icon dir">' + (open ? '▾' : '▸') + '</span>'
            + '<span class="nav-label">' + highlight(workspace.name) + '</span>'
            + '<span class="muted mono nav-meta">'
            + workspace.repositories.length + ' repo(s)</span>';
        item.onclick = () => toggle('ws:' + workspace.id);
        item.oncontextmenu = (event) => {
            DCodeApp.showContextMenu(event, [
                { label: 'Refresh', action: () => render(root) },
                { label: 'Open workspace', action: () => DCodeApp.openWorkspace(workspace) },
                '-',
                { label: 'Expand', action: () => { expanded.add('ws:' + workspace.id); paint(); } },
                { label: 'Collapse', action: () => { expanded.delete('ws:' + workspace.id); paint(); } }
            ]);
        };
        li.appendChild(item);
        if (open) {
            const children = document.createElement('ul');
            for (const repo of workspace.repositories) {
                children.appendChild(await repositoryNode(workspace, repo));
            }
            li.appendChild(children);
        }
        return li;
    }

    async function repositoryNode(workspace, repository) {
        const li = document.createElement('li');
        const item = document.createElement('div');
        item.className = 'tree-item';
        const readonly = repository.accessPolicy === 'READ_ONLY'
            ? ' <span class="badge" title="read only">RO</span>' : '';
        const open = expanded.has('repo:' + workspace.id + ':' + repository.id);
        item.innerHTML = '<span class="tree-icon dir">' + (open ? '▾' : '▸') + '</span>'
            + '<span class="mono nav-label">' + highlight(repository.name) + '</span>' + readonly;
        item.onclick = () => toggle('repo:' + workspace.id + ':' + repository.id);
        item.oncontextmenu = (event) => {
            DCodeApp.showContextMenu(event, [
                { label: 'Refresh', action: () => render(root) },
                {
                    label: 'Open in editor tree', action: () => {
                        DCodeApp.openTab('files:' + workspace.id, 'Files — ' + repository.name,
                            () => DCodeExplorer.renderFileTree(workspace));
                    }
                }
            ]);
        };
        li.appendChild(item);
        if (open) {
            li.appendChild(await fileTreeRoot(workspace, repository.name));
        }
        return li;
    }

    function toggle(key) {
        if (expanded.has(key)) { expanded.delete(key); } else { expanded.add(key); }
        paint();
    }

    async function fileTreeRoot(workspace, repositoryName) {
        const li = document.createElement('li');
        li.appendChild(await renderTreeLevel(workspace, '', repositoryName));
        return li;
    }

    async function renderTreeLevel(workspace, path, repositoryName) {
        const ul = document.createElement('ul');
        try {
            const q = '?depth=1' + (path ? '&path=' + encodeURIComponent(path) : '');
            const data = await DCodeApi.get('/api/v1/workspaces/' + workspace.id + '/tree' + q);
            for (const entry of data.entries || []) {
                const li = document.createElement('li');
                const item = document.createElement('div');
                item.className = 'tree-item';
                item.innerHTML = '<span class="tree-icon ' + (entry.type === 'dir' ? 'dir' : '') + '">'
                    + (entry.type === 'dir' ? '▸' : '·') + '</span>'
                    + '<span class="mono nav-label">' + escapeHtml(entry.name) + '</span>';
                if (entry.type === 'dir') {
                    item.onclick = () => toggleDir(li, workspace, entry.path, repositoryName);
                } else {
                    item.onclick = () => openFile(workspace, entry.path, repositoryName);
                }
                item.oncontextmenu = (event) => {
                    DCodeApp.showContextMenu(event, [
                        { label: 'View file', action: () => openFile(workspace, entry.path, repositoryName) },
                        { label: 'AST summary', action: () => DCodeTooling.runJavaTool('java_ast_summary', { path: entry.path }) },
                        { label: 'Analyze imports', action: () => DCodeTooling.runJavaTool('java_analyze_imports', { path: entry.path }) },
                        { label: 'Find references (type)', action: () => DCodeTooling.runJavaTool('java_find_references', { repository: repositoryName, kind: 'type', name: entry.name.replace(/\.java$/, '') }) }
                    ]);
                };
                li.appendChild(item);
                ul.appendChild(li);
            }
        } catch (e) {
            DCodeApp.logConsole('error', 'tree: ' + e.message);
        }
        return ul;
    }

    function toggleDir(li, workspace, path, repositoryName) {
        const existing = li.querySelector('ul');
        if (existing) {
            existing.remove();
            return;
        }
        const child = document.createElement('ul');
        child.innerHTML = '<li><span class="muted">loading…</span></li>';
        li.appendChild(child);
        renderTreeLevel(workspace, path, repositoryName).then((level) => {
            child.replaceWith(level);
        });
    }

    async function openFile(workspace, path, repositoryName) {
        try {
            const data = await DCodeApi.get('/api/v1/workspaces/' + workspace.id
                + '/files?path=' + encodeURIComponent(path));
            DCodeEditor.open(workspace, repositoryName, path, data.content);
        } catch (e) {
            DCodeApp.logConsole('error', 'file: ' + e.message);
        }
    }

    async function renderFileTree(workspace) {
        const container = document.createElement('div');
        container.className = 'flow';
        const toolbarEl = document.createElement('div');
        toolbarEl.className = 'toolbar';
        const refresh = document.createElement('button');
        refresh.className = 'btn btn-small';
        refresh.textContent = 'Refresh';
        refresh.onclick = () => {
            container.innerHTML = '';
            renderFileTree(workspace);
        };
        toolbarEl.appendChild(refresh);
        container.appendChild(toolbarEl);
        for (const repo of workspace.repositories) {
            const section = document.createElement('div');
            section.className = 'section-title';
            section.textContent = repo.name;
            container.appendChild(section);
            container.appendChild(await renderTreeLevel(workspace, '', repo.name));
        }
        return container;
    }

    function escapeHtml(text) {
        return String(text).replace(/&/g, '&amp;').replace(/</g, '&lt;')
            .replace(/>/g, '&gt;').replace(/"/g, '&quot;');
    }

    return { render, renderFileTree, escapeHtml };
})();
