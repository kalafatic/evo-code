package eu.kalafatic.dcode.harness.agent;

import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.AgentRun;
import eu.kalafatic.dcode.core.model.ExecutionState;
import eu.kalafatic.dcode.core.service.ExecutionAuditService;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent engine: agent-run lifecycle management.
 *
 * <p>Runs are recorded from start to terminal state and published on the event bus.
 * Definitions and role configuration stay data; the engine never hardcodes agent roles.</p>
 */
public final class AgentEngine {

    private final EventBus eventBus;
    private final java.util.function.Supplier<ExecutionAuditService> audit;
    private final ConcurrentHashMap<String, AgentRun> runs = new ConcurrentHashMap<>();

    public AgentEngine(EventBus eventBus) {
        this(eventBus, (ExecutionAuditService) null);
    }

    public AgentEngine(EventBus eventBus, ExecutionAuditService audit) {
        this(eventBus, audit == null ? () -> null : () -> audit);
    }

    public AgentEngine(EventBus eventBus,
                       java.util.function.Supplier<ExecutionAuditService> audit) {
        this.eventBus = Objects.requireNonNull(eventBus, "eventBus");
        this.audit = audit == null ? () -> null : audit;
    }

    /** Starts a new agent run for a session. */
    public AgentRun startRun(String sessionId, AgentRole role, String providerId,
                             String modelId) {
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), sessionId,
                Objects.requireNonNull(role, "role"), providerId, modelId,
                ExecutionState.RUNNING, null, Instant.now(), null);
        runs.put(run.id(), run);
        record(run);
        eventBus.publish(StandardEvent.of(DCodeEventType.AGENT_RUN_STARTED, sessionId,
                "{\"runId\":\"" + run.id() + "\",\"role\":\"" + role + "\"}"));
        return run;
    }

    /** Completes a run with a terminal state and optional summary. */
    public AgentRun finishRun(String runId, ExecutionState state, String summary) {
        AgentRun current = require(runId);
        if (!state.isTerminal()) {
            throw new IllegalArgumentException("Terminal state required, got " + state);
        }
        AgentRun finished = new AgentRun(current.id(), current.sessionId(), current.role(),
                current.providerId(), current.modelId(), state, summary,
                current.startedAt(), Instant.now());
        runs.put(runId, finished);
        record(finished);
        eventBus.publish(StandardEvent.of(DCodeEventType.AGENT_RUN_COMPLETED,
                current.sessionId(),
                "{\"runId\":\"" + runId + "\",\"state\":\"" + state + "\"}"));
        return finished;
    }

    public AgentRun require(String runId) {
        AgentRun run = runs.get(runId);
        if (run == null) {
            throw new IllegalArgumentException("Unknown agent run: " + runId);
        }
        return run;
    }

    /** All runs of one session, oldest first. */
    public List<AgentRun> runsOf(String sessionId) {
        List<AgentRun> result = new ArrayList<>();
        for (AgentRun run : runs.values()) {
            if (sessionId.equals(run.sessionId())) {
                result.add(run);
            }
        }
        result.sort((a, b) -> a.startedAt().compareTo(b.startedAt()));
        return result;
    }

    private void record(AgentRun run) {
        ExecutionAuditService service = audit.get();
        if (service != null) {
            try {
                service.recordAgentRun(run);
            } catch (RuntimeException e) {
                // audit persistence is best-effort
            }
        }
    }
}
