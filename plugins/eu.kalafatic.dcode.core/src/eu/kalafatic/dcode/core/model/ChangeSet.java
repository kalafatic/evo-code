package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * A reviewable set of changes produced by a session, scoped to one repository.
 * Multi-repository work produces one change set per repository; there is deliberately no
 * cross-repository atomic transaction abstraction.
 *
 * <p>Change sets are versioned: rework creates a new version whose {@code predecessorId}
 * points at the previous version. Historical versions are immutable and retained; review
 * and verification records bind to a specific version.</p>
 */
public record ChangeSet(
        String id,
        String sessionId,
        String repositoryId,
        String summary,
        List<FileChange> files,
        Instant createdAt,
        int version,
        String predecessorId,
        String agentRunId,
        String state
) {
    public ChangeSet {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        Objects.requireNonNull(repositoryId, "repositoryId");
        files = files == null ? List.of() : List.copyOf(files);
        state = state == null || state.isBlank() ? "FINALIZED" : state;
    }

    /** Backward-compatible constructor (unversioned, final). */
    public ChangeSet(String id, String sessionId, String repositoryId, String summary,
                     List<FileChange> files, Instant createdAt) {
        this(id, sessionId, repositoryId, summary, files, createdAt, 0, null, null, "FINALIZED");
    }

    public boolean isEmpty() {
        return files.isEmpty();
    }
}
