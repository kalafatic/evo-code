package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One evolutionary strategy (a possible path toward the goal) with lineage to its parent
 * and a lifecycle status. Failed strategies are retained, never erased.
 */
public record Strategy(
        String id,
        String processId,
        String parentStrategyId,
        String name,
        String rationale,
        String status,
        String outcome,
        Instant createdAt,
        Instant updatedAt
) {
    public Strategy {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(processId, "processId");
        status = status == null || status.isBlank() ? "PROPOSED" : status;
    }

    /** Strategy lifecycle states. */
    public static final String PROPOSED = "PROPOSED";
    public static final String EVALUATED = "EVALUATED";
    public static final String SELECTED = "SELECTED";
    public static final String EXECUTING = "EXECUTING";
    public static final String COMPLETED = "COMPLETED";
    public static final String FAILED = "FAILED";
    public static final String REJECTED = "REJECTED";

    public Strategy with(String newStatus, String newOutcome) {
        return new Strategy(id, processId, parentStrategyId, name, rationale, newStatus,
                newOutcome, createdAt, Instant.now());
    }
}
