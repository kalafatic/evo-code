package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * Persistent representation of the engineering process for a session — the authoritative
 * (non-conversation) process-intelligence state: goal, current understanding, current
 * strategy, and lifecycle state. Updated by the execution loop as evidence accumulates.
 */
public record Process(
        String id,
        String sessionId,
        String goal,
        String state,
        String understanding,
        String currentStrategyId,
        Instant createdAt,
        Instant updatedAt
) {
    public Process {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(sessionId, "sessionId");
        state = state == null || state.isBlank() ? "ACTIVE" : state;
    }

    /** Process lifecycle states. */
    public static final String ACTIVE = "ACTIVE";
    public static final String EVALUATING = "EVALUATING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String BLOCKED = "BLOCKED";
    public static final String CANCELLED = "CANCELLED";

    public Process with(String newState, String newUnderstanding, String newStrategyId) {
        return new Process(id, sessionId, goal, newState, newUnderstanding,
                newStrategyId, createdAt, Instant.now());
    }
}
