package eu.kalafatic.dcode.core;

import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.config.InMemoryConfigurationService;
import eu.kalafatic.dcode.core.event.DCodeEvent;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.DefaultDCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.LifecycleException;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.model.DCodeVersion;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * DCode core runtime: the single root object of every DCode runtime mode.
 *
 * <p>Declarative Services registers this component under {@link EventBus},
 * {@link DCodeLifecycle} and {@link ConfigurationService}; every other bundle consumes
 * those contracts. The class is also usable outside OSGi (plain start/stop) which keeps it
 * testable and independent of RCP.</p>
 *
 * <p>Core must initialize without SWT, without a Workbench and without a display; this
 * class is that gate (WP5.2).</p>
 */
public final class CoreRuntime implements EventBus, DCodeLifecycle, ConfigurationService {

    private final EventBus eventBus = new InMemoryEventBus();
    private final DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
    private volatile ConfigurationService configurationService;

    /** Creates a runtime for the given home directory. */
    public CoreRuntime(Path homeDir) {
        Objects.requireNonNull(homeDir, "homeDir");
        initialize(homeDir);
    }

    /** Creates a runtime using the default DCode home ({@code ~/.dcode}). */
    public CoreRuntime() {
        initialize(defaultHome());
    }

    private void initialize(Path homeDir) {
        try {
            Files.createDirectories(homeDir);
            CoreConfiguration defaults = CoreConfiguration.defaults(homeDir);
            configurationService = new InMemoryConfigurationService(defaults);
            lifecycle.registerAction(LifecycleStage.CORE_SERVICES, "core-directories", new DCodeLifecycle.LifecycleAction() {
                @Override
                public void start() throws Exception {
                    Files.createDirectories(defaults.workspacesDir());
                    Files.createDirectories(defaults.artifactsDir());
                    Files.createDirectories(defaults.logsDir());
                }
            });
            lifecycle.registerAction(LifecycleStage.CORE_SERVICES, "core-log", new DCodeLifecycle.LifecycleAction() {
                @Override
                public void start() {
                    DCodeLog.info("core", "EVO-Code core " + DCodeVersion.VERSION
                            + " initializing in " + homeDir);
                }

                @Override
                public void stop() {
                    DCodeLog.info("core", "EVO-Code core stopped");
                }
            });
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize EVO-Code core runtime in "
                    + homeDir, e);
        }
    }

    /** Starts the core lifecycle through CORE_SERVICES. */
    public void start() {
        try {
            lifecycle.startThrough(LifecycleStage.CORE_SERVICES);
        } catch (LifecycleException e) {
            DCodeLog.error("core", "Core startup failed", e);
            throw new IllegalStateException("Core startup failed", e);
        }
    }

    /** Stops the core lifecycle. */
    public void stop() {
        try {
            lifecycle.stop();
        } catch (LifecycleException e) {
            DCodeLog.error("core", "Core shutdown incomplete", e);
        }
    }

    /** DS activation entry point. */
    public void activate() {
        start();
    }

    /** DS deactivation entry point. */
    public void deactivate() {
        stop();
    }

    private static Path defaultHome() {
        String home = System.getProperty("dcode.home");
        if (home != null && !home.isBlank()) {
            return Path.of(home);
        }
        return Path.of(System.getProperty("user.home", "."), ".dcode");
    }

    // ---- EventBus delegation ----

    @Override
    public void publish(DCodeEvent event) {
        eventBus.publish(event);
    }

    @Override
    public EventBus.Subscription subscribe(Predicate<DCodeEvent> filter,
                                           Consumer<DCodeEvent> handler) {
        return eventBus.subscribe(filter, handler);
    }

    // ---- DCodeLifecycle delegation ----

    @Override
    public void startThrough(LifecycleStage stage) throws LifecycleException {
        lifecycle.startThrough(stage);
    }

    @Override
    public void startAll() throws LifecycleException {
        lifecycle.startAll();
    }

    @Override
    public eu.kalafatic.dcode.core.lifecycle.StageState getStageState(LifecycleStage stage) {
        return lifecycle.getStageState(stage);
    }

    @Override
    public LifecycleStage getCurrentStage() {
        return lifecycle.getCurrentStage();
    }

    @Override
    public boolean isStarted(LifecycleStage stage) {
        return lifecycle.isStarted(stage);
    }

    @Override
    public boolean isReady() {
        return lifecycle.isReady();
    }

    /** Diagnostic: stage states of the underlying lifecycle. */
    public java.util.Map<String, String> describeStages() {
        return lifecycle.describe();
    }

    /** Diagnostic: last lifecycle failure, or null. */
    public Exception lastFailure() {
        return lifecycle.getLastFailure();
    }

    @Override
    public void registerAction(LifecycleStage stage, String name,
                               DCodeLifecycle.LifecycleAction action) {
        lifecycle.registerAction(stage, name, action);
    }

    @Override
    public void addProbe(LifecycleStage stage,
                         eu.kalafatic.dcode.core.lifecycle.ReadinessProbe probe) {
        lifecycle.addProbe(stage, probe);
    }

    // ---- ConfigurationService delegation ----

    @Override
    public CoreConfiguration getConfiguration() {
        return configurationService.getConfiguration();
    }

    @Override
    public void update(CoreConfiguration configuration) {
        configurationService.update(configuration);
    }

    @Override
    public java.util.Set<String> secretKeys() {
        return configurationService.secretKeys();
    }

    @Override
    public void setSecret(String key, String value) {
        configurationService.setSecret(key, value);
    }

    @Override
    public java.util.Optional<String> getSecret(String key) {
        return configurationService.getSecret(key);
    }

    @Override
    public void removeSecret(String key) {
        configurationService.removeSecret(key);
    }
}
