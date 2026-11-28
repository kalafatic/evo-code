package eu.kalafatic.dcode.core.model;

import java.time.Instant;
import java.util.Objects;

/**
 * One operating-system process executed by the harness, persisted for observability and
 * restart reconciliation. States: QUEUED, RUNNING, EXITED, TIMED_OUT, CANCELLED,
 * INTERRUPTED, PROCESS_OBSERVED.
 */
public record ProcessExecution(
        String id,
        String sessionId,
        String agentRunId,
        String toolCallId,
        String command,
        String cwd,
        String state,
        Long osPid,
        String startInstant,
        Integer exitCode,
        String label,
        Instant startedAt,
        Instant endedAt
) {
    public ProcessExecution {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(command, "command");
        Objects.requireNonNull(state, "state");
    }
}
