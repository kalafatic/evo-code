package eu.kalafatic.dcode.core.service;

import eu.kalafatic.dcode.core.model.Decision;
import eu.kalafatic.dcode.core.model.Process;
import eu.kalafatic.dcode.core.model.Strategy;

import java.util.List;
import java.util.Optional;

/**
 * Durable process-intelligence store: the persistent (non-conversation) representation of
 * the engineering process — goal, current understanding, strategies, and decisions. The
 * execution loop drives it; it is never reconstructed from chat history alone.
 */
public interface ProcessService {

    /** Returns the process for a session, creating it lazily from the goal. */
    Process ensureProcess(String sessionId, String goal);

    Optional<Process> getProcess(String sessionId);

    /** Records a strategy; when {@code parentId} is null it is a root strategy. */
    Strategy recordStrategy(String processId, String parentStrategyId, String name,
                            String rationale, String status);

    /** Updates a strategy's status/outcome. */
    Strategy updateStrategy(String strategyId, String status, String outcome);

    List<Strategy> strategiesOf(String processId);

    /** Records a decision (reasoning + evidence + alternatives). */
    Decision recordDecision(String processId, String sessionId, String kind, String summary,
                            String evidence, String alternatives);

    List<Decision> decisionsOf(String processId);

    /** Updates the process state/understanding and current strategy. */
    Process updateProcess(String sessionId, String state, String understanding,
                          String currentStrategyId);
}
