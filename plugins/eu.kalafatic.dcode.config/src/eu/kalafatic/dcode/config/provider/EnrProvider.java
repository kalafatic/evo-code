package eu.kalafatic.dcode.config.provider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.runtime.RuntimeAdapter;
import eu.kalafatic.dcode.config.runtime.RuntimeCapabilities;
import eu.kalafatic.dcode.config.runtime.RuntimeStatus;
import eu.kalafatic.dcode.config.runtime.RuntimeType;
import eu.kalafatic.dcode.config.runtime.StreamHandle;
import eu.kalafatic.dcode.config.runtime.StreamListener;
import eu.kalafatic.dcode.config.runtime.StreamResult;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/**
 * ENR adapter over the family ENR 1.0 wire contract (base {@code /enr/v1}):
 * health, model discovery, model load/unload, blocking chat and SSE streaming.
 * ENR is the default family inference engine; this adapter makes DCode consume it
 * exactly like any other runtime through the {@link RuntimeAdapter} contract.
 *
 * <p>Wire-only integration: DCode never binds ENR classes. The endpoint is
 * configurable ({@code dcode.enr.endpoint} system property, then
 * {@link #DEFAULT_BASE_URL}); the optional bearer token is supplied through the
 * token supplier (typically the credential vault) and never logged.</p>
 */
public final class EnrProvider implements RuntimeAdapter {

    public static final String DEFAULT_BASE_URL = "http://127.0.0.1:28780";

    /**
     * Well-known local ENR API endpoints in detection order. The authoritative
     * ENR API port is 28780 for every family application; port 8111 is retired.
     * Single source of truth — referenced by runtime detection, endpoint
     * re-check, the model-management client and the local runtime scanner, so no
     * caller hardcodes a port.
     */
    public static final List<String> WELL_KNOWN_ENDPOINTS = List.of(DEFAULT_BASE_URL);

    public static final String DEFAULT_PROVIDER_ID = "enr";
    public static final String RUNTIME_TYPE_ENR = "enr";

    private static final Set<String> REPORTED_CAPABILITIES = Set.of(
            "ENR_INFERENCE", "ENR_STREAMING", "ENR_MODEL_MANAGEMENT",
            "ENR_RUNTIME_MONITORING");

    private final String providerId;
    private final String displayName;
    private final String baseUrl;
    private final Duration timeout;
    private final HttpLlmSupport http;
    private final Supplier<String> authTokenSupplier;
    private volatile String authToken;

    public EnrProvider(String providerId, String displayName, String baseUrl,
                       Duration timeout) {
        this(providerId, displayName, baseUrl, timeout, null);
    }

    public EnrProvider(String providerId, String displayName, String baseUrl,
                       Duration timeout, Supplier<String> authTokenSupplier) {
        this.providerId = providerId != null ? providerId : DEFAULT_PROVIDER_ID;
        this.displayName = displayName != null ? displayName : "ENR - EVO Native Runtime";
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : DEFAULT_BASE_URL;
        this.timeout = timeout != null ? timeout : Duration.ofMinutes(5);
        this.authTokenSupplier = authTokenSupplier;
        this.http = new HttpLlmSupport(this::effectiveToken, this.timeout);
    }

    public static EnrProvider localDefault() {
        // legacy bootstrap path: the dcode.enr.endpoint system property overrides the
        // family default endpoint; managed runtime registrations always configure the
        // endpoint explicitly.
        String configured = System.getProperty("dcode.enr.endpoint");
        String endpoint = configured != null && !configured.isBlank() ? configured
                : DEFAULT_BASE_URL;
        return new EnrProvider(DEFAULT_PROVIDER_ID, null, endpoint, null);
    }

    private String effectiveToken() {
        if (authTokenSupplier != null) {
            String supplied = authTokenSupplier.get();
            if (supplied != null && !supplied.isBlank()) {
                return supplied;
            }
        }
        return authToken;
    }

    @Override
    public String id() {
        return providerId;
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.ENR;
    }

    @Override
    public String endpoint() {
        return baseUrl;
    }

    @Override
    public String providerId() {
        return providerId;
    }

    @Override
    public String displayName() {
        return displayName;
    }

    @Override
    public String baseUrl() {
        return baseUrl;
    }

    @Override
    public boolean isLocal() {
        return true;
    }

    @Override
    public RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities(true, true, true, true, true, false, true, true,
                REPORTED_CAPABILITIES);
    }

    @Override
    public List<ModelDefinition> listModels() {
        JsonNode root = http.getJson(baseUrl + "/enr/v1/models", Duration.ofSeconds(20));
        JsonNode models = root.isArray() ? root : root.path("models");
        if (!models.isArray()) {
            throw new ProviderException("ENR response has no models array");
        }
        List<ModelDefinition> result = new ArrayList<>();
        for (JsonNode model : models) {
            String id = model.path("id").asText(model.path("name").asText(""));
            if (id.isBlank()) {
                continue;
            }
            boolean available = model.path("available").asBoolean(true);
            boolean loaded = model.path("loaded").asBoolean(false);
            long sizeBytes = model.path("sizeBytes").asLong(0);
            long contextLength = model.path("contextLength").asLong(0);
            String state = model.path("state").asText("");
            Set<String> capabilities = new LinkedHashSet<>();
            if (!state.isBlank()) {
                capabilities.add("state:" + state);
            }
            result.add(new ModelDefinition(id, model.path("name").asText(id), providerId,
                    contextLength, available, sizeBytes, "", loaded, capabilities));
        }
        return result;
    }

    @Override
    public ConnectionTestResult testConnection() {
        String failure = null;
        try {
            JsonNode health = http.getJson(baseUrl + "/enr/v1/health", Duration.ofSeconds(10));
            String status = health.path("status").asText("");
            boolean ready = health.path("ready").asBoolean(false);
            if ("UP".equalsIgnoreCase(status) || "healthy".equalsIgnoreCase(status)
                    || ready) {
                return ConnectionTestResult.ok("ENR reachable ("
                        + (status.isBlank() ? "ready" : status) + ")");
            }
            return ConnectionTestResult.ok("ENR reachable, runtime status " + status);
        } catch (ProviderException e) {
            failure = e.getMessage();
        }
        // Legacy fallback: some ENR deployments expose a plain /health endpoint.
        try {
            JsonNode health = http.getJson(baseUrl + "/health", Duration.ofSeconds(10));
            String status = health.path("status").asText("");
            if ("UP".equalsIgnoreCase(status) || "healthy".equalsIgnoreCase(status)) {
                return ConnectionTestResult.ok("ENR reachable (" + status + ")");
            }
            return ConnectionTestResult.ok("ENR reachable, runtime status " + status);
        } catch (ProviderException e) {
            return ConnectionTestResult.error(failure != null ? failure : e.getMessage());
        }
    }

    @Override
    public ChatResponse complete(ChatRequest request) {
        ObjectNode body = writeChatBody(request);
        JsonNode response = http.postJson(baseUrl + "/enr/v1/chat", http.write(body), timeout);
        return parseChatResponse(response);
    }

    @Override
    public StreamHandle stream(ChatRequest request, StreamListener listener) {
        ObjectNode body = writeChatBody(request);
        StringBuilder content = new StringBuilder();
        return http.streamSse(baseUrl + "/enr/v1/chat/stream", http.write(body), timeout,
                new HttpLlmSupport.SseHandler() {
                    private String finishReason;
                    private int promptTokens;
                    private int completionTokens;
                    private boolean done;

                    @Override
                    public void onEvent(HttpLlmSupport.SseEvent event) {
                        try {
                            JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper()
                                    .readTree(event.data());
                            if ("done".equals(event.event()) || node.path("done").asBoolean(false)) {
                                done = true;
                                return;
                            }
                            if (node.hasNonNull("chunk")) {
                                String chunk = node.path("chunk").asText("");
                                if (!chunk.isEmpty()) {
                                    content.append(chunk);
                                    listener.onToken(chunk);
                                }
                                return;
                            }
                            // terminal summary frame: model/finishReason/promptTokens/completionTokens
                            if (node.hasNonNull("finishReason") && !node.hasNonNull("chunk")) {
                                finishReason = node.path("finishReason").asText(null);
                                promptTokens = node.path("promptTokens").asInt(0);
                                completionTokens = node.path("completionTokens").asInt(0);
                            }
                        } catch (Exception ignored) {
                            // non-JSON frame: ignore
                        }
                    }

                    @Override
                    public void onFinished() {
                        if (!done && content.isEmpty() && finishReason == null
                                && promptTokens == 0) {
                            listener.onError(new ProviderException(
                                    "ENR stream ended without a completion"));
                            return;
                        }
                        listener.onComplete(new StreamResult(new ChatResponse(
                                content.toString(), finishReason != null ? finishReason
                                : "stop", promptTokens, completionTokens), false, null));
                    }

                    @Override
                    public void onCancelled() {
                        listener.onComplete(StreamResult.cancelled(content.toString()));
                    }

                    @Override
                    public void onFailure(ProviderException error) {
                        listener.onError(error);
                    }
                });
    }

    @Override
    public ModelDefinition loadModel(String modelId) {
        ObjectNode body = new ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        JsonNode response = http.postJson(baseUrl + "/enr/v1/models/" + encode(modelId)
                + "/load", http.write(body), Duration.ofMinutes(5));
        return findModel(modelId).orElseGet(() -> new ModelDefinition(modelId, modelId,
                providerId, 0, response.path("success").asBoolean(true), 0, "",
                "ready".equals(response.path("state").asText("")), Set.of()));
    }

    @Override
    public ModelDefinition unloadModel(String modelId) {
        ObjectNode body = new ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        http.postJson(baseUrl + "/enr/v1/models/" + encode(modelId) + "/unload",
                http.write(body), Duration.ofMinutes(5));
        return findModel(modelId).orElseGet(() -> new ModelDefinition(modelId, modelId,
                providerId, 0, false, 0, "", false, Set.of()));
    }

    @Override
    public RuntimeStatus runtimeStatus() {
        try {
            JsonNode status = http.getJson(baseUrl + "/enr/v1/runtime", Duration.ofSeconds(10));
            boolean running = status.path("running").asBoolean(false);
            JsonNode devices = status.path("devices");
            int deviceCount = devices.isArray() ? devices.size() : 0;
            return running
                    ? RuntimeStatus.ok("running, " + deviceCount + " device(s)")
                    : RuntimeStatus.reachable("reported running=false");
        } catch (ProviderException e) {
            return RuntimeStatus.failed(e.getMessage());
        }
    }

    private ObjectNode writeChatBody(ChatRequest request) {
        ObjectNode body = new ObjectNode(
                com.fasterxml.jackson.databind.node.JsonNodeFactory.instance);
        body.put("model", request.model());
        ArrayNode messages = body.putArray("messages");
        for (ChatMessage message : request.messages()) {
            ObjectNode entry = messages.addObject();
            entry.put("role", message.role().name().toLowerCase());
            entry.put("content", message.content());
        }
        if (request.temperature() > 0) {
            body.put("temperature", request.temperature());
        }
        if (request.maxTokens() > 0) {
            body.put("maxTokens", request.maxTokens());
        }
        return body;
    }

    private ChatResponse parseChatResponse(JsonNode response) {
        String content = response.path("content").asText("");
        String finish = response.path("finishReason").asText(null);
        if (finish == null || finish.isBlank()) {
            String reason = response.path("reason").asText("");
            finish = reason.isBlank() ? null : reason;
        }
        int promptTokens = response.path("promptTokens").asInt(0);
        int completionTokens = response.path("completionTokens").asInt(0);
        if (promptTokens == 0) {
            promptTokens = response.path("prompt_tokens").asInt(0);
        }
        if (completionTokens == 0) {
            completionTokens = response.path("generated_tokens").asInt(0);
        }
        return new ChatResponse(content, finish, promptTokens, completionTokens);
    }

    private java.util.Optional<ModelDefinition> findModel(String modelId) {
        try {
            return listModels().stream().filter(m -> m.id().equals(modelId)).findFirst();
        } catch (ProviderException e) {
            return java.util.Optional.empty();
        }
    }

    private static String encode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    @Override
    public void updateConfiguration(Map<String, String> configuration) {
        Objects.requireNonNull(configuration, "configuration");
        for (Map.Entry<String, String> entry : configuration.entrySet()) {
            String value = entry.getValue();
            switch (entry.getKey()) {
                case "endpoint", "baseUrl", "base_url" -> {
                    if (value == null || value.isBlank()) {
                        throw new ProviderException("ENR endpoint must not be blank");
                    }
                }
                case "authToken", "auth_token", "token" -> {
                    if (value != null && !value.isBlank() && value.length() < 8) {
                        throw new ProviderException("ENR auth token is too short");
                    }
                    authToken = (value == null || value.isBlank()) ? null : value;
                }
                default -> {
                }
            }
        }
    }

    @Override
    public Map<String, String> getConfiguration() {
        Map<String, String> config = new LinkedHashMap<>();
        config.put("endpoint", baseUrl);
        config.put("authToken", authToken == null ? "" : "****** (configured)");
        return config;
    }
}
