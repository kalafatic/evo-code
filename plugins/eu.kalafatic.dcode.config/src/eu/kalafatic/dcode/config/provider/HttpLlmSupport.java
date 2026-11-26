package eu.kalafatic.dcode.config.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.config.runtime.StreamHandle;
import eu.kalafatic.dcode.config.runtime.StreamListener;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Shared HTTP plumbing for LLM providers: JDK HttpClient, bearer token injection and
 * Jackson JSON handling. Provider implementations keep their wire shapes here, not in the
 * harness. Streaming (SSE) support includes cancellation: closing the response stream
 * aborts the in-flight HTTP request.
 */
public final class HttpLlmSupport {

    private final HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Supplier<String> apiKeySupplier;
    private final Duration defaultTimeout;

    public HttpLlmSupport(Supplier<String> apiKeySupplier, Duration defaultTimeout) {
        this.apiKeySupplier = apiKeySupplier;
        this.defaultTimeout = defaultTimeout;
        this.client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    public JsonNode postJson(String url, String requestJson, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout != null ? timeout : defaultTimeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson));
        applyAuth(builder);
        return send(builder.build());
    }

    public JsonNode getJson(String url, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout != null ? timeout : defaultTimeout)
                .GET();
        applyAuth(builder);
        return send(builder.build());
    }

    public String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new ProviderException("Failed to serialize request", e);
        }
    }

    /**
     * Opens a streaming (SSE) POST request. The returned stream must be closed by the
     * caller; closing it aborts the in-flight request and unblocks {@link SseStream#next()}.
     */
    public SseStream openSse(String url, String requestJson, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(timeout != null ? timeout : defaultTimeout)
                .header("Content-Type", "application/json")
                .header("Accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson));
        applyAuth(builder);
        HttpResponse<InputStream> response;
        try {
            response = client.send(builder.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (IOException e) {
            throw new ProviderException(describeIoFailure(url, e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Provider request interrupted");
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = readLimited(response.body());
            try {
                response.body().close();
            } catch (IOException ignored) {
                // already consumed
            }
            throw new ProviderException("Provider returned HTTP " + status
                    + (body.isBlank() ? "" : ": " + body));
        }
        return new SseStream(response.body());
    }

    /**
     * Runs a streaming request on a background thread, pumping SSE events into the given
     * handler. The returned handle cancels the generation by aborting the HTTP stream.
     */
    public StreamHandle streamSse(String url, String requestJson, Duration timeout,
                                  SseHandler handler) {
        Objects.requireNonNull(handler, "handler");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<SseStream> streamRef = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                SseStream stream = openSse(url, requestJson, timeout);
                streamRef.set(stream);
                SseEvent event;
                while (!cancelled.get() && (event = stream.next()) != null) {
                    handler.onEvent(event);
                }
                if (cancelled.get()) {
                    handler.onCancelled();
                } else {
                    handler.onFinished();
                }
            } catch (ProviderException e) {
                handler.onFailure(e);
            } catch (Exception e) {
                if (cancelled.get()) {
                    handler.onCancelled();
                } else {
                    handler.onFailure(new ProviderException(
                            "Provider stream failed: " + e.getMessage(), e));
                }
            }
        }, "dcode-provider-stream");
        thread.setDaemon(true);
        thread.start();
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                SseStream stream = streamRef.get();
                if (stream != null) {
                    stream.close();
                }
            }
        };
    }

    /**
     * Runs a streaming request on a background thread, pumping raw response lines
     * (NDJSON-style protocols such as Ollama) into the given handler. The returned
     * handle cancels the generation by aborting the HTTP stream.
     */
    public StreamHandle streamLines(String url, String requestJson, Duration timeout,
                                    LineHandler handler) {
        Objects.requireNonNull(handler, "handler");
        AtomicBoolean cancelled = new AtomicBoolean(false);
        AtomicReference<SseStream> streamRef = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try {
                SseStream stream = openSse(url, requestJson, timeout);
                streamRef.set(stream);
                String line;
                while (!cancelled.get() && (line = stream.nextLine()) != null) {
                    if (!line.isBlank()) {
                        handler.onLine(line);
                    }
                }
                if (cancelled.get()) {
                    handler.onCancelled();
                } else {
                    handler.onFinished();
                }
            } catch (ProviderException e) {
                handler.onFailure(e);
            } catch (Exception e) {
                if (cancelled.get()) {
                    handler.onCancelled();
                } else {
                    handler.onFailure(new ProviderException(
                            "Provider stream failed: " + e.getMessage(), e));
                }
            }
        }, "dcode-provider-lines");
        thread.setDaemon(true);
        thread.start();
        return () -> {
            if (cancelled.compareAndSet(false, true)) {
                SseStream stream = streamRef.get();
                if (stream != null) {
                    stream.close();
                }
            }
        };
    }

    /** Handler of one raw line stream. */
    public interface LineHandler {
        void onLine(String line);

        void onFinished();

        void onCancelled();

        void onFailure(ProviderException error);
    }

    /** Handler of one streaming request. */
    public interface SseHandler {
        void onEvent(SseEvent event);

        void onFinished();

        void onCancelled();

        void onFailure(ProviderException error);
    }

    /** One parsed SSE frame. */
    public record SseEvent(String event, String data) {
        public SseEvent {
            event = event == null ? "" : event;
            data = data == null ? "" : data;
        }
    }

    /**
     * Reader over an SSE response body. {@link #next()} blocks until the next frame
     * arrives; {@link #close()} aborts the underlying HTTP request.
     */
    public static final class SseStream implements AutoCloseable {

        private final InputStream body;
        private final BufferedReader reader;

        private SseStream(InputStream body) {
            this.body = body;
            this.reader = new BufferedReader(new InputStreamReader(body,
                    StandardCharsets.UTF_8));
        }

        /** Returns the next SSE frame, or null at end of stream. */
        public SseEvent next() throws IOException {
            String event = "";
            StringBuilder data = new StringBuilder();
            boolean sawAnything = false;
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.isBlank()) {
                    if (sawAnything) {
                        break;
                    }
                    continue;
                }
                sawAnything = true;
                if (line.startsWith(":")) {
                    continue; // comment / keep-alive
                }
                int colon = line.indexOf(':');
                String field = colon < 0 ? line : line.substring(0, colon);
                String value = colon < 0 ? "" : line.substring(colon + 1);
                if (value.startsWith(" ")) {
                    value = value.substring(1);
                }
                switch (field) {
                    case "event" -> event = value;
                    case "data" -> {
                        if (!data.isEmpty()) {
                            data.append('\n');
                        }
                        data.append(value);
                    }
                    default -> {
                        // ignore other fields (id, retry)
                    }
                }
            }
            return sawAnything ? new SseEvent(event, data.toString()) : null;
        }

        /**
         * Returns the next raw line, or null at end of stream. Used for NDJSON
         * protocols without SSE framing (e.g. Ollama).
         */
        public String nextLine() throws IOException {
            return reader.readLine();
        }

        @Override
        public void close() {
            try {
                body.close();
            } catch (IOException ignored) {
                // aborting the stream is best-effort
            }
        }
    }

    private void applyAuth(HttpRequest.Builder builder) {
        if (apiKeySupplier != null) {
            String key = apiKeySupplier.get();
            if (key != null && !key.isBlank()) {
                builder.header("Authorization", "Bearer " + key);
            }
        }
    }

    private String readLimited(InputStream body) {
        try {
            String text = new String(body.readNBytes(300), StandardCharsets.UTF_8);
            return text == null ? "" : text;
        } catch (IOException e) {
            return "";
        }
    }

    /**
     * Turns an I/O failure into an actionable diagnostic (connection refused / timeout /
     * unknown host / TLS / other), including the endpoint. The UI surfaces this message
     * directly, so it must say what failed and what to check.
     */
    private static String describeIoFailure(String url, IOException e) {
        String reason;
        if (e instanceof java.net.ConnectException) {
            reason = "connection refused — the runtime/provider is not running or the "
                    + "endpoint is wrong";
        } else if (e instanceof java.net.http.HttpConnectTimeoutException) {
            reason = "connection timed out — the runtime/provider is unreachable";
        } else if (e instanceof java.net.UnknownHostException) {
            reason = "unknown host — check the endpoint hostname";
        } else if (e instanceof java.net.SocketTimeoutException) {
            reason = "request timed out — the model may still be loading or overloaded";
        } else if (e instanceof javax.net.ssl.SSLException) {
            reason = "TLS/SSL failure — check the endpoint scheme and certificate";
        } else {
            reason = e.getMessage() != null && !e.getMessage().isBlank()
                    ? e.getMessage() : e.getClass().getSimpleName();
        }
        return "Provider request failed at " + url + " (" + reason + ")";
    }

    private JsonNode send(HttpRequest request) {
        HttpResponse<String> response;
        try {
            response = client.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderException(describeIoFailure(request.uri().toString(), e));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("Provider request interrupted");
        }
        int status = response.statusCode();
        if (status < 200 || status >= 300) {
            String body = response.body();
            if (body != null && body.length() > 300) {
                body = body.substring(0, 300);
            }
            throw new ProviderException("Provider returned HTTP " + status
                    + (body == null || body.isBlank() ? "" : ": " + body));
        }
        String body = response.body();
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new ProviderException("Failed to parse provider response", e);
        }
    }
}
