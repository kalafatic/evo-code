/**
 * Sessions and tasks: the central interaction model. States come exclusively from the
 * backend; the UI renders the server's SessionState values.
 */
window.DCodeSessions = (function () {
    function render(container) {
        container.innerHTML = '';
        const toolbar = document.createElement('div');
        toolbar.className = 'toolbar';
        const newBtn = document.createElement('button');
        newBtn.className = 'btn btn-primary';
        newBtn.textContent = 'New session';
        newBtn.onclick = () => DCodeApp.openModal('new-session');
        toolbar.appendChild(newBtn);
        const refresh = document.createElement('button');
        refresh.className = 'btn';
        refresh.textContent = 'Refresh';
        refresh.onclick = () => render(container);
        toolbar.appendChild(refresh);
        const search = document.createElement('input');
        search.type = 'search';
        search.className = 'search-input';
        search.placeholder = 'Filter sessions…';
        search.oninput = () => load(list, search.value);
        toolbar.appendChild(search);
        container.appendChild(toolbar);

        const list = document.createElement('ul');
        list.className = 'list';
        container.appendChild(list);
        load(list, '');
    }

    async function load(list, filter) {
        list.innerHTML = '';
        const needle = String(filter || '').toLowerCase();
        try {
            const workspaces = await DCodeApi.get('/api/v1/workspaces');
            for (const workspace of workspaces) {
                const sessions = await DCodeApi.get('/api/v1/sessions?workspaceId='
                    + encodeURIComponent(workspace.id));
                for (const session of sessions) {
                    const titleText = session.title || session.id;
                    if (needle && !titleText.toLowerCase().includes(needle)
                            && !String(workspace.name).toLowerCase().includes(needle)
                            && !String(session.id).toLowerCase().includes(needle)) {
                        continue;
                    }
                    const li = document.createElement('li');
                    li.className = 'list-item';
                    const title = document.createElement('div');
                    title.className = 'item-title';
                    title.textContent = session.title || session.id;
                    const sub = document.createElement('div');
                    sub.className = 'item-sub';
                    sub.innerHTML = workspace.name + ' · ' + badge(session.state)
                        + ' · ' + session.agentRole;
                    li.appendChild(title);
                    li.appendChild(sub);
                    li.onclick = () => DCodeApp.openSession(session);
                    li.oncontextmenu = (event) => {
                        DCodeApp.showContextMenu(event, [
                            {
                                label: 'Open',
                                action: () => DCodeApp.openSession(session)
                            },
                            {
                                label: 'Rename…',
                                action: () => renameSession(session, list)
                            },
                            {
                                label: 'Delete', danger: true,
                                action: () => deleteSession(session, list)
                            }
                        ]);
                    };
                    list.appendChild(li);
                }
            }
            if (list.children.length === 0) {
                const empty = document.createElement('div');
                empty.className = 'empty-note';
                empty.style.padding = '10px';
                empty.textContent = 'No sessions yet. Use "New session" to start one.';
                list.appendChild(empty);
            }
        } catch (e) {
            const error = document.createElement('div');
            error.className = 'warning-box';
            error.textContent = 'Sessions could not be loaded.';
            list.appendChild(error);
            const retry = document.createElement('button');
            retry.className = 'btn btn-small';
            retry.textContent = 'Retry';
            retry.onclick = () => load(list);
            list.appendChild(retry);
            DCodeApp.reportError('list sessions', e);
        }
    }

    function badge(state) {
        const cls = {
            CREATED: 'badge',
            RUNNING: 'badge badge-running',
            SUSPENDED: 'badge badge-suspended',
            WAITING_FOR_APPROVAL: 'badge badge-waiting',
            COMPLETED: 'badge badge-completed',
            FAILED: 'badge badge-failed',
            CANCELLED: 'badge badge-failed',
            ARCHIVED: 'badge'
        }[state] || 'badge';
        return '<span class="' + cls + '">' + state + '</span>';
    }

    async function renameSession(session, list) {
        const title = prompt('New title for ' + (session.title || session.id) + ':', 
            session.title || '');
        if (title === null) {
            return;
        }
        try {
            await DCodeApi.post('/api/v1/sessions/' + session.id + '/rename',
                { title: title.trim() });
            DCodeApp.logConsole('system', 'session renamed: ' + session.id);
            load(list);
        } catch (e) {
            DCodeApp.reportError('rename session', e);
        }
    }

    async function deleteSession(session, list) {
        if (!confirm('Delete the session "' + (session.title || session.id)
            + '"?\nIts message history is removed as well.')) {
            return;
        }
        try {
            await DCodeApi.del('/api/v1/sessions/' + session.id);
            DCodeApp.logConsole('system', 'session deleted: ' + session.id);
            load(list);
        } catch (e) {
            DCodeApp.reportError('delete session', e);
        }
    }

    function copyText(text) {
        const content = text || '';
        if (!content) {
            return;
        }
        navigator.clipboard.writeText(content).then(() =>
            DCodeApp.logConsole('info', 'Copied to clipboard'));
    }

    /** Extracts the content of fenced code blocks (``` … ```); '' when none. */
    function extractCodeBlocks(text) {
        const source = text || '';
        const blocks = [];
        let fence = source.indexOf('```');
        while (fence >= 0) {
            const end = source.indexOf('```', fence + 3);
            if (end < 0) {
                break;
            }
            let block = source.slice(fence + 3, end);
            const newline = block.indexOf('\n');
            if (newline >= 0) {
                block = block.slice(newline + 1); // strip the language tag line
            }
            if (block.trim()) {
                blocks.push(block.trim());
            }
            fence = source.indexOf('```', end + 3);
        }
        return blocks.join('\n');
    }

    function renderDetail(session) {
        const pane = document.createElement('div');
        pane.className = 'pane active';

        const head = document.createElement('div');
        head.className = 'toolbar';
        const title = document.createElement('span');
        title.className = 'mono';
        title.style.fontWeight = '600';
        title.textContent = session.title || session.id;
        head.appendChild(title);
        head.innerHTML += ' ' + badge(session.state);
        const spacer = document.createElement('span');
        spacer.className = 'spacer';
        head.appendChild(spacer);
        const resumeBtn = document.createElement('button');
        resumeBtn.className = 'btn btn-primary';
        resumeBtn.textContent = 'Submit task';
        resumeBtn.onclick = () => DCodeApp.openModal('submit-task', session);
        head.appendChild(resumeBtn);
        const cancelBtn = document.createElement('button');
        cancelBtn.className = 'btn btn-danger';
        cancelBtn.textContent = 'Cancel';
        cancelBtn.onclick = async () => {
            await DCodeApi.post('/api/v1/sessions/' + session.id + '/cancel');
            DCodeApp.logConsole('system', 'cancellation requested for ' + session.id);
        };
        if (session.state === 'RUNNING') {
            head.appendChild(cancelBtn);
        }
        const changesBtn = document.createElement('button');
        changesBtn.className = 'btn';
        changesBtn.textContent = 'Changes / diff';
        changesBtn.onclick = () => DCodeApp.openTab('changes:' + session.id, 'Changes',
            () => renderChanges(session));
        head.appendChild(changesBtn);
        pane.appendChild(head);

        const body = document.createElement('div');
        body.className = 'col';
        body.style.flex = '1';
        body.style.overflow = 'auto';
        body.style.padding = '10px';

        const messages = document.createElement('div');
        messages.className = 'card';
        messages.innerHTML = '<h3>Conversation</h3>';
        body.appendChild(messages);
        const messagesList = document.createElement('div');
        messagesList.id = 'session-messages-' + session.id;
        messages.appendChild(messagesList);

        const reviews = document.createElement('div');
        reviews.className = 'card';
        reviews.innerHTML = '<h3>Reviews</h3>';
        body.appendChild(reviews);
        const reviewsList = document.createElement('div');
        reviewsList.id = 'session-reviews-' + session.id;
        reviews.appendChild(reviewsList);

        pane.appendChild(body);
        loadMessages(session, messagesList);
        loadReviews(session, reviewsList);
        return pane;
    }

    async function loadMessages(session, container) {
        try {
            const messages = await DCodeApi.get('/api/v1/sessions/' + session.id + '/messages');
            container.innerHTML = '';
            for (const message of messages) {
                appendMessage(container, message);
            }
            container.scrollTop = container.scrollHeight;
        } catch (e) {
            DCodeApp.logConsole('error', 'messages: ' + e.message);
        }
    }

    function appendMessage(container, message) {
        const role = (message.role || '').toLowerCase();
        const line = document.createElement('div');
        line.className = 'log-line log-' + role;
        const ts = document.createElement('span');
        ts.className = 'ts';
        ts.textContent = (message.timestamp || '').slice(11, 19) + ' ';
        line.appendChild(ts);

        const content = message.content || '';
        if (role === 'assistant' || role === 'user') {
            // Conversation roles render as markdown (code syntax highlighting +
            // per-block copy); system/tool lines stay plain mono text.
            const body = document.createElement('div');
            body.className = 'chat-body';
            body.innerHTML = DCodeMarkdown.renderMarkdown(content);
            DCodeMarkdown.wireCodeCopy(body);
            line.appendChild(body);
        } else {
            line.appendChild(document.createTextNode(content));
        }
        // Message model has no structured parts (flat role/content strings), so
        // "Copy Code" extracts fenced blocks and falls back to the whole text.
        // Retry/Continue are intentionally absent: no backend operation exists.
        line.oncontextmenu = (event) => {
            DCodeApp.showContextMenu(event, [
                {
                    label: 'Copy',
                    action: () => copyText(content)
                },
                {
                    label: 'Copy Code',
                    action: () => {
                        const code = extractCodeBlocks(content);
                        copyText(code || content);
                    }
                }
            ]);
        };
        container.appendChild(line);
        container.scrollTop = container.scrollHeight;
    }

    async function loadReviews(session, container) {
        try {
            const reviews = await DCodeApi.get('/api/v1/sessions/' + session.id + '/reviews');
            container.innerHTML = '';
            if (!reviews.length) {
                const note = document.createElement('div');
                note.className = 'empty-note';
                note.textContent = 'No reviews for this session.';
                container.appendChild(note);
                return;
            }
            for (const review of reviews) {
                const row = document.createElement('div');
                row.className = 'kv';
                row.innerHTML = '<span class="k">review</span>'
                    + '<span class="v">' + badge(review.status) + '</span>';
                const actions = document.createElement('div');
                actions.className = 'row';
                if (review.status === 'PENDING') {
                    const approve = document.createElement('button');
                    approve.className = 'btn btn-small btn-primary';
                    approve.textContent = 'Approve';
                    approve.onclick = async () => {
                        await DCodeApi.post('/api/v1/reviews/' + review.id + '/approve',
                            { approverId: 'web', comment: 'approved in web UI' });
                        loadReviews(session, container);
                    };
                    actions.appendChild(approve);
                    const reject = document.createElement('button');
                    reject.className = 'btn btn-small btn-danger';
                    reject.textContent = 'Reject';
                    reject.onclick = async () => {
                        await DCodeApi.post('/api/v1/reviews/' + review.id + '/reject',
                            { approverId: 'web', comment: 'rejected in web UI' });
                        loadReviews(session, container);
                    };
                    actions.appendChild(reject);
                }
                container.appendChild(row);
                container.appendChild(actions);
            }
        } catch (e) {
            DCodeApp.logConsole('error', 'reviews: ' + e.message);
        }
    }

    function renderChanges(session) {
        const container = document.createElement('div');
        container.className = 'flow';
        const note = document.createElement('div');
        note.className = 'muted';
        note.textContent = 'Live git state of the session workspace (server-authoritative).';
        container.appendChild(note);
        const body = document.createElement('div');
        container.appendChild(body);
        (async () => {
            try {
                const data = await DCodeApi.get('/api/v1/sessions/' + session.id + '/changes');
                body.innerHTML = '';
                for (const repository of data.repositories || []) {
                    const card = document.createElement('div');
                    card.className = 'card';
                    const title = document.createElement('h3');
                    title.textContent = repository.name + ' · branch ' + repository.branch
                        + (repository.error ? ' · error: ' + repository.error : '');
                    card.appendChild(title);
                    const files = document.createElement('div');
                    files.className = 'muted mono';
                    files.style.marginBottom = '6px';
                    files.textContent = (repository.changedFiles || []).join(', ')
                        || '(clean)';
                    card.appendChild(files);
                    card.appendChild(DCodeDiff.render(repository.diff || ''));
                    body.appendChild(card);
                }
            } catch (e) {
                DCodeApp.logConsole('error', 'changes: ' + e.message);
            }
        })();
        return container;
    }

    return { render, renderDetail, appendMessage, badge };
})();
