package eu.kalafatic.dcode.persistence.service;

import eu.kalafatic.dcode.core.model.Decision;
import eu.kalafatic.dcode.core.model.Process;
import eu.kalafatic.dcode.core.model.Strategy;
import eu.kalafatic.dcode.core.service.ProcessService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** SQLite-backed process-intelligence store. */
public final class SqliteProcessService implements ProcessService {

    private final SqliteDatabaseManager database;

    public SqliteProcessService(SqliteDatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Process ensureProcess(String sessionId, String goal) {
        Objects.requireNonNull(sessionId, "sessionId");
        Process existing = getProcess(sessionId).orElse(null);
        if (existing != null) {
            return existing;
        }
        Process process = new Process(UUID.randomUUID().toString(), sessionId,
                goal == null ? "" : goal, Process.ACTIVE, null, null,
                Instant.now(), Instant.now());
        database.inTransaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "INSERT INTO processes(id, session_id, goal, state, understanding,"
                            + " current_strategy_id, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?)")) {
                s.setString(1, process.id());
                s.setString(2, process.sessionId());
                s.setString(3, process.goal());
                s.setString(4, process.state());
                s.setString(5, process.understanding());
                s.setString(6, process.currentStrategyId());
                s.setString(7, process.createdAt().toString());
                s.setString(8, process.updatedAt().toString());
                s.executeUpdate();
            }
        });
        return process;
    }

    @Override
    public Optional<Process> getProcess(String sessionId) {
        return database.query(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM processes WHERE session_id = ?")) {
                s.setString(1, sessionId);
                try (ResultSet rs = s.executeQuery()) {
                    if (!rs.next()) {
                        return Optional.<Process>empty();
                    }
                    return Optional.of(new Process(
                            rs.getString("id"),
                            rs.getString("session_id"),
                            rs.getString("goal"),
                            rs.getString("state"),
                            rs.getString("understanding"),
                            rs.getString("current_strategy_id"),
                            Instant.parse(rs.getString("created_at")),
                            Instant.parse(rs.getString("updated_at"))));
                }
            }
        });
    }

    @Override
    public Strategy recordStrategy(String processId, String parentStrategyId, String name,
                                   String rationale, String status) {
        Strategy strategy = new Strategy(UUID.randomUUID().toString(), processId,
                parentStrategyId, name, rationale, status, null, Instant.now(), Instant.now());
        database.inTransaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "INSERT INTO strategies(id, process_id, parent_strategy_id, name, rationale,"
                            + " status, outcome, created_at, updated_at) "
                            + "VALUES (?,?,?,?,?,?,?,?,?)")) {
                s.setString(1, strategy.id());
                s.setString(2, strategy.processId());
                s.setString(3, strategy.parentStrategyId());
                s.setString(4, strategy.name());
                s.setString(5, strategy.rationale());
                s.setString(6, strategy.status());
                s.setString(7, strategy.outcome());
                s.setString(8, strategy.createdAt().toString());
                s.setString(9, strategy.updatedAt().toString());
                s.executeUpdate();
            }
        });
        return strategy;
    }

    @Override
    public Strategy updateStrategy(String strategyId, String status, String outcome) {
        database.inTransaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "UPDATE strategies SET status = ?, outcome = ?, updated_at = ? WHERE id = ?")) {
                s.setString(1, status);
                s.setString(2, outcome);
                s.setString(3, Instant.now().toString());
                s.setString(4, strategyId);
                s.executeUpdate();
            }
        });
        return database.query(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM strategies WHERE id = ?")) {
                s.setString(1, strategyId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? mapStrategy(rs) : null;
                }
            }
        });
    }

    @Override
    public List<Strategy> strategiesOf(String processId) {
        return database.query(connection -> {
            List<Strategy> result = new ArrayList<>();
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM strategies WHERE process_id = ? ORDER BY created_at ASC")) {
                s.setString(1, processId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapStrategy(rs));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public Decision recordDecision(String processId, String sessionId, String kind,
                                   String summary, String evidence, String alternatives) {
        Decision decision = new Decision(UUID.randomUUID().toString(), processId, sessionId,
                kind, summary, evidence, alternatives, Instant.now());
        database.inTransaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "INSERT INTO decisions(id, process_id, session_id, kind, summary, evidence,"
                            + " alternatives, created_at) VALUES (?,?,?,?,?,?,?,?)")) {
                s.setString(1, decision.id());
                s.setString(2, decision.processId());
                s.setString(3, decision.sessionId());
                s.setString(4, decision.kind());
                s.setString(5, decision.summary());
                s.setString(6, decision.evidence());
                s.setString(7, decision.alternatives());
                s.setString(8, decision.createdAt().toString());
                s.executeUpdate();
            }
        });
        return decision;
    }

    @Override
    public List<Decision> decisionsOf(String processId) {
        return database.query(connection -> {
            List<Decision> result = new ArrayList<>();
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM decisions WHERE process_id = ? ORDER BY created_at ASC")) {
                s.setString(1, processId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        result.add(new Decision(
                                rs.getString("id"),
                                rs.getString("process_id"),
                                rs.getString("session_id"),
                                rs.getString("kind"),
                                rs.getString("summary"),
                                rs.getString("evidence"),
                                rs.getString("alternatives"),
                                Instant.parse(rs.getString("created_at"))));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public Process updateProcess(String sessionId, String state, String understanding,
                                 String currentStrategyId) {
        database.inTransaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "UPDATE processes SET state = ?, understanding = ?, current_strategy_id = ?,"
                            + " updated_at = ? WHERE session_id = ?")) {
                s.setString(1, state);
                s.setString(2, understanding);
                s.setString(3, currentStrategyId);
                s.setString(4, Instant.now().toString());
                s.setString(5, sessionId);
                s.executeUpdate();
            }
        });
        return getProcess(sessionId).orElseThrow();
    }

    private static Strategy mapStrategy(ResultSet rs) throws java.sql.SQLException {
        return new Strategy(
                rs.getString("id"),
                rs.getString("process_id"),
                rs.getString("parent_strategy_id"),
                rs.getString("name"),
                rs.getString("rationale"),
                rs.getString("status"),
                rs.getString("outcome"),
                Instant.parse(rs.getString("created_at")),
                Instant.parse(rs.getString("updated_at")));
    }
}
