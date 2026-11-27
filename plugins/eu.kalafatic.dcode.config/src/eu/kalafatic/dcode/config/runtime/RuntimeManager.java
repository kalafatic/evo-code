package eu.kalafatic.dcode.config.runtime;

import eu.kalafatic.dcode.config.credentials.CredentialVault;
import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.ConnectionTestResult;
import eu.kalafatic.dcode.config.provider.EnrProvider;
import eu.kalafatic.dcode.config.provider.LlmProvider;
import eu.kalafatic.dcode.config.provider.OllamaProvider;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.routing.ModelRouter;
import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.log.DCodeLog;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * Authoritative runtime manager: persists runtime registrations, binds credentials to
 * the vault, creates adapters and keeps the registry, the persisted store and the model
 * router defaults in sync.
 *
 * <p>This manager is the single inference runtime registry of a DCode instance; the
 * coding harness consumes it through the {@link ProviderRegistry} contract.</p>
 */
public final class RuntimeManager implements RuntimeRegistry, ProviderRegistry {

    public static final String SECRET_AUTH_TOKEN = "authToken";
    public static final String SECRET_API_KEY = "apiKey";

    private final DefaultRuntimeRegistry registry;
    private final RuntimeStore store;
    private final CredentialVault vault;
    private final ModelRouter modelRouter;
    private final RuntimeAdapterFactory factory = new RuntimeAdapterFactory();

    public RuntimeManager(DefaultRuntimeRegistry registry, RuntimeStore store,
                          CredentialVault vault, ModelRouter modelRouter) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.store = Objects.requireNonNull(store, "store");
        this.vault = Objects.requireNonNull(vault, "vault");
        this.modelRouter = modelRouter; // optional in embedded deployments
        load();
    }

    private void load() {
        RuntimeStoreData data = store.load();
        if (data.registrations().isEmpty()) {
            DCodeLog.info("config", "No runtime registrations found; seeding defaults "
                    + "(ENR + Ollama)");
            data = seedDefaults();
            store.save(data);
        }
        for (RuntimeRegistration registration : data.registrations()) {
            try {
                registry.register(registration, adapter(registration));
            } catch (Exception e) {
                DCodeLog.error("config", "Failed to register runtime "
                        + registration.id(), e);
            }
        }
        String defaultRuntimeId = data.defaultRuntimeId();
        if (defaultRuntimeId != null && registry.isRegistered(defaultRuntimeId)) {
            registry.setDefault(defaultRuntimeId, data.defaultModelId());
            String modelId = data.defaultModelId();
            if (modelRouter != null && modelId != null && !modelId.isBlank()) {
                modelRouter.setDefaultModel(defaultRuntimeId, modelId);
            }
        }
        DCodeLog.info("config", "Runtime manager started with "
                + registry.runtimes().size() + " runtime(s)");
        recheckEnrEndpoint();
    }

    /**
     * Existing installations keep the ENR registration at the authoritative default
     * (28780). When no explicit override is configured and the default is
     * unreachable while a known ENR API endpoint answers, the registration is
     * migrated automatically — runtime discovery works without manual repair.
     */
    private void recheckEnrEndpoint() {
        if (System.getProperty("dcode.enr.endpoint") != null
                && !System.getProperty("dcode.enr.endpoint").isBlank()) {
            return; // explicit user override: never touch it
        }
        Thread.ofVirtual().name("dcode-enr-redetect").start(() -> {
            var info = registry.info(EnrProvider.DEFAULT_PROVIDER_ID).orElse(null);
            if (info == null || !RuntimeType.ENR.equals(info.type())
                    || !EnrProvider.DEFAULT_BASE_URL.equals(info.endpoint())) {
                return;
            }
            for (String candidate : EnrProvider.WELL_KNOWN_ENDPOINTS) {
                if (candidate.equals(info.endpoint()) || !enrAnswers(candidate)) {
                    continue;
                }
                DCodeLog.info("config", "Migrating ENR runtime endpoint "
                        + info.endpoint() + " -> " + candidate + " (detected live)");
                update(new RuntimeRegistration(info.id(), info.name(), info.type(),
                        candidate, info.enabled(), info.defaultRuntime(),
                        info.configuration()));
                return;
            }
        });
    }

    private static boolean enrAnswers(String endpoint) {
        try {
            java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                            java.net.URI.create(endpoint + "/enr/v1/models"))
                    .timeout(java.time.Duration.ofSeconds(3)).GET().build();
            java.net.http.HttpResponse<String> response = java.net.http.HttpClient
                    .newHttpClient().send(request,
                            java.net.http.HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            return false;
        }
    }

    private RuntimeStoreData seedDefaults() {
        String enrEndpoint = System.getProperty("dcode.enr.endpoint", null);
        if (enrEndpoint == null || enrEndpoint.isBlank()) {
            enrEndpoint = detectEnrEndpoint();
        }
        RuntimeRegistration enr = new RuntimeRegistration(EnrProvider.DEFAULT_PROVIDER_ID,
                "ENR - EVO Native Runtime", RuntimeType.ENR, enrEndpoint, true, false,
                Map.of());
        RuntimeRegistration ollama = new RuntimeRegistration("ollama", "Ollama",
                RuntimeType.OLLAMA, OllamaProvider.DEFAULT_BASE_URL, true, false,
                Map.of());
        return new RuntimeStoreData(enr.id(), null,
                List.of(enr, ollama));
    }

    /**
     * Probes the well-known ENR API endpoints (authoritative 28780) and selects a
     * reachable one; falls back to the default when none responds. A local probe
     * fails fast (connection refused), so startup is never meaningfully delayed.
     */
    private static String detectEnrEndpoint() {
        for (String candidate : EnrProvider.WELL_KNOWN_ENDPOINTS) {
            try {
                java.net.http.HttpRequest request = java.net.http.HttpRequest.newBuilder(
                                java.net.URI.create(candidate + "/enr/v1/health"))
                        .timeout(java.time.Duration.ofSeconds(3)).GET().build();
                java.net.http.HttpResponse<String> response = java.net.http.HttpClient
                        .newHttpClient().send(request,
                                java.net.http.HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() == 200) {
                    DCodeLog.info("config", "Detected ENR endpoint at " + candidate);
                    return candidate;
                }
            } catch (Exception ignored) {
                // endpoint not answering: try the next candidate
            }
        }
        DCodeLog.info("config", "No reachable ENR endpoint; using default "
                + EnrProvider.DEFAULT_BASE_URL);
        return EnrProvider.DEFAULT_BASE_URL;
    }

    private RuntimeAdapter adapter(RuntimeRegistration registration) {
        return factory.create(registration,
                () -> vault.get(secretKey(registration.id(), SECRET_AUTH_TOKEN))
                        .orElse(null),
                () -> vault.get(secretKey(registration.id(), SECRET_API_KEY))
                        .orElse(null));
    }

    static String secretKey(String runtimeId, String secretName) {
        return "runtime." + runtimeId + "." + secretName;
    }

    // ---- RuntimeRegistry ----

    @Override
    public List<RuntimeInfo> runtimes() {
        return registry.runtimes();
    }

    @Override
    public Optional<RuntimeInfo> info(String id) {
        return registry.info(id);
    }

    @Override
    public void register(RuntimeRegistration registration) {
        register(registration, null, null);
    }

    /** Registers a runtime with optional credentials (stored in the vault). */
    public void register(RuntimeRegistration registration, String authToken, String apiKey) {
        registry.register(registration, adapter(registration));
        storeSecrets(registration.id(), authToken, apiKey);
        persist();
    }

    @Override
    public void unregister(String id) {
        registry.unregister(id);
        vault.remove(secretKey(id, SECRET_AUTH_TOKEN));
        vault.remove(secretKey(id, SECRET_API_KEY));
        persist();
    }

    @Override
    public void update(RuntimeRegistration registration) {
        update(registration, null, null);
    }

    /** Updates a runtime registration with optional credential changes. */
    public void update(RuntimeRegistration registration, String authToken, String apiKey) {
        // recreate the adapter so endpoint/type/name changes take effect immediately
        registry.update(registration, adapter(registration));
        storeSecrets(registration.id(), authToken, apiKey);
        persist();
    }

    @Override
    public void setEnabled(String id, boolean enabled) {
        registry.setEnabled(id, enabled);
        persist();
    }

    @Override
    public void setDefault(String id, String defaultModelId) {
        registry.setDefault(id, defaultModelId);
        if (modelRouter != null && defaultModelId != null && !defaultModelId.isBlank()) {
            modelRouter.setDefaultModel(id, defaultModelId);
        }
        persist();
    }

    @Override
    public Optional<RuntimeInfo> defaultRuntime() {
        return registry.defaultRuntime();
    }

    @Override
    public Optional<String> defaultModel() {
        return registry.defaultModel();
    }

    @Override
    public List<ModelDefinition> listModels(String id) {
        return registry.listModels(id);
    }

    @Override
    public List<ModelDefinition> listAllModels() {
        return registry.listAllModels();
    }

    @Override
    public ConnectionTestResult testConnection(String id) {
        return registry.testConnection(id);
    }

    @Override
    public ChatResponse complete(String id, ChatRequest request) {
        return registry.complete(id, request);
    }

    @Override
    public StreamHandle stream(String id, ChatRequest request, StreamListener listener) {
        return registry.stream(id, request, listener);
    }

    @Override
    public ModelDefinition loadModel(String id, String modelId) {
        return registry.loadModel(id, modelId);
    }

    @Override
    public ModelDefinition unloadModel(String id, String modelId) {
        return registry.unloadModel(id, modelId);
    }

    @Override
    public ModelDefinition pullModel(String id, String modelId) {
        return registry.pullModel(id, modelId);
    }

    @Override
    public RuntimeStatus runtimeStatus(String id) {
        return registry.runtimeStatus(id);
    }

    @Override
    public boolean isEnabled(String id) {
        return registry.isEnabled(id);
    }

    @Override
    public boolean isRegistered(String id) {
        return registry.isRegistered(id);
    }

    @Override
    public Map<String, String> credentialState(String id) {
        Map<String, String> state = new LinkedHashMap<>();
        state.put(SECRET_AUTH_TOKEN, vault.masked(secretKey(id, SECRET_AUTH_TOKEN)));
        state.put(SECRET_API_KEY, vault.masked(secretKey(id, SECRET_API_KEY)));
        return Map.copyOf(state);
    }

    /** Records a status snapshot after an external probe. */
    public void recordStatus(String id, RuntimeStatus status) {
        registry.recordStatus(id, status);
    }

    private static final long STATUS_TTL_MILLIS = 30_000;
    private long lastAvailabilityRefresh;

    /**
     * Refreshes the live availability of every enabled runtime when the previous
     * snapshot is older than the TTL. Probing runs in parallel and is time-boxed;
     * the registry snapshot (status + model count) is what the management APIs and
     * the Runtime Manager display. Never throws: unreachable runtimes simply get an
     * honest "unreachable" status.
     */
    public void refreshAvailabilityIfStale() {
        synchronized (this) {
            if (System.currentTimeMillis() - lastAvailabilityRefresh < STATUS_TTL_MILLIS) {
                return;
            }
            lastAvailabilityRefresh = System.currentTimeMillis();
        }
        List<RuntimeInfo> targets = registry.runtimes().stream()
                .filter(RuntimeInfo::enabled)
                .toList();
        List<java.util.concurrent.CompletableFuture<?>> probes = new ArrayList<>();
        for (RuntimeInfo info : targets) {
            probes.add(java.util.concurrent.CompletableFuture.runAsync(() -> probe(info)));
        }
        try {
            java.util.concurrent.CompletableFuture.allOf(probes.toArray(
                    new java.util.concurrent.CompletableFuture[0])).get(8,
                    java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            DCodeLog.warn("config", "Runtime availability refresh incomplete: "
                    + e.getMessage());
        }
    }

    private void probe(RuntimeInfo info) {
        RuntimeAdapter adapter;
        try {
            adapter = factory.create(
                    new RuntimeRegistration(info.id(), info.name(), info.type(),
                            info.endpoint(), info.enabled(), info.defaultRuntime(),
                            info.configuration()),
                    () -> vault.get(secretKey(info.id(), SECRET_AUTH_TOKEN))
                            .orElse(null),
                    () -> vault.get(secretKey(info.id(), SECRET_API_KEY))
                            .orElse(null));
        } catch (RuntimeException e) {
            return;
        }
        RuntimeStatus status;
        try {
            status = adapter.runtimeStatus();
        } catch (RuntimeException e) {
            status = RuntimeStatus.failed("unreachable: " + e.getMessage());
        }
        registry.recordStatus(info.id(), status);
        int modelCount = -1;
        if (status.reachable()) {
            try {
                modelCount = adapter.listModels().size();
            } catch (RuntimeException ignored) {
                // reachable but discovery failed: keep the count unknown
            }
        } else {
            modelCount = 0;
        }
        registry.recordModelCount(info.id(), modelCount);
    }

    private void storeSecrets(String id, String authToken, String apiKey) {
        if (authToken != null) {
            if (authToken.isBlank()) {
                vault.remove(secretKey(id, SECRET_AUTH_TOKEN));
            } else {
                vault.put(secretKey(id, SECRET_AUTH_TOKEN), authToken);
            }
        }
        if (apiKey != null) {
            if (apiKey.isBlank()) {
                vault.remove(secretKey(id, SECRET_API_KEY));
            } else {
                vault.put(secretKey(id, SECRET_API_KEY), apiKey);
            }
        }
    }

    private void persist() {
        String defaultRuntimeId = registry.defaultRuntime().map(RuntimeInfo::id)
                .orElse(null);
        String defaultModelId = registry.defaultModel().orElse(null);
        List<RuntimeRegistration> registrations = registry.runtimes().stream()
                .map(info -> new RuntimeRegistration(info.id(), info.name(), info.type(),
                        info.endpoint(), info.enabled(), info.defaultRuntime(),
                        info.configuration()))
                .toList();
        store.save(new RuntimeStoreData(defaultRuntimeId, defaultModelId, registrations));
    }

    // ---- ProviderRegistry bridge (harness contract) ----

    @Override
    public void register(LlmProvider provider) {
        registry.register(provider);
    }

    @Override
    public List<LlmProvider> providers() {
        return registry.providers();
    }

    @Override
    public Optional<LlmProvider> find(String providerId) {
        return registry.find(providerId);
    }

    /** Startup wiring convenience (kept package-private for the activator). */
    public static RuntimeManager create(ConfigurationService configuration,
                                        CredentialVault vault, ModelRouter modelRouter) {
        DefaultRuntimeRegistry registry = new DefaultRuntimeRegistry();
        RuntimeStore store = new JsonFileRuntimeStore(
                configuration.getConfiguration().dataDir());
        return new RuntimeManager(registry, store, vault, modelRouter);
    }
}
