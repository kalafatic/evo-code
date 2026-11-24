package eu.kalafatic.dcode.harness.registry;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Default harness registry.
 *
 * <p>The DCode native harness is always present and integrated. External CLI harnesses
 * (OpenCode, Claude Code, Aider) are reported with their true availability — detected
 * on PATH — and {@code integrated=false} until a backend execution adapter exists.
 * The UI must only offer task execution through usable harnesses and never silently
 * substitute one harness for another.</p>
 *
 * <p>Harness settings (default selection, enablement) are held here and persisted to
 * {@code <dataDir>/harnesses.json} when a store is provided; the no-arg constructor
 * keeps an in-memory registry for tests and API fallbacks.</p>
 */
public final class DefaultHarnessRegistry implements HarnessRegistry {

    private final List<HarnessDescriptor> descriptors = List.of(
            new HarnessDescriptor("dcode", "EVO-Code",
                    "Native EVO-Code coding harness (tool-calling agent loop)",
                    true, true, List.of("tools", "streaming", "sessions", "review")),
            new HarnessDescriptor("opencode", "OpenCode",
                    "OpenCode CLI coding agent (external; not integrated into this build)",
                    false, cliOnPath("opencode"),
                    List.of("cli")),
            new HarnessDescriptor("claude", "Claude Code",
                    "Anthropic Claude Code CLI (external; not integrated into this build)",
                    false, cliOnPath("claude"),
                    List.of("cli")),
            new HarnessDescriptor("aider", "Aider",
                    "Aider AI pair-programming CLI (external; not integrated into this build)",
                    false, cliOnPath("aider"),
                    List.of("cli"))
    );

    private final JsonFileHarnessStore store;
    private final Map<String, Boolean> enabled = new ConcurrentHashMap<>();
    private final AtomicReference<String> defaultHarnessId = new AtomicReference<>();

    /** In-memory registry (tests, API fallbacks). */
    public DefaultHarnessRegistry() {
        this((JsonFileHarnessStore) null);
    }

    /** Persisted registry backed by {@code <dataDir>/harnesses.json}. */
    public DefaultHarnessRegistry(Path dataDir) {
        this(dataDir == null ? null : new JsonFileHarnessStore(dataDir));
    }

    private DefaultHarnessRegistry(JsonFileHarnessStore store) {
        this.store = store;
        if (store != null) {
            JsonFileHarnessStore.HarnessStoreData data = store.load();
            enabled.putAll(data.enabled());
            String persisted = data.defaultHarnessId();
            defaultHarnessId.set(persisted != null && find(persisted).isPresent()
                    ? persisted : "dcode");
        } else {
            defaultHarnessId.set("dcode");
        }
    }

    @Override
    public List<HarnessDescriptor> harnesses() {
        return descriptors;
    }

    @Override
    public Optional<HarnessDescriptor> find(String id) {
        return descriptors.stream().filter(descriptor -> descriptor.id().equals(id))
                .findFirst();
    }

    @Override
    public void selectDefaultHarness(String harnessId) {
        if (harnessId == null || harnessId.isBlank()) {
            defaultHarnessId.set("");
            persist();
            return;
        }
        if (find(harnessId).isEmpty()) {
            throw new IllegalArgumentException("Unknown harness: " + harnessId);
        }
        defaultHarnessId.set(harnessId);
        persist();
    }

    @Override
    public void setHarnessEnabled(String harnessId, boolean enabledValue) {
        if (find(harnessId).isEmpty()) {
            throw new IllegalArgumentException("Unknown harness: " + harnessId);
        }
        enabled.put(harnessId, enabledValue);
        persist();
    }

    @Override
    public Optional<String> defaultHarnessId() {
        String id = defaultHarnessId.get();
        return id == null || id.isBlank() ? Optional.empty() : Optional.of(id);
    }

    @Override
    public boolean isEnabled(String harnessId) {
        return enabled.getOrDefault(harnessId, Boolean.TRUE);
    }

    private void persist() {
        if (store != null) {
            store.save(new JsonFileHarnessStore.HarnessStoreData(
                    defaultHarnessId.get(), enabled));
        }
    }

    /** Detects an executable on PATH (honest availability reporting). */
    private static boolean cliOnPath(String name) {
        ProcessBuilder builder = new ProcessBuilder("which", name);
        try {
            Process process = builder.start();
            return process.waitFor() == 0;
        } catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return false;
        }
    }
}
