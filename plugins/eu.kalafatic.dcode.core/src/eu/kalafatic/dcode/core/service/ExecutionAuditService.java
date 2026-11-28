package eu.kalafatic.dcode.core.service;

import eu.kalafatic.dcode.core.model.AgentRun;
import eu.kalafatic.dcode.core.model.ProcessExecution;
import eu.kalafatic.dcode.core.model.ToolCall;

import java.util.List;

/**
 * Durable audit trail for the execution lifecycle: agent runs, tool calls and process
 * executions. Implemented by the persistence bundle (SQLite); the harness calls it
 * fire-and-forget when available. Persistence is never a correctness dependency of the
 * in-memory execution path.
 */
public interface ExecutionAuditService {

    /** Records or updates one agent run (start and terminal states). */
    void recordAgentRun(AgentRun run);

    /** Records or updates one tool call (start and terminal states). */
    void recordToolCall(ToolCall call);

    /** Records or updates one process execution (start and terminal states). */
    void recordProcessExecution(ProcessExecution execution);

    /** All recorded agent runs of one session, oldest first. */
    List<AgentRun> runsOf(String sessionId);

    /** All recorded tool calls of one session, oldest first. */
    List<ToolCall> toolCallsOf(String sessionId);

    /** All recorded process executions of one session, oldest first. */
    List<ProcessExecution> processExecutionsOf(String sessionId);
}
