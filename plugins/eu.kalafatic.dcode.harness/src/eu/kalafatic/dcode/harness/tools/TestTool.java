package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.harness.process.ExecutionService;
import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Test execution of a repository. Java projects use Maven {@code test}; other build
 * technologies fall back to the build tool's test task. Runs through the shared
 * {@link ExecutionService} so it is cancellable and its output is bounded.
 */
public final class TestTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 900;
    private static final int TAIL_CHARS = 4000;

    private final ExecutionService executions;

    public TestTool() {
        this(new ExecutionService());
    }

    public TestTool(ExecutionService executions) {
        this.executions = executions;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("run_tests",
                "Run the test suite of a workspace repository and report success or failure.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"}},"
                        + "\"required\":[\"repository\"]}",
                false, ToolScope.REPOSITORY);
    }

    @Override
    public boolean supportsCancellation() {
        return true;
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String repoName = ToolArgs.string(arguments, "repository", null);
        if (repoName == null) {
            return ToolResult.failure("Argument 'repository' is required");
        }
        RepositoryRef repository = GitStatusTool.findRepository(context, repoName);
        if (repository == null) {
            return ToolResult.failure("Unknown repository: " + repoName);
        }
        Path repoDir = context.repositories().resolveDir(context.workspace(), repository);
        List<String> command = detectTestCommand(repoDir);
        if (command == null) {
            return ToolResult.failure("No supported test technology detected in " + repoName);
        }

        context.log("run_tests: " + String.join(" ", command) + " (" + repoName + ")");
        try {
            ExecutionService.Execution execution = executions.start(
                    new ExecutionService.ExecutionSpec(
                            command, repoDir, Map.of(), DEFAULT_TIMEOUT_SECONDS,
                            context.session().getId(),
                            context.agentRunId(),
                            context.toolCallId(),
                            "run_tests: " + repoName,
                            context.cancellationRequested()));
            ExecutionService.AwaitResult result = execution.await();
            String out = execution.output();
            if (result.timedOut()) {
                return ToolResult.failure("Tests timed out after " + DEFAULT_TIMEOUT_SECONDS
                        + "s in " + repoName);
            }
            if (result.cancelled()) {
                return ToolResult.failure("Tests cancelled in " + repoName);
            }
            if (result.exitCode() != 0) {
                return ToolResult.failure("Tests FAILED in " + repoName + " (exit "
                        + result.exitCode() + ")\n" + out);
            }
            return ToolResult.ok("Tests PASSED in " + repoName + "\n" + tail(out, TAIL_CHARS));
        } catch (IOException e) {
            return ToolResult.failure("Failed to start tests: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Tests interrupted in " + repoName);
        }
    }

    private static List<String> detectTestCommand(Path repoDir) {
        if (Files.isExecutable(repoDir.resolve("mvnw"))) {
            return List.of("./mvnw", "-B", "test");
        }
        if (Files.exists(repoDir.resolve("pom.xml"))) {
            return List.of("mvn", "-B", "test");
        }
        if (Files.isExecutable(repoDir.resolve("gradlew"))) {
            return List.of("./gradlew", "test");
        }
        if (Files.exists(repoDir.resolve("build.gradle"))
                || Files.exists(repoDir.resolve("build.gradle.kts"))) {
            return List.of("gradle", "test");
        }
        return null;
    }

    private static String tail(String text, int maxChars) {
        if (text.length() <= maxChars) {
            return text;
        }
        return "...\n" + text.substring(text.length() - maxChars);
    }
}
