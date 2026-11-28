package eu.kalafatic.dcode.core.service;

import eu.kalafatic.dcode.core.model.ChangeSet;

import java.util.List;
import java.util.Optional;

/**
 * Durable change-set store. Change sets are versioned per (session, repository): persisting
 * a change set with version {@code 0} assigns the next version and links the predecessor.
 * Historical versions are immutable and retained.
 */
public interface ChangeSetService {

    /**
     * Persists a change set (and its file changes + diffs). When {@code version} is 0 the
     * next version for the session/repository is assigned and {@code predecessorId} is set
     * to the previous version. Returns the persisted change set with the resolved version.
     */
    ChangeSet persist(ChangeSet changeSet);

    Optional<ChangeSet> find(String id);

    /** All change sets of a session, oldest first. */
    List<ChangeSet> listBySession(String sessionId);

    /** All change sets of a session for one repository, oldest first. */
    List<ChangeSet> listBySessionAndRepository(String sessionId, String repositoryId);

    /** The latest (highest-version) change set for a session/repository, if any. */
    Optional<ChangeSet> latest(String sessionId, String repositoryId);
}
