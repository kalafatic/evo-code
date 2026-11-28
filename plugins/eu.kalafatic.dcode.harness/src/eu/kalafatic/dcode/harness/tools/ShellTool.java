package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.harness.process.ExecutionService;
import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Controlled shell execution inside the workspace.
 *
 * <p>Stateless: every invocation delegates to the shared {@link ExecutionService}, which
 * owns the process, its output and its cancellation. The working directory is confined to
 * the workspace; the environment is sanitized; execution is time-bounded and cancellable.</p>
 */
public final class ShellTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 300;
    private static final int MAX_OUTPUT_CHARS = 100_000;

    private final ExecutionService executions;

    public ShellTool() {
        this(new ExecutionService());
    }

    public ShellTool(ExecutionService executions) {
        this.executions = executions;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("shell",
                "Execute a shell command inside the workspace (bounded, cancellable). "
                        + "Potentially destructive; results are audited.",
                "{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"},"
                        + "\"cwd\":{\"type\":\"string\",\"description\":\"relative working directory\"},"
                        + "\"timeoutSeconds\":{\"type\":\"integer\"}},\"required\":[\"command\"]}",
                false, ToolScope.WORKSPACE);
    }

    @Override
    public boolean supportsCancellation() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String command = ToolArgs.string(arguments, "command", null);
        if (command == null || command.isBlank()) {
            return ToolResult.failure("Argument 'command' is required");
        }
        int timeoutSeconds = Math.min(ToolArgs.integer(arguments, "timeoutSeconds",
                DEFAULT_TIMEOUT_SECONDS), 3600);
        String cwdArg = ToolArgs.string(arguments, "cwd", "");
        Path cwd;
        try {
            cwd = cwdArg.isBlank()
                    ? context.workspaceRoot()
                    : eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                            context.workspaceRoot(), cwdArg);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }

        context.log("shell: " + command);
        try {
            ExecutionService.Execution execution = executions.start(
                    new ExecutionService.ExecutionSpec(
                            List.of("/bin/bash", "-c", command),
                            cwd, Map.of(), timeoutSeconds,
                            context.session().getId(),
                            context.agentRunId(),
                            context.toolCallId(),
                            "shell: " + command,
                            context.cancellationRequested()));
            ExecutionService.AwaitResult result = execution.await();
            String out = execution.output();
            if (out.length() > MAX_OUTPUT_CHARS) {
                out = out.substring(0, MAX_OUTPUT_CHARS) + "\n... (truncated)";
            }
            if (result.timedOut()) {
                return ToolResult.failure("Command timed out after " + timeoutSeconds
                        + "s: " + command);
            }
            if (result.cancelled()) {
                return ToolResult.failure("Command cancelled: " + command);
            }
            if (result.exitCode() != 0) {
                return ToolResult.failure("Command exited with code " + result.exitCode()
                        + ": " + command + "\n" + out);
            }
            return ToolResult.ok(out.isEmpty() ? "(exit 0)" : out);
        } catch (java.io.IOException e) {
            return ToolResult.failure("Failed to execute command: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Shell execution interrupted");
        }
    }
}
