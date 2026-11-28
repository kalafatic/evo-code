package eu.kalafatic.dcode.tests.persistence;

import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.AgentRun;
import eu.kalafatic.dcode.core.model.ExecutionState;
import eu.kalafatic.dcode.core.model.ProcessExecution;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.ToolCall;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.persistence.db.SchemaMigrationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;
import eu.kalafatic.dcode.persistence.service.SqliteExecutionAuditService;
import eu.kalafatic.dcode.persistence.service.SqliteSessionService;
import eu.kalafatic.dcode.persistence.service.SqliteWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies the execution audit trail (agent runs, tool calls, process executions) is
 * durably recorded and survives a database reopen.
 */
class ExecutionAuditPersistenceTest {

    @TempDir
    Path temp;

    private SqliteDatabaseManager database;
    private SqliteExecutionAuditService audit;
    private String sessionId;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabaseManager(temp.resolve("audit.db"));
        new SchemaMigrationService(database).migrate();
        audit = new SqliteExecutionAuditService(database);

        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        SqliteWorkspaceService workspaces = new SqliteWorkspaceService(database, bus);
        var workspace = workspaces.createWorkspace("audit", WorkspaceMode.LOCAL,
                temp.resolve("ws").toString());
        SessionService sessions = new SqliteSessionService(database, bus, workspaces);
        Session session = sessions.createSession(new SessionService.CreateSessionRequest(
                "audit", workspace.id(), null, null, AgentRole.MAIN, null));
        sessionId = session.getId();
    }

    @Test
    void agentRunToolCallAndProcessSurviveRestart() {
        Instant started = Instant.now();

        audit.recordAgentRun(new AgentRun("run-1", sessionId, AgentRole.MAIN, "ollama",
                "qwen", ExecutionState.RUNNING, null, started, null));
        audit.recordToolCall(new ToolCall("call-1", sessionId, "run-1", "shell",
                "{\"command\":\"echo hi\"}", "", ExecutionState.RUNNING, null, started, null));
        audit.recordProcessExecution(new ProcessExecution("proc-1", sessionId, "run-1",
                "call-1", "echo hi", "/tmp", "RUNNING", 42L, started.toString(), null,
                "shell", started, null));

        // Terminal updates via the same id.
        audit.recordAgentRun(new AgentRun("run-1", sessionId, AgentRole.MAIN, "ollama",
                "qwen", ExecutionState.COMPLETED, "done", started, Instant.now()));
        audit.recordToolCall(new ToolCall("call-1", sessionId, "run-1", "shell",
                "{\"command\":\"echo hi\"}", "{\"success\":true}", ExecutionState.COMPLETED,
                null, started, Instant.now()));
        audit.recordProcessExecution(new ProcessExecution("proc-1", sessionId, "run-1",
                "call-1", "echo hi", "/tmp", "EXITED", 42L, started.toString(), 0, "shell",
                started, Instant.now()));

        // Simulated restart: a new service over the same database.
        SqliteExecutionAuditService reopened = new SqliteExecutionAuditService(database);

        assertEquals(1, reopened.runsOf(sessionId).size());
        assertEquals(ExecutionState.COMPLETED, reopened.runsOf(sessionId).get(0).state());
        assertEquals(1, reopened.toolCallsOf(sessionId).size());
        assertEquals(ExecutionState.COMPLETED, reopened.toolCallsOf(sessionId).get(0).state());
        assertEquals(1, reopened.processExecutionsOf(sessionId).size());
        assertEquals("EXITED", reopened.processExecutionsOf(sessionId).get(0).state());
        assertEquals(0, reopened.processExecutionsOf(sessionId).get(0).exitCode());
    }
}
