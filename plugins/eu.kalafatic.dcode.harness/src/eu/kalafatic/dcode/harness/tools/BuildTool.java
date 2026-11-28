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
 * Build verification of a repository using its detected build technology
 * (Maven wrapper, Maven, Gradle wrapper, Gradle). Executed inside the repository working
 * directory with a bounded timeout via the shared {@link ExecutionService}.
 *
 * <p>The default Maven goal is {@code verify} (not {@code clean}), so a default build
 * genuinely compiles and verifies the project; {@code clean} is used only when explicitly
 * requested. A build reports success only when the underlying command exited 0.</p>
 */
public final class BuildTool implements Tool {

    private static final int DEFAULT_TIMEOUT_SECONDS = 900;
    private static final int TAIL_CHARS = 4000;

    private final ExecutionService executions;

    public BuildTool() {
        this(new ExecutionService());
    }

    public BuildTool(ExecutionService executions) {
        this.executions = executions;
    }

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("run_build",
                "Build a workspace repository with its detected build technology (Maven/"
                        + "Gradle wrapper preferred) and report success or failure with log output.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"},"
                        + "\"goal\":{\"type\":\"string\",\"description\":\"build goal, default 'verify' for Maven, 'build' for Gradle\"}},"
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
        String customGoal = ToolArgs.string(arguments, "goal", "");
        List<String> command = detectBuildCommand(repoDir, customGoal);
        if (command == null) {
            return ToolResult.failure("No supported build technology detected in " + repoName
                    + " (looked for mvnw, pom.xml, gradlew, build.gradle)");
        }

        context.log("run_build: " + String.join(" ", command) + " (" + repoName + ")");
        try {
            ExecutionService.Execution execution = executions.start(
                    new ExecutionService.ExecutionSpec(
                            command, repoDir, Map.of(), DEFAULT_TIMEOUT_SECONDS,
                            context.session().getId(),
                            context.agentRunId(),
                            context.toolCallId(),
                            "run_build: " + repoName,
                            context.cancellationRequested()));
            ExecutionService.AwaitResult result = execution.await();
            String out = execution.output();
            if (result.timedOut()) {
                return ToolResult.failure("Build timed out after " + DEFAULT_TIMEOUT_SECONDS
                        + "s in " + repoName);
            }
            if (result.cancelled()) {
                return ToolResult.failure("Build cancelled in " + repoName);
            }
            if (result.exitCode() != 0) {
                return ToolResult.failure("Build FAILED in " + repoName + " (exit "
                        + result.exitCode() + ")\n" + out);
            }
            return ToolResult.ok("Build SUCCESS in " + repoName + "\n" + tail(out, TAIL_CHARS));
        } catch (IOException e) {
            return ToolResult.failure("Failed to start build: " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolResult.failure("Build interrupted in " + repoName);
        }
    }

    private static List<String> detectBuildCommand(Path repoDir, String customGoal) {
        if (Files.isExecutable(repoDir.resolve("mvnw"))) {
            return List.of("./mvnw", "-B",
                    customGoal.isBlank() ? "verify" : customGoal);
        }
        if (Files.exists(repoDir.resolve("pom.xml"))) {
            return List.of("mvn", "-B",
                    customGoal.isBlank() ? "verify" : customGoal);
        }
        if (Files.isExecutable(repoDir.resolve("gradlew"))) {
            return List.of("./gradlew", customGoal.isBlank() ? "build" : customGoal);
        }
        if (Files.exists(repoDir.resolve("build.gradle"))
                || Files.exists(repoDir.resolve("build.gradle.kts"))) {
            return List.of("gradle", customGoal.isBlank() ? "build" : customGoal);
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
