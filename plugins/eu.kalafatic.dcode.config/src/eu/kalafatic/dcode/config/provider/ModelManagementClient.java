package eu.kalafatic.dcode.config.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Wire client for the shared family model management service hosted by ENR
 * (ENR {@code /api/v1/models/...} surface). DCode never implements model
 * pulling, job tracking or provider registration itself; it consumes the
 * single shared implementation through this client, exactly like the ENR
 * web UI, the ENR CLI and the Java API.
 *
 * <p>OSGi note: methods return raw JSON as {@link String} so no Jackson
 * types cross the bundle boundary (each bundle loads its own Jackson copy);
 * callers parse with their own mapper. The ENR endpoint follows the same
 * resolution as {@link EnrProvider} ({@code dcode.enr.endpoint} system
 * property, default family endpoint); the optional bearer token supplier
 * protects the admin surface.</p>
 */
public final class ModelManagementClient {

    public static final String DEFAULT_BASE_URL = EnrProvider.DEFAULT_BASE_URL;

    private final String baseUrl;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.function.Supplier<String> authTokenSupplier;

    public ModelManagementClient() {
        this(null, null);
    }

    public ModelManagementClient(String baseUrl, java.util.function.Supplier<String> authTokenSupplier) {
        this.baseUrl = resolveBaseUrl(baseUrl);
        this.authTokenSupplier = authTokenSupplier;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                // ENR runs on loopback; loopback traffic must bypass any
                // environment proxy (same convention as the provider tests).
                .proxy(java.net.ProxySelector.of(null))
                .build();
    }

    public String baseUrl() {
        return baseUrl;
    }

    private static String resolveBaseUrl(String baseUrl) {
        if (baseUrl != null && !baseUrl.isBlank()) {
            return baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        }
        String configured = System.getProperty("dcode.enr.endpoint");
        if (configured != null && !configured.isBlank()) {
            return configured.endsWith("/")
                    ? configured.substring(0, configured.length() - 1) : configured;
        }
        // Family convention (mirror of RuntimeManager.detectEnrEndpoint): when no
        // explicit endpoint is configured, probe the well-known ENR API endpoint
        // (28780) and use it when reachable. Local probes fail fast (connection
        // refused), so startup is never delayed.
        for (String candidate : EnrProvider.WELL_KNOWN_ENDPOINTS) {
            if (enrAnswers(candidate)) {
                return candidate;
            }
        }
        return DEFAULT_BASE_URL;
    }

    private static boolean enrAnswers(String endpoint) {
        try {
            HttpRequest request = HttpRequest.newBuilder(
                            URI.create(endpoint + "/api/v1/health"))
                    .timeout(Duration.ofSeconds(3)).GET().build();
            HttpResponse<String> response = HttpClient.newHttpClient().send(request,
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    // ------------------------------------------------------------ operations

    /** GET /api/v1/models/providers — returns the raw JSON document. */
    public String providersJson() {
        return send("GET", "/api/v1/models/providers", null);
    }

    /** GET /api/v1/models/downloads — all shared download jobs (newest first). */
    public String downloadsJson() {
        return send("GET", "/api/v1/models/downloads", null);
    }

    /** GET /api/v1/models/providers/{provider}/available — raw JSON document. */
    public String availableJson(String provider) {
        return send("GET", "/api/v1/models/providers/" + encode(provider) + "/available", null);
    }

    /** GET /api/v1/models/providers/{provider}/installed — raw JSON document. */
    public String installedJson(String provider) {
        return send("GET", "/api/v1/models/providers/" + encode(provider) + "/installed", null);
    }

    /** POST /api/v1/models/providers/{provider}/pull — returns the job handle JSON. */
    public String pullJson(String provider, String model) {
        Objects.requireNonNull(model, "model");
        ObjectNode body = mapper.createObjectNode();
        body.put("model", model);
        return send("POST", "/api/v1/models/providers/" + encode(provider) + "/pull", body);
    }

    /** GET /api/v1/models/downloads/{jobId} — raw status JSON. */
    public String statusJson(String jobId) {
        return send("GET", "/api/v1/models/downloads/" + encode(jobId), null);
    }

    /** DELETE /api/v1/models/downloads/{jobId} — raw cancellation result JSON. */
    public String cancelJson(String jobId) {
        return send("DELETE", "/api/v1/models/downloads/" + encode(jobId), null);
    }

    /** DELETE /api/v1/models/providers/{provider}/{modelId} — raw removal result JSON. */
    public String removeJson(String provider, String model) {
        return send("DELETE", "/api/v1/models/providers/" + encode(provider) + "/"
                + encode(model), null);
    }

    /**
     * Starts a pull and polls the shared job status until it reaches a terminal
     * state. Used by legacy synchronous callers (the runtime adapter contract);
     * interactive surfaces should use {@link #pullJson} plus {@link #statusJson}
     * polling instead so progress and cancellation stay available.
     */
    public void pullAndWait(String provider, String model, long pollIntervalMs, long timeoutMs) {
        JsonNode job = parse(pullJson(provider, model));
        String jobId = job.path("jobId").asText(job.path("job_id").asText(""));
        if (jobId.isBlank()) {
            throw new ProviderException("ENR model management returned no job id for "
                    + provider + "/" + model);
        }
        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
        while (System.currentTimeMillis() < deadline) {
            JsonNode status = parse(statusJson(jobId));
            String state = status.path("status").asText("");
            switch (state) {
                case "COMPLETED" -> {
                    return;
                }
                case "FAILED" -> throw new ProviderException("Model pull failed: "
                        + status.path("error").asText("unknown error"));
                case "CANCELLED" -> throw new ProviderException("Model pull cancelled: " + model);
                default -> {
                }
            }
            try {
                Thread.sleep(Math.max(50, pollIntervalMs));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new ProviderException("Model pull monitoring interrupted");
            }
        }
        throw new ProviderException("Model pull timed out for " + provider + "/" + model
                + " (job " + jobId + ")");
    }

    // ------------------------------------------------------------ wire

    private String send(String method, String path, JsonNode body) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .header("Content-Type", "application/json");
        if (authTokenSupplier != null) {
            String token = authTokenSupplier.get();
            if (token != null && !token.isBlank()) {
                builder.header("Authorization", "Bearer " + token);
            }
        }
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body.toString());
        builder.method(method, publisher);
        HttpResponse<String> response;
        try {
            response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        } catch (IOException e) {
            throw new ProviderException("ENR model management unreachable at " + baseUrl
                    + " (" + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()) + ")");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ProviderException("ENR model management request interrupted");
        }
        String responseBody = response.body() == null ? "" : response.body();
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String message = responseBody;
            String code = "HTTP_" + response.statusCode();
            try {
                JsonNode parsed = mapper.readTree(responseBody);
                JsonNode error = parsed.path("error");
                if (!error.isMissingNode()) {
                    code = error.path("code").asText(code);
                    message = error.path("message").asText(message);
                }
            } catch (IOException ignored) {
                // keep raw body as the message
            }
            throw new ProviderException("ENR model management error " + code + ": " + message);
        }
        return responseBody;
    }

    private JsonNode parse(String json) {
        if (json == null || json.isBlank()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(json);
        } catch (IOException e) {
            throw new ProviderException("ENR model management returned invalid JSON", e);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }
}
