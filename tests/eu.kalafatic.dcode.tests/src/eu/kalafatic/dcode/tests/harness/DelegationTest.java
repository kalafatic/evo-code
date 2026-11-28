package eu.kalafatic.dcode.tests.harness;

import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.ExecutionState;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.harness.agent.AgentEngine;
import eu.kalafatic.dcode.harness.change.ChangeManager;
import eu.kalafatic.dcode.harness.change.InMemoryReviewService;
import eu.kalafatic.dcode.harness.context.ContextEngine;
import eu.kalafatic.dcode.harness.execution.SessionExecutor;
import eu.kalafatic.dcode.harness.orchestration.ToolCallingAgentLoop;
import eu.kalafatic.dcode.harness.session.InMemorySessionService;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.harness.tool.ToolEngine;
import eu.kalafatic.dcode.harness.tools.DelegateTaskTool;
import eu.kalafatic.dcode.harness.tools.ListFilesTool;
import eu.kalafatic.dcode.harness.tools.ReadFileTool;
import eu.kalafatic.dcode.repositories.git.JGitEngine;
import eu.kalafatic.dcode.repositories.service.InMemoryWorkspaceService;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;
import eu.kalafatic.dcode.repositories.workspace.RepositoryWorkspaceManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Genuine multi-agent execution: a coordinator (MAIN) delegates a bounded subtask to a
 * specialist (ANALYZER) via the {@code delegate_task} tool. The specialist performs real
 * tool work under its own execution identity and returns a structured result.
 */
class DelegationTest {

    @TempDir
    Path temp;

    @Test
    void coordinatorDelegatesToSpecialist() throws Exception {
        Path root = temp.resolve("workspace");
        Files.createDirectories(root);
        Path repoDir = root.resolve("project");
        JGitEngine gitEngine = new JGitEngine();
        gitEngine.init(repoDir);
        Files.writeString(repoDir.resolve("README.md"), "# Project\n");
        gitEngine.commit(repoDir, "seed", "T", "t@example.org");

        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        InMemoryWorkspaceService workspaceService = new InMemoryWorkspaceService(bus);
        Workspace workspace = workspaceService.createWorkspace("multi", WorkspaceMode.LOCAL,
                root.toString());
        workspaceService.addRepository(workspace.id(), new RepositoryRef("p1", "project",
                repoDir.toString(), null, "master", RepositoryAccessPolicy.READ_WRITE,
                Instant.now()));

        RepositoryWorkspaceManager wm = new RepositoryWorkspaceManager(gitEngine);
        RepositoryOperations operations = new RepositoryOperations(gitEngine, wm::repositoryDir);
        InMemorySessionService sessionService = new InMemorySessionService(bus, workspaceService);

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new ListFilesTool());
        toolRegistry.register(new ReadFileTool());
        ToolEngine toolEngine = new ToolEngine(toolRegistry, bus);
        AgentEngine agentEngine = new AgentEngine(bus);
        ContextEngine contextEngine = new ContextEngine(operations);

        DefaultProviderRegistry providers = new DefaultProviderRegistry();
        providers.register(new FakeLlmProvider(
                "{\"tool\":\"delegate_task\",\"arguments\":{\"task\":\"list files and read README.md, then summarize\",\"role\":\"ANALYZER\"}}",
                "{\"tool\":\"list_files\",\"arguments\":{}}",
                "{\"tool\":\"read_file\",\"arguments\":{\"path\":\"project/README.md\"}}",
                "{\"final\":\"Found README.md describing the project.\"}",
                "{\"final\":\"Delegated analysis to a specialist and integrated the result.\"}"));
        DefaultModelRouter router = new DefaultModelRouter();
        router.setDefaultModel("fake", "fake-model");

        ToolCallingAgentLoop loop = new ToolCallingAgentLoop(providers, toolEngine,
                toolRegistry, contextEngine, sessionService);
        toolRegistry.register(new DelegateTaskTool(providers, router, loop, agentEngine, bus,
                () -> sessionService));

        ChangeManager changeManager = new ChangeManager(operations);
        SessionExecutor executor = new SessionExecutor(sessionService, workspaceService,
                new InMemoryReviewService(), providers, router, operations, null,
                agentEngine, loop, changeManager, bus);

        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "delegate", workspace.id(), "fake", "fake-model", null, null));
        executor.submit(session.getId(), "Delegate repository analysis to a specialist.");

        SessionState state = awaitTerminal(sessionService, session.getId());
        assertEquals(SessionState.COMPLETED, state);

        // Two real agent runs: the coordinator (MAIN) and the specialist (ANALYZER).
        var runs = agentEngine.runsOf(session.getId());
        assertEquals(2, runs.size(), runs.toString());
        assertEquals("MAIN", runs.get(0).role().name());
        assertEquals(ExecutionState.COMPLETED, runs.get(0).state());
        assertEquals("ANALYZER", runs.get(1).role().name());
        assertEquals(ExecutionState.COMPLETED, runs.get(1).state());

        // The specialist performed real tool work.
        assertTrue(toolEngine.callsOf(session.getId()).stream()
                .anyMatch(c -> c.toolName().equals("list_files")));
        assertTrue(toolEngine.callsOf(session.getId()).stream()
                .anyMatch(c -> c.toolName().equals("read_file")));

        // The coordinator received the structured result.
        assertTrue(sessionService.listMessages(session.getId()).stream()
                .anyMatch(m -> m.content() != null
                        && m.content().contains("Delegated analysis to a specialist")));
    }

    private static SessionState awaitTerminal(InMemorySessionService sessions, String id)
            throws InterruptedException {
        for (int i = 0; i < 100; i++) {
            SessionState state = sessions.getSession(id).orElseThrow().getState();
            if (state.isTerminal()) {
                return state;
            }
            Thread.sleep(50);
        }
        return sessions.getSession(id).orElseThrow().getState();
    }
}
