package eu.kalafatic.dcode.core.model;

/** Modification status of a file within a change set. */
public enum FileChangeStatus {
    ADDED,
    MODIFIED,
    DELETED,
    RENAMED,
    UNTRACKED
}
