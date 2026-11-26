/**
 * DCodeChat — a prominent, intuitive "ask the model" chat surface.
 *
 * The point of this module is discoverability: the previous UI buried direct inference
 * inside the Runtimes view. This renders one clean, full-width chat tab with a large
 * prompt composer, message bubbles, streaming output and a sensible model default.
 *
 * It talks to the existing `/api/v1` surface (runtimes, models, inference/chat/stream).
 */
window.DCodeChat = (function () {

    // Native ENR models are protocol-test only (ctx 4/32) — never a sensible chat default.
    const PROTOCOL_TEST_MODELS = new Set(['evo', 'tiny-evo']);

    function el(tag, className, text) {
        const node = document.createElement(tag);
        if (className) { node.className = className; }
        if (text !== undefined) { node.textContent = text; }
        return node;
    }

    /** Builds the chat view; returns the root Node (the tab renders this). */
    function render() {
        const root = el('div', 'chat-view');

        // ---- header: title + runtime/model selectors ----
        const header = el('div', 'chat-header');
        const title = el('div', 'chat-title');
        title.appendChild(el('span', 'chat-title-icon', '💬'));
        const titleText = el('div', 'chat-title-text');
        titleText.appendChild(el('div', 'chat-title-main', 'Chat with EVO-Code'));
        titleText.appendChild(el('div', 'chat-title-sub',
            'Ask about code, request an implementation, or explain a design. '
            + 'Streaming response from the selected model.'));
        title.appendChild(titleText);
        header.appendChild(title);

        const selectors = el('div', 'chat-selectors');
        const runtimeSelect = el('select', 'chat-select');
        runtimeSelect.title = 'Inference runtime';
        const modelSelect = el('select', 'chat-select');
        modelSelect.title = 'Model';
        selectors.appendChild(labeled('Runtime', runtimeSelect));
        selectors.appendChild(labeled('Model', modelSelect));
        const modelHint = el('div', 'chat-model-hint');
        modelHint.hidden = true;
        selectors.appendChild(modelHint);
        header.appendChild(selectors);
        root.appendChild(header);

        // ---- transcript ----
        const transcript = el('div', 'chat-transcript');
        const welcome = el('div', 'chat-welcome');
        welcome.appendChild(el('div', 'chat-welcome-icon', '◈'));
        welcome.appendChild(el('div', 'chat-welcome-title', 'What would you like to build?'));
        welcome.appendChild(el('div', 'chat-welcome-sub',
            'Type a prompt below. Press Enter to send, Shift+Enter for a new line.'));
        const examples = el('div', 'chat-examples');
        ['Explain what this project does',
            'Write a Java method that reverses a string',
            'Suggest a unit test for Calculator.add',
            'Why might my build fail?'].forEach((text) => {
            const chip = el('button', 'chat-chip', text);
            chip.type = 'button';
            chip.onclick = () => {
                composer.value = text;
                composer.focus();
            };
            examples.appendChild(chip);
        });
        welcome.appendChild(examples);
        transcript.appendChild(welcome);
        root.appendChild(transcript);

        // ---- composer ----
        const composerWrap = el('div', 'chat-composer-wrap');
        const composer = el('textarea', 'chat-composer');
        composer.rows = 3;
        composer.placeholder = 'Ask EVO-Code anything — e.g. "write a Python function '
            + 'that parses ISO dates"…';
        composerWrap.appendChild(composer);
        const actions = el('div', 'chat-actions');
        const hint = el('span', 'chat-hint', 'Enter to send · Shift+Enter for newline');
        actions.appendChild(hint);
        const stopBtn = el('button', 'btn btn-danger chat-btn', 'Stop');
        stopBtn.disabled = true;
        const clearBtn = el('button', 'btn chat-btn', 'Clear');
        const sendBtn = el('button', 'btn btn-primary chat-btn chat-send', 'Send ➤');
        actions.appendChild(clearBtn);
        actions.appendChild(stopBtn);
        actions.appendChild(sendBtn);
        composerWrap.appendChild(actions);
        root.appendChild(composerWrap);

        // ---- state ----
        const history = [];
        let abort = null;
        let requestId = null;
        let streaming = false;

        function labeled(label, control) {
            const wrap = el('label', 'chat-select-wrap');
            wrap.appendChild(el('span', 'chat-select-label', label));
            wrap.appendChild(control);
            return wrap;
        }

        function clearWelcome() {
            if (welcome.parentElement) { welcome.remove(); }
        }

        function appendMessage(role, text) {
            clearWelcome();
            const row = el('div', 'chat-msg chat-msg-' + role);
            const avatar = el('div', 'chat-avatar', role === 'user' ? '🧑' : '◈');
            const bubble = el('div', 'chat-bubble');
            const head = el('div', 'chat-msg-head');
            head.appendChild(el('span', 'chat-msg-who', role === 'user' ? 'You' : 'EVO-Code'));
            const copy = el('button', 'chat-copy', 'copy');
            copy.type = 'button';
            copy.onclick = () => {
                const content = bubble.querySelector('.chat-bubble-body');
                DCodeMarkdown.copyToClipboard(content ? content.innerText : text)
                    .then(() => { copy.textContent = 'copied';
                        setTimeout(() => { copy.textContent = 'copy'; }, 1400); })
                    .catch(() => {});
            };
            head.appendChild(copy);
            bubble.appendChild(head);
            const body = el('div', 'chat-bubble-body');
            if (role === 'assistant') {
                body.innerHTML = DCodeMarkdown.renderMarkdown(text || '');
            } else {
                body.textContent = text;
            }
            bubble.appendChild(body);
            row.appendChild(avatar);
            row.appendChild(bubble);
            transcript.appendChild(row);
            transcript.scrollTop = transcript.scrollHeight;
            return body;
        }

        function setStreaming(value) {
            streaming = value;
            sendBtn.disabled = value;
            stopBtn.disabled = !value;
            composer.disabled = value;
        }

        function send() {
            const prompt = composer.value.trim();
            if (!prompt || streaming || !runtimeSelect.value || !modelSelect.value) {
                return;
            }
            composer.value = '';
            appendMessage('user', prompt);
            history.push({ role: 'user', content: prompt });
            const assistantBody = appendMessage('assistant', '');
            assistantBody.classList.add('chat-streaming');
            assistantBody.textContent = '…';

            setStreaming(true);
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
                        throw new Error((payload && payload.message)
                            || ('HTTP ' + response.status));
                    });
                }
                const reader = response.body.getReader();
                const decoder = new TextDecoder();
                let buffer = '';
                let finished = false;

                function pump() {
                    return reader.read().then(({ done, value }) => {
                        buffer += decoder.decode(value || new Uint8Array(),
                            { stream: !done });
                        let idx;
                        while ((idx = buffer.indexOf('\n\n')) >= 0) {
                            handleFrame(buffer.slice(0, idx));
                            buffer = buffer.slice(idx + 2);
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
                    if (!data) { return; }
                    let payload;
                    try { payload = JSON.parse(data); } catch (e) { return; }
                    if (event === 'start') {
                        requestId = payload.requestId;
                    } else if (event === 'done') {
                        finished = true;
                        assistantBody.innerHTML = DCodeMarkdown.renderMarkdown(full || '');
                        assistantBody.classList.remove('chat-streaming');
                        if (payload.cancelled) {
                            assistantBody.appendChild(
                                el('span', 'chat-note', ' [cancelled]'));
                        }
                        history.push({ role: 'assistant', content: payload.content || full });
                        endStream();
                    } else if (event === 'error') {
                        finished = true;
                        assistantBody.classList.remove('chat-streaming');
                        assistantBody.appendChild(
                            el('span', 'chat-error', ' ⚠ ' + (payload.message || 'failed')));
                        endStream();
                    } else if (typeof payload.token === 'string') {
                        full += payload.token;
                        assistantBody.innerHTML = DCodeMarkdown.renderMarkdown(full);
                        transcript.scrollTop = transcript.scrollHeight;
                    }
                }
                return pump();
            }).catch((e) => {
                assistantBody.classList.remove('chat-streaming');
                if (e.name !== 'AbortError') {
                    assistantBody.appendChild(el('span', 'chat-error', ' ⚠ ' + e.message));
                }
                endStream();
            });
        }

        function endStream() {
            setStreaming(false);
            abort = null;
            requestId = null;
            composer.focus();
        }

        stopBtn.onclick = () => {
            if (abort) { abort.abort(); }
            if (requestId) {
                DCodeApi.post('/api/v1/inference/cancel', { requestId })
                    .catch(() => {});
            }
            setStreaming(false);
        };
        clearBtn.onclick = () => {
            history.length = 0;
            transcript.innerHTML = '';
            transcript.appendChild(welcome);
        };
        sendBtn.onclick = send;
        composer.onkeydown = (e) => {
            if (e.key === 'Enter' && !e.shiftKey) {
                e.preventDefault();
                send();
            }
        };

        // ---- load runtimes/models, pick an intuitive default ----
        function pickDefaultRuntime(runtimes) {
            const enabled = runtimes.filter((r) => r.enabled);
            if (!enabled.length) { return null; }
            const byId = (id) => enabled.find((r) => r.id === id);
            // Prefer a real chat runtime over the native protocol-test runtime.
            return byId('enr-openai') || byId('ollama')
                || enabled.find((r) => r.id !== 'enr')
                || enabled.find((r) => r.default)
                || enabled[0];
        }

        const SELECTION_KEY = 'dcode.chat.selection.v1';

        function readSelection() {
            try {
                const raw = localStorage.getItem(SELECTION_KEY);
                return raw ? JSON.parse(raw) : null;
            } catch (e) { return null; }
        }

        function saveSelection() {
            try {
                localStorage.setItem(SELECTION_KEY, JSON.stringify({
                    runtimeId: runtimeSelect.value,
                    modelId: modelSelect.value
                }));
            } catch (e) { /* storage unavailable */ }
        }

        function showModelHint(text) {
            modelHint.textContent = text || '';
            modelHint.hidden = !text;
        }

        function loadModels(selectModelId, autoSelectDefault) {
            modelSelect.innerHTML = '';
            showModelHint('');
            if (!runtimeSelect.value) { saveSelection(); return Promise.resolve(); }
            return DCodeApi.get('/api/v1/runtimes/' + runtimeSelect.value + '/models')
                .then((models) => {
                    const usable = (models || []).filter((m) => m.available !== false);
                    const chatModels = usable.filter(
                        (m) => !PROTOCOL_TEST_MODELS.has(m.id));
                    const list = chatModels.length ? chatModels : usable;
                    let matched = false;
                    for (const model of list) {
                        const option = el('option', null, model.id);
                        option.value = model.id;
                        modelSelect.appendChild(option);
                        if (selectModelId && model.id === selectModelId) {
                            matched = true;
                        }
                    }
                    if (!list.length) {
                        const option = el('option', null, '(no models published)');
                        option.value = '';
                        modelSelect.appendChild(option);
                    } else if (matched) {
                        modelSelect.value = selectModelId;
                    } else if (autoSelectDefault) {
                        modelSelect.selectedIndex = 0;
                    } else {
                        // Runtime switched: never silently pick an arbitrary model.
                        const placeholder = el('option', null, '(select a model)');
                        placeholder.value = '';
                        modelSelect.insertBefore(placeholder, modelSelect.firstChild);
                        modelSelect.value = '';
                        showModelHint(selectModelId
                            ? 'Previous model "' + selectModelId
                                + '" is not available on this runtime — select a model.'
                            : 'Select a model for this runtime.');
                    }
                    saveSelection();
                })
                .catch((e) => {
                    DCodeApp.logConsole('error', e.message);
                    showModelHint('Could not load models for this runtime.');
                });
        }

        runtimeSelect.onchange = () => loadModels(modelSelect.value, false);
        modelSelect.onchange = saveSelection;

        DCodeApi.get('/api/v1/runtimes').then((runtimes) => {
            runtimeSelect.innerHTML = '';
            const enabled = runtimes.filter((r) => r.enabled);
            for (const runtime of enabled) {
                const option = el('option', null,
                    runtime.name + (runtime.default ? ' ★' : ''));
                option.value = runtime.id;
                runtimeSelect.appendChild(option);
            }
            const saved = readSelection();
            const savedRuntime = saved && saved.runtimeId
                ? enabled.find((r) => r.id === saved.runtimeId) : null;
            const preferred = savedRuntime || pickDefaultRuntime(runtimes);
            if (preferred) { runtimeSelect.value = preferred.id; }
            const restoreModel = (saved && savedRuntime
                && saved.runtimeId === preferred.id) ? saved.modelId : null;
            loadModels(restoreModel, true);
        }).catch((e) => DCodeApp.logConsole('error', e.message));

        setTimeout(() => composer.focus(), 50);
        return root;
    }

    return { render };
})();
