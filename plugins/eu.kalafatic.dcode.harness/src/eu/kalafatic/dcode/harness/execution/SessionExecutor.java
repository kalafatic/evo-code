package eu.kalafatic.dcode.harness.execution;

import eu.kalafatic.dcode.config.git.GitAccountManager;
import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ProviderException;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.routing.ModelRouter;
import eu.kalafatic.dcode.config.routing.ModelSelection;
import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.ChangeSet;
import eu.kalafatic.dcode.core.model.ExecutionState;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.Process;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.Strategy;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.service.ProcessService;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.VerificationService;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.harness.agent.AgentEngine;
import eu.kalafatic.dcode.harness.change.ChangeManager;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.harness.evaluation.EvaluationEngine;
import eu.kalafatic.dcode.harness.orchestration.ToolCallingAgentLoop;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Session execution engine: submits agent runs for sessions, tracks runtime execution
 * state separately from persisted session state, and enforces cancellation and timeouts.
 *
 * <p>Dependencies are resolved per submission through suppliers so that the durable
 * (SQLite) services are used whenever present, regardless of bundle start order.
 * Long-running work never blocks caller threads.</p>
 *
 * <p>Inference runtime selection follows the explicit chain: per-task override, then
 * per-session selection, then the router default. A missing or disabled runtime fails
 * the task with a clear error — never a silent switch to another runtime.</p>
 */
public final class SessionExecutor {

    private final Supplier<SessionService> sessionSupplier;
    private final Supplier<WorkspaceService> workspaceSupplier;
    private final Supplier<ReviewService> reviewSupplier;
    private final ProviderRegistry providerRegistry;
    private final ModelRouter modelRouter;
    private final RepositoryOperations repositoryOperations;
    private final GitAccountManager gitAccounts;
    private final AgentEngine agentEngine;
    private final ToolCallingAgentLoop agentLoop;
    private final ChangeManager changeManager;
    private final EventBus eventBus;

    private final ExecutorService executor =
            Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "dcode-session-executor");
                t.setDaemon(true);
                return t;
            });
    private final ConcurrentHashMap<String, Future<?>> running = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicBoolean> cancellations = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ModelSelection> taskOverrides = new ConcurrentHashMap<>();
    private Supplier<VerificationService> verificationSupplier = () -> null;
    private Supplier<eu.kalafatic.dcode.core.service.ProcessService> processSupplier =
            () -> null;
    private EvaluationEngine evaluationEngine;
    private int maxReworkCycles = 2;

    /** Injects the durable verification store (optional; resolved lazily). */
    public void setVerificationSupplier(Supplier<VerificationService> supplier) {
        this.verificationSupplier = supplier == null ? () -> null : supplier;
    }

    /**
     * Injects the independent evaluator. When present, a completed implementation is
     * evaluated by a separate model and, on {@code NEEDS_REWORK}, refined through a bounded
     * evolutionary rework loop before the session can complete. When absent, the previous
     * self-certified completion path is preserved (backward compatible).
     */
    public void setEvaluationEngine(EvaluationEngine engine) {
        this.evaluationEngine = engine;
    }

    /** Bounds the number of evolutionary rework cycles (default 2). */
    public void setMaxReworkCycles(int maxReworkCycles) {
        this.maxReworkCycles = Math.max(0, maxReworkCycles);
    }

    /** Injects the durable process-intelligence store (optional; resolved lazily). */
    public void setProcessSupplier(
            Supplier<eu.kalafatic.dcode.core.service.ProcessService> supplier) {
        this.processSupplier = supplier == null ? () -> null : supplier;
    }

    public SessionExecutor(Supplier<SessionService> sessionSupplier,
                           Supplier<WorkspaceService> workspaceSupplier,
                           Supplier<ReviewService> reviewSupplier,
                           ProviderRegistry providerRegistry, ModelRouter modelRouter,
                           RepositoryOperations repositoryOperations,
                           GitAccountManager gitAccounts, AgentEngine agentEngine,
                           ToolCallingAgentLoop agentLoop, ChangeManager changeManager,
                           EventBus eventBus) {
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.reviewSupplier = Objects.requireNonNull(reviewSupplier, "reviewSupplier");
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.modelRouter = Objects.requireNonNull(modelRouter, "modelRouter");
        this.repositoryOperations = Objects.requireNonNull(repositoryOperations,
                "repositoryOperations");
        this.gitAccounts = gitAccounts;
        this.agentEngine = Objects.requireNonNull(agentEngine, "agentEngine");
        this.agentLoop = Objects.requireNonNull(agentLoop, "agentLoop");
        this.changeManager = Objects.requireNonNull(changeManager, "changeManager");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
    }

    /** Convenience constructor for direct (non-OSGi) wiring. */
    public SessionExecutor(SessionService sessionService, WorkspaceService workspaceService,
                           ReviewService reviewService, ProviderRegistry providerRegistry,
                           ModelRouter modelRouter, RepositoryOperations repositoryOperations,
                           GitAccountManager gitAccounts, AgentEngine agentEngine,
                           ToolCallingAgentLoop agentLoop, ChangeManager changeManager,
                           EventBus eventBus) {
        this(() -> sessionService, () -> workspaceService, () -> reviewService,
                providerRegistry, modelRouter, repositoryOperations, gitAccounts, agentEngine,
                agentLoop, changeManager, eventBus);
    }

    /** Submits a task for asynchronous execution in a session. */
    public synchronized void submit(String sessionId, String task) {
        submit(sessionId, task, null, null);
    }

    /**
     * Submits a task with an optional per-task runtime/model override. The override is
     * validated and used for this run only; the session selection and the configured
     * default remain unchanged.
     */
    public synchronized void submit(String sessionId, String task,
                                    String providerIdOverride, String modelIdOverride) {
        if (running.containsKey(sessionId)) {
            throw new IllegalStateException("Session already executing: " + sessionId);
        }
        SessionService sessionService = sessionSupplier.get();
        Session session = sessionService.transition(sessionId, SessionState.RUNNING);
        sessionService.appendMessage(sessionId, MessageRole.USER, task);
        AtomicBoolean cancelled = new AtomicBoolean(false);
        cancellations.put(sessionId, cancelled);
        if (providerIdOverride != null && !providerIdOverride.isBlank()
                || modelIdOverride != null && !modelIdOverride.isBlank()) {
            taskOverrides.put(sessionId, new ModelSelection(providerIdOverride,
                    modelIdOverride));
        }
        Future<?> future = executor.submit(() -> execute(session, task, cancelled));
        running.put(sessionId, future);
    }

    /** Requests cancellation of a running session. Idempotent. */
    public void cancel(String sessionId) {
        AtomicBoolean cancelled = cancellations.get(sessionId);
        if (cancelled != null) {
            cancelled.set(true);
        }
    }

    /** True while a session has runtime execution work. */
    public boolean isExecuting(String sessionId) {
        Future<?> future = running.get(sessionId);
        return future != null && !future.isDone();
    }

    private void execute(Session session, String task, AtomicBoolean cancelled) {
        String sessionId = session.getId();
        SessionService sessionService = sessionSupplier.get();
        WorkspaceService workspaceService = workspaceSupplier.get();
        ReviewService reviewService = reviewSupplier.get();
        DCodeLog.info("execution", "Session " + sessionId + " execution started");
        try {
            Workspace workspace = workspaceService.getWorkspace(session.getWorkspaceId())
                    .orElseThrow(() -> new IllegalStateException("Workspace disappeared: "
                            + session.getWorkspaceId()));

            ModelSelection selection = resolveModel(session);
            validateRuntime(selection, sessionId);

            // Persistent process intelligence: the process is the authoritative
            // (non-conversation) representation of the engineering effort.
            eu.kalafatic.dcode.core.model.Process process = null;
            eu.kalafatic.dcode.core.model.Strategy strategy = null;
            eu.kalafatic.dcode.core.service.ProcessService processService =
                    processSupplier.get();
            if (processService != null) {
                process = processService.ensureProcess(sessionId, task);
                strategy = processService.recordStrategy(process.id(), null,
                        "Initial strategy", "Single-agent execution of: " + task,
                        eu.kalafatic.dcode.core.model.Strategy.EXECUTING);
                processService.updateProcess(sessionId, Process.ACTIVE,
                        "Executing with strategy", strategy.id());
                processService.recordDecision(process.id(), sessionId, "STRATEGY_SELECTED",
                        "Selected initial strategy for the task",
                        "Task submitted: " + task, null);
            }

            var run = agentEngine.startRun(sessionId,
                    session.getAgentRole() != null ? session.getAgentRole() : AgentRole.MAIN,
                    selection.providerId(), selection.modelId());
            Map<String, Object> services = buildServices(run.id());
            var outcome = agentLoop.run(workspace, session, task, selection.providerId(),
                    selection.modelId(), services, cancelled::get);

            if (cancelled.get()) {
                agentEngine.finishRun(run.id(), ExecutionState.CANCELLED, outcome.summary());
                recordProcessOutcome(sessionId, processService, strategy, Process.CANCELLED,
                        Strategy.FAILED, "Execution cancelled", outcome.summary());
                sessionService.transition(sessionId, SessionState.CANCELLED);
                return;
            }
            if (!outcome.success()) {
                agentEngine.finishRun(run.id(), ExecutionState.FAILED, outcome.summary());
                recordProcessOutcome(sessionId, processService, strategy, Process.FAILED,
                        Strategy.FAILED, "Execution failed", outcome.summary());
                sessionService.appendMessage(sessionId, MessageRole.ASSISTANT,
                        "[FAILED] " + outcome.summary());
                sessionService.transition(sessionId, SessionState.FAILED);
                eventBus.publish(StandardEvent.of(DCodeEventType.SYSTEM_ERROR, sessionId,
                        "{\"message\":\"Execution failed (runtime="
                                + escape(selection.providerId()) + ", model="
                                + escape(selection.modelId()) + "): "
                                + escape(outcome.summary()) + "\"}"));
                return;
            }

            var changeSets = changeManager.collectChanges(workspace, sessionId, run.id());
            for (var changeSet : changeSets) {
                reviewService.createReview(sessionId, changeSet.id());
            }
            agentEngine.finishRun(run.id(), ExecutionState.COMPLETED, outcome.summary());
            completeWithEvaluation(session, workspace, task, selection,
                    session.getAgentRole() != null ? session.getAgentRole() : AgentRole.MAIN,
                    changeSets, cancelled, processService, strategy, outcome.summary());
            DCodeLog.info("execution", "Session " + sessionId + " execution phase complete ("
                    + outcome.toolCalls() + " tool calls, " + outcome.iterations()
                    + " iterations)");
        } catch (Exception e) {
            DCodeLog.error("execution", "Session " + sessionId + " execution error", e);
            try {
                agentEngine.runsOf(sessionId).stream()
                        .filter(r -> r.state() == ExecutionState.RUNNING)
                        .forEach(r -> agentEngine.finishRun(r.id(), ExecutionState.FAILED,
                                e.getMessage()));
                sessionService.transition(sessionId, SessionState.FAILED);
                eventBus.publish(StandardEvent.of(DCodeEventType.SYSTEM_ERROR, sessionId,
                        "{\"message\":\"Execution failed: " + escape(e.getMessage())
                                + "\"}"));
            } catch (Exception transitionError) {
                DCodeLog.error("execution", "Failed to mark session " + sessionId
                        + " FAILED", transitionError);
            }
        } finally {
            running.remove(sessionId);
            cancellations.remove(sessionId);
            taskOverrides.remove(sessionId);
        }
    }

    /**
     * Resolves the effective provider/model pair: per-task override first, then the
     * session selection, then the router default. A partial selection (provider without
     * model) keeps the provider and falls back to the router model, preserving the
     * historical selection semantics. The router is consulted only when needed.
     */
    private ModelSelection resolveModel(Session session) {
        ModelSelection override = taskOverrides.get(session.getId());
        String sessionProvider = session.getProviderId();
        String sessionModel = session.getModelId();
        if (override != null && notBlank(override.providerId())) {
            String model = notBlank(override.modelId()) ? override.modelId()
                    : notBlank(sessionModel) ? sessionModel
                    : routeFallback(session).modelId();
            return new ModelSelection(override.providerId(), model);
        }
        if (notBlank(sessionProvider)) {
            String model = notBlank(sessionModel) ? sessionModel
                    : routeFallback(session).modelId();
            return new ModelSelection(sessionProvider, model);
        }
        return routeFallback(session);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private ModelSelection routeFallback(Session session) {
        AgentRole role = session.getAgentRole() != null ? session.getAgentRole()
                : AgentRole.MAIN;
        try {
            return modelRouter.resolve(role);
        } catch (ProviderException routerError) {
            // No explicit model configuration anywhere: resolve through the runtime
            // registry — the default runtime first, then the other enabled runtimes.
            // Models with an unknown or large enough context window are preferred so
            // the coding-agent protocol can actually run; tiny toy models are only
            // chosen when nothing better is registered.
            if (providerRegistry instanceof eu.kalafatic.dcode.config.runtime.RuntimeRegistry runtimes) {
                List<eu.kalafatic.dcode.config.runtime.RuntimeInfo> candidates =
                        new java.util.ArrayList<>();
                runtimes.defaultRuntime().ifPresent(candidates::add);
                runtimes.runtimes().stream()
                        .filter(eu.kalafatic.dcode.config.runtime.RuntimeInfo::enabled)
                        .filter(runtime -> candidates.stream().noneMatch(
                                existing -> existing.id().equals(runtime.id())))
                        .forEach(candidates::add);
                // Pass 1a (all runtimes): a model with a KNOWN context window large
                // enough for the coding-agent protocol — real coding models win over
                // toy/unknown-context models on any runtime.
                for (eu.kalafatic.dcode.config.runtime.RuntimeInfo runtime : candidates) {
                    List<ModelDefinition> models;
                    try {
                        models = runtimes.listModels(runtime.id());
                    } catch (RuntimeException e) {
                        continue; // discovery failed on this runtime: try the next
                    }
                    String registeredDefault = candidates.get(0).id().equals(runtime.id())
                            ? runtimes.defaultModel().orElse("") : "";
                    ModelSelection selection = pickModel(runtime.id(), models,
                            registeredDefault, ContextPreference.KNOWN_USABLE);
                    if (selection != null) {
                        DCodeLog.info("execution", "No configured model; using "
                                + selection.modelId() + " on runtime "
                                + runtime.id());
                        return selection;
                    }
                }
                // Pass 1b (all runtimes): models with an unknown context window are
                // still preferred over known-tiny ones; only reached when no runtime
                // publishes a known usable model.
                for (eu.kalafatic.dcode.config.runtime.RuntimeInfo runtime : candidates) {
                    try {
                        ModelSelection selection = pickModel(runtime.id(),
                                runtimes.listModels(runtime.id()), "",
                                ContextPreference.UNKNOWN_CONTEXT);
                        if (selection != null) {
                            DCodeLog.warn("execution", "No model with a known usable "
                                    + "context window; using " + selection.modelId()
                                    + " on runtime " + runtime.id());
                            return selection;
                        }
                    } catch (RuntimeException e) {
                        // discovery failed on this runtime: try the next
                    }
                }
                // Pass 2: nothing usable found — take any available model so the
                // execution still produces an honest attempt instead of a routing
                // error.
                for (eu.kalafatic.dcode.config.runtime.RuntimeInfo runtime : candidates) {
                    try {
                        ModelSelection selection = pickModel(runtime.id(),
                                runtimes.listModels(runtime.id()), "",
                                ContextPreference.ANY);
                        if (selection != null) {
                            DCodeLog.warn("execution", "Only a minimal-context model is "
                                    + "available (" + selection.modelId() + " on "
                                    + runtime.id() + "); the coding-agent protocol "
                                    + "may not fit its context window");
                            return selection;
                        }
                    } catch (RuntimeException e) {
                        // discovery failed on this runtime: try the next
                    }
                }
            }
            throw routerError;
        }
    }

    /**
     * Context-window preference used when no model is configured anywhere:
     * {@link #KNOWN_USABLE} requires a known context window >= 4096 tokens,
     * {@link #UNKNOWN_CONTEXT} accepts unknown context windows (the model does
     * not publish its size), {@link #ANY} accepts every available model.
     */
    private enum ContextPreference {
        KNOWN_USABLE,
        UNKNOWN_CONTEXT,
        ANY
    }

    /**
     * Picks a model for an unconfigured execution: the registered runtime default
     * model, then the first available model matching the given context preference.
     */
    private static ModelSelection pickModel(String providerId, List<ModelDefinition> models,
                                            String registeredDefault,
                                            ContextPreference preference) {
        if (!registeredDefault.isBlank()) {
            boolean defaultPresent = models.stream()
                    .anyMatch(model -> model.id().equals(registeredDefault));
            if (defaultPresent) {
                return new ModelSelection(providerId, registeredDefault);
            }
        }
        java.util.Optional<ModelDefinition> usable = models.stream()
                .filter(ModelDefinition::available)
                .filter(model -> switch (preference) {
                    case KNOWN_USABLE -> model.contextWindowTokens() >= 4096;
                    case UNKNOWN_CONTEXT -> model.contextWindowTokens() <= 0;
                    case ANY -> true;
                })
                .findFirst();
        return usable.map(model -> new ModelSelection(providerId, model.id()))
                .orElse(null);
    }

    /**
     * Pre-flight validation of the selected runtime/model. Fails fast with a clear
     * message; the selection is never silently replaced by another runtime.
     */
    private void validateRuntime(ModelSelection selection, String sessionId) {
        String providerId = selection.providerId();
        String modelId = selection.modelId();
        if (!providerRegistry.find(providerId).isPresent()) {
            throw new ProviderException("Runtime '" + providerId + "' is not registered");
        }
        if (!providerRegistry.isEnabled(providerId)) {
            throw new ProviderException("Runtime '" + providerId + "' is disabled; "
                    + "enable it or select another runtime");
        }
        List<ModelDefinition> models;
        try {
            models = providerRegistry.listModels(providerId);
        } catch (ProviderException e) {
            // Model discovery unsupported or runtime unreachable: the completion call
            // reports the definitive error.
            DCodeLog.warn("execution", "Model discovery on runtime '" + providerId
                    + "' failed: " + e.getMessage());
            return;
        }
        if (modelId == null || modelId.isBlank()) {
            return;
        }
        if (models.isEmpty()) {
            return;
        }
        boolean known = models.stream().anyMatch(m -> m.id().equals(modelId));
        if (!known) {
            throw new ProviderException("Model '" + modelId + "' is not available on "
                    + "runtime '" + providerId + "' (discovered: "
                    + models.stream().limit(5).map(ModelDefinition::id)
                    .collect(java.util.stream.Collectors.joining(", ")) + ")");
        }
    }

    /** Builds the per-run service map the agent loop needs (reused by rework runs). */
    private Map<String, Object> buildServices(String agentRunId) {
        Map<String, Object> services = new HashMap<>();
        services.put("repositoryOperations", repositoryOperations);
        if (gitAccounts != null) {
            services.put("gitAccounts", gitAccounts);
        }
        services.put("sessionService", sessionSupplier.get());
        services.put("eventBus", eventBus);
        services.put("agentRunId", agentRunId);
        return services;
    }

    /**
     * Independent evaluation + bounded evolutionary rework. With an evaluator wired, a
     * completed implementation is assessed by a separate model; {@code NEEDS_REWORK}
     * records the failed branch, derives a successor strategy, runs a fresh rework agent
     * and re-evaluates (up to {@link #maxReworkCycles} cycles). Without an evaluator the
     * previous self-certified completion is preserved exactly.
     */
    private void completeWithEvaluation(Session session, Workspace workspace, String task,
            ModelSelection selection, AgentRole role, List<ChangeSet> initialChanges,
            AtomicBoolean cancelled,
            eu.kalafatic.dcode.core.service.ProcessService processService,
            eu.kalafatic.dcode.core.model.Strategy initialStrategy, String initialSummary) {
        String sessionId = session.getId();
        SessionService sessionService = sessionSupplier.get();
        ReviewService reviewService = reviewSupplier.get();
        List<ChangeSet> changes = initialChanges;
        eu.kalafatic.dcode.core.model.Strategy strategy = initialStrategy;
        String summary = initialSummary;

        if (evaluationEngine == null) {
            VerificationService verification = verificationSupplier.get();
            if (verification != null) {
                for (var changeSet : changes) {
                    verification.record(sessionId, changeSet.id(),
                            VerificationService.Verdict.VERIFIED_WITH_LIMITATIONS,
                            "Agent run completed; build/test evidence in tool calls and "
                                    + "process executions");
                }
            }
            sessionService.appendMessage(sessionId, MessageRole.ASSISTANT,
                    "Completed: " + summary);
            SessionState next = changes.isEmpty() ? SessionState.COMPLETED
                    : SessionState.WAITING_FOR_APPROVAL;
            recordProcessOutcome(sessionId, processService, strategy,
                    changes.isEmpty() ? Process.COMPLETED : Process.EVALUATING,
                    Strategy.COMPLETED, "Execution completed", summary);
            sessionService.transition(sessionId, next);
            return;
        }

        for (int cycle = 0; cycle <= maxReworkCycles; cycle++) {
            EvaluationEngine.Evaluation evaluation = runEvaluation(session, task, changes,
                    summary, selection);
            if (evaluation.accepted()
                    || evaluation.verdict() == EvaluationEngine.Verdict.INCONCLUSIVE) {
                // INCONCLUSIVE (including an unavailable evaluator model) degrades to a
                // soft acceptance with explicit limitations: the deterministic approval
                // gate remains the backstop. It must never fail otherwise-valid work.
                boolean inconclusive =
                        evaluation.verdict() == EvaluationEngine.Verdict.INCONCLUSIVE;
                recordVerification(sessionId, changes, evaluation);
                recordDecision(processService, sessionId,
                        inconclusive ? "EVALUATION_INCONCLUSIVE" : "EVALUATION_ACCEPTED",
                        evaluation);
                updateStrategy(processService, strategy, Strategy.COMPLETED,
                        evaluation.verdict() + ": " + evaluation.findings());
                completeAndTransition(sessionId, sessionService, changes, summary,
                        processService, strategy);
                return;
            }
            if (evaluation.needsRework() && cycle < maxReworkCycles) {
                updateStrategy(processService, strategy, Strategy.FAILED,
                        "rework required: " + evaluation.findings());
                eu.kalafatic.dcode.core.model.Strategy next = deriveStrategy(processService,
                        strategy, evaluation);
                recordDecision(processService, sessionId, "STRATEGY_ADAPTED", evaluation);
                sessionService.appendMessage(sessionId, MessageRole.SYSTEM,
                        "[Rework " + (cycle + 1) + "] " + evaluation.findings() + " -> "
                                + evaluation.recommendedAction());
                var reworkRun = agentEngine.startRun(sessionId, role,
                        selection.providerId(), selection.modelId());
                var reworkOutcome = agentLoop.run(workspace, session,
                        reworkTask(task, evaluation), selection.providerId(),
                        selection.modelId(), buildServices(reworkRun.id()), cancelled::get);
                if (cancelled.get()) {
                    agentEngine.finishRun(reworkRun.id(), ExecutionState.CANCELLED,
                            reworkOutcome.summary());
                    updateStrategy(processService, next, Strategy.FAILED, "cancelled");
                    sessionService.transition(sessionId, SessionState.CANCELLED);
                    return;
                }
                if (!reworkOutcome.success()) {
                    agentEngine.finishRun(reworkRun.id(), ExecutionState.FAILED,
                            reworkOutcome.summary());
                    updateStrategy(processService, next, Strategy.FAILED,
                            reworkOutcome.summary());
                    sessionService.transition(sessionId, SessionState.FAILED);
                    return;
                }
                agentEngine.finishRun(reworkRun.id(), ExecutionState.COMPLETED,
                        reworkOutcome.summary());
                changes = changeManager.collectChanges(workspace, sessionId, reworkRun.id());
                for (var changeSet : changes) {
                    reviewService.createReview(sessionId, changeSet.id());
                }
                strategy = next;
                summary = reworkOutcome.summary();
                continue;
            }
            // FAIL / BLOCKED / INCONCLUSIVE, or the rework budget is exhausted.
            updateStrategy(processService, strategy, Strategy.FAILED,
                    evaluation.verdict() + ": " + evaluation.findings());
            recordDecision(processService, sessionId, "EVALUATION_REJECTED", evaluation);
            sessionService.appendMessage(sessionId, MessageRole.ASSISTANT,
                    "[EVALUATION " + evaluation.verdict() + "] " + evaluation.findings());
            sessionService.transition(sessionId, SessionState.FAILED);
            eventBus.publish(StandardEvent.of(DCodeEventType.SYSTEM_ERROR, sessionId,
                    "{\"message\":\"Independent evaluation rejected the result ("
                            + evaluation.verdict() + "): " + escape(evaluation.findings())
                            + "\"}"));
            return;
        }
    }

    /** Runs the independent evaluator as its own (traced) EVALUATOR agent run. */
    private EvaluationEngine.Evaluation runEvaluation(Session session, String task,
            List<ChangeSet> changes, String summary, ModelSelection fallback) {
        ModelSelection selection = evaluationEngine.resolveModel(fallback);
        var evalRun = agentEngine.startRun(session.getId(), AgentRole.EVALUATOR,
                selection.providerId(), selection.modelId());
        EvaluationEngine.Evidence evidence = new EvaluationEngine.Evidence(task, summary,
                buildChangeSummary(changes));
        EvaluationEngine.Evaluation evaluation = evaluationEngine.evaluate(evidence, fallback);
        agentEngine.finishRun(evalRun.id(), ExecutionState.COMPLETED, evaluation.summary());
        return evaluation;
    }

    /** Maps an independent verdict onto the persistent verification store. */
    private void recordVerification(String sessionId, List<ChangeSet> changes,
                                    EvaluationEngine.Evaluation evaluation) {
        VerificationService verification = verificationSupplier.get();
        if (verification == null) {
            return;
        }
        VerificationService.Verdict verdict = switch (evaluation.verdict()) {
            case PASS -> VerificationService.Verdict.VERIFIED;
            case PASS_WITH_LIMITATIONS, INCONCLUSIVE ->
                    VerificationService.Verdict.VERIFIED_WITH_LIMITATIONS;
            case NEEDS_REWORK -> VerificationService.Verdict.NEEDS_REWORK;
            case FAIL -> VerificationService.Verdict.FAILED;
            case BLOCKED -> VerificationService.Verdict.BLOCKED;
        };
        for (var changeSet : changes) {
            verification.record(sessionId, changeSet.id(), verdict,
                    "Independent evaluation (" + evaluation.confidence() + "): "
                            + evaluation.findings());
        }
    }

    /** Terminal transition shared by the evaluated acceptance path. */
    private void completeAndTransition(String sessionId, SessionService sessionService,
                                       List<ChangeSet> changes, String summary,
                                       eu.kalafatic.dcode.core.service.ProcessService processService,
                                       eu.kalafatic.dcode.core.model.Strategy strategy) {
        sessionService.appendMessage(sessionId, MessageRole.ASSISTANT, "Completed: " + summary);
        SessionState next = changes.isEmpty() ? SessionState.COMPLETED
                : SessionState.WAITING_FOR_APPROVAL;
        if (processService != null) {
            try {
                processService.updateProcess(sessionId,
                        changes.isEmpty() ? Process.COMPLETED : Process.EVALUATING, summary,
                        strategy != null ? strategy.id() : null);
            } catch (RuntimeException e) {
                DCodeLog.warn("execution", "Failed to update process: " + e.getMessage());
            }
        }
        sessionService.transition(sessionId, next);
        DCodeLog.info("execution", "Session " + sessionId + " finished -> " + next);
    }

    private void updateStrategy(eu.kalafatic.dcode.core.service.ProcessService service,
                                eu.kalafatic.dcode.core.model.Strategy strategy,
                                String status, String outcome) {
        if (service == null || strategy == null) {
            return;
        }
        try {
            service.updateStrategy(strategy.id(), status, outcome);
        } catch (RuntimeException e) {
            DCodeLog.warn("execution", "Failed to update strategy: " + e.getMessage());
        }
    }

    private eu.kalafatic.dcode.core.model.Strategy deriveStrategy(
            eu.kalafatic.dcode.core.service.ProcessService service,
            eu.kalafatic.dcode.core.model.Strategy parent,
            EvaluationEngine.Evaluation evaluation) {
        if (service == null || parent == null) {
            return null;
        }
        try {
            return service.recordStrategy(parent.processId(), parent.id(),
                    "Rework after " + evaluation.verdict(), evaluation.recommendedAction(),
                    eu.kalafatic.dcode.core.model.Strategy.EXECUTING);
        } catch (RuntimeException e) {
            DCodeLog.warn("execution", "Failed to derive strategy: " + e.getMessage());
            return null;
        }
    }

    private void recordDecision(eu.kalafatic.dcode.core.service.ProcessService service,
                                String sessionId, String kind,
                                EvaluationEngine.Evaluation evaluation) {
        if (service == null) {
            return;
        }
        try {
            String processId = service.getProcess(sessionId).map(p -> p.id()).orElse(null);
            if (processId != null) {
                service.recordDecision(processId, sessionId, kind,
                        evaluation.verdict() + ": " + evaluation.findings(),
                        "Evaluation evidence: " + evaluation.findings(),
                        evaluation.recommendedAction());
            }
        } catch (RuntimeException e) {
            DCodeLog.warn("execution", "Failed to record decision: " + e.getMessage());
        }
    }

    /** Renders the change sets (with diffs) as the evaluator's evidence. */
    private static String buildChangeSummary(List<ChangeSet> changes) {
        if (changes == null || changes.isEmpty()) {
            return "(no changes recorded)";
        }
        StringBuilder out = new StringBuilder();
        for (ChangeSet changeSet : changes) {
            out.append("- ").append(changeSet.summary()).append('\n');
            for (var file : changeSet.files()) {
                out.append("  * ").append(file.path()).append(" [").append(file.status())
                        .append("]\n");
                if (file.diff() != null && !file.diff().isBlank()) {
                    out.append(file.diff()).append('\n');
                }
            }
        }
        return out.toString();
    }

    /** Frames the rework task from the evaluator's findings. */
    private static String reworkTask(String task, EvaluationEngine.Evaluation evaluation) {
        return "REWORK REQUESTED. Original task:\n" + task
                + "\n\nIndependent evaluation findings:\n" + evaluation.findings()
                + "\n\nRecommended action:\n" + evaluation.recommendedAction()
                + "\n\nRework the implementation to address these findings, then verify.";
    }

    /**
     * Persists the process/strategy outcome and a decision record. Best-effort: a
     * persistence failure must never corrupt the session's execution result.
     */
    private void recordProcessOutcome(String sessionId,
                                      eu.kalafatic.dcode.core.service.ProcessService service,
                                      eu.kalafatic.dcode.core.model.Strategy strategy,
                                      String processState, String strategyState,
                                      String decisionKind, String outcome) {
        if (service == null) {
            return;
        }
        try {
            String strategyId = strategy != null ? strategy.id() : null;
            if (strategy != null) {
                service.updateStrategy(strategy.id(), strategyState, outcome);
            }
            service.updateProcess(sessionId, processState, outcome, strategyId);
            String processId = service.getProcess(sessionId)
                    .map(p -> p.id()).orElse(null);
            service.recordDecision(processId, sessionId, decisionKind, outcome,
                    "Execution evidence: " + outcome, null);
        } catch (RuntimeException e) {
            DCodeLog.warn("execution", "Failed to record process outcome: " + e.getMessage());
        }
    }

    private static String escape(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
