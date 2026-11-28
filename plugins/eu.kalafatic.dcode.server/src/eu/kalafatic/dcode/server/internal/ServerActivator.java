package eu.kalafatic.dcode.server.internal;

import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistry;
import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.port.DCodePorts;
import eu.kalafatic.dcode.core.port.PortAllocator;
import eu.kalafatic.dcode.core.server.WebServerControl;
import eu.kalafatic.dcode.core.server.WebServerState;
import eu.kalafatic.dcode.core.service.DynamicServiceLookup;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.ServiceAvailabilityGate;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.harness.execution.SessionExecutor;
import eu.kalafatic.dcode.harness.tool.ToolRegistry;
import eu.kalafatic.dcode.server.DCodeApi;
import eu.kalafatic.dcode.server.DCodeHttpServer;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import java.util.function.Supplier;

/**
 * Starts the NanoHTTPD API server at the SERVER lifecycle stage. Startup is retried
 * through a service-availability gate: bundle start order is irrelevant.
 *
 * <p>Startup follows the shared application configuration:
 * <ul>
 *   <li>{@code webServer.enabled=false} — the server is never started (state DISABLED).</li>
 *   <li>{@code webServer.autoStart=false} — the server is not started automatically;
 *       it can be started later via the {@link WebServerControl} service
 *       (RCP Web Interface menu or headless CLI).</li>
 * </ul>
 * Defaults keep the historical behavior: the server auto-starts in both headless
 * and RCP modes. The single {@link WebServerControl} service registered here is
 * the only start/stop authority; nothing else touches the HTTP server instance.
 */
public final class ServerActivator implements BundleActivator {

    private DCodeHttpServer server;
    private DynamicServiceLookup services;
    private ServiceAvailabilityGate gate;
    private BundleContext context;
    private ConfigurationService configuration;
    private DCodeApi api;
    private volatile int boundPort = -1;
    private volatile String boundAddress = "127.0.0.1";
    private volatile WebServerState state = WebServerState.STOPPED;
    private ServiceRegistration<WebServerControl> controlRegistration;

    @Override
    public void start(BundleContext context) {
        this.context = context;
        gate = new ServiceAvailabilityGate(context, new Class<?>[]{
                EventBus.class, DCodeLifecycle.class, ConfigurationService.class,
                ProviderRegistry.class, RuntimeRegistry.class, ToolRegistry.class,
                SessionExecutor.class},
                this::wire);
        gate.runIfAvailable();
    }

    private void wire() {
        EventBus eventBus = context.getService(context.getServiceReference(EventBus.class));
        DCodeLifecycle lifecycle = context.getService(
                context.getServiceReference(DCodeLifecycle.class));
        configuration = context.getService(
                context.getServiceReference(ConfigurationService.class));
        ProviderRegistry providers = context.getService(
                context.getServiceReference(ProviderRegistry.class));
        RuntimeRegistry runtimes = context.getService(
                context.getServiceReference(RuntimeRegistry.class));
        ToolRegistry tools = context.getService(context.getServiceReference(ToolRegistry.class));
        SessionExecutor executor = context.getService(
                context.getServiceReference(SessionExecutor.class));

        services = new DynamicServiceLookup(context);

        Supplier<SessionService> sessionSupplier = () -> services.get(SessionService.class);
        Supplier<WorkspaceService> workspaceSupplier = () -> services.get(WorkspaceService.class);
        Supplier<ReviewService> reviewSupplier = () -> services.get(ReviewService.class);

        api = new DCodeApi(sessionSupplier, workspaceSupplier, reviewSupplier,
                providers, runtimes, tools, executor, lifecycle, configuration, eventBus,
                () -> services.get(eu.kalafatic.dcode.harness.tool.ToolEngine.class),
                () -> services.get(eu.kalafatic.dcode.repositories.service.RepositoryOperations.class),
                () -> services.get(eu.kalafatic.dcode.config.git.GitAccountManager.class),
                () -> services.get(eu.kalafatic.dcode.harness.registry.HarnessRegistry.class),
                () -> services.get(eu.kalafatic.dcode.config.agent.AgentRegistry.class),
                () -> services.get(eu.kalafatic.dcode.config.profile.ProfileRegistry.class));
        api.setChangeSetSupplier(() -> services.get(
                eu.kalafatic.dcode.core.service.ChangeSetService.class));
        api.setVerificationSupplier(() -> services.get(
                eu.kalafatic.dcode.core.service.VerificationService.class));
        api.setEventLogSupplier(() -> services.get(
                eu.kalafatic.dcode.persistence.events.EventLog.class));

        // Graceful shutdown via POST /api/v1/server/shutdown (the thin CLI `stop`).
        api.setShutdownAction(() -> {
            DCodeLog.info("server", "Graceful shutdown requested via API");
            stopHttpServer();
            try {
                context.getBundle(0).stop();
            } catch (Exception e) {
                DCodeLog.warn("server", "Framework stop failed, falling back to System.exit: "
                        + e.getMessage());
                System.exit(0);
            }
        });

        boolean enabled = configuration.getConfiguration().webServerEnabled();
        boolean autoStart = configuration.getConfiguration().webServerAutoStart();
        if (!enabled) {
            state = WebServerState.DISABLED;
            DCodeLog.info("server",
                    "EVO-Code web server disabled (webServer.enabled=false)");
        } else if (!autoStart) {
            state = WebServerState.STOPPED;
            DCodeLog.info("server",
                    "EVO-Code web server auto-start disabled (webServer.autoStart=false); "
                            + "start it via the Web Interface menu or CLI");
        } else {
            lifecycle.registerAction(LifecycleStage.SERVER, "http-server",
                    new DCodeLifecycle.LifecycleAction() {
                        @Override
                        public void start() throws Exception {
                            startHttpServer();
                        }

                        @Override
                        public void stop() {
                            stopHttpServer();
                        }
                    });
        }

        controlRegistration = context.registerService(WebServerControl.class,
                new WebServerControl() {
                    @Override
                    public WebServerState state() {
                        return state;
                    }

                    @Override
                    public boolean isRunning() {
                        return state == WebServerState.RUNNING && server != null;
                    }

                    @Override
                    public void start() {
                        startHttpServer();
                    }

                    @Override
                    public void stop() {
                        stopHttpServer();
                    }

                    @Override
                    public void restart() {
                        stopHttpServer();
                        startHttpServer();
                    }

                    @Override
                    public int port() {
                        return boundPort;
                    }

                    @Override
                    public String url() {
                        return isRunning() ? "http://" + boundAddress + ":" + boundPort + "/"
                                : null;
                    }
                }, null);

        try {
            lifecycle.startThrough(LifecycleStage.SERVER);
            // Re-activation recovery: when this bundle is stopped and started again
            // (update/restart scenarios), the shared lifecycle has already passed the
            // SERVER stage — startThrough() is a no-op for started stages, so the
            // newly registered action never fires. Start the server directly to
            // restore the configured state.
            if (enabled && autoStart && lifecycle.isStarted(LifecycleStage.SERVER)
                    && server == null) {
                startHttpServer();
            }
        } catch (Exception e) {
            DCodeLog.error("server", "Failed to advance lifecycle to SERVER", e);
        }
    }

    private synchronized void startHttpServer() {
        if (!configuration.getConfiguration().webServerEnabled()) {
            throw new IllegalStateException(
                    "Web server disabled (webServer.enabled=false) in dcode-config.json");
        }
        if (server != null) {
            return;
        }
        state = WebServerState.STARTING;
        try {
            int configuredPort = configuration.getConfiguration().httpPort();
            String bindAddress = configuration.getConfiguration().bindAddress();
            int port = configuredPort > 0 ? configuredPort
                    : PortAllocator.allocate(DCodePorts.HTTP,
                            DCodePorts.HTTP_FALLBACK_RANGE);
            boolean conflictFallback = false;
            try {
                server = new DCodeHttpServer(bindAddress, port, api,
                        new eu.kalafatic.dcode.server.StaticContent.BundleEntry(
                                context.getBundle()));
                server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false);
            } catch (java.io.IOException bindFailure) {
                // Configured port occupied: deterministic fallback inside the DCode
                // 58xxx block (never a silent jump to an unrelated port). The fallback
                // is deliberately NOT persisted: a transient process (e.g. a CLI
                // command such as `status`) must not overwrite the configured port,
                // and a primary server should return to its configured port once it
                // is free again.
                if (configuredPort <= 0) {
                    throw bindFailure;
                }
                int fallback = PortAllocator.allocate(DCodePorts.HTTP,
                        DCodePorts.HTTP_FALLBACK_RANGE);
                server = new DCodeHttpServer(bindAddress, fallback, api,
                        new eu.kalafatic.dcode.server.StaticContent.BundleEntry(
                                context.getBundle()));
                server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false);
                port = fallback;
                conflictFallback = true;
                DCodeLog.warn("server", "Configured port " + configuredPort
                        + " is occupied — bound fallback port " + fallback
                        + " (58xxx block)");
            }
            if (configuredPort <= 0) {
                try {
                    configuration.update(
                            configuration.getConfiguration().withHttpPort(port));
                } catch (RuntimeException persistFailure) {
                    DCodeLog.warn("server", "Could not persist the bound port "
                            + port + ": " + persistFailure.getMessage());
                }
            }
            boundPort = port;
            boundAddress = bindAddress;
            state = WebServerState.RUNNING;
            // published for ops tooling and runtime-verification tests
            System.setProperty("dcode.server.port", String.valueOf(port));
            api.setHttpPortActual(port, bindAddress);
            // The actual bound endpoint is the single discovery source for clients
            // (thin CLI). Written only after a successful bind, never on conflict.
            persistEndpoint(bindAddress, port);
            DCodeLog.info("server", "EVO-Code HTTP API listening on "
                    + bindAddress + ":" + port + " ("
                    + (configuredPort > 0 ? "configured" : "allocated") + ")");
        } catch (Exception e) {
            server = null;
            state = WebServerState.FAILED;
            DCodeLog.error("server", "Failed to start EVO-Code HTTP API", e);
            throw e instanceof RuntimeException re ? re
                    : new IllegalStateException("Failed to start EVO-Code HTTP API: "
                            + e.getMessage(), e);
        }
    }

    /**
     * Persists the actually bound endpoint to {@code <dataDir>/server-endpoint.json}
     * so thin clients (the CLI) can discover a server that bound a non-configured
     * (fallback) port. This is separate from {@code dcode-config.json httpPort}, which
     * remains the configured/desired port. Never throws; a failed write only reduces
     * discoverability, it does not break the server.
     */
    private void persistEndpoint(String address, int port) {
        try {
            java.nio.file.Path dataDir = configuration.getConfiguration().dataDir();
            java.nio.file.Path endpointFile = dataDir.resolve("server-endpoint.json");
            com.fasterxml.jackson.databind.ObjectMapper mapper =
                    new com.fasterxml.jackson.databind.ObjectMapper();
            com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
            node.put("host", address == null ? "127.0.0.1" : address);
            node.put("port", port);
            node.put("pid", ProcessHandle.current().pid());
            node.put("boundAt", System.currentTimeMillis());
            java.nio.file.Files.createDirectories(dataDir);
            java.nio.file.Files.writeString(endpointFile,
                    mapper.writerWithDefaultPrettyPrinter().writeValueAsString(node));
        } catch (Exception e) {
            DCodeLog.warn("server", "Could not persist server endpoint: " + e.getMessage());
        }
    }

    private synchronized void stopHttpServer() {
        if (server == null) {
            state = configuration.getConfiguration().webServerEnabled()
                    ? WebServerState.STOPPED : WebServerState.DISABLED;
            return;
        }
        state = WebServerState.STOPPING;
        server.stop();
        server = null;
        boundPort = -1;
        System.clearProperty("dcode.server.port");
        state = configuration.getConfiguration().webServerEnabled()
                ? WebServerState.STOPPED : WebServerState.DISABLED;
        DCodeLog.info("server", "EVO-Code HTTP API stopped");
    }

    @Override
    public void stop(BundleContext context) {
        if (controlRegistration != null) {
            controlRegistration.unregister();
            controlRegistration = null;
        }
        if (server != null) {
            server.stop();
            server = null;
        }
        // The published port must never outlive the server: consumers (the UI web
        // resolution, ops tooling) must not see a stale dcode.server.port after the
        // server bundle stops.
        System.clearProperty("dcode.server.port");
        if (services != null) {
            services.close();
        }
        if (gate != null) {
            gate.close();
        }
    }
}
