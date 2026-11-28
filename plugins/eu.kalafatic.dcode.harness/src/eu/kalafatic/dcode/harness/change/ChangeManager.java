package eu.kalafatic.dcode.harness.change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.core.model.ChangeSet;
import eu.kalafatic.dcode.core.model.DiffHunks;
import eu.kalafatic.dcode.core.model.FileChange;
import eu.kalafatic.dcode.core.model.FileChangeStatus;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.service.ChangeSetService;
import eu.kalafatic.dcode.repositories.git.GitEngine;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Change management: collects and durably persists per-repository change sets after agent
 * execution. Change sets are versioned per (session, repository); rework produces a new
 * version without destroying prior versions. Real per-file status and unified diffs are
 * captured; untracked and added files carry a snapshot so they remain inspectable after
 * the working tree changes again.
 */
public final class ChangeManager {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final Set<String> MUTATING_TOOLS = Set.of("write_file", "edit_file");

    private final RepositoryOperations repositoryOperations;
    private final eu.kalafatic.dcode.harness.tool.ToolEngine toolEngine;
    private final Supplier<ChangeSetService> changeSets;

    public ChangeManager(RepositoryOperations repositoryOperations) {
        this(repositoryOperations, null, () -> null);
    }

    public ChangeManager(RepositoryOperations repositoryOperations,
                         eu.kalafatic.dcode.harness.tool.ToolEngine toolEngine) {
        this(repositoryOperations, toolEngine, () -> null);
    }

    public ChangeManager(RepositoryOperations repositoryOperations,
                         eu.kalafatic.dcode.harness.tool.ToolEngine toolEngine,
                         Supplier<ChangeSetService> changeSets) {
        this.repositoryOperations = repositoryOperations;
        this.toolEngine = toolEngine;
        this.changeSets = changeSets == null ? () -> null : changeSets;
    }

    /** Collects and persists change sets for all session repositories with changes. */
    public List<ChangeSet> collectChanges(Workspace workspace, String sessionId,
                                          String agentRunId) {
        List<ChangeSet> result = new ArrayList<>();
        for (RepositoryRef repository : workspace.repositories()) {
            List<GitEngine.Change> changes;
            try {
                changes = repositoryOperations.changes(workspace, repository);
            } catch (RuntimeException gitError) {
                // Plain-folder workspaces (no .git) or broken repositories: use the
                // session's own tool-call record as the authoritative change list.
                changes = fallbackChanges(sessionId);
            }
            if (changes.isEmpty()) {
                continue;
            }
            List<FileChange> files = new ArrayList<>();
            for (GitEngine.Change change : changes) {
                FileChangeStatus status = change.oldPath() != null
                        ? FileChangeStatus.RENAMED
                        : FileChangeStatus.valueOf(change.status());
                files.add(new FileChange(change.path(), change.oldPath(), status,
                        DiffHunks.parse(change.diff()), change.diff()));
            }
            ChangeSet changeSet = new ChangeSet(UUID.randomUUID().toString(), sessionId,
                    repository.id(), "Changes in repository " + repository.name(), files,
                    java.time.Instant.now(), 0, null, agentRunId, "FINALIZED");
            ChangeSetService service = changeSets.get();
            if (service != null) {
                changeSet = service.persist(changeSet);
            }
            result.add(changeSet);
        }
        return result;
    }

    /** Synthesized per-file changes from the session's own mutating tool calls. */
    private List<GitEngine.Change> fallbackChanges(String sessionId) {
        List<GitEngine.Change> result = new ArrayList<>();
        for (String path : sessionToolFiles(sessionId)) {
            result.add(new GitEngine.Change(path, null, "ADDED",
                    snapshotDiff(path, sessionId)));
        }
        return result;
    }

    /** Files written/modified by the session's own mutating tools (fallback source). */
    public static List<String> sessionToolFiles(
            eu.kalafatic.dcode.harness.tool.ToolEngine engine, String sessionId) {
        if (engine == null) {
            return List.of();
        }
        Set<String> files = new LinkedHashSet<>();
        for (eu.kalafatic.dcode.core.model.ToolCall call : engine.callsOf(sessionId)) {
            if (!MUTATING_TOOLS.contains(call.toolName())) {
                continue;
            }
            try {
                JsonNode arguments = MAPPER.readTree(call.argumentsJson());
                String path = arguments.path("path").asText("");
                if (!path.isBlank()) {
                    files.add(path);
                }
            } catch (Exception ignored) {
                // unparseable arguments: skip this call
            }
        }
        return List.copyOf(files);
    }

    private String snapshotDiff(String path, String sessionId) {
        if (toolEngine == null) {
            return "";
        }
        for (eu.kalafatic.dcode.core.model.ToolCall call : toolEngine.callsOf(sessionId)) {
            if (!"write_file".equals(call.toolName())) {
                continue;
            }
            try {
                JsonNode arguments = MAPPER.readTree(call.argumentsJson());
                if (path.equals(arguments.path("path").asText(""))) {
                    StringBuilder diff = new StringBuilder();
                    diff.append("--- /dev/null\n+++ b/").append(path).append('\n');
                    for (String line : arguments.path("content").asText("").split("\n", -1)) {
                        diff.append('+').append(line).append('\n');
                    }
                    return diff.toString();
                }
            } catch (Exception ignored) {
                // unparseable call: skip
            }
        }
        return "";
    }

    private List<String> sessionToolFiles(String sessionId) {
        return sessionToolFiles(toolEngine, sessionId);
    }
}
