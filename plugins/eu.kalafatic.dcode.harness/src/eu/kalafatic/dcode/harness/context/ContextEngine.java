package eu.kalafatic.dcode.harness.context;

import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;

import java.util.List;

/**
 * Context engine: bounded, relevance-aware context construction for agent runs.
 *
 * <p>Context includes the task, the available tool catalog, a per-repository state
 * summary (branch, cleanliness, changed files) and the conversation history. Repository
 * contents are never dumped wholesale; tools provide on-demand access.</p>
 */
public final class ContextEngine {

    private static final int MAX_HISTORY_MESSAGES = 40;

    private final RepositoryOperations repositoryOperations;

    public ContextEngine(RepositoryOperations repositoryOperations) {
        this.repositoryOperations = repositoryOperations;
    }

    /** Builds the system prompt for one agent run. */
    public String buildSystemPrompt(Workspace workspace, Session session, String task,
                                    List<ToolDefinition> tools) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are EVO-Code, a native AI coding agent of the Darwin family. ");
        prompt.append("You work on a development task inside a workspace containing one or "
                + "more Git repositories.\n\n");
        prompt.append("## Task\n").append(task).append("\n\n");

        prompt.append("## Tool protocol\n");
        prompt.append("Respond with exactly one JSON object per message.\n");
        prompt.append("- To use a tool: {\"tool\": \"<tool name>\", \"arguments\": {<tool args>}}\n");
        prompt.append("- To finish: {\"final\": \"<summary of what was done, what remains, "
                + "verification results>\"}\n");
        prompt.append("Never produce anything other than these JSON objects. After each tool "
                + "call you receive the tool output and may continue.\n\n");

        prompt.append("## Available tools\n");
        for (ToolDefinition tool : tools) {
            prompt.append("- ").append(tool.name()).append(": ").append(tool.description())
                    .append(" [").append(tool.readOnly() ? "read-only" : "mutating")
                    .append(", scope=").append(tool.scope()).append(']');
            java.util.List<String> parameterNames = parameterNames(tool);
            if (!parameterNames.isEmpty()) {
                prompt.append(" args: ").append(String.join(", ", parameterNames));
            }
            prompt.append('\n');
        }
        prompt.append('\n');

        prompt.append("## Workspace state\n");
        prompt.append("workspace: ").append(workspace.name()).append('\n');
        for (RepositoryRef repository : workspace.repositories()) {
            prompt.append(repositorySummary(workspace, repository)).append('\n');
        }
        prompt.append('\n');

        prompt.append("## Rules\n");
        prompt.append("- All file paths are workspace-relative. Never access files outside "
                + "the workspace.\n");
        prompt.append("- Read-only repositories must never be modified.\n");
        prompt.append("- Verify your changes: run the build and tests where available, and "
                + "report real results in your final message.\n");
        prompt.append("- Do not commit or push unless explicitly instructed.\n");
        prompt.append("- Do not invent facts about files you did not read.\n");
        return prompt.toString();
    }

    /**
     * Extracts the argument names from a tool's JSON-schema parameters block. Agents see
     * the exact argument names (e.g. {@code oldText}/{@code newText}) and stop guessing
     * them from the description.
     */
    private static java.util.List<String> parameterNames(ToolDefinition tool) {
        java.util.List<String> names = new java.util.ArrayList<>();
        try {
            com.fasterxml.jackson.databind.JsonNode schema =
                    new com.fasterxml.jackson.databind.ObjectMapper().readTree(
                            tool.parametersJson());
            com.fasterxml.jackson.databind.JsonNode properties = schema.path("properties");
            if (properties.isObject()) {
                properties.fieldNames().forEachRemaining(names::add);
            }
        } catch (Exception ignored) {
            // Invalid schema: expose nothing rather than misleading the agent.
        }
        return names;
    }

    /** Returns the bounded conversation history as a prompt string. */
    public String historySummary(List<eu.kalafatic.dcode.core.model.Message> messages) {
        if (messages.isEmpty()) {
            return "";
        }
        int from = Math.max(0, messages.size() - MAX_HISTORY_MESSAGES);
        StringBuilder out = new StringBuilder();
        for (eu.kalafatic.dcode.core.model.Message message : messages.subList(from,
                messages.size())) {
            String content = message.content();
            if (content.length() > 2000) {
                content = content.substring(0, 2000) + "...";
            }
            out.append(message.role().name().toLowerCase()).append(": ")
                    .append(content).append('\n');
        }
        return out.toString();
    }

    /**
     * Per-repository summary: bounded state description. Never contains repository file
     * contents.
     */
    public String repositorySummary(Workspace workspace, RepositoryRef repository) {
        try {
            var info = repositoryOperations.info(workspace, repository);
            var changed = repositoryOperations.changedFiles(workspace, repository);
            StringBuilder out = new StringBuilder();
            out.append("- repository: ").append(repository.name());
            out.append(" (branch=").append(info.branch());
            out.append(", clean=").append(info.clean());
            out.append(", commits=").append(info.commitCount());
            out.append(", ").append(repository.accessPolicy()).append(")");
            if (!changed.isEmpty()) {
                out.append(", changed files: ");
                out.append(String.join(", ", changed.subList(0, Math.min(changed.size(), 20))));
            }
            return out.toString();
        } catch (Exception e) {
            return "- repository: " + repository.name() + " (unavailable: " + e.getMessage() + ")";
        }
    }
}
