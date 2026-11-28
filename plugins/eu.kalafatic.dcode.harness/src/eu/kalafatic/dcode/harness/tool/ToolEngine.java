package eu.kalafatic.dcode.harness.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.model.ExecutionState;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.ToolCall;
import eu.kalafatic.dcode.core.service.ExecutionAuditService;

import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tool execution engine: permission gate, audit records, events and cancellation.
 *
 * <p>Every invocation is recorded as a {@link ToolCall} (audit/history integration) and
 * published on the event bus. Mutating tools require a RUNNING session. Tool
 * implementations never run outside this engine.</p>
 */
public final class ToolEngine {

    private final ToolRegistry registry;
    private final EventBus eventBus;
    private final java.util.function.Supplier<ExecutionAuditService> audit;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Tool> runningTools = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, ToolCall> calls = new ConcurrentHashMap<>();
    private final AtomicLong sequence = new AtomicLong();

    public ToolEngine(ToolRegistry registry, EventBus eventBus) {
        this(registry, eventBus, (ExecutionAuditService) null);
    }

    public ToolEngine(ToolRegistry registry, EventBus eventBus, ExecutionAuditService audit) {
        this(registry, eventBus, audit == null ? () -> null : () -> audit);
    }

    public ToolEngine(ToolRegistry registry, EventBus eventBus,
                      java.util.function.Supplier<ExecutionAuditService> audit) {
        this.registry = Objects.requireNonNull(registry, "registry");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.audit = audit == null ? () -> null : audit;
    }

    /** Executes one tool invocation synchronously. */
    public ToolCall execute(ToolContext context, String toolName, Map<String, Object> arguments) {
        Objects.requireNonNull(context, "context");
        Tool tool = registry.find(toolName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown tool: " + toolName));
        ToolDefinition definition = tool.definition();
        if (!definition.readOnly() && context.session().getState() != SessionState.RUNNING) {
            return failedCall(context, definition, arguments,
                    "Tool '" + toolName + "' mutates state; the session must be RUNNING "
                            + "(current: " + context.session().getState() + ")");
        }

        String callId = UUID.randomUUID().toString();
        String argsJson = toJson(arguments);
        ToolCall started = new ToolCall(callId, context.session().getId(), null, toolName,
                argsJson, "", ExecutionState.RUNNING, null, Instant.now(), null);
        calls.put(callId, started);
        runningTools.put(context.session().getId(), tool);
        record(started);
        publish(DCodeEventType.TOOL_CALL_STARTED, context, callId, toolName, argsJson);

        ToolResult result;
        try {
            // Inject the tool-call correlation so process-executing tools can record
            // which call (and agent run) spawned them.
            ToolContext effectiveContext = new ToolContext(context.session(),
                    context.workspace(), context.workspaceRoot(), context.repositories(),
                    context.gitAccounts(), context.cancellationRequested(), context.log(),
                    callId, context.agentRunId());
            result = tool.execute(effectiveContext, arguments);
        } catch (Throwable t) {
            result = ToolResult.failure(t.getClass().getSimpleName() + ": " + t.getMessage());
        } finally {
            runningTools.remove(context.session().getId(), tool);
        }

        ToolCall completed = new ToolCall(callId, context.session().getId(), null, toolName,
                argsJson, toJson(result), result.success() ? ExecutionState.COMPLETED
                : ExecutionState.FAILED,
                result.success() ? null : result.error(), started.startedAt(), Instant.now());
        calls.put(callId, completed);
        record(completed);
        publish(DCodeEventType.TOOL_CALL_COMPLETED, context, callId, toolName,
                toJson(result));
        return completed;
    }

    /** Requests cancellation of the tool currently running for a session (best effort). */
    public void cancel(String sessionId) {
        Tool tool = runningTools.get(sessionId);
        if (tool != null && tool.supportsCancellation()) {
            tool.cancel();
        }
    }

    /** All recorded tool calls of one session, oldest first. */
    public List<ToolCall> callsOf(String sessionId) {
        List<ToolCall> result = new ArrayList<>();
        for (ToolCall call : calls.values()) {
            if (sessionId.equals(call.sessionId())) {
                result.add(call);
            }
        }
        result.sort((a, b) -> a.startedAt().compareTo(b.startedAt()));
        return result;
    }

    /**
     * Resolves a user-supplied relative path against a root directory, rejecting absolute
     * paths, parent traversal and symlink escapes. Symlink check applies when the file
     * exists.
     */
    public static Path resolveContained(Path root, String relativePath) {
        if (relativePath == null || relativePath.isBlank()) {
            throw new IllegalArgumentException("Path is required");
        }
        Path path = Path.of(relativePath);
        if (path.isAbsolute()) {
            throw new IllegalArgumentException("Absolute paths are not allowed: " + relativePath);
        }
        Path resolved = root.resolve(path).normalize();
        if (!resolved.startsWith(root.normalize())) {
            throw new IllegalArgumentException("Path escapes the workspace: " + relativePath);
        }
        try {
            if (java.nio.file.Files.exists(resolved)) {
                Path real = resolved.toRealPath();
                Path realRoot = root.toRealPath();
                if (!real.startsWith(realRoot)) {
                    throw new IllegalArgumentException("Path resolves outside the workspace "
                            + "via symlink: " + relativePath);
                }
            }
        } catch (java.io.IOException e) {
            throw new IllegalArgumentException("Cannot verify path: " + relativePath, e);
        }
        return resolved;
    }

    private ToolCall failedCall(ToolContext context, ToolDefinition definition,
                                Map<String, Object> arguments, String error) {
        String callId = UUID.randomUUID().toString();
        ToolCall call = new ToolCall(callId, context.session().getId(), null,
                definition.name(), toJson(arguments), "", ExecutionState.FAILED, error,
                Instant.now(), Instant.now());
        calls.put(callId, call);
        record(call);
        publish(DCodeEventType.TOOL_CALL_COMPLETED, context, callId, definition.name(), "");
        return call;
    }

    private void record(ToolCall call) {
        ExecutionAuditService service = audit.get();
        if (service != null) {
            try {
                service.recordToolCall(call);
            } catch (RuntimeException e) {
                // audit persistence is best-effort; never fail a tool for it
            }
        }
    }

    private void publish(DCodeEventType type, ToolContext context, String callId,
                         String toolName, String data) {
        eventBus.publish(StandardEvent.of(type, context.session().getId(),
                "{\"callId\":\"" + callId + "\",\"tool\":\"" + toolName + "\",\"data\":"
                        + safeJsonString(data) + "}"));
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            return "{}";
        }
    }

    private static String safeJsonString(String value) {
        if (value == null) {
            return "\"\"";
        }
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }
}
