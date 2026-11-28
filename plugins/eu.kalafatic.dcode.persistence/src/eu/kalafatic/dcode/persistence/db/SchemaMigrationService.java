package eu.kalafatic.dcode.persistence.db;

import eu.kalafatic.dcode.core.log.DCodeLog;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Forward-only, transactional schema migrations (family convention). Applied
 * sequentially inside one transaction per version; the {@code schema_version} table is
 * the single source of truth.
 */
public final class SchemaMigrationService {

    private final SqliteDatabaseManager database;

    public SchemaMigrationService(SqliteDatabaseManager database) {
        this.database = database;
    }

    /** Applies all pending migrations. Idempotent. */
    public void migrate() {
        int current = currentVersion();
        for (Migration migration : MIGRATIONS) {
            if (migration.version() <= current) {
                continue;
            }
            DCodeLog.info("persistence", "Applying database migration V" + migration.version());
            final Migration m = migration;
            database.inTransaction(connection -> {
                m.apply(connection);
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO schema_version(version, applied_at) VALUES (?, ?)")) {
                    statement.setInt(1, m.version());
                    statement.setString(2, Instant.now().toString());
                    statement.executeUpdate();
                }
            });
        }
    }

    /** Current schema version (0 for a fresh database). */
    public int currentVersion() {
        return database.query(connection -> {
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE TABLE IF NOT EXISTS schema_version("
                        + "version INTEGER PRIMARY KEY, applied_at TEXT NOT NULL)");
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery(
                         "SELECT MAX(version) FROM schema_version")) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        });
    }

    private interface Migration {
        int version();

        void apply(Connection connection) throws SQLException;
    }

    private static final List<Migration> MIGRATIONS = List.of(
            new Migration() {
                @Override
                public int version() {
                    return 1;
                }

                @Override
                public void apply(Connection connection) throws SQLException {
                    try (Statement s = connection.createStatement()) {
                        s.execute("CREATE TABLE workspaces("
                                + "id TEXT PRIMARY KEY,"
                                + "name TEXT NOT NULL,"
                                + "mode TEXT NOT NULL,"
                                + "root_path TEXT,"
                                + "created_at TEXT NOT NULL)");
                        s.execute("CREATE TABLE repositories("
                                + "id TEXT PRIMARY KEY,"
                                + "workspace_id TEXT NOT NULL REFERENCES workspaces(id) ON DELETE CASCADE,"
                                + "name TEXT NOT NULL,"
                                + "local_path TEXT NOT NULL,"
                                + "remote_url TEXT,"
                                + "branch TEXT,"
                                + "access_policy TEXT NOT NULL,"
                                + "added_at TEXT NOT NULL)");
                        s.execute("CREATE TABLE sessions("
                                + "id TEXT PRIMARY KEY,"
                                + "title TEXT NOT NULL,"
                                + "workspace_id TEXT NOT NULL,"
                                + "state TEXT NOT NULL,"
                                + "provider_id TEXT,"
                                + "model_id TEXT,"
                                + "agent_role TEXT,"
                                + "created_at TEXT NOT NULL,"
                                + "updated_at TEXT NOT NULL,"
                                + "metadata_json TEXT NOT NULL DEFAULT '{}')");
                        s.execute("CREATE TABLE messages("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "role TEXT NOT NULL,"
                                + "content TEXT NOT NULL,"
                                + "sequence INTEGER NOT NULL,"
                                + "timestamp TEXT NOT NULL)");
                        s.execute("CREATE INDEX idx_messages_session ON messages(session_id, sequence)");
                        s.execute("CREATE TABLE tool_calls("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "agent_run_id TEXT,"
                                + "tool_name TEXT NOT NULL,"
                                + "arguments_json TEXT NOT NULL,"
                                + "result_json TEXT,"
                                + "state TEXT NOT NULL,"
                                + "error_message TEXT,"
                                + "started_at TEXT NOT NULL,"
                                + "completed_at TEXT)");
                        s.execute("CREATE INDEX idx_tool_calls_session ON tool_calls(session_id)");
                        s.execute("CREATE TABLE agent_runs("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "role TEXT NOT NULL,"
                                + "provider_id TEXT,"
                                + "model_id TEXT,"
                                + "state TEXT NOT NULL,"
                                + "summary TEXT,"
                                + "started_at TEXT NOT NULL,"
                                + "completed_at TEXT)");
                        s.execute("CREATE TABLE events("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT,"
                                + "type TEXT NOT NULL,"
                                + "sequence INTEGER NOT NULL,"
                                + "timestamp TEXT NOT NULL,"
                                + "data_json TEXT NOT NULL)");
                        s.execute("CREATE INDEX idx_events_session ON events(session_id, sequence)");
                        s.execute("CREATE TABLE change_sets("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "repository_id TEXT NOT NULL,"
                                + "summary TEXT,"
                                + "created_at TEXT NOT NULL)");
                        s.execute("CREATE TABLE change_files("
                                + "change_set_id TEXT NOT NULL REFERENCES change_sets(id) ON DELETE CASCADE,"
                                + "path TEXT NOT NULL,"
                                + "status TEXT NOT NULL)");
                        s.execute("CREATE TABLE reviews("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "change_set_id TEXT NOT NULL,"
                                + "status TEXT NOT NULL,"
                                + "approver_id TEXT,"
                                + "comment TEXT,"
                                + "created_at TEXT NOT NULL,"
                                + "decided_at TEXT)");
                    }
                }
            },
            new Migration() {
                @Override
                public int version() {
                    return 2;
                }

                @Override
                public void apply(Connection connection) throws SQLException {
                    try (Statement s = connection.createStatement()) {
                        s.execute("CREATE TABLE process_executions("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "agent_run_id TEXT,"
                                + "tool_call_id TEXT,"
                                + "command TEXT NOT NULL,"
                                + "cwd TEXT,"
                                + "state TEXT NOT NULL,"
                                + "os_pid INTEGER,"
                                + "start_instant TEXT,"
                                + "exit_code INTEGER,"
                                + "label TEXT,"
                                + "started_at TEXT NOT NULL,"
                                + "ended_at TEXT)");
                        s.execute("CREATE INDEX idx_process_executions_session"
                                + " ON process_executions(session_id, started_at)");
                    }
                }
            },
            new Migration() {
                @Override
                public int version() {
                    return 3;
                }

                @Override
                public void apply(Connection connection) throws SQLException {
                    try (Statement s = connection.createStatement()) {
                        s.execute("ALTER TABLE change_sets ADD COLUMN version INTEGER NOT NULL DEFAULT 0");
                        s.execute("ALTER TABLE change_sets ADD COLUMN predecessor_id TEXT");
                        s.execute("ALTER TABLE change_sets ADD COLUMN agent_run_id TEXT");
                        s.execute("ALTER TABLE change_sets ADD COLUMN state TEXT NOT NULL DEFAULT 'FINALIZED'");
                        s.execute("ALTER TABLE change_files ADD COLUMN old_path TEXT");
                        s.execute("ALTER TABLE change_files ADD COLUMN diff TEXT");
                        s.execute("CREATE TABLE verifications("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "change_set_id TEXT,"
                                + "verdict TEXT NOT NULL,"
                                + "evidence TEXT,"
                                + "created_at TEXT NOT NULL)");
                        s.execute("CREATE INDEX idx_verifications_session"
                                + " ON verifications(session_id, created_at)");
                        s.execute("CREATE INDEX idx_verifications_changeset"
                                + " ON verifications(change_set_id, created_at)");
                        s.execute("CREATE TABLE build_results("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,"
                                + "agent_run_id TEXT,"
                                + "change_set_id TEXT,"
                                + "kind TEXT NOT NULL,"
                                + "command TEXT,"
                                + "cwd TEXT,"
                                + "outcome TEXT NOT NULL,"
                                + "exit_code INTEGER,"
                                + "tests_run INTEGER,"
                                + "tests_failed INTEGER,"
                                + "tests_skipped INTEGER,"
                                + "output_ref TEXT,"
                                + "started_at TEXT NOT NULL,"
                                + "ended_at TEXT)");
                        s.execute("CREATE INDEX idx_build_results_session"
                                + " ON build_results(session_id, started_at)");
                    }
                }
            },
            new Migration() {
                @Override
                public int version() {
                    return 4;
                }

                @Override
                public void apply(Connection connection) throws SQLException {
                    try (Statement s = connection.createStatement()) {
                        s.execute("CREATE TABLE processes("
                                + "id TEXT PRIMARY KEY,"
                                + "session_id TEXT NOT NULL UNIQUE,"
                                + "goal TEXT NOT NULL,"
                                + "state TEXT NOT NULL,"
                                + "understanding TEXT,"
                                + "current_strategy_id TEXT,"
                                + "created_at TEXT NOT NULL,"
                                + "updated_at TEXT NOT NULL)");
                        s.execute("CREATE TABLE strategies("
                                + "id TEXT PRIMARY KEY,"
                                + "process_id TEXT NOT NULL,"
                                + "parent_strategy_id TEXT,"
                                + "name TEXT NOT NULL,"
                                + "rationale TEXT,"
                                + "status TEXT NOT NULL,"
                                + "outcome TEXT,"
                                + "created_at TEXT NOT NULL,"
                                + "updated_at TEXT NOT NULL)");
                        s.execute("CREATE INDEX idx_strategies_process"
                                + " ON strategies(process_id, created_at)");
                        s.execute("CREATE TABLE decisions("
                                + "id TEXT PRIMARY KEY,"
                                + "process_id TEXT NOT NULL,"
                                + "session_id TEXT,"
                                + "kind TEXT NOT NULL,"
                                + "summary TEXT NOT NULL,"
                                + "evidence TEXT,"
                                + "alternatives TEXT,"
                                + "created_at TEXT NOT NULL)");
                        s.execute("CREATE INDEX idx_decisions_process"
                                + " ON decisions(process_id, created_at)");
                    }
                }
            });
}
