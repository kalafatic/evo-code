package eu.kalafatic.dcode.harness.tools;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.routing.ModelRouter;
import eu.kalafatic.dcode.config.routing.ModelSelection;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.ExecutionState;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.harness.agent.AgentEngine;
import eu.kalafatic.dcode.harness.orchestration.ToolCallingAgentLoop;
import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Dynamic delegation: runs a bounded sub-agent (a distinct role + model, its own execution
 * identity) through the existing native loop and returns a structured result. This is a
 * real multi-agent capability — the specialist does actual work, recorded as its own agent
 * run, and hands a structured result back to the coordinator.
 */
public final class DelegateTaskTool implements Tool {

    private final ProviderRegistry providers;
    private final ModelRouter modelRouter;
    private final ToolCallingAgentLoop loop;
    private final AgentEngine agentEngine;
    private final EventBus eventBus;
    private final Supplier<SessionService> sessionSupplier;
    private final ObjectMapper mapper = new ObjectMapper();

    public DelegateTaskTool(ProviderRegistry providers, ModelRouter modelRouter,
                            ToolCallingAgentLoop loop, AgentEngine agentEngine,
                            EventBus eventBus, Supplier<SessionService> sessionSupplier) {
        this.providers = providers;
        this.modelRouter = modelRouter;
        this.loop = loop;
        this.agentEngine = agentEngine;
        this.eventBus = eventBus;
        this.sessionSupplier = sessionSupplier;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("delegate_task",
                "Delegate a bounded engineering subtask to a specialist sub-agent with a "
                        + "distinct role (and optionally a distinct model). Returns a "
                        + "structured result. Roles: ANALYZER, ARCHITECT, PLANNER, REVIEWER, "
                        + "TESTER, DOCUMENTER, MAIN.",
                "{\"type\":\"object\",\"properties\":{"
                        + "\"task\":{\"type\":\"string\",\"description\":\"bounded subtask instructions\"},"
                        + "\"role\":{\"type\":\"string\",\"description\":\"agent role, default ANALYZER\"},"
                        + "\"providerId\":{\"type\":\"string\"},"
                        + "\"modelId\":{\"type\":\"string\"}},"
                        + "\"required\":[\"task\"]}",
                false, ToolScope.WORKSPACE);
    }

    @Override
    public boolean supportsCancellation() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String task = ToolArgs.string(arguments, "task", null);
        if (task == null || task.isBlank()) {
            return ToolResult.failure("Argument 'task' is required");
        }
        AgentRole role = parseRole(ToolArgs.string(arguments, "role", "ANALYZER"));
        String providerId = ToolArgs.string(arguments, "providerId", "");
        String modelId = ToolArgs.string(arguments, "modelId", "");

        ModelSelection selection = resolveModel(role, providerId, modelId);
        String sessionId = context.session().getId();

        var run = agentEngine.startRun(sessionId, role, selection.providerId(),
                selection.modelId());
        Map<String, Object> services = new HashMap<>();
        services.put("repositoryOperations", context.repositories());
        if (context.gitAccounts() != null) {
            services.put("gitAccounts", context.gitAccounts());
        }
        services.put("sessionService", sessionSupplier.get());
        services.put("eventBus", eventBus);
        services.put("agentRunId", run.id());

        ToolCallingAgentLoop.Outcome outcome;
        try {
            outcome = loop.run(context.workspace(), context.session(),
                    "DELEGATED TASK (" + role + "):\n" + task,
                    selection.providerId(), selection.modelId(), services,
                    context.cancellationRequested());
        } catch (RuntimeException e) {
            agentEngine.finishRun(run.id(), ExecutionState.FAILED, e.getMessage());
            return ToolResult.failure("Delegate sub-agent failed: " + e.getMessage());
        }

        agentEngine.finishRun(run.id(),
                outcome.success() ? ExecutionState.COMPLETED : ExecutionState.FAILED,
                outcome.summary());

        ObjectNode result = mapper.createObjectNode();
        result.put("success", outcome.success());
        result.put("role", role.name());
        result.put("runId", run.id());
        result.put("modelId", selection.modelId());
        result.put("summary", outcome.summary() != null ? outcome.summary() : "");
        result.put("toolCalls", outcome.toolCalls());
        result.put("iterations", outcome.iterations());
        if (outcome.error() != null) {
            result.put("error", outcome.error());
        }
        return ToolResult.ok(result.toString());
    }

    private ModelSelection resolveModel(AgentRole role, String providerId, String modelId) {
        if (providerId != null && !providerId.isBlank()) {
            return new ModelSelection(providerId, modelId);
        }
        try {
            return modelRouter.resolve(role);
        } catch (RuntimeException e) {
            return modelRouter.defaultModel();
        }
    }

    private static AgentRole parseRole(String role) {
        try {
            return AgentRole.valueOf(role.trim().toUpperCase());
        } catch (RuntimeException e) {
            return AgentRole.ANALYZER;
        }
    }
}
