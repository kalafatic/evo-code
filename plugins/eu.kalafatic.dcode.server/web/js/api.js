/**
 * DCode API client. Single source of truth for backend communication.
 * Base URL derives from the current origin — no hard-coded host or port.
 */
window.DCodeApi = (function () {
    const base = location.origin;
    // Frontend revision — visible in the UI, logged at boot and asserted by the
    // backend `/api/v1/health` frontendVersion field (FrontendDeliveryTest).
    window.DCODE_FRONTEND_BUILD = 'web-20261127-r7';
    // Shared with settings.html so one sign-in covers both pages of the same
    // browser (mirrors the DStudio web client's dstudio.web.token behavior).
    const TOKEN_KEY = 'dcode.web.token';
    let authToken = null;

    function loadToken() {
        try {
            authToken = localStorage.getItem(TOKEN_KEY) || null;
        } catch (e) {
            authToken = null;
        }
    }

    function setToken(token) {
        authToken = token || null;
        try {
            if (authToken) {
                localStorage.setItem(TOKEN_KEY, authToken);
            } else {
                localStorage.removeItem(TOKEN_KEY);
            }
        } catch (e) { /* storage unavailable */ }
    }

    function hasToken() {
        return authToken !== null;
    }

    async function request(method, path, body) {
        const headers = { 'Content-Type': 'application/json' };
        if (authToken) {
            headers['Authorization'] = 'Bearer ' + authToken;
        }
        const options = { method, headers };
        if (body !== undefined && body !== null) {
            options.body = JSON.stringify(body);
        }
        const response = await fetch(base + path, options);
        let payload = null;
        const text = await response.text();
        if (text) {
            try {
                payload = JSON.parse(text);
            } catch (e) {
                payload = { code: 'PARSE_ERROR', message: text.slice(0, 300) };
            }
        }
        if (response.status === 401) {
            const error = new Error('Authentication required');
            error.code = 'AUTHENTICATION_FAILED';
            error.status = 401;
            error.payload = payload;
            throw error;
        }
        if (!response.ok) {
            const error = new Error((payload && payload.message) || ('HTTP ' + response.status));
            error.code = payload && payload.code ? payload.code : 'HTTP_ERROR';
            error.status = response.status;
            error.payload = payload;
            throw error;
        }
        return payload;
    }

    loadToken();

    return {
        get: (path) => request('GET', path),
        post: (path, body) => request('POST', path, body || {}),
        del: (path) => request('DELETE', path),
        setToken,
        hasToken,
        base
    };
})();
