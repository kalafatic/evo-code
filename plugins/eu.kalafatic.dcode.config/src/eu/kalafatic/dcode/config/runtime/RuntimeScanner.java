package eu.kalafatic.dcode.config.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.config.provider.EnrProvider;
import eu.kalafatic.dcode.config.provider.OllamaProvider;
import eu.kalafatic.dcode.core.log.DCodeLog;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Local inference-service scanner: probes endpoints and classifies what they serve
 * (DCode backend, ENR, Ollama, OpenAI-compatible API, unknown HTTP service).
 *
 * <p>Used by the "Scan local runtimes" workflows of the RCP and Web IDEs to find and
 * register runtimes without typing endpoints. Detection is wire-level only: each
 * candidate endpoint is probed with the documented health/discovery paths of the
 * supported services.</p>
 */
public final class RuntimeScanner {

    /** One scan finding. */
    public record Finding(
            String endpoint,
            String kind,          // dcode | enr | ollama | openai | http | unreachable
            String name,
            String description,
            boolean reachable,
            int modelCount,
            List<String> sampleModels
    ) {
        public Finding {
            kind = kind == null ? "unreachable" : kind;
            name = name == null ? endpoint : name;
            description = description == null ? "" : description;
            sampleModels = sampleModels == null ? List.of() : List.copyOf(sampleModels);
        }

        public RuntimeType runtimeType() {
            return switch (kind) {
                case "enr" -> RuntimeType.ENR;
                case "ollama" -> RuntimeType.OLLAMA;
                case "openai" -> RuntimeType.OPENAI_COMPATIBLE;
                default -> null;
            };
        }

        public boolean isRuntime() {
            return runtimeType() != null;
        }
    }

    /** Default well-known local endpoints of the supported services. */
    public static final List<String> DEFAULT_ENDPOINTS = buildDefaultEndpoints();

    private static List<String> buildDefaultEndpoints() {
        List<String> endpoints = new ArrayList<>();
        endpoints.add("http://127.0.0.1:48080"); // DCode default API port
        endpoints.addAll(EnrProvider.WELL_KNOWN_ENDPOINTS); // ENR API 28780
        endpoints.add(OllamaProvider.DEFAULT_BASE_URL); // Ollama 11434
        return List.copyOf(endpoints);
    }

    private static final Duration TIMEOUT = Duration.ofSeconds(4);

    private final HttpClient client = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** Scans the default well-known local endpoints. */
    public List<Finding> scanLocal() {
        return scan(DEFAULT_ENDPOINTS);
    }

    /** Scans the given endpoints (parallel; each probe is best-effort and time-boxed). */
    public List<Finding> scan(List<String> endpoints) {
        List<CompletableFuture<Finding>> futures = new ArrayList<>();
        for (String endpoint : endpoints) {
            futures.add(CompletableFuture.supplyAsync(() -> probe(endpoint)));
        }
        List<Finding> findings = new ArrayList<>();
        for (CompletableFuture<Finding> future : futures) {
            try {
                findings.add(future.get(TIMEOUT.toMillis() + 3000, TimeUnit.MILLISECONDS));
            } catch (Exception e) {
                findings.add(new Finding("", "unreachable", "", "probe failed: "
                        + e.getMessage(), false, 0, List.of()));
            }
        }
        findings.sort(java.util.Comparator.comparing(f -> f.endpoint()));
        return List.copyOf(findings);
    }

    private Finding probe(String endpoint) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1)
                : endpoint;
        // 1. DCode backend
        JsonNode dcodeHealth = getJson(base + "/api/v1/health");
        if (dcodeHealth != null && "DCODE".equalsIgnoreCase(
                dcodeHealth.path("application").asText(""))) {
            return new Finding(base, "dcode", "EVO-Code server",
                    "EVO-Code backend " + dcodeHealth.path("version").asText("")
                    + " (status=" + dcodeHealth.path("status").asText("")
                    + ", ready=" + dcodeHealth.path("ready").asBoolean(false) + ")",
                    true, 0, List.of());
        }
        // 2. ENR (native surface, then family surface)
        JsonNode enrNative = getJson(base + "/api/v1/health");
        if (enrNative != null && ("healthy".equalsIgnoreCase(enrNative.path("status")
                .asText("")) || "unhealthy".equalsIgnoreCase(enrNative.path("status")
                .asText("")))) {
            JsonNode enrStatus = getJson(base + "/api/v1/status");
            String version = enrStatus != null
                    ? enrStatus.path("version").asText("") : "";
            JsonNode enrModels = getJson(base + "/api/v1/models");
            List<String> samples = modelSamples(enrModels, "models", "id");
            return new Finding(base, "enr", "ENR — EVO Native Runtime",
                    "ENR runtime" + (version.isBlank() ? "" : " " + version),
                    true, samples.size(), samples);
        }
        JsonNode enrFamily = getJson(base + "/enr/v1/health");
        if (enrFamily != null && "UP".equalsIgnoreCase(enrFamily.path("status").asText(""))) {
            return new Finding(base, "enr", "ENR — EVO Native Runtime",
                    "ENR family API 1.0 (status=UP)", true, 0, List.of());
        }
        // 3. Ollama
        JsonNode ollamaVersion = getJson(base + "/api/version");
        if (ollamaVersion != null && ollamaVersion.path("version").isValueNode()) {
            JsonNode tags = getJson(base + "/api/tags");
            List<String> samples = modelSamples(tags, "models", "name");
            return new Finding(base, "ollama", "Ollama",
                    "Ollama " + ollamaVersion.path("version").asText(""),
                    true, samples.size(), samples);
        }
        // 4. OpenAI-compatible API
        JsonNode openAiModels = getJson(base + "/v1/models");
        if (openAiModels != null && openAiModels.path("data").isArray()) {
            List<String> samples = modelSamples(openAiModels, "data", "id");
            return new Finding(base, "openai", "OpenAI-compatible API",
                    "OpenAI-compatible /v1/models endpoint", true, samples.size(),
                    samples);
        }
        if (openAiModels != null && openAiModels.path("object").asText("").equals("list")) {
            List<String> samples = modelSamples(openAiModels, "data", "id");
            return new Finding(base, "openai", "OpenAI-compatible API",
                    "OpenAI-compatible /v1/models endpoint", true, samples.size(),
                    samples);
        }
        // 5. Generic HTTP service
        if (getJson(base + "/") != null || httpHead(base) != -1) {
            return new Finding(base, "http", "HTTP service",
                    "HTTP endpoint without a recognized inference API", true, 0,
                    List.of());
        }
        return new Finding(base, "unreachable", base,
                "no response (connection refused or timeout)", false, 0, List.of());
    }

    private JsonNode getJson(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT).GET().build();
            HttpResponse<String> response = client.send(request,
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return null;
            }
            String body = response.body();
            if (body == null || body.isBlank()) {
                return mapper.createObjectNode();
            }
            try {
                return mapper.readTree(body);
            } catch (Exception e) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
    }

    private int httpHead(String url) {
        try {
            HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(TIMEOUT).method("HEAD", HttpRequest.BodyPublishers.noBody())
                    .build();
            HttpResponse<Void> response = client.send(request,
                    HttpResponse.BodyHandlers.discarding());
            return response.statusCode();
        } catch (Exception e) {
            return -1;
        }
    }

    private List<String> modelSamples(JsonNode root, String arrayField, String idField) {
        if (root == null) {
            return List.of();
        }
        JsonNode array = root.path(arrayField);
        if (!array.isArray()) {
            return List.of();
        }
        Set<String> ids = new LinkedHashSet<>();
        for (JsonNode entry : array) {
            String id = entry.path(idField).asText("");
            if (!id.isBlank()) {
                ids.add(id);
            }
            if (ids.size() >= 5) {
                break;
            }
        }
        return List.copyOf(ids);
    }
}
