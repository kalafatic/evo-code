package eu.kalafatic.dcode.core.service;

import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.Message;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Session application service contract.
 *
 * <p>The single authority for session creation, state transitions and message history in
 * DCode. All clients (RCP, Web, HTTP API) use this contract; no client mutates sessions
 * directly. Implementations must validate transitions through the session state machine,
 * persist before publishing events, and serialize concurrent mutations per session.</p>
 */
public interface SessionService {

    Session createSession(CreateSessionRequest request);

    Optional<Session> getSession(String sessionId);

    List<Session> listSessions(String workspaceId);

    /**
     * Performs a validated state transition.
     *
     * @throws InvalidStateTransitionException when the transition is not permitted
     */
    Session transition(String sessionId, SessionState target);

    Message appendMessage(String sessionId, MessageRole role, String content);

    List<Message> listMessages(String sessionId);

    /**
     * Renames a session. The new title must not be blank; a blank title is rejected.
     * Published as {@code SESSION_RENAMED} after persistence.
     *
     * @throws IllegalArgumentException when the session does not exist or the title is blank
     */
    Session renameSession(String sessionId, String newTitle);

    /**
     * Deletes a session together with its message history. Idempotent for unknown ids.
     * Published as {@code SESSION_DELETED} after persistence.
     */
    void deleteSession(String sessionId);

    /**
     * Single authoritative completion decision. Re-evaluates a WAITING_FOR_APPROVAL
     * session against persisted evidence (change sets, reviews, verifications) and
     * transitions it to COMPLETED only when satisfied. The default implementation is a
     * no-op for in-memory fallbacks; the durable implementation overrides it.
     */
    default Session evaluateCompletion(String sessionId) {
        return getSession(sessionId).orElseThrow(
                () -> new IllegalArgumentException("Unknown session: " + sessionId));
    }

    /** Request payload for session creation. */
    record CreateSessionRequest(
            String title,
            String workspaceId,
            String providerId,
            String modelId,
            AgentRole agentRole,
            Map<String, String> metadata
    ) {
        public CreateSessionRequest {
            metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        }
    }
}
