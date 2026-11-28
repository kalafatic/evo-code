package eu.kalafatic.dcode.core.model;

import java.util.List;
import java.util.Objects;

/**
 * One changed file of a change set.
 *
 * @param path repository-relative path
 * @param oldPath original path for renames, otherwise null
 * @param status modification status
 * @param hunks parsed unified-diff hunks (never empty for text changes)
 * @param diff full per-file unified diff text, or a snapshot for untracked/added files
 *        (carries enough information to inspect the change after the file changes again)
 */
public record FileChange(
        String path,
        String oldPath,
        FileChangeStatus status,
        List<DiffHunk> hunks,
        String diff
) {
    public FileChange {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(status, "status");
        hunks = hunks == null ? List.of() : List.copyOf(hunks);
        diff = diff == null ? "" : diff;
    }

    /** Backward-compatible constructor without explicit diff text. */
    public FileChange(String path, String oldPath, FileChangeStatus status,
                      List<DiffHunk> hunks) {
        this(path, oldPath, status, hunks, "");
    }
}
