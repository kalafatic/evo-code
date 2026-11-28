package eu.kalafatic.dcode.persistence.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.ChangeSet;
import eu.kalafatic.dcode.core.model.Message;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.service.ChangeSetService;
import eu.kalafatic.dcode.core.service.ExecutionAuditService;
import eu.kalafatic.dcode.core.service.InvalidStateTransitionException;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.VerificationService;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SQLite-backed session service (WP5.7). Replaces the in-memory implementation without
 * changing the {@link SessionService} contract; sessions, messages and transitions
 * survive process restarts.
 *
 * <p>Persistence completes before events are published. Mutations are serialized per
 * session.</p>
 */
public final class SqliteSessionService implements SessionService {

    private final SqliteDatabaseManager database;
    private final EventBus eventBus;
    private final WorkspaceService workspaceService;
    private final ChangeSetService changeSetService;
    private final VerificationService verificationService;
    private final ReviewService reviewService;
    private final ExecutionAuditService executionAuditService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final ConcurrentHashMap<String, Object> sessionLocks = new ConcurrentHashMap<>();

    public SqliteSessionService(SqliteDatabaseManager database, EventBus eventBus,
                                WorkspaceService workspaceService) {
        this(database, eventBus, workspaceService, null, null, null, null);
    }

    public SqliteSessionService(SqliteDatabaseManager database, EventBus eventBus,
                                WorkspaceService workspaceService,
                                ChangeSetService changeSetService,
                                VerificationService verificationService,
                                ReviewService reviewService,
                                ExecutionAuditService executionAuditService) {
        this.database = Objects.requireNonNull(database, "database");
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.workspaceService = Objects.requireNonNull(workspaceService, "workspaceService");
        this.changeSetService = changeSetService;
        this.verificationService = verificationService;
        this.reviewService = reviewService;
        this.executionAuditService = executionAuditService;
    }

    @Override
    public Session createSession(CreateSessionRequest request) {
        Objects.requireNonNull(request, "request");
        String workspaceId = Objects.requireNonNull(request.workspaceId(), "workspaceId");
        workspaceService.getWorkspace(workspaceId)
                .orElseThrow(() -> new IllegalArgumentException("Unknown workspace: " + workspaceId));
        String id = UUID.randomUUID().toString();
        Session session = Session.builder()
                .id(id)
                .title(request.title() != null && !request.title().isBlank()
                        ? request.title() : "Untitled session")
                .workspaceId(workspaceId)
                .state(SessionState.CREATED)
                .providerId(request.providerId())
                .modelId(request.modelId())
                .agentRole(request.agentRole() != null ? request.agentRole() : AgentRole.MAIN)
                .metadata(request.metadata())
                .build();
        String metadataJson = toJson(session.getMetadata());
        database.inTransaction(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "INSERT INTO sessions(id, title, workspace_id, state, provider_id, model_id,"
                            + " agent_role, created_at, updated_at, metadata_json) "
                            + "VALUES (?,?,?,?,?,?,?,?,?,?)")) {
                statement.setString(1, session.getId());
                statement.setString(2, session.getTitle());
                statement.setString(3, session.getWorkspaceId());
                statement.setString(4, session.getState().name());
                statement.setString(5, session.getProviderId());
                statement.setString(6, session.getModelId());
                statement.setString(7, session.getAgentRole().name());
                statement.setString(8, session.getCreatedAt().toString());
                statement.setString(9, session.getUpdatedAt().toString());
                statement.setString(10, metadataJson);
                statement.executeUpdate();
            }
        });
        eventBus.publish(StandardEvent.of(DCodeEventType.SESSION_CREATED, id,
                "{\"sessionId\":\"" + id + "\"}"));
        return session;
    }

    @Override
    public Optional<Session> getSession(String sessionId) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM sessions WHERE id = ?")) {
                statement.setString(1, sessionId);
                try (ResultSet rs = statement.executeQuery()) {
                    return rs.next() ? Optional.of(mapSession(rs)) : Optional.<Session>empty();
                }
            }
        });
    }

    @Override
    public List<Session> listSessions(String workspaceId) {
        return database.query(connection -> {
            List<Session> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM sessions WHERE workspace_id = ? ORDER BY created_at DESC")) {
                statement.setString(1, workspaceId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(mapSession(rs));
                    }
                }
            }
            return result;
        });
    }

    @Override
    public Session transition(String sessionId, SessionState target) {
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            Session current = getSession(sessionId)
                    .orElseThrow(() -> new IllegalArgumentException("Unknown session: " + sessionId));
            if (!SessionState.isValidTransition(current.getState(), target)) {
                throw new InvalidStateTransitionException("Invalid session transition "
                        + current.getState() + " -> " + target + " for session " + sessionId);
            }
            Instant updatedAt = Instant.now();
            database.inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE sessions SET state = ?, updated_at = ? WHERE id = ?")) {
                    statement.setString(1, target.name());
                    statement.setString(2, updatedAt.toString());
                    statement.setString(3, sessionId);
                    statement.executeUpdate();
                }
            });
            eventBus.publish(StandardEvent.of(DCodeEventType.SESSION_STATE_CHANGED, sessionId,
                    "{\"from\":\"" + current.getState() + "\",\"to\":\"" + target + "\"}"));
            return getSession(sessionId).orElseThrow();
        }
    }

    @Override
    public Message appendMessage(String sessionId, MessageRole role, String content) {
        Objects.requireNonNull(role, "role");
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            if (getSession(sessionId).isEmpty()) {
                throw new IllegalArgumentException("Unknown session: " + sessionId);
            }
            Message message = new Message(UUID.randomUUID().toString(), sessionId, role,
                    content, nextSequence(sessionId), Instant.now());
            database.inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "INSERT INTO messages(id, session_id, role, content, sequence, timestamp)"
                                + " VALUES (?,?,?,?,?,?)")) {
                    statement.setString(1, message.id());
                    statement.setString(2, message.sessionId());
                    statement.setString(3, message.role().name());
                    statement.setString(4, message.content());
                    statement.setLong(5, message.sequence());
                    statement.setString(6, message.timestamp().toString());
                    statement.executeUpdate();
                }
            });
            eventBus.publish(StandardEvent.of(DCodeEventType.MESSAGE_APPENDED, sessionId,
                    "{\"messageId\":\"" + message.id() + "\",\"role\":\"" + role + "\"}"));
            return message;
        }
    }

    @Override
    public Session renameSession(String sessionId, String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("Session title must not be blank");
        }
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            getSession(sessionId).orElseThrow(
                    () -> new IllegalArgumentException("Unknown session: " + sessionId));
            Instant updatedAt = Instant.now();
            database.inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "UPDATE sessions SET title = ?, updated_at = ? WHERE id = ?")) {
                    statement.setString(1, newTitle);
                    statement.setString(2, updatedAt.toString());
                    statement.setString(3, sessionId);
                    statement.executeUpdate();
                }
            });
            eventBus.publish(StandardEvent.of(DCodeEventType.SESSION_RENAMED, sessionId,
                    "{\"title\":\"" + escapeJson(newTitle) + "\"}"));
            return getSession(sessionId).orElseThrow();
        }
    }

    @Override
    public void deleteSession(String sessionId) {
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            if (getSession(sessionId).isEmpty()) {
                return; // idempotent
            }
            database.inTransaction(connection -> {
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM messages WHERE session_id = ?")) {
                    statement.setString(1, sessionId);
                    statement.executeUpdate();
                }
                try (PreparedStatement statement = connection.prepareStatement(
                        "DELETE FROM sessions WHERE id = ?")) {
                    statement.setString(1, sessionId);
                    statement.executeUpdate();
                }
            });
            eventBus.publish(StandardEvent.of(DCodeEventType.SESSION_DELETED, sessionId,
                    "{\"sessionId\":\"" + escapeJson(sessionId) + "\"}"));
        }
    }

    @Override
    public Session evaluateCompletion(String sessionId) {
        if (changeSetService == null || verificationService == null || reviewService == null) {
            return getSession(sessionId).orElseThrow(
                    () -> new IllegalArgumentException("Unknown session: " + sessionId));
        }
        Object lock = sessionLocks.computeIfAbsent(sessionId, k -> new Object());
        synchronized (lock) {
            Session session = getSession(sessionId).orElseThrow(
                    () -> new IllegalArgumentException("Unknown session: " + sessionId));
            if (session.getState() != SessionState.WAITING_FOR_APPROVAL) {
                return session;
            }
            List<ChangeSet> changeSets = changeSetService.listBySession(sessionId);
            if (changeSets.isEmpty()) {
                return session;
            }
            // Latest version per repository must be APPROVED and verified.
            for (ChangeSet latest : latestPerRepository(changeSets)) {
                boolean approved = reviewService.reviewsOf(sessionId).stream()
                        .filter(r -> latest.id().equals(r.changeSetId()))
                        .anyMatch(r -> r.status() == ReviewService.Status.APPROVED);
                if (!approved) {
                    return session;
                }
                Optional<VerificationService.Verification> verification =
                        verificationService.latestForChangeSet(latest.id());
                boolean verified = verification.isPresent()
                        && (verification.get().verdict() == VerificationService.Verdict.VERIFIED
                        || verification.get().verdict()
                                == VerificationService.Verdict.VERIFIED_WITH_LIMITATIONS);
                if (!verified) {
                    return session;
                }
            }
            return transition(sessionId, SessionState.COMPLETED);
        }
    }

    private static List<ChangeSet> latestPerRepository(List<ChangeSet> changeSets) {
        Map<String, ChangeSet> latest = new HashMap<>();
        for (ChangeSet changeSet : changeSets) {
            ChangeSet current = latest.get(changeSet.repositoryId());
            if (current == null || changeSet.version() > current.version()) {
                latest.put(changeSet.repositoryId(), changeSet);
            }
        }
        return new ArrayList<>(latest.values());
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    @Override
    public List<Message> listMessages(String sessionId) {
        return database.query(connection -> {
            List<Message> result = new ArrayList<>();
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT * FROM messages WHERE session_id = ? ORDER BY sequence ASC")) {
                statement.setString(1, sessionId);
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(new Message(rs.getString("id"),
                                rs.getString("session_id"),
                                MessageRole.valueOf(rs.getString("role")),
                                rs.getString("content"),
                                rs.getLong("sequence"),
                                Instant.parse(rs.getString("timestamp"))));
                    }
                }
            }
            return result;
        });
    }

    private long nextSequence(String sessionId) {
        return database.query(connection -> {
            try (PreparedStatement statement = connection.prepareStatement(
                    "SELECT COALESCE(MAX(sequence), 0) + 1 FROM messages WHERE session_id = ?")) {
                statement.setString(1, sessionId);
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }

    private static Session mapSession(ResultSet rs) throws java.sql.SQLException {
        return Session.builder()
                .id(rs.getString("id"))
                .title(rs.getString("title"))
                .workspaceId(rs.getString("workspace_id"))
                .state(SessionState.valueOf(rs.getString("state")))
                .createdAt(Instant.parse(rs.getString("created_at")))
                .updatedAt(Instant.parse(rs.getString("updated_at")))
                .providerId(rs.getString("provider_id"))
                .modelId(rs.getString("model_id"))
                .agentRole(rs.getString("agent_role") != null
                        ? AgentRole.valueOf(rs.getString("agent_role")) : AgentRole.MAIN)
                .metadata(parseMetadata(rs.getString("metadata_json")))
                .build();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseMetadata(String metadataJson) {
        if (metadataJson == null || metadataJson.isBlank()) {
            return new HashMap<>();
        }
        try {
            Map<String, String> parsed = new ObjectMapper().readValue(metadataJson, Map.class);
            return parsed != null ? new HashMap<>(parsed) : new HashMap<>();
        } catch (Exception e) {
            return new HashMap<>();
        }
    }

    private String toJson(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
