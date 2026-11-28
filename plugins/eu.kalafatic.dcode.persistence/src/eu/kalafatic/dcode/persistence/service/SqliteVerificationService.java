package eu.kalafatic.dcode.persistence.service;

import eu.kalafatic.dcode.core.service.VerificationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** SQLite-backed verification store. */
public final class SqliteVerificationService implements VerificationService {

    private final SqliteDatabaseManager database;

    public SqliteVerificationService(SqliteDatabaseManager database) {
        this.database = Objects.requireNonNull(database, "database");
    }

    @Override
    public Verification record(String sessionId, String changeSetId, Verdict verdict,
                               String evidence) {
        Verification verification = new Verification(UUID.randomUUID().toString(), sessionId,
                changeSetId, verdict, evidence, Instant.now());
        database.inTransaction(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "INSERT INTO verifications(id, session_id, change_set_id, verdict, evidence,"
                            + " created_at) VALUES (?,?,?,?,?,?)")) {
                s.setString(1, verification.id());
                s.setString(2, verification.sessionId());
                s.setString(3, verification.changeSetId());
                s.setString(4, verification.verdict().name());
                s.setString(5, verification.evidence());
                s.setString(6, verification.createdAt().toString());
                s.executeUpdate();
            }
        });
        return verification;
    }

    @Override
    public Optional<Verification> latestForChangeSet(String changeSetId) {
        return database.query(connection -> {
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM verifications WHERE change_set_id = ? ORDER BY created_at DESC LIMIT 1")) {
                s.setString(1, changeSetId);
                try (ResultSet rs = s.executeQuery()) {
                    return rs.next() ? Optional.of(map(rs)) : Optional.<Verification>empty();
                }
            }
        });
    }

    @Override
    public List<Verification> listBySession(String sessionId) {
        return database.query(connection -> {
            List<Verification> result = new ArrayList<>();
            try (PreparedStatement s = connection.prepareStatement(
                    "SELECT * FROM verifications WHERE session_id = ? ORDER BY created_at ASC")) {
                s.setString(1, sessionId);
                try (ResultSet rs = s.executeQuery()) {
                    while (rs.next()) {
                        result.add(map(rs));
                    }
                }
            }
            return result;
        });
    }

    private static Verification map(ResultSet rs) throws java.sql.SQLException {
        return new Verification(
                rs.getString("id"),
                rs.getString("session_id"),
                rs.getString("change_set_id"),
                Verdict.valueOf(rs.getString("verdict")),
                rs.getString("evidence"),
                Instant.parse(rs.getString("created_at")));
    }
}
