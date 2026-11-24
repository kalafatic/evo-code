/**
 * Runtime management, model explorer and direct inference chat for the Web IDE.
 * The backend is the single authority: registrations, endpoints, model metadata and
 * defaults all come from the shared API. Credentials never reach the browser — the
 * backend only ever reports masked markers.
 */
window.DCodeRuntimes = (function () {
    const TYPE_NAMES = { enr: 'ENR', ollama: 'Ollama', openai: 'OpenAI-compatible' };

    function typeLabel(type) {
        return TYPE_NAMES[type] || type;
    }

    // ---------- Runtime Explorer (left panel view) ----------

    function renderRuntimes(container) {
        container.innerHTML = '';
        const toolbar = document.createElement('div');
        toolbar.className = 'toolbar';
        const add = document.createElement('button');
        add.className = 'btn btn-primary';
        add.textContent = 'Add runtime';
        add.onclick = () => DCodeApp.openModal('runtime-edit', null);
        toolbar.appendChild(add);
        const scan = document.createElement('button');
        scan.className = 'btn';
        scan.textContent = 'Scan local…';
        scan.title = 'Find Ollama/ENR/EVO-Code services on this machine and register them';
        scan.onclick = () => DCodeApp.openModal('runtime-scan', null);
        toolbar.appendChild(scan);
        const refresh = document.createElement('button');
        refresh.className = 'btn';
        refresh.textContent = 'Refresh';
        refresh.onclick = () => renderRuntimes(container);
        toolbar.appendChild(refresh);
        const chat = document.createElement('button');
        chat.className = 'btn';
        chat.textContent = 'Direct chat';
        chat.onclick = () => openDirectChat();
        toolbar.appendChild(chat);
        container.appendChild(toolbar);

        const list = document.createElement('ul');
        list.className = 'list';
        container.appendChild(list);
        loadRuntimes(list);
    }

    async function loadRuntimes(list) {
        list.innerHTML = '';
        try {
            const runtimes = await DCodeApi.get('/api/v1/runtimes');
            if (!runtimes.length) {
                const empty = document.createElement('div');
                empty.className = 'empty-note';
                empty.style.padding = '10px';
                empty.textContent = 'No runtimes registered.';
                list.appendChild(empty);
                return;
            }
            for (const runtime of runtimes) {
                list.appendChild(runtimeRow(runtime, list));
            }
        } catch (e) {
            DCodeApp.logConsole('error', 'runtimes: ' + e.message);
        }
    }

    function runtimeRow(runtime, list) {
        const li = document.createElement('li');
        li.className = 'list-item';

        const title = document.createElement('div');
        title.className = 'item-title';
        title.innerHTML = (runtime.default ? '★ ' : '')
            + escapeHtml(runtime.name || runtime.id)
            + ' <span class="badge">' + typeLabel(runtime.type) + '</span>'
            + (runtime.enabled ? '' : ' <span class="badge badge-failed">disabled</span>');
        li.appendChild(title);

        const sub = document.createElement('div');
        sub.className = 'item-sub';
        const status = runtime.status || {};
        sub.innerHTML = escapeHtml(runtime.endpoint) + ' · '
            + (status.reachable ? '<span style="color:var(--green)">● reachable</span>'
                : '<span style="color:var(--red)">● untested/unreachable</span>')
            + (runtime.credentials && runtime.credentials.authToken ? ' · token ●' : '');
        li.appendChild(sub);

        const actions = document.createElement('div');
        actions.className = 'row';
        actions.style.padding = '4px 0';
        actions.appendChild(runtimeActionButton('Test', () => testRuntime(runtime, list)));
        actions.appendChild(runtimeActionButton(runtime.enabled ? 'Disable' : 'Enable',
            () => toggleRuntime(runtime, list)));
        actions.appendChild(runtimeActionButton('Edit', () => editRuntime(runtime)));
        actions.appendChild(runtimeActionButton('Default', () => setDefaultRuntime(runtime, list)));
        actions.appendChild(runtimeActionButton('Remove', () => removeRuntime(runtime, list), true));
        li.appendChild(actions);
        li.onclick = (event) => {
            if (event.target === actions) {
                return;
            }
            showRuntimeDetails(runtime);
        };
        // Context menu mirrors the RCP Runtime & Models view menu (same endpoints).
        li.oncontextmenu = (event) => {
            DCodeApp.showContextMenu(event, [
                { label: 'Test connection', action: () => testRuntime(runtime, list) },
                { label: 'Refresh models', action: () => loadRuntimes(list) },
                {
                    label: runtime.enabled ? 'Disable' : 'Enable',
                    action: () => toggleRuntime(runtime, list)
                },
                { label: 'Set as default', action: () => setDefaultRuntime(runtime, list) },
                { label: 'Edit…', action: () => editRuntime(runtime) },
                { label: 'Remove', danger: true, action: () => removeRuntime(runtime, list) }
            ]);
        };
        return li;
    }

    async function testRuntime(runtime, list) {
        try {
            const result = await DCodeApi.post('/api/v1/runtimes/'
                + runtime.id + '/test');
            DCodeApp.logConsole(result.success ? 'system' : 'error',
                'runtime ' + runtime.id + ' test: '
                + (result.success ? 'OK' : 'FAILED') + ' — ' + result.message);
            loadRuntimes(list);
        } catch (e) {
            DCodeApp.logConsole('error', e.message);
        }
    }

    async function toggleRuntime(runtime, list) {
        await DCodeApi.post('/api/v1/runtimes/' + runtime.id + '/'
            + (runtime.enabled ? 'disable' : 'enable'));
        loadRuntimes(list);
    }

    function editRuntime(runtime) {
        DCodeApp.openModal('runtime-edit', runtime);
    }

    async function setDefaultRuntime(runtime, list) {
        await DCodeApi.post('/api/v1/runtimes/' + runtime.id + '/default', {});
        DCodeApp.logConsole('system', runtime.id + ' is now the default runtime');
        loadRuntimes(list);
    }

    async function removeRuntime(runtime, list) {
        if (!confirm('Remove the runtime registration "' + runtime.id + '"?\n'
            + 'Models and data on the external runtime are untouched.')) {
            return;
        }
        await DCodeApi.del('/api/v1/runtimes/' + runtime.id);
        DCodeApp.logConsole('system', 'runtime registration removed: ' + runtime.id);
        loadRuntimes(list);
    }

    function runtimeActionButton(label, action, danger) {
        const btn = document.createElement('button');
        btn.className = 'btn btn-small' + (danger ? ' btn-danger' : '');
        btn.textContent = label;
        btn.onclick = (event) => {
            event.stopPropagation();
            action();
        };
        return btn;
    }

    async function showRuntimeDetails(runtime) {
        try {
            const details = await DCodeApi.get('/api/v1/runtimes/' + runtime.id);
            const container = document.createElement('div');
            container.className = 'flow';
            const title = document.createElement('div');
            title.className = 'section-title';
            title.textContent = 'Runtime — ' + details.name + ' (' + details.id + ')';
            container.appendChild(title);
            const card = document.createElement('div');
            card.className = 'card';
            const caps = details.capabilities || {};
            card.innerHTML = '<h3>' + escapeHtml(details.name) + '</h3>'
                + kv('id', details.id)
                + kv('type', typeLabel(details.type) + ' (' + details.type + ')')
                + kv('endpoint', details.endpoint)
                + kv('local', details.local ? 'yes' : 'no')
                + kv('enabled', details.enabled ? 'yes' : 'no')
                + kv('default', details.default ? 'yes' : 'no')
                + kv('streaming', caps.streaming ? 'yes' : 'no')
                + kv('cancellation', caps.cancellation ? 'yes' : 'no')
                + kv('model load/unload', (caps.modelLoad ? 'load ' : '')
                    + (caps.modelUnload ? 'unload ' : '') || 'not supported')
                + kv('model pull', caps.modelPull ? 'yes' : 'no')
                + kv('status', (details.status || {}).message || 'not tested');
            container.appendChild(card);
            const modelsTitle = document.createElement('div');
            modelsTitle.className = 'section-title';
            modelsTitle.textContent = 'Models';
            container.appendChild(modelsTitle);
            const modelsCard = document.createElement('div');
            modelsCard.className = 'card';
            container.appendChild(modelsCard);
            renderModelList(modelsCard, details.models || [], details.id, details.capabilities);
            DCodeApp.openTab('runtime:' + details.id, 'Runtime — ' + details.name,
                () => container);
        } catch (e) {
            DCodeApp.logConsole('error', 'runtime details: ' + e.message);
        }
    }

    function kv(key, value) {
        return '<div class="kv"><span class="k">' + key + '</span>'
            + '<span class="v">' + escapeHtml(String(value)) + '</span></div>';
    }

    // ---------- Model Explorer (left panel view) ----------

    function renderModels(container) {
        container.innerHTML = '';
        const toolbar = document.createElement('div');
        toolbar.className = 'toolbar';
        const search = document.createElement('input');
        search.type = 'text';
        search.id = 'model-search';
        search.placeholder = 'search models…';
        search.style.flex = '1';
        search.oninput = () => applyModelFilter();
        toolbar.appendChild(search);
        const refresh = document.createElement('button');
        refresh.className = 'btn';
        refresh.textContent = 'Refresh';
        refresh.onclick = () => renderModels(container);
        toolbar.appendChild(refresh);
        container.appendChild(toolbar);
        const list = document.createElement('ul');
        list.className = 'list';
        container.appendChild(list);
        loadModels(list);
    }

    async function loadModels(list) {
        list.innerHTML = '';
        try {
            const runtimes = await DCodeApi.get('/api/v1/runtimes');
            for (const runtime of runtimes) {
                if (!runtime.enabled) {
                    continue;
                }
                const group = document.createElement('li');
                group.className = 'list-item';
                const title = document.createElement('div');
                title.className = 'item-title';
                title.innerHTML = '<span class="tree-icon dir">▸</span> '
                    + escapeHtml(runtime.name)
                    + ' <span class="badge">' + typeLabel(runtime.type) + '</span>'
                    + (runtime.default ? ' ★' : '');
                group.appendChild(title);
                list.appendChild(group);
                try {
                    const models = await DCodeApi.get('/api/v1/runtimes/'
                        + runtime.id + '/models');
                    for (const model of models) {
                        list.appendChild(modelRow(runtime, model));
                    }
                } catch (e) {
                    DCodeApp.logConsole('error', runtime.id + ' models: ' + e.message);
                }
            }
            if (!list.children.length) {
                const empty = document.createElement('div');
                empty.className = 'empty-note';
                empty.style.padding = '10px';
                empty.textContent = 'No enabled runtimes with models.';
                list.appendChild(empty);
            }
            applyModelFilter();
        } catch (e) {
            DCodeApp.logConsole('error', 'models: ' + e.message);
        }
    }

    function modelRow(runtime, model) {
        const li = document.createElement('li');
        li.className = 'list-item model-row';
        li.dataset.model = (model.id || '').toLowerCase();
        li.style.paddingLeft = '26px';
        const title = document.createElement('div');
        title.className = 'item-title';
        title.innerHTML = escapeHtml(model.id) + (model.loaded ? ' <span class="badge badge-running">loaded</span>' : '')
            + (model.available ? '' : ' <span class="badge badge-failed">unavailable</span>');
        li.appendChild(title);
        const sub = document.createElement('div');
        sub.className = 'item-sub';
        sub.textContent = [
            model.contextWindowTokens ? 'ctx ' + model.contextWindowTokens : '',
            model.sizeBytes ? humanSize(model.sizeBytes) : '',
            model.quantization ? model.quantization : '',
            (model.capabilities || []).join(', ')
        ].filter(Boolean).join(' · ') || 'runtime: ' + runtime.id;
        li.appendChild(sub);
        const actions = document.createElement('div');
        actions.className = 'row';
        actions.style.padding = '3px 0';
        const caps = runtime.capabilities || {};
        if (caps.modelLoad) {
            actions.appendChild(modelActionButton('Load', runtime, model, 'load'));
        }
        if (caps.modelUnload) {
            actions.appendChild(modelActionButton('Unload', runtime, model, 'unload'));
        }
        if (caps.modelPull) {
            actions.appendChild(modelActionButton('Pull', runtime, model, 'pull'));
        }
        if (actions.children.length) {
            li.appendChild(actions);
        }
        return li;
    }

    function modelActionButton(label, runtime, model, action) {
        const btn = document.createElement('button');
        btn.className = 'btn btn-small';
        btn.textContent = label;
        btn.onclick = async (event) => {
            event.stopPropagation();
            try {
                await DCodeApi.post('/api/v1/runtimes/' + runtime.id + '/models/'
                    + encodeURIComponent(model.id) + '/' + action);
                DCodeApp.logConsole('system', action + ' ' + model.id
                    + ' on ' + runtime.id + ' done');
                renderModels(document.getElementById('left-content'));
            } catch (e) {
                DCodeApp.logConsole('error', action + ' failed: ' + e.message);
            }
        };
        return btn;
    }

    function applyModelFilter() {
        const query = (document.getElementById('model-search') || {}).value || '';
        const filter = query.trim().toLowerCase();
        document.querySelectorAll('.model-row').forEach((row) => {
            row.style.display = !filter || row.dataset.model.indexOf(filter) >= 0
                ? '' : 'none';
        });
    }

    function humanSize(bytes) {
        if (bytes > 1e9) {
            return (bytes / 1e9).toFixed(1) + ' GB';
        }
        if (bytes > 1e6) {
            return (bytes / 1e6).toFixed(1) + ' MB';
        }
        if (bytes > 1e3) {
            return (bytes / 1e3).toFixed(1) + ' KB';
        }
        return bytes + ' B';
    }

    function renderModelList(container, models, runtimeId, capabilities) {
        container.innerHTML = '';
        if (!models.length) {
            const note = document.createElement('div');
            note.className = 'empty-note';
            note.textContent = 'No models published.';
            container.appendChild(note);
            return;
        }
        for (const model of models) {
            const row = document.createElement('div');
            row.className = 'list-item';
            row.innerHTML = '<div class="item-title">' + escapeHtml(model.id)
                + (model.loaded ? ' <span class="badge badge-running">loaded</span>' : '')
                + '</div><div class="item-sub">'
                + [model.contextWindowTokens ? 'ctx ' + model.contextWindowTokens : '',
                    model.sizeBytes ? humanSize(model.sizeBytes) : '',
                    model.quantization || ''].filter(Boolean).join(' · ')
                + '</div>';
            if (capabilities.modelLoad || capabilities.modelUnload) {
                const actions = document.createElement('div');
                actions.className = 'row';
                if (capabilities.modelLoad) {
                    const load = document.createElement('button');
                    load.className = 'btn btn-small';
                    load.textContent = 'Load';
                    load.onclick = async () => {
                        await DCodeApi.post('/api/v1/runtimes/' + runtimeId
                            + '/models/' + encodeURIComponent(model.id) + '/load');
                        showRuntimeDetails((await DCodeApi.get('/api/v1/runtimes/'
                            + runtimeId)));
                    };
                    actions.appendChild(load);
                }
                if (capabilities.modelUnload) {
                    const unload = document.createElement('button');
                    unload.className = 'btn btn-small';
                    unload.textContent = 'Unload';
                    unload.onclick = async () => {
                        await DCodeApi.post('/api/v1/runtimes/' + runtimeId
                            + '/models/' + encodeURIComponent(model.id) + '/unload');
                        showRuntimeDetails((await DCodeApi.get('/api/v1/runtimes/'
                            + runtimeId)));
                    };
                    actions.appendChild(unload);
                }
                row.appendChild(actions);
            }
            container.appendChild(row);
        }
    }

    // ---------- Direct Inference Chat (center tab) ----------

    function openDirectChat() {
        DCodeApp.openTab('direct-chat', 'Direct Inference Chat', () => renderDirectChat());
    }

    function renderDirectChat() {
        const container = document.createElement('div');
        container.className = 'direct-chat';

        const selectors = document.createElement('div');
        selectors.className = 'row';
        selectors.style.padding = '8px 10px';
        const runtimeLabel = document.createElement('span');
        runtimeLabel.className = 'muted';
        runtimeLabel.textContent = 'Runtime';
        selectors.appendChild(runtimeLabel);
        const runtimeSelect = document.createElement('select');
        runtimeSelect.style.maxWidth = '260px';
        selectors.appendChild(runtimeSelect);
        const modelLabel = document.createElement('span');
        modelLabel.className = 'muted';
        modelLabel.textContent = 'Model';
        selectors.appendChild(modelLabel);
        const modelSelect = document.createElement('select');
        modelSelect.style.maxWidth = '320px';
        selectors.appendChild(modelSelect);
        container.appendChild(selectors);

        const transcript = document.createElement('div');
        transcript.className = 'direct-chat-transcript';
        container.appendChild(transcript);
        DCodeMarkdown.wireCodeCopy(transcript);

        const inputRow = document.createElement('div');
        inputRow.className = 'row';
        inputRow.style.padding = '8px 10px';
        const input = document.createElement('textarea');
        input.rows = 3;
        input.placeholder = 'Prompt the selected model directly (no coding task)…';
        input.style.flex = '1';
        inputRow.appendChild(input);
        const send = document.createElement('button');
        send.className = 'btn btn-primary';
        send.textContent = 'Send';
        inputRow.appendChild(send);
        const cancel = document.createElement('button');
        cancel.className = 'btn btn-danger';
        cancel.textContent = 'Cancel generation';
        cancel.disabled = true;
        inputRow.appendChild(cancel);
        const clear = document.createElement('button');
        clear.className = 'btn';
        clear.textContent = 'Clear';
        inputRow.appendChild(clear);
        container.appendChild(inputRow);

        const history = [];
        let abort = null;
        let requestId = null;

        function loadRuntimes() {
            DCodeApi.get('/api/v1/runtimes').then((runtimes) => {
                runtimeSelect.innerHTML = '';
                for (const runtime of runtimes) {
                    if (!runtime.enabled) {
                        continue;
                    }
                    const option = document.createElement('option');
                    option.value = runtime.id;
                    option.textContent = runtime.name + (runtime.default ? ' ★' : '');
                    runtimeSelect.appendChild(option);
                }
                if (!runtimeSelect.options.length) {
                    const option = document.createElement('option');
                    option.value = '';
                    option.textContent = '(no enabled runtimes)';
                    runtimeSelect.appendChild(option);
                }
                loadModels();
            }).catch((e) => DCodeApp.logConsole('error', e.message));
        }

        function loadModels() {
            modelSelect.innerHTML = '';
            if (!runtimeSelect.value) {
                return;
            }
            DCodeApi.get('/api/v1/runtimes/' + runtimeSelect.value + '/models')
                .then((models) => {
                    for (const model of models) {
                        const option = document.createElement('option');
                        option.value = model.id;
                        option.textContent = model.id;
                        modelSelect.appendChild(option);
                    }
                    if (!modelSelect.options.length) {
                        const option = document.createElement('option');
                        option.value = '';
                        option.textContent = '(no models published)';
                        modelSelect.appendChild(option);
                    }
                }).catch((e) => DCodeApp.logConsole('error', e.message));
        }

        runtimeSelect.onchange = loadModels;

        function append(role, text, extraClass) {
            const line = document.createElement('div');
            line.className = 'log-line log-' + role + (extraClass ? ' ' + extraClass : '');
            if (role === 'assistant' && text.trim()) {
                const head = document.createElement('div');
                head.className = 'msg-head';
                const who = document.createElement('span');
                who.className = 'who';
                who.textContent = role;
                head.appendChild(who);
                const copy = document.createElement('button');
                copy.className = 'msg-action';
                copy.textContent = 'copy';
                copy.onclick = () => {
                    DCodeMarkdown.copyToClipboard(String(text)).then(() => {
                        copy.textContent = 'copied';
                        setTimeout(() => { copy.textContent = 'copy'; }, 1400);
                    }).catch(() => { /* clipboard unavailable */ });
                };
                head.appendChild(copy);
                line.appendChild(head);
                const body = document.createElement('div');
                body.className = 'chat-body';
                body.innerHTML = DCodeMarkdown.renderMarkdown(text);
                line.appendChild(body);
            } else {
                line.appendChild(document.createTextNode(text));
            }
            transcript.appendChild(line);
            transcript.scrollTop = transcript.scrollHeight;
        }

        function sendPrompt() {
            const prompt = input.value.trim();
            if (!prompt || !runtimeSelect.value || !modelSelect.value) {
                return;
            }
            input.value = '';
            append('user', '\n▶ ' + prompt);
            history.push({ role: 'user', content: prompt });
            const assistantLine = document.createElement('div');
            assistantLine.className = 'log-line log-assistant';
            const assistantHead = document.createElement('div');
            assistantHead.className = 'msg-head';
            const assistantWho = document.createElement('span');
            assistantWho.className = 'who';
            assistantWho.textContent = 'assistant';
            const assistantCopy = document.createElement('button');
            assistantCopy.className = 'msg-action';
            assistantCopy.textContent = 'copy';
            assistantCopy.onclick = () => {
                DCodeMarkdown.copyToClipboard(full).then(() => {
                    assistantCopy.textContent = 'copied';
                    setTimeout(() => { assistantCopy.textContent = 'copy'; }, 1400);
                }).catch(() => { /* clipboard unavailable */ });
            };
            assistantHead.appendChild(assistantWho);
            assistantHead.appendChild(assistantCopy);
            assistantLine.appendChild(assistantHead);
            const assistantBody = document.createElement('div');
            assistantBody.className = 'chat-body direct-chat-stream';
            assistantLine.appendChild(assistantBody);
            transcript.appendChild(assistantLine);
            transcript.scrollTop = transcript.scrollHeight;

            send.disabled = true;
            cancel.disabled = false;
            abort = new AbortController();
            requestId = null;
            let full = '';

            fetch(DCodeApi.base + '/api/v1/inference/chat/stream', {
                method: 'POST',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify({
                    runtimeId: runtimeSelect.value,
                    model: modelSelect.value,
                    messages: history.slice()
                }),
                signal: abort.signal
            }).then((response) => {
                if (!response.ok) {
                    return response.json().then((payload) => {
                        throw new Error((payload && payload.message) || 'HTTP ' + response.status);
                    });
                }
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';
                let finished = false;
                function pump() {
                    return reader.read().then(({ done, value }) => {
                        buffer += decoder.decode(value || new Uint8Array(), { stream: !done });
                        let idx;
                        while ((idx = buffer.indexOf('\n\n')) >= 0) {
                            const frame = buffer.slice(0, idx);
                            buffer = buffer.slice(idx + 2);
                            handleFrame(frame);
                        }
                        if (!done && !finished) {
                            return pump();
                        }
                    });
                }
                function handleFrame(frame) {
                    let event = '';
                    let data = '';
                    for (const line of frame.split('\n')) {
                        if (line.startsWith('event:')) {
                            event = line.slice(6).trim();
                        } else if (line.startsWith('data:')) {
                            data += (data ? '\n' : '') + line.slice(5).trim();
                        }
                    }
                    if (!data) {
                        return;
                    }
                    let payload;
                    try {
                        payload = JSON.parse(data);
                    } catch (e) {
                        return;
                    }
                    if (event === 'start') {
                        requestId = payload.requestId;
                    } else if (event === 'done') {
                        finished = true;
                        history.push({ role: 'assistant', content: payload.content || '' });
                        assistantBody.innerHTML = DCodeMarkdown.renderMarkdown(full);
                        if (payload.cancelled) {
                            assistantBody.appendChild(document.createTextNode(
                                ' [cancelled]'));
                        }
                        endStream();
                    } else if (event === 'error') {
                        finished = true;
                        assistantBody.appendChild(document.createTextNode(
                            ' ⚠ ' + (payload.message || 'failed')));
                        endStream();
                    } else if (typeof payload.token === 'string') {
                        full += payload.token;
                        assistantBody.innerHTML = DCodeMarkdown.renderMarkdown(full);
                        transcript.scrollTop = transcript.scrollHeight;
                    }
                }
                return pump();
            }).catch((e) => {
                if (e.name !== 'AbortError') {
                    assistantBody.appendChild(document.createTextNode(' ⚠ ' + e.message));
                }
                endStream();
            });
        }

        function endStream() {
            send.disabled = false;
            cancel.disabled = true;
            abort = null;
        }

        send.onclick = sendPrompt;
        input.onkeydown = (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                sendPrompt();
            }
        };
        cancel.onclick = () => {
            if (abort) {
                abort.abort();
            }
            if (requestId) {
                DCodeApi.post('/api/v1/inference/cancel', { requestId })
                    .catch(() => {});
            }
            endStream();
            DCodeApp.logConsole('system', 'generation cancelled');
        };
        clear.onclick = () => {
            history.length = 0;
            transcript.innerHTML = '';
        };

        loadRuntimes();
        return container;
    }

    // ---------- runtime add/edit modal ----------

    function buildRuntimeModal(modal, overlay, existing) {
        modalTitle(modal, existing ? 'Edit runtime — ' + existing.id : 'Add runtime');
        const body = document.createElement('div');
        body.className = 'modal-body';
        modal.appendChild(body);

        const typeField = document.createElement('div');
        typeField.className = 'field';
        typeField.innerHTML = '<label>Runtime type</label>';
        const typeSelect = document.createElement('select');
        for (const [wire, label] of Object.entries(TYPE_NAMES)) {
            const option = document.createElement('option');
            option.value = wire;
            option.textContent = label;
            typeSelect.appendChild(option);
        }
        typeField.appendChild(typeSelect);
        body.appendChild(typeField);

        const endpointField = document.createElement('div');
        endpointField.className = 'field';
        endpointField.innerHTML = '<label>Endpoint / base URL</label>';
        const endpointInput = document.createElement('input');
        endpointInput.type = 'text';
        endpointInput.placeholder = 'http://127.0.0.1:28780';
        endpointField.appendChild(endpointInput);
        body.appendChild(endpointField);

        const nameField = document.createElement('div');
        nameField.className = 'field';
        nameField.innerHTML = '<label>Display name</label>';
        const nameInput = document.createElement('input');
        nameInput.type = 'text';
        nameField.appendChild(nameInput);
        body.appendChild(nameField);

        const tokenField = document.createElement('div');
        tokenField.className = 'field';
        tokenField.innerHTML = '<label>Bearer token (optional, stored in the backend vault)</label>';
        const tokenInput = document.createElement('input');
        tokenInput.type = 'password';
        tokenInput.placeholder = existing ? '(unchanged)' : 'token';
        tokenField.appendChild(tokenInput);
        body.appendChild(tokenField);

        const keyField = document.createElement('div');
        keyField.className = 'field';
        keyField.innerHTML = '<label>API key (optional, stored in the backend vault)</label>';
        const keyInput = document.createElement('input');
        keyInput.type = 'password';
        keyInput.placeholder = existing ? '(unchanged)' : 'key';
        keyField.appendChild(keyInput);
        body.appendChild(keyField);

        const result = document.createElement('div');
        result.className = 'muted';
        result.style.marginBottom = '8px';
        body.appendChild(result);
        const modelsList = document.createElement('div');
        modelsList.className = 'card';
        modelsList.style.maxHeight = '160px';
        modelsList.style.overflow = 'auto';
        body.appendChild(modelsList);

        const actions = document.createElement('div');
        actions.className = 'modal-actions';
        modal.appendChild(actions);
        const testBtn = document.createElement('button');
        testBtn.className = 'btn';
        testBtn.textContent = 'Test connection';
        actions.appendChild(testBtn);
        const discoverBtn = document.createElement('button');
        discoverBtn.className = 'btn';
        discoverBtn.textContent = 'Discover models';
        actions.appendChild(discoverBtn);
        const spacer = document.createElement('span');
        spacer.className = 'spacer';
        spacer.style.flex = '1';
        actions.appendChild(spacer);
        const saveBtn = document.createElement('button');
        saveBtn.className = 'btn btn-primary';
        saveBtn.textContent = existing ? 'Save' : 'Save registration';
        actions.appendChild(saveBtn);

        if (existing) {
            typeSelect.value = existing.type;
            typeSelect.disabled = true;
            endpointInput.value = existing.endpoint || '';
            nameInput.value = existing.name || '';
        }

        async function probe() {
            result.textContent = 'testing…';
            modelsList.innerHTML = '';
            try {
                let payload;
                if (existing) {
                    payload = await DCodeApi.post('/api/v1/runtimes/' + existing.id + '/test');
                } else {
                    payload = await DCodeApi.post('/api/v1/runtimes/preflight', {
                        type: typeSelect.value,
                        endpoint: endpointInput.value.trim(),
                        authToken: tokenInput.value || null,
                        apiKey: keyInput.value || null
                    });
                }
                result.textContent = (payload.success ? 'OK' : 'FAILED') + ' — ' + payload.message;
                for (const model of payload.models || []) {
                    const row = document.createElement('div');
                    row.className = 'list-item';
                    row.innerHTML = '<div class="item-title">' + escapeHtml(model.id)
                        + '</div><div class="item-sub">'
                        + [model.contextWindowTokens ? 'ctx ' + model.contextWindowTokens : '',
                            model.quantization || ''].filter(Boolean).join(' · ') + '</div>';
                    modelsList.appendChild(row);
                }
            } catch (e) {
                result.textContent = 'FAILED — ' + e.message;
            }
        }

        testBtn.onclick = probe;
        discoverBtn.onclick = probe;

        saveBtn.onclick = async () => {
            try {
                if (existing) {
                    await DCodeApi.post('/api/v1/runtimes/' + existing.id, {
                        name: nameInput.value || existing.name,
                        endpoint: endpointInput.value.trim(),
                        authToken: tokenInput.value || undefined,
                        apiKey: keyInput.value || undefined
                    });
                    DCodeApp.logConsole('system', 'runtime updated: ' + existing.id);
                } else {
                    const created = await DCodeApi.post('/api/v1/runtimes', {
                        type: typeSelect.value,
                        endpoint: endpointInput.value.trim(),
                        name: nameInput.value || null,
                        authToken: tokenInput.value || undefined,
                        apiKey: keyInput.value || undefined
                    });
                    DCodeApp.logConsole('system', 'runtime registered: ' + created.id);
                }
                overlay.remove();
                DCodeApp.renderRuntimesNow();
            } catch (e) {
                result.textContent = 'FAILED — ' + e.message;
            }
        };
    }

    function modalTitle(modal, text) {
        const title = document.createElement('div');
        title.className = 'modal-title';
        title.innerHTML = '<span>' + escapeHtml(text) + '</span>'
            + '<span class="close" onclick="this.closest(\'.overlay\').remove()">✕</span>';
        modal.appendChild(title);
    }

    function escapeHtml(text) {
        return String(text == null ? '' : text)
            .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
            .replace(/"/g, '&quot;');
    }

    // ---------- helpers for session/task modals ----------

    /** Fills a select with enabled runtimes (used by the session/task dialogs). */
    async function populateRuntimeSelect(select, includeDefaultOption) {
        if (includeDefaultOption) {
            const option = document.createElement('option');
            option.value = '';
            option.textContent = '(platform default)';
            select.appendChild(option);
        }
        const runtimes = await DCodeApi.get('/api/v1/runtimes');
        for (const runtime of runtimes) {
            if (!runtime.enabled) {
                continue;
            }
            const option = document.createElement('option');
            option.value = runtime.id;
            option.textContent = runtime.name + (runtime.default ? ' ★' : '');
            select.appendChild(option);
        }
    }

    /** Fills a model select for a runtime id (used by the session/task dialogs). */
    async function populateModelSelect(select, runtimeId, includeDefaultOption) {
        select.innerHTML = '';
        if (includeDefaultOption) {
            const option = document.createElement('option');
            option.value = '';
            option.textContent = '(runtime default)';
            select.appendChild(option);
        }
        if (!runtimeId) {
            return;
        }
        const models = await DCodeApi.get('/api/v1/runtimes/' + runtimeId + '/models');
        for (const model of models) {
            const option = document.createElement('option');
            option.value = model.id;
            option.textContent = model.id;
            select.appendChild(option);
        }
    }

    // ---------- local runtime scan modal ----------

    function buildScanModal(modal, overlay) {
        modalTitle(modal, 'Scan local runtimes');
        const body = document.createElement('div');
        body.className = 'modal-body';
        modal.appendChild(body);
        const status = document.createElement('div');
        status.className = 'muted';
        status.textContent = 'Probing local endpoints (Ollama 11434, ENR 8111/28780, '
            + 'EVO-Code 48080)…';
        body.appendChild(status);
        const list = document.createElement('div');
        body.appendChild(list);
        const actions = document.createElement('div');
        actions.className = 'modal-actions';
        modal.appendChild(actions);
        const rescan = document.createElement('button');
        rescan.className = 'btn';
        rescan.textContent = 'Scan again';
        actions.appendChild(rescan);
        const close = document.createElement('button');
        close.className = 'btn';
        close.textContent = 'Close';
        close.onclick = () => overlay.remove();
        actions.appendChild(close);
        rescan.onclick = load;
        load();

        async function load() {
            status.textContent = 'Probing local endpoints…';
            list.innerHTML = '';
            try {
                const findings = await DCodeApi.post('/api/v1/runtimes/scan', {});
                let reachable = 0;
                let addable = 0;
                for (const finding of findings) {
                    if (finding.reachable) {
                        reachable++;
                    }
                    const isRuntime = !!finding.type;
                    if (!isRuntime || finding.registered) {
                        continue;
                    }
                    addable++;
                    const row = document.createElement('div');
                    row.className = 'list-item';
                    row.innerHTML = '<div class="item-title">' + escapeHtml(finding.name)
                        + ' <span class="badge">' + escapeHtml(finding.kind) + '</span></div>'
                        + '<div class="item-sub">' + escapeHtml(finding.endpoint) + ' · '
                        + escapeHtml(finding.description) + ' · ' + finding.modelCount
                        + ' model(s)'
                        + (finding.sampleModels && finding.sampleModels.length
                            ? ' · e.g. ' + escapeHtml(finding.sampleModels.slice(0, 3).join(', '))
                            : '') + '</div>';
                    const add = document.createElement('button');
                    add.className = 'btn btn-small btn-primary';
                    add.textContent = 'Register';
                    add.onclick = async () => {
                        try {
                            await DCodeApi.post('/api/v1/runtimes', {
                                type: finding.type,
                                endpoint: finding.endpoint,
                                name: finding.name
                            });
                            DCodeApp.logConsole('success', 'runtime registered: '
                                + finding.endpoint);
                            load();
                            DCodeApp.renderRuntimesNow();
                        } catch (e) {
                            DCodeApp.reportError('register runtime', e);
                        }
                    };
                    row.appendChild(add);
                    list.appendChild(row);
                }
                if (!addable) {
                    const note = document.createElement('div');
                    note.className = 'empty-note';
                    note.style.padding = '8px 0';
                    note.textContent = 'No new runtimes found. All detected services '
                        + 'are already registered or unrecognized.';
                    list.appendChild(note);
                }
                status.textContent = reachable + ' endpoint(s) reachable · ' + addable
                    + ' runtime(s) ready to register';
            } catch (e) {
                status.textContent = 'Scan failed: ' + e.message;
                DCodeApp.reportError('scan local runtimes', e);
            }
        }
    }

    return {
        renderRuntimes,
        renderModels,
        openDirectChat,
        buildRuntimeModal,
        buildScanModal,
        populateRuntimeSelect,
        populateModelSelect
    };
})();
