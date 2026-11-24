package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.config.git.GitAccountManager;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.util.Map;

/**
 * Commits all changes of a workspace repository. Enforced by the repository access
 * policy: read-only repositories are rejected before any git operation runs.
 */
public final class GitCommitTool implements Tool {

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("git_commit",
                "Commit all working-tree changes of a workspace repository. Requires a "
                        + "writable repository and an explicit commit message.",
                "{\"type\":\"object\",\"properties\":{\"repository\":{\"type\":\"string\"},"
                        + "\"message\":{\"type\":\"string\"},\"account\":{\"type\":\"string\","
                        + "\"description\":\"git account name for commit identity, optional\"}},"
                        + "\"required\":[\"repository\",\"message\"]}",
                false, ToolScope.REPOSITORY);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String repoName = ToolArgs.string(arguments, "repository", null);
        String message = ToolArgs.string(arguments, "message", null);
        if (repoName == null || message == null || message.isBlank()) {
            return ToolResult.failure("Arguments 'repository' and 'message' are required");
        }
        RepositoryRef repository = GitStatusTool.findRepository(context, repoName);
        if (repository == null) {
            return ToolResult.failure("Unknown repository: " + repoName);
        }
        String authorName = "EVO-Code Agent";
        String authorEmail = "agent@dcode.local";
        String account = ToolArgs.string(arguments, "account", "");
        if (!account.isBlank() && context.gitAccounts() != null) {
            var gitAccount = context.gitAccounts().find(account);
            if (gitAccount.isPresent()) {
                var identity = context.gitAccounts().commitIdentity(account);
                authorName = identity.userName();
                authorEmail = identity.userEmail();
            }
        }
        try {
            String hash = context.repositories().commit(context.workspace(), repository,
                    message, authorName, authorEmail);
            context.log("git_commit: " + repoName + " -> " + hash);
            return ToolResult.ok("Committed " + repoName + " as " + hash);
        } catch (eu.kalafatic.dcode.repositories.service.RepositoryAccessException e) {
            return ToolResult.failure(e.getMessage());
        } catch (Exception e) {
            return ToolResult.failure("Commit failed: " + e.getMessage());
        }
    }
}
