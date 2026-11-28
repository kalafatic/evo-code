package eu.kalafatic.dcode.tests.harness;

import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.ExecutionState;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.Strategy;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.harness.agent.AgentEngine;
import eu.kalafatic.dcode.harness.change.ChangeManager;
import eu.kalafatic.dcode.harness.change.InMemoryReviewService;
import eu.kalafatic.dcode.harness.context.ContextEngine;
import eu.kalafatic.dcode.harness.evaluation.EvaluationEngine;
import eu.kalafatic.dcode.harness.execution.SessionExecutor;
import eu.kalafatic.dcode.harness.orchestration.ToolCallingAgentLoop;
import eu.kalafatic.dcode.harness.session.InMemorySessionService;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.harness.tool.ToolEngine;
import eu.kalafatic.dcode.harness.tools.ListFilesTool;
import eu.kalafatic.dcode.harness.tools.WriteFileTool;
import eu.kalafatic.dcode.persistence.db.SchemaMigrationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;
import eu.kalafatic.dcode.persistence.service.SqliteProcessService;
import eu.kalafatic.dcode.repositories.git.JGitEngine;
import eu.kalafatic.dcode.repositories.service.InMemoryWorkspaceService;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;
import eu.kalafatic.dcode.repositories.workspace.RepositoryWorkspaceManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Evolutionary engine behaviour end-to-end (Scenarios C/D/G): the implementing agent
 * produces work; an independent EVALUATOR rejects it (NEEDS_REWORK); the harness records
 * the failed branch, derives a successor strategy, runs a fresh rework agent and
 * re-evaluates to PASS. Failed branches are retained, decisions are recorded, and
 * completion is evidence-based rather than self-certified.
 */
class EvolutionEvaluationTest {

    @TempDir
    Path temp;

    private Path repoDir;
    private Workspace workspace;
    private InMemorySessionService sessionService;
    private AgentEngine agentEngine;
    private SqliteProcessService processService;

    @BeforeEach
    void setUp() throws Exception {
        Path root = temp.resolve("workspace");
        Files.createDirectories(root);
        repoDir = root.resolve("project");
        JGitEngine gitEngine = new JGitEngine();
        gitEngine.init(repoDir);
        Files.writeString(repoDir.resolve("README.md"), "# Project\n");
        gitEngine.commit(repoDir, "seed", "Test", "t@example.org");

        RepositoryWorkspaceManager workspaceManager = new RepositoryWorkspaceManager(gitEngine);
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        InMemoryWorkspaceService workspaceService = new InMemoryWorkspaceService(bus);
        workspace = workspaceService.createWorkspace("evo", WorkspaceMode.LOCAL, root.toString());
        workspaceService.addRepository(workspace.id(), new RepositoryRef("p1", "project",
                repoDir.toString(), null, "master", RepositoryAccessPolicy.READ_WRITE,
                Instant.now()));

        RepositoryOperations operations = new RepositoryOperations(gitEngine,
                workspaceManager::repositoryDir);
        sessionService = new InMemorySessionService(bus, workspaceService);

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new ListFilesTool());
        toolRegistry.register(new WriteFileTool());
        ToolEngine toolEngine = new ToolEngine(toolRegistry, bus);
        agentEngine = new AgentEngine(bus);
        ContextEngine contextEngine = new ContextEngine(operations);

        DefaultProviderRegistry providers = new DefaultProviderRegistry();
        providers.register(new FakeLlmProvider(
                // 1. MAIN (implementer) writes an incomplete implementation.
                "{\"tool\":\"write_file\",\"arguments\":{\"path\":\"project/src/Greeter.java\","
                        + "\"content\":\"public class Greeter { }\"}}",
                "{\"final\":\"Created Greeter class.\"}",
                // 2. EVALUATOR rejects: NEEDS_REWORK.
                "{\"verdict\":\"NEEDS_REWORK\",\"findings\":\"missing greet() method\","
                        + "\"confidence\":\"high\",\"recommendedAction\":\"add a greet() method\"}",
                // 3. MAIN (rework) adds the missing method.
                "{\"tool\":\"write_file\",\"arguments\":{\"path\":\"project/src/Greeter.java\","
                        + "\"content\":\"public class Greeter { public String greet() { return \\\"Hello\\\"; } }\"}}",
                "{\"final\":\"Added greet() method.\"}",
                // 4. EVALUATOR accepts: PASS.
                "{\"verdict\":\"PASS\",\"findings\":\"greet() present and minimal\","
                        + "\"confidence\":\"high\",\"recommendedAction\":\"none\"}"));
        DefaultModelRouter router = new DefaultModelRouter();
        router.setDefaultModel("fake", "fake-model");

        ToolCallingAgentLoop loop = new ToolCallingAgentLoop(providers, toolEngine,
                toolRegistry, contextEngine, sessionService);
        ChangeManager changeManager = new ChangeManager(operations);

        SessionExecutor executor = new SessionExecutor(sessionService, workspaceService,
                new InMemoryReviewService(), providers, router, operations, null,
                agentEngine, loop, changeManager, bus);
        executor.setEvaluationEngine(new EvaluationEngine(providers, router));

        SqliteDatabaseManager database = new SqliteDatabaseManager(temp.resolve("process.db"));
        new SchemaMigrationService(database).migrate();
        processService = new SqliteProcessService(database);
        executor.setProcessSupplier(() -> processService);
        executor.setMaxReworkCycles(2);
        this.executor = executor;
    }

    private SessionExecutor executor;

    @Test
    void failedBranchIsRetainedAndReworkedUntilAccepted() throws Exception {
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "Add greet()", workspace.id(), null, null, null, null));
        executor.submit(session.getId(), "Add a greet() method to the Greeter class.");

        SessionState state = awaitTerminal(session.getId());
        assertEquals(SessionState.WAITING_FOR_APPROVAL, state);

        // Rework actually happened: the final file contains the fixed implementation.
        String content = Files.readString(repoDir.resolve("src/Greeter.java"));
        assertTrue(content.contains("greet()"), content);

        // Four real agent runs: MAIN (implementer), EVALUATOR, MAIN (rework), EVALUATOR.
        var runs = agentEngine.runsOf(session.getId());
        assertEquals(4, runs.size(), runs.toString());
        assertEquals(AgentRole.MAIN, runs.get(0).role());
        assertEquals(AgentRole.EVALUATOR, runs.get(1).role());
        assertEquals(AgentRole.MAIN, runs.get(2).role());
        assertEquals(AgentRole.EVALUATOR, runs.get(3).role());
        assertTrue(runs.stream().allMatch(r -> r.state() == ExecutionState.COMPLETED),
                runs.toString());

        // Evolution: two strategies with lineage; the failed branch is retained and the
        // derived successor completed.
        var process = processService.getProcess(session.getId()).orElseThrow();
        var strategies = processService.strategiesOf(process.id());
        assertEquals(2, strategies.size(), strategies.toString());
        var failed = strategies.get(0);
        var successor = strategies.get(1);
        assertEquals(Strategy.FAILED, failed.status());
        assertEquals(Strategy.COMPLETED, successor.status());
        assertEquals(failed.id(), successor.parentStrategyId());

        // Decision history records both the strategy adaptation and the acceptance.
        var decisions = processService.decisionsOf(process.id());
        assertTrue(decisions.stream().anyMatch(d -> d.kind().equals("STRATEGY_ADAPTED")),
                decisions.toString());
        assertTrue(decisions.stream().anyMatch(d -> d.kind().equals("EVALUATION_ACCEPTED")),
                decisions.toString());
    }

    private SessionState awaitTerminal(String sessionId) throws InterruptedException {
        for (int i = 0; i < 200; i++) {
            SessionState state = sessionService.getSession(sessionId).orElseThrow().getState();
            if (state.isTerminal() || state == SessionState.WAITING_FOR_APPROVAL) {
                return state;
            }
            Thread.sleep(50);
        }
        return sessionService.getSession(sessionId).orElseThrow().getState();
    }
}
