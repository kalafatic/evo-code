package eu.kalafatic.dcode.persistence.service;

import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.AgentRun;
import eu.kalafatic.dcode.core.model.ExecutionState;
import eu.kalafatic.dcode.core.model.ProcessExecution;
import eu.kalafatic.dcode.core.model.ToolCall;
import eu.kalafatic.dcode.core.service.ExecutionAuditService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * SQLite-backed execution audit trail: agent runs, tool calls and process executions.
 * Records are upserted by stable id (start writes the RUNNING row, terminal writes the
 * completed row), so the lifecycle survives restart.
 */
public final class SqliteExecutionAuditService implements ExecutionAuditService {

    private final SqliteDatabaseManager database;

    public SqliteExecutionAuditService(SqliteDatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public void recordAgentRun(AgentRun run) {
        database.inTransaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "INSERT OR REPLACE INTO agent_runs(id, session_id, role, provider_id,"
                            + " model_id, state, summary, started_at, completed_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, run.id());
                s.setString(2, run.sessionId());
                s.setString(3, run.role().name());
                s.setString(4, run.providerId());
                s.setString(5, run.modelId());
                s.setString(6, run.state().name());
                s.setString(7, run.summary());
                s.setString(8, ts(run.startedAt()));
                s.setString(9, ts(run.completedAt()));
                s.executeUpdate();
            }
        });
    }

    @Override
    public void recordToolCall(ToolCall call) {
        database.inTransaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "INSERT OR REPLACE INTO tool_calls(id, session_id, agent_run_id,"
                            + " tool_name, arguments_json, result_json, state,"
                            + " error_message, started_at, completed_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, call.id());
                s.setString(2, call.sessionId());
                s.setString(3, call.agentRunId());
                s.setString(4, call.toolName());
                s.setString(5, call.argumentsJson());
                s.setString(6, call.resultJson());
                s.setString(7, call.state().name());
                s.setString(8, call.errorMessage());
                s.setString(9, ts(call.startedAt()));
                s.setString(10, ts(call.completedAt()));
                s.executeUpdate();
            }
        });
    }

    @Override
    public void recordProcessExecution(ProcessExecution execution) {
        database.inTransaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "INSERT OR REPLACE INTO process_executions(id, session_id, agent_run_id,"
                            + " tool_call_id, command, cwd, state, os_pid, start_instant,"
                            + " exit_code, label, started_at, ended_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, execution.id());
                s.setString(2, execution.sessionId());
                s.setString(3, execution.agentRunId());
                s.setString(4, execution.toolCallId());
                s.setString(5, execution.command());
                s.setString(6, execution.cwd());
                s.setString(7, execution.state());
                if (execution.osPid() == null) {
                    s.setNull(8, java.sql.Types.BIGINT);
                } else {
                    s.setLong(8, execution.osPid());
                }
                s.setString(9, execution.startInstant());
                if (execution.exitCode() == null) {
                    s.setNull(10, java.sql.Types.INTEGER);
                } else {
                    s.setInt(10, execution.exitCode());
                }
                s.setString(11, execution.label());
                s.setString(12, ts(execution.startedAt()));
                s.setString(13, ts(execution.endedAt()));
                s.executeUpdate();
            }
        });
    }

    @Override
    public List<AgentRun> runsOf(String sessionId) {
        return database.query(connection -> {
            List<AgentRun> result = new ArrayList<>();
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM agent_runs WHERE session_id = ? ORDER BY started_at ASC")) {
                s.setString(1, sessionId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        result.add(new AgentRun(
                                rs.getString("id"),
                                rs.getString("session_id"),
                                AgentRole.valueOf(rs.getString("role")),
                                rs.getString("provider_id"),
                                rs.getString("model_id"),
                                ExecutionState.valueOf(rs.getString("state")),
                                rs.getString("summary"),
                                Instant.parse(rs.getString("started_at")),
                                rs.getString("completed_at") != null
                                        ? Instant.parse(rs.getString("completed_at")) : null));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public List<ToolCall> toolCallsOf(String sessionId) {
        return database.query(connection -> {
            List<ToolCall> result = new ArrayList<>();
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM tool_calls WHERE session_id = ? ORDER BY started_at ASC")) {
                s.setString(1, sessionId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        result.add(new ToolCall(
                                rs.getString("id"),
                                rs.getString("session_id"),
                                rs.getString("agent_run_id"),
                                rs.getString("tool_name"),
                                rs.getString("arguments_json"),
                                rs.getString("result_json"),
                                ExecutionState.valueOf(rs.getString("state")),
                                rs.getString("error_message"),
                                Instant.parse(rs.getString("started_at")),
                                rs.getString("completed_at") != null
                                        ? Instant.parse(rs.getString("completed_at")) : null));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public List<ProcessExecution> processExecutionsOf(String sessionId) {
        return database.query(connection -> {
            List<ProcessExecution> result = new ArrayList<>();
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM process_executions WHERE session_id = ? "
                            + "ORDER BY started_at ASC")) {
                s.setString(1, sessionId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapProcessExecution(rs));
                    }
                }
            }
            return result;
        });
    }

    private static ProcessExecution mapProcessExecution(ResultSet rs)
            throws java.sql.SQLException {
        long pid = rs.getLong("os_pid");
        boolean pidNull = rs.wasNull();
        int exit = rs.getInt("exit_code");
        boolean exitNull = rs.wasNull();
        return new ProcessExecution(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("agent_run_id"),
                rs.getString("tool_call_id"),
                rs.getString("command"),
                rs.getString("cwd"),
                rs.getString("state"),
                pidNull ? null : pid,
                rs.getString("start_instant"),
                exitNull ? null : exit,
                rs.getString("label"),
                Instant.parse(rs.getString("started_at")),
                rs.getString("ended_at") != null ? Instant.parse(rs.getString("ended_at")) : null);
    }

    private static String ts(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
