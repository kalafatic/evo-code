package eu.kalafatic.dcode.repositories.git;

import java.nio.file.Path;
import java.util.List;

/**
 * DCode Git engine contract: Java-native Git operations on repository working directories.
 *
 * <p>This is the single Git abstraction of DCode. Implementations operate on a repository
 * working directory and never touch global git configuration implicitly; identity is passed
 * explicitly to commit operations. Multi-repository operations are never atomic — callers
 * must track per-repository results (family rule).</p>
 */
public interface GitEngine {

    /** Basic repository state. */
    record RepositoryInfo(
            String branch,
            String remoteUrl,
            boolean clean,
            int commitCount
    ) {
    }

    /** Returns basic repository information. */
    RepositoryInfo info(Path repoDir) throws GitException;

    /** One working-tree change with status and per-file unified diff. */
    record Change(
            String path,
            String oldPath,
            String status,
            String diff
    ) {
    }

    /**
     * Returns per-file working-tree changes (added, modified, removed, missing, renamed,
     * untracked) with their status and unified diff. Rename detection is enabled.
     */
    List<Change> changes(Path repoDir) throws GitException;

    /** Lists local branch names. */
    List<String> branches(Path repoDir) throws GitException;

    /**
     * Returns repository-relative paths of all working-tree changes: added, modified,
     * removed, missing and untracked files.
     */
    List<String> changedFiles(Path repoDir) throws GitException;

    /** Returns a unified diff of the tracked working-tree changes (untracked excluded). */
    String diff(Path repoDir) throws GitException;

    /** Returns up to {@code maxCount} commit summaries, newest first. */
    List<String> log(Path repoDir, int maxCount) throws GitException;

    /** Returns the HEAD commit hash, or {@code null} when the repository has no commits. */
    String headCommit(Path repoDir) throws GitException;

    /** Returns true when the working tree has any changes (including untracked files). */
    boolean hasChanges(Path repoDir) throws GitException;

    /** Initializes a new repository in the given directory. */
    void init(Path repoDir) throws GitException;

    /** Clones a remote (URL or local path) into a non-existing target directory. */
    void clone(String remoteUrl, Path targetDir) throws GitException;

    /** Stages all working-tree changes (including deletions) and commits them. */
    String commit(Path repoDir, String message, String authorName, String authorEmail)
            throws GitException;

    /** Fetches and fast-forwards the current branch from its upstream. */
    void pull(Path repoDir) throws GitException;

    /** Pushes the current branch to its upstream. */
    void push(Path repoDir) throws GitException;

    /** Checks out an existing branch. */
    void checkoutBranch(Path repoDir, String branch) throws GitException;

    /** Creates a local branch tracking {@code origin/<branch>} and checks it out. */
    void checkoutRemoteBranch(Path repoDir, String branch) throws GitException;
}
