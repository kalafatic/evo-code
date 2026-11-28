package eu.kalafatic.dcode.repositories.service;

import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.repositories.git.GitEngine;

import java.nio.file.Path;
import java.util.List;

/**
 * Policy-enforcing facade over {@link GitEngine}.
 *
 * <p>Every mutating operation verifies the repository's access policy before execution.
 * Read-only repositories may be inspected but never modified — this is enforced here, not
 * by convention at call sites. Results are reported per repository; there is deliberately
 * no cross-repository atomicity.</p>
 */
public final class RepositoryOperations {

    private final GitEngine engine;
    private final java.util.function.BiFunction<Workspace, RepositoryRef, Path> repositoryDirResolver;

    public RepositoryOperations(GitEngine engine,
                                java.util.function.BiFunction<Workspace, RepositoryRef, Path> repositoryDirResolver) {
        this.engine = engine;
        this.repositoryDirResolver = repositoryDirResolver;
    }

    public Path resolveDir(Workspace workspace, RepositoryRef repository) {
        return repositoryDirResolver.apply(workspace, repository);
    }

    // ---- read operations (allowed on READ_ONLY) ----

    public GitEngine.RepositoryInfo info(Workspace workspace, RepositoryRef repository) {
        return engine.info(resolveDir(workspace, repository));
    }

    public List<String> changedFiles(Workspace workspace, RepositoryRef repository) {
        return engine.changedFiles(resolveDir(workspace, repository));
    }

    /** Per-file working-tree changes with status and unified diff (read-only safe). */
    public List<GitEngine.Change> changes(Workspace workspace, RepositoryRef repository) {
        return engine.changes(resolveDir(workspace, repository));
    }

    public String diff(Workspace workspace, RepositoryRef repository) {
        return engine.diff(resolveDir(workspace, repository));
    }

    public List<String> log(Workspace workspace, RepositoryRef repository, int maxCount) {
        return engine.log(resolveDir(workspace, repository), maxCount);
    }

    public String headCommit(Workspace workspace, RepositoryRef repository) {
        return engine.headCommit(resolveDir(workspace, repository));
    }

    public boolean hasChanges(Workspace workspace, RepositoryRef repository) {
        return engine.hasChanges(resolveDir(workspace, repository));
    }

    public List<String> branches(Workspace workspace, RepositoryRef repository) {
        return engine.branches(resolveDir(workspace, repository));
    }

    // ---- mutating operations (rejected on READ_ONLY) ----

    public String commit(Workspace workspace, RepositoryRef repository, String message,
                         String authorName, String authorEmail) {
        requireWritable(repository);
        return engine.commit(resolveDir(workspace, repository), message, authorName, authorEmail);
    }

    public void pull(Workspace workspace, RepositoryRef repository) {
        requireWritable(repository);
        engine.pull(resolveDir(workspace, repository));
    }

    public void push(Workspace workspace, RepositoryRef repository) {
        requireWritable(repository);
        engine.push(resolveDir(workspace, repository));
    }

    public void checkoutBranch(Workspace workspace, RepositoryRef repository, String branch) {
        requireWritable(repository);
        engine.checkoutBranch(resolveDir(workspace, repository), branch);
    }

    private void requireWritable(RepositoryRef repository) {
        if (repository.isReadOnly()) {
            throw new RepositoryAccessException("Repository '" + repository.name()
                    + "' is READ_ONLY; mutating operations are not permitted");
        }
    }
}
