package eu.kalafatic.dcode.persistence.internal;

import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.core.service.ServiceAvailabilityGate;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.persistence.config.DurableConfigurationService;
import eu.kalafatic.dcode.persistence.db.SchemaMigrationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;
import eu.kalafatic.dcode.persistence.events.EventLog;
import eu.kalafatic.dcode.persistence.recovery.SessionRecoveryService;
import eu.kalafatic.dcode.persistence.service.SqliteReviewService;
import eu.kalafatic.dcode.persistence.service.SqliteSessionService;
import eu.kalafatic.dcode.persistence.service.SqliteWorkspaceService;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

import java.util.Dictionary;
import java.util.Hashtable;

/**
 * Wires the persistence layer in OSGi: SQLite database, migrations, durable
 * configuration, SQLite-backed session/workspace/review services and restart recovery.
 *
 * <p>SQLite-backed services register with a higher service ranking so consumers prefer
 * them over the in-memory fallbacks of the harness/repositories bundles. Startup
 * retries through a service-availability gate; bundle start order is irrelevant.</p>
 */
public final class PersistenceActivator implements BundleActivator {

    private BundleContext context;
    private ServiceRegistration<SessionService> sessionRegistration;
    private ServiceRegistration<WorkspaceService> workspaceRegistration;
    private ServiceRegistration<ReviewService> reviewRegistration;
    private EventLog eventLog;
    private DurableConfigurationService durableConfig;
    private ServiceRegistration<ConfigurationService> configRegistration;
    private SqliteDatabaseManager database;
    private ServiceAvailabilityGate gate;

    @Override
    public void start(BundleContext context) {
        this.context = context;
        gate = new ServiceAvailabilityGate(context, new Class<?>[]{
                ConfigurationService.class, EventBus.class, DCodeLifecycle.class},
                this::wire);
        gate.runIfAvailable();
    }

    private void wire() {
        ConfigurationService coreConfig = context.getService(
                context.getServiceReference(ConfigurationService.class));
        EventBus eventBus = context.getService(context.getServiceReference(EventBus.class));
        DCodeLifecycle lifecycle = context.getService(
                context.getServiceReference(DCodeLifecycle.class));
        CoreConfiguration configuration = coreConfig.getConfiguration();

        try {
            java.nio.file.Files.createDirectories(configuration.dataDir());
            database = new SqliteDatabaseManager(
                    configuration.dataDir().resolve("dcode.db"));
            new SchemaMigrationService(database).migrate();

            durableConfig = new DurableConfigurationService(configuration.dataDir(),
                    configuration);

            SqliteWorkspaceService workspaceService =
                    new SqliteWorkspaceService(database, eventBus);
            SqliteSessionService sessionService =
                    new SqliteSessionService(database, eventBus, workspaceService);
            SqliteReviewService reviewService = new SqliteReviewService(database);

            eventLog = new EventLog(database, eventBus);

            Dictionary<String, Object> highRanking = new Hashtable<>();
            highRanking.put("service.ranking", 10);
            sessionRegistration = context.registerService(SessionService.class,
                    sessionService, highRanking);
            workspaceRegistration = context.registerService(WorkspaceService.class,
                    workspaceService, highRanking);
            configRegistration = context.registerService(ConfigurationService.class,
                    durableConfig, highRanking);
            reviewRegistration = context.registerService(ReviewService.class,
                    reviewService, highRanking);

            // Restart recovery runs only for the primary server. A transient/secondary
            // process (a CLI command, or a second DCode instance) that finds the
            // configured HTTP port already occupied must not touch in-flight sessions:
            // the live server owns their execution state, and marking a genuinely
            // RUNNING session FAILED would corrupt active work.
            int httpPort = durableConfig.getConfiguration().httpPort();
            if (httpPort <= 0
                    || eu.kalafatic.dcode.core.port.PortInspector.occupant(httpPort).isEmpty()) {
                new SessionRecoveryService(sessionService, workspaceService).recover();
            } else {
                DCodeLog.info("recovery", "Configured HTTP port " + httpPort
                        + " already in use — skipping restart recovery "
                        + "(not the primary server)");
            }

            lifecycle.registerAction(LifecycleStage.PERSISTENCE, "persistence-services",
                    new DCodeLifecycle.LifecycleAction() {
                        @Override
                        public void start() {
                            DCodeLog.info("persistence", "SQLite persistence started ("
                                    + configuration.dataDir().resolve("dcode.db") + ")");
                        }

                        @Override
                        public void stop() {
                            DCodeLog.info("persistence", "SQLite persistence stopped");
                        }
                    });
            lifecycle.startThrough(LifecycleStage.PERSISTENCE);
        } catch (Exception e) {
            DCodeLog.error("persistence", "Failed to initialize persistence", e);
        }
    }

    @Override
    public void stop(BundleContext context) {
        if (sessionRegistration != null) {
            sessionRegistration.unregister();
        }
        if (workspaceRegistration != null) {
            workspaceRegistration.unregister();
        }
        if (configRegistration != null) {
            configRegistration.unregister();
        }
        if (reviewRegistration != null) {
            reviewRegistration.unregister();
        }
        if (eventLog != null) {
            eventLog.close();
        }
        if (database != null) {
            database.close();
        }
        if (gate != null) {
            gate.close();
        }
        sessionRegistration = null;
        workspaceRegistration = null;
        configRegistration = null;
        reviewRegistration = null;
        eventLog = null;
        durableConfig = null;
        database = null;
    }
}
