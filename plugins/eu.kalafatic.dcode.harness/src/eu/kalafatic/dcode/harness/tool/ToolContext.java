package eu.kalafatic.dcode.harness.tool;

import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.config.git.GitAccountManager;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;

import java.nio.file.Path;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Execution context handed to every tool invocation: session/workspace identity,
 * policy-enforcing repository operations, git accounts, cancellation and log sink, plus
 * optional correlation to the owning tool call and agent run. Tools must never bypass
 * these services to reach the filesystem.
 */
public record ToolContext(
        Session session,
        Workspace workspace,
        Path workspaceRoot,
        RepositoryOperations repositories,
        GitAccountManager gitAccounts,
        BooleanSupplier cancellationRequested,
        Consumer<String> log,
        String toolCallId,
        String agentRunId
) {
    /** Backward-compatible constructor without run/tool correlation. */
    public ToolContext(Session session, Workspace workspace, Path workspaceRoot,
                       RepositoryOperations repositories, GitAccountManager gitAccounts,
                       BooleanSupplier cancellationRequested, Consumer<String> log) {
        this(session, workspace, workspaceRoot, repositories, gitAccounts,
                cancellationRequested, log, null, null);
    }

    public boolean isCancelled() {
        return cancellationRequested.getAsBoolean();
    }

    public void log(String message) {
        if (log != null) {
            log.accept(message);
        }
    }
}
