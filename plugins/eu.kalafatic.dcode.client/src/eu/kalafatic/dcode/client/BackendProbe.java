package eu.kalafatic.dcode.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Backend connection probe: classifies an endpoint into the explicit connection state
 * model and produces a human-readable diagnosis.
 *
 * <p>The probe distinguishes connectivity, API availability, API compatibility and
 * backend readiness. Connecting to the wrong service (e.g. an ENR or Ollama endpoint)
 * is reported as {@link ConnectionState#ERROR} with the detected identity instead of
 * turning into a stream of HTTP 404 errors.</p>
 */
public final class BackendProbe {

    /** Result of one probe. */
    public record ProbeResult(
            ConnectionState state,
            BackendIdentity identity,
            String detail,
            String diagnostic
    ) {
        public ProbeResult {
            identity = identity == null
                    ? new BackendIdentity("", "", false, "", java.util.Map.of())
                    : identity;
            detail = detail == null ? "" : detail;
            diagnostic = diagnostic == null ? "" : diagnostic;
        }

        public boolean isDCode() {
            return identity.isDCode();
        }

        /** User-facing diagnosis line. */
        public String describe() {
            return detail + (diagnostic.isBlank() ? "" : " (" + diagnostic + ")");
        }
    }

    /** Classification of API failures (drives retry and notification policy). */
    public enum FailureClass {
        /** Endpoint does not support the requested operation; retrying is pointless. */
        PERMANENT_CONTRACT,
        /** Credentials are missing or rejected. */
        AUTHENTICATION,
        /** Invalid request payload; retrying unchanged would fail again. */
        VALIDATION,
        /** Temporary failure (network, overload); a retry may succeed. */
        TRANSIENT,
        UNKNOWN
    }

    /** Probes the endpoint behind the given client. */
    public CompletableFuture<ProbeResult> probe(DCodeApiClient client) {
        Objects.requireNonNull(client, "client");
        return client.health().handle((health, throwable) -> classify(health, throwable,
                client.baseUrl()));
    }

    /** Probes a raw endpoint URL (no client needed). */
    public CompletableFuture<ProbeResult> probe(String baseUrl) {
        try {
            return probe(new DCodeApiClient(baseUrl));
        } catch (IllegalArgumentException e) {
            return CompletableFuture.completedFuture(new ProbeResult(ConnectionState.ERROR,
                    new BackendIdentity("", "", false, "", java.util.Map.of()),
                    "Invalid endpoint URL: " + baseUrl,
                    e.getMessage()));
        }
    }

    static ProbeResult classify(JsonNode health, Throwable throwable, String endpoint) {
        if (throwable != null) {
            if (throwable instanceof DCodeApiException apiError) {
                return switch (apiError.status()) {
                    case 401 -> new ProbeResult(ConnectionState.ERROR,
                            new BackendIdentity("", "", false, "", java.util.Map.of()),
                            "Authentication required by the server at " + endpoint,
                            "HTTP 401 " + code(apiError));
                    case 403 -> new ProbeResult(ConnectionState.ERROR,
                            new BackendIdentity("", "", false, "", java.util.Map.of()),
                            "The server at " + endpoint + " refused the request",
                            "HTTP 403 " + code(apiError));
                    case 404 -> new ProbeResult(ConnectionState.ERROR,
                            new BackendIdentity("", "", false, "", java.util.Map.of()),
                            "The endpoint " + endpoint
                                    + " does not provide the EVO-Code API "
                                    + "(/api/v1/health is missing)",
                            "HTTP 404 on /api/v1/health");
                    case 405 -> new ProbeResult(ConnectionState.ERROR,
                            new BackendIdentity("", "", false, "", java.util.Map.of()),
                            "The endpoint " + endpoint
                                    + " does not provide the EVO-Code API",
                            "HTTP 405 on /api/v1/health");
                    default -> new ProbeResult(ConnectionState.ERROR,
                            new BackendIdentity("", "", false, "", java.util.Map.of()),
                            "The EVO-Code backend at " + endpoint
                                    + " failed the health check",
                            "HTTP " + apiError.status() + " " + code(apiError));
                };
            }
            String cause = rootMessage(throwable);
            return new ProbeResult(ConnectionState.ERROR,
                    new BackendIdentity("", "", false, "", java.util.Map.of()),
                    "The endpoint " + endpoint + " is unreachable",
                    cause == null || cause.isBlank() ? throwable.getClass().getSimpleName()
                            : cause);
        }
        BackendIdentity identity = BackendIdentity.parse(health);
        if (!identity.isDCode()) {
            return new ProbeResult(ConnectionState.ERROR, identity,
                    "The endpoint " + endpoint + " is not an EVO-Code server (it reports: "
                            + identity.describe() + ")",
                    "EVO-Code API contract check failed: expected application=DCODE, got "
                            + (identity.application().isBlank() ? "(none)"
                            : "'" + identity.application() + "'"));
        }
        if (identity.ready()) {
            return new ProbeResult(ConnectionState.READY, identity,
                    "EVO-Code backend ready: " + identity.describe(),
                    "health check passed");
        }
        if ("UP".equalsIgnoreCase(identity.status())) {
            return new ProbeResult(ConnectionState.INITIALIZING, identity,
                    "EVO-Code backend is starting up: " + identity.describe(),
                    "ready=false, status=UP");
        }
        return new ProbeResult(ConnectionState.DEGRADED, identity,
                "EVO-Code backend reports degraded readiness: " + identity.describe(),
                "ready=false, status=" + identity.status());
    }

    /** Classifies an API failure for retry/notification policy. */
    public static FailureClass classifyFailure(Throwable throwable) {
        if (throwable instanceof DCodeApiException apiError) {
            return switch (apiError.status()) {
                case 401, 403 -> FailureClass.AUTHENTICATION;
                case 400, 422 -> FailureClass.VALIDATION;
                case 404, 405, 501 -> FailureClass.PERMANENT_CONTRACT;
                case 429, 500, 502, 503, 504 -> FailureClass.TRANSIENT;
                default -> FailureClass.UNKNOWN;
            };
        }
        if (throwable instanceof java.net.ConnectException
                || throwable instanceof java.net.http.HttpConnectTimeoutException
                || throwable instanceof java.net.UnknownHostException
                || throwable instanceof java.net.SocketException
                || throwable instanceof java.io.IOException) {
            return FailureClass.TRANSIENT;
        }
        return FailureClass.UNKNOWN;
    }

    private static String code(DCodeApiException error) {
        return error.code() == null || error.code().isBlank() ? "" : error.code();
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        if (current.getMessage() != null && !current.getMessage().isBlank()) {
            return current.getMessage();
        }
        if (current != throwable) {
            return current.getClass().getSimpleName();
        }
        return throwable.getClass().getSimpleName();
    }
}
