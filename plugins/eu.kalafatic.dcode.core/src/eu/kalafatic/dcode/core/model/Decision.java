package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One structured process decision (why the harness chose a strategy, delegated work,
 * switched models, adapted, etc.) with its evidence and considered alternatives. Part of
 * the persistent decision history, distinct from the execution history.
 */
public record Decision(
        String id,
        String processId,
        String sessionId,
        String kind,
        String summary,
        String evidence,
        String alternatives,
        Instant createdAt
) {
    public Decision {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(processId, "processId");
    }
}
