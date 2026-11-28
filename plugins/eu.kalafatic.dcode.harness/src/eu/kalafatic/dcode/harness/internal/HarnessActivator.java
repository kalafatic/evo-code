package eu.kalafatic.dcode.harness.internal;

import eu.kalafatic.dcode.config.git.GitAccountManager;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.routing.ModelRouter;
import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.service.DynamicServiceLookup;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.core.service.ServiceAvailabilityGate;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.harness.agent.AgentEngine;
import eu.kalafatic.dcode.harness.change.ChangeManager;
import eu.kalafatic.dcode.harness.change.InMemoryReviewService;
import eu.kalafatic.dcode.harness.context.ContextEngine;
import eu.kalafatic.dcode.harness.evaluation.EvaluationEngine;
import eu.kalafatic.dcode.harness.execution.SessionExecutor;
import eu.kalafatic.dcode.harness.orchestration.ToolCallingAgentLoop;
import eu.kalafatic.dcode.harness.process.ExecutionService;
import eu.kalafatic.dcode.harness.session.InMemorySessionService;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.harness.tool.ToolEngine;
import eu.kalafatic.dcode.harness.tool.ToolRegistry;
import eu.kalafatic.dcode.harness.tools.BuildTool;
import eu.kalafatic.dcode.harness.tools.DelegateTaskTool;
import eu.kalafatic.dcode.harness.tools.EditFileTool;
import eu.kalafatic.dcode.harness.tools.GitCommitTool;
import eu.kalafatic.dcode.harness.tools.GitDiffTool;
import eu.kalafatic.dcode.harness.tools.GitStatusTool;
import eu.kalafatic.dcode.harness.tools.ListFilesTool;
import eu.kalafatic.dcode.harness.tools.ReadFileTool;
import eu.kalafatic.dcode.harness.tools.SearchTool;
import eu.kalafatic.dcode.harness.tools.ShellTool;
import eu.kalafatic.dcode.harness.tools.TestTool;
import eu.kalafatic.dcode.harness.tools.WriteFileTool;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.osgi.framework.ServiceRegistration;

import java.util.function.Supplier;

/**
 * Wires the DCode native harness in OSGi (manual wiring, family convention).
 *
 * <p>Session/workspace/review services are resolved dynamically so the durable SQLite
 * implementations (higher service ranking) are used whenever present. Startup retries
 * through a service-availability gate: bundle start order is irrelevant.</p>
 */
public final class HarnessActivator implements BundleActivator {

    private BundleContext context;
    private ServiceRegistration<ToolRegistry> toolRegistryRegistration;
    private ServiceRegistration<SessionService> sessionServiceRegistration;
    private ServiceRegistration<SessionExecutor> sessionExecutorRegistration;
    private ServiceRegistration<ReviewService> reviewServiceRegistration;
    private ServiceRegistration<ToolEngine> toolEngineRegistration;
    private ServiceRegistration<ExecutionService> executionServiceRegistration;
    private ServiceRegistration<eu.kalafatic.dcode.harness.registry.HarnessRegistry>
            harnessRegistryRegistration;
    private DynamicServiceLookup services;
    private ServiceAvailabilityGate gate;
    private InMemorySessionService fallbackSessionService;

    @Override
    public void start(BundleContext context) {
        this.context = context;
        gate = new ServiceAvailabilityGate(context, new Class<?>[]{
                EventBus.class, DCodeLifecycle.class, WorkspaceService.class,
                RepositoryOperations.class, ProviderRegistry.class, ModelRouter.class,
                ConfigurationService.class},
                this::wire);
        gate.runIfAvailable();
    }

    private void wire() {
        EventBus eventBus = context.getService(context.getServiceReference(EventBus.class));
        DCodeLifecycle lifecycle = context.getService(
                context.getServiceReference(DCodeLifecycle.class));
        RepositoryOperations repositoryOperations = context.getService(
                context.getServiceReference(RepositoryOperations.class));
        ProviderRegistry providerRegistry = context.getService(
                context.getServiceReference(ProviderRegistry.class));
        ModelRouter modelRouter = context.getService(
                context.getServiceReference(ModelRouter.class));
        ConfigurationService configurationService = context.getService(
                context.getServiceReference(ConfigurationService.class));
        ServiceReference<GitAccountManager> gitRef =
                context.getServiceReference(GitAccountManager.class);
        GitAccountManager gitAccounts = gitRef != null ? context.getService(gitRef) : null;

        // Fallback session service: registered only when no (durable) implementation
        // exists yet; dynamic lookup prefers the best-ranked service at call time.
        ServiceReference<SessionService> sessionRef =
                context.getServiceReference(SessionService.class);
        services = new DynamicServiceLookup(context);
        fallbackSessionService = new InMemorySessionService(eventBus,
                () -> services.get(WorkspaceService.class));
        if (sessionRef == null) {
            sessionServiceRegistration = context.registerService(SessionService.class,
                    fallbackSessionService, null);
        }

        if (services.get(ReviewService.class) == null) {
            reviewServiceRegistration = context.registerService(ReviewService.class,
                    new InMemoryReviewService(), null);
        }

        Supplier<SessionService> sessionSupplier = () -> {
            SessionService service = services.get(SessionService.class);
            return service != null ? service : fallbackSessionService;
        };
        Supplier<WorkspaceService> workspaceSupplier = () -> services.get(WorkspaceService.class);
        Supplier<ReviewService> reviewSupplier = () -> {
            ReviewService service = services.get(ReviewService.class);
            if (service == null) {
                throw new IllegalStateException("No review service available");
            }
            return service;
        };

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        // One shared execution service: process lifecycle is a single authority and tools
        // are stateless adapters over it (no per-session mutable process state). The audit
        // service is resolved lazily so the persistence bundle's registration order does
        // not matter.
        java.util.function.Supplier<eu.kalafatic.dcode.core.service.ExecutionAuditService>
                auditSupplier = () -> services.get(
                        eu.kalafatic.dcode.core.service.ExecutionAuditService.class);
        ExecutionService executionService = new ExecutionService(
                ExecutionService.DEFAULT_MAX_OUTPUT_CHARS, auditSupplier);
        toolRegistry.register(new ListFilesTool());
        toolRegistry.register(new ReadFileTool());
        toolRegistry.register(new WriteFileTool());
        toolRegistry.register(new EditFileTool());
        toolRegistry.register(new SearchTool());
        toolRegistry.register(new ShellTool(executionService));
        toolRegistry.register(new BuildTool(executionService));
        toolRegistry.register(new TestTool(executionService));
        toolRegistry.register(new GitStatusTool());
        toolRegistry.register(new GitDiffTool());
        toolRegistry.register(new GitCommitTool());

        ToolEngine toolEngine = new ToolEngine(toolRegistry, eventBus, auditSupplier);
        AgentEngine agentEngine = new AgentEngine(eventBus, auditSupplier);
        ContextEngine contextEngine = new ContextEngine(repositoryOperations);
        ToolCallingAgentLoop agentLoop = new ToolCallingAgentLoop(providerRegistry,
                toolEngine, toolRegistry, contextEngine, fallbackSessionService);
        toolRegistry.register(new DelegateTaskTool(providerRegistry, modelRouter, agentLoop,
                agentEngine, eventBus, sessionSupplier));
        ChangeManager changeManager = new ChangeManager(repositoryOperations, toolEngine,
                () -> services.get(
                        eu.kalafatic.dcode.core.service.ChangeSetService.class));
        SessionExecutor sessionExecutor = new SessionExecutor(sessionSupplier,
                workspaceSupplier, reviewSupplier, providerRegistry, modelRouter,
                repositoryOperations, gitAccounts, agentEngine, agentLoop, changeManager,
                eventBus);
        sessionExecutor.setVerificationSupplier(() -> services.get(
                eu.kalafatic.dcode.core.service.VerificationService.class));
        sessionExecutor.setProcessSupplier(() -> services.get(
                eu.kalafatic.dcode.core.service.ProcessService.class));
        sessionExecutor.setEvaluationEngine(new EvaluationEngine(providerRegistry,
                modelRouter));

        toolRegistryRegistration = context.registerService(ToolRegistry.class,
                toolRegistry, null);
        sessionExecutorRegistration = context.registerService(SessionExecutor.class,
                sessionExecutor, null);
        // Expose the single tool engine (audit + events) to API clients such as the
        // web interface; tools are never executed outside this engine.
        toolEngineRegistration = context.registerService(ToolEngine.class,
                toolEngine, null);
        // Expose the process-execution authority for API observability (list/cancel).
        executionServiceRegistration = context.registerService(ExecutionService.class,
                executionService, null);
        harnessRegistryRegistration = context.registerService(
                eu.kalafatic.dcode.harness.registry.HarnessRegistry.class,
                new eu.kalafatic.dcode.harness.registry.DefaultHarnessRegistry(
                        configurationService.getConfiguration().dataDir()), null);

        lifecycle.registerAction(LifecycleStage.HARNESS, "harness-services",
                new DCodeLifecycle.LifecycleAction() {
                    @Override
                    public void start() {
                        DCodeLog.info("harness", "Native coding harness started ("
                                + toolRegistry.tools().size() + " tools)");
                    }

                    @Override
                    public void stop() {
                        DCodeLog.info("harness", "Native coding harness stopped");
                    }
                });
        try {
            lifecycle.startThrough(LifecycleStage.HARNESS);
        } catch (Exception e) {
            DCodeLog.error("harness", "Failed to advance lifecycle to HARNESS", e);
        }
    }

    @Override
    public void stop(BundleContext context) {
        if (toolRegistryRegistration != null) {
            toolRegistryRegistration.unregister();
        }
        if (sessionServiceRegistration != null) {
            sessionServiceRegistration.unregister();
        }
        if (sessionExecutorRegistration != null) {
            sessionExecutorRegistration.unregister();
        }
        if (reviewServiceRegistration != null) {
            reviewServiceRegistration.unregister();
        }
        if (toolEngineRegistration != null) {
            toolEngineRegistration.unregister();
        }
        if (executionServiceRegistration != null) {
            executionServiceRegistration.unregister();
        }
        if (harnessRegistryRegistration != null) {
            harnessRegistryRegistration.unregister();
        }
        if (services != null) {
            services.close();
        }
        if (gate != null) {
            gate.close();
        }
        toolRegistryRegistration = null;
        sessionServiceRegistration = null;
        sessionExecutorRegistration = null;
        reviewServiceRegistration = null;
        toolEngineRegistration = null;
        executionServiceRegistration = null;
        harnessRegistryRegistration = null;
        fallbackSessionService = null;
    }
}
