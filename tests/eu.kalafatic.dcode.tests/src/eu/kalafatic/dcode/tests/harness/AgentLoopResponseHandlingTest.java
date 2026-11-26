package eu.kalafatic.dcode.tests.harness;

import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
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
import eu.kalafatic.dcode.harness.tools.GitStatusTool;
import eu.kalafatic.dcode.harness.tools.WriteFileTool;
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
 * Agent-loop response handling: empty model output fails with a clear diagnostic
 * (never a silent completion), and non-JSON plain text is treated as a final answer.
 */
class AgentLoopResponseHandlingTest {

    @TempDir
    Path temp;

    private SessionExecutor executor;
    private InMemorySessionService sessionService;
    private Workspace workspace;
    private DefaultProviderRegistry providers;
    private InMemoryEventBus bus;

    @BeforeEach
    void setUp() throws Exception {
        Path root = temp.resolve("workspace");
        Files.createDirectories(root);
        Path repoDir = root.resolve("project");
        JGitEngine gitEngine = new JGitEngine();
        gitEngine.init(repoDir);
        Files.writeString(repoDir.resolve("README.md"), "# Project\n");
        gitEngine.commit(repoDir, "seed", "Test", "t@example.org");

        bus = new InMemoryEventBus(null, t -> {});
        InMemoryWorkspaceService workspaceService = new InMemoryWorkspaceService(bus);
        workspace = workspaceService.createWorkspace("responses", WorkspaceMode.LOCAL,
                root.toString());
        workspaceService.addRepository(workspace.id(), new RepositoryRef("p1", "project",
                repoDir.toString(), null, "master", RepositoryAccessPolicy.READ_WRITE,
                Instant.now()));

        RepositoryWorkspaceManager workspaceManager = new RepositoryWorkspaceManager(gitEngine);
        RepositoryOperations operations = new RepositoryOperations(gitEngine,
                workspaceManager::repositoryDir);

        DefaultToolRegistry toolRegistry = new DefaultToolRegistry();
        toolRegistry.register(new GitStatusTool());
        toolRegistry.register(new WriteFileTool());
        ToolEngine toolEngine = new ToolEngine(toolRegistry, bus);

        sessionService = new InMemorySessionService(bus, workspaceService);
        providers = new DefaultProviderRegistry();
        DefaultModelRouter router = new DefaultModelRouter();
        router.setDefaultModel("fake", "fake-model");

        AgentEngine agentEngine = new AgentEngine(bus);
        ContextEngine contextEngine = new ContextEngine(operations);
        ToolCallingAgentLoop loop = new ToolCallingAgentLoop(providers, toolEngine,
                toolRegistry, contextEngine, sessionService);
        ChangeManager changeManager = new ChangeManager(operations);
        executor = new SessionExecutor(sessionService, workspaceService,
                new InMemoryReviewService(), providers, router, operations, null,
                agentEngine, loop, changeManager, bus);
    }

    @Test
    void emptyModelResponseFailsWithDiagnostic() throws Exception {
        providers.register(new FakeLlmProvider(""));
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "empty-response", workspace.id(), "fake", "fake-model", null, null));
        executor.submit(session.getId(), "do something");
        var finalSession = awaitTerminal(session.getId());
        assertEquals(SessionState.FAILED, finalSession.getState());
    }

    @Test
    void plainTextResponseIsFinalAnswer() throws Exception {
        providers.register(new FakeLlmProvider("No structured action, just prose."));
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "plain-text", workspace.id(), "fake", "fake-model", null, null));
        executor.submit(session.getId(), "do something");
        var finalSession = awaitTerminal(session.getId());
        assertEquals(SessionState.COMPLETED, finalSession.getState());
        boolean hasProse = false;
        for (var message : sessionService.listMessages(session.getId())) {
            if (message.content() != null && message.content().contains("just prose")) {
                hasProse = true;
            }
        }
        assertTrue(hasProse);
    }

    @Test
    void dsmlToolCallIsParsedAndExecuted() throws Exception {
        // DeepSeek-style DSML function-call markup must be normalized to the harness
        // action shape and executed (regression: "neither a tool call nor a final
        // message" when a DeepSeek-routed model fell back to DSML).
        providers.register(new FakeLlmProvider(
                "<｜DSML｜ calls><｜DSML｜ invoke name=\"write_file\">"
                        + "<｜DSML｜ parameter name=\"arguments\" string=\"false\">"
                        + "{\"path\":\"dsml.txt\",\"content\":\"hello from dsml\"}"
                        + "</｜DSML｜ parameter></｜DSML｜ invoke></｜DSML｜ calls>",
                "{\"final\":\"wrote the file\"}"));
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "dsml-tool", workspace.id(), "fake", "fake-model", null, null));
        executor.submit(session.getId(), "write a file");
        var finalSession = awaitTerminal(session.getId());
        assertEquals(SessionState.COMPLETED, finalSession.getState());
        Path written = workspace.rootPath() != null
                ? Path.of(workspace.rootPath()).resolve("dsml.txt")
                : temp.resolve("workspace").resolve("dsml.txt");
        assertTrue(Files.exists(written), "DSML tool call must write the file");
        assertEquals("hello from dsml", Files.readString(written));
    }

    private eu.kalafatic.dcode.core.model.Session awaitTerminal(String sessionId)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15000;
        while (System.currentTimeMillis() < deadline) {
            var session = sessionService.getSession(sessionId).orElseThrow();
            if (session.getState().isTerminal()) {
                return session;
            }
            Thread.sleep(50);
        }
        return sessionService.getSession(sessionId).orElseThrow();
    }
}
