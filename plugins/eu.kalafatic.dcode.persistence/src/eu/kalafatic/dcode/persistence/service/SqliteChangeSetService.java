package eu.kalafatic.dcode.persistence.service;

import eu.kalafatic.dcode.core.model.ChangeSet;
import eu.kalafatic.dcode.core.model.DiffHunks;
import eu.kalafatic.dcode.core.model.FileChange;
import eu.kalafatic.dcode.core.model.FileChangeStatus;
import eu.kalafatic.dcode.core.service.ChangeSetService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * SQLite-backed change-set store. Versioning is assigned on persist (version 0 = next);
 * historical versions and their file diffs are immutable and retained.
 */
public final class SqliteChangeSetService implements ChangeSetService {

    private final SqliteDatabaseManager database;

    public SqliteChangeSetService(SqliteDatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public ChangeSet persist(ChangeSet changeSet) {
        String id = changeSet.id() == null || changeSet.id().isBlank()
                ? UUID.randomUUID().toString() : changeSet.id();
        int version = changeSet.version();
        String predecessorId = changeSet.predecessorId();
        if (version <= 0) {
            String[] computed = computeNextVersion(changeSet.sessionId(),
                    changeSet.repositoryId());
            version = Integer.parseInt(computed[1]) + 1;
            predecessorId = computed[0];
        }
        final int resolvedVersion = version;
        final String resolvedPredecessor = predecessorId;
        database.inTransaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "INSERT INTO change_sets(id, session_id, repository_id, summary, created_at,"
                            + " version, predecessor_id, agent_run_id, state) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, id);
                s.setString(2, changeSet.sessionId());
                s.setString(3, changeSet.repositoryId());
                s.setString(4, changeSet.summary());
                s.setString(5, changeSet.createdAt().toString());
                s.setInt(6, resolvedVersion);
                s.setString(7, resolvedPredecessor);
                s.setString(8, changeSet.agentRunId());
                s.setString(9, changeSet.state());
                s.executeUpdate();
            }
            try (PreparedStatement s = connection.prepareStatement(
                    "INSERT INTO change_files(change_set_id, path, status, old_path, diff) "
                            + "VALUES (?,?,?,?,?)")) {
                for (FileChange file : changeSet.files()) {
                    s.setString(1, id);
                    s.setString(2, file.path());
                    s.setString(3, file.status().name());
                    s.setString(4, file.oldPath());
                    s.setString(5, file.diff());
                    s.addBatch();
                }
                s.executeBatch();
            }
        });
        return new ChangeSet(id, changeSet.sessionId(), changeSet.repositoryId(),
                changeSet.summary(), changeSet.files(), changeSet.createdAt(),
                resolvedVersion, resolvedPredecessor, changeSet.agentRunId(), changeSet.state());
    }

    /** Returns {@code [latestId|null, latestVersion]} for a session/repository. */
    private String[] computeNextVersion(String sessionId, String repositoryId) {
        return database.query(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT id, version FROM change_sets WHERE session_id = ? AND repository_id = ?"
                            + " ORDER BY version DESC LIMIT 1")) {
                s.setString(1, sessionId);
                s.setString(2, repositoryId);
                try (ResultSet rs = s.executeQuery()) {
                    if (rs.next()) {
                        return new String[]{rs.getString("id"), String.valueOf(rs.getInt("version"))};
                    }
                    return new String[]{null, "0"};
                }
            }
        });
    }

    @Override
    public Optional<ChangeSet> find(String id) {
        return database.query(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM change_sets WHERE id = ?")) {
                s.setString(1, id);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<ChangeSet>empty();
                    }
                    return Optional.of(mapChangeSet(connection, rs));
                }
            }
        });
    }

    @Override
    public List<ChangeSet> listBySession(String sessionId) {
        return database.query(connection -> {
            List<ChangeSet> result = new ArrayList<>();
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM change_sets WHERE session_id = ? ORDER BY created_at ASC, version ASC")) {
                s.setString(1, sessionId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapChangeSet(connection, rs));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public List<ChangeSet> listBySessionAndRepository(String sessionId, String repositoryId) {
        return database.query(connection -> {
            List<ChangeSet> result = new ArrayList<>();
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM change_sets WHERE session_id = ? AND repository_id = ?"
                            + " ORDER BY version ASC")) {
                s.setString(1, sessionId);
                s.setString(2, repositoryId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapChangeSet(connection, rs));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public Optional<ChangeSet> latest(String sessionId, String repositoryId) {
        List<ChangeSet> all = listBySessionAndRepository(sessionId, repositoryId);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(all.size() - 1));
    }

    private static ChangeSet mapChangeSet(java.sql.Connection connection, ResultSet rs)
            throws java.sql.SQLException {
        String id = rs.getString("id");
        return new ChangeSet(
                id,
                rs.getString("session_id"),
                rs.getString("repository_id"),
                rs.getString("summary"),
                loadFiles(connection, id),
                Instant.parse(rs.getString("created_at")),
                rs.getInt("version"),
                rs.getString("predecessor_id"),
                rs.getString("agent_run_id"),
                rs.getString("state"));
    }

    private static List<FileChange> loadFiles(java.sql.Connection connection, String changeSetId)
            throws java.sql.SQLException {
        List<FileChange> files = new ArrayList<>();
        try (PreparedStatement s = connection.prepareStatement(
                "SELECT * FROM change_files WHERE change_set_id = ? ORDER BY path ASC")) {
            s.setString(1, changeSetId);
            try (ResultSet rs = s.executeQuery()) {
                while (rs.next()) {
                    String diff = rs.getString("diff");
                    files.add(new FileChange(
                            rs.getString("path"),
                            rs.getString("old_path"),
                            FileChangeStatus.valueOf(rs.getString("status")),
                            DiffHunks.parse(diff),
                            diff == null ? "" : diff));
                }
            }
        }
        return files;
    }
}
