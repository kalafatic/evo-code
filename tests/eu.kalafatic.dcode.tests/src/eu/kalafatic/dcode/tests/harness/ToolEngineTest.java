package eu.kalafatic.dcode.tests.harness;

import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.ExecutionState;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolEngine;
import eu.kalafatic.dcode.harness.tools.ReadFileTool;
import eu.kalafatic.dcode.harness.tools.SearchTool;
import eu.kalafatic.dcode.harness.tools.ShellTool;
import eu.kalafatic.dcode.harness.tools.WriteFileTool;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ToolEngineTest {

    @TempDir
    Path temp;

    private ToolEngine toolEngine;
    private Session session;
    private Workspace workspace;
    private InMemoryEventBus bus;
    private java.util.List<String> events;

    @BeforeEach
    void setUp() {
        bus = new InMemoryEventBus(null, t -> {});
        events = new java.util.concurrent.CopyOnWriteArrayList<>();
        bus.subscribe(e -> true, e -> events.add(e.type().name()));
        DefaultToolRegistry registry = new DefaultToolRegistry();
        registry.register(new ReadFileTool());
        registry.register(new SearchTool());
        registry.register(new WriteFileTool());
        registry.register(new ShellTool());
        toolEngine = new ToolEngine(registry, bus);
        workspace = new Workspace("w1", "ws", WorkspaceMode.LOCAL, temp.toString(),
                List.of(), Instant.now());
        session = Session.builder().id("s1").workspaceId("w1").title("t")
                .state(SessionState.RUNNING).build();
    }

    private ToolContext context(SessionState state) {
        Session s = Session.builder().id("s1").workspaceId("w1").title("t")
                .state(state).build();
        return new ToolContext(s, workspace, temp, null, null, () -> false, null);
    }

    @Test
    void fileToolsRoundTrip() throws Exception {
        var write = toolEngine.execute(context(SessionState.RUNNING), "write_file",
                Map.of("path", "a.txt", "content", "hello"));
        assertEquals(ExecutionState.COMPLETED, write.state());
        assertTrue(Files.exists(temp.resolve("a.txt")));

        var read = toolEngine.execute(context(SessionState.RUNNING), "read_file",
                Map.of("path", "a.txt"));
        assertEquals(ExecutionState.COMPLETED, read.state());

        assertTrue(events.contains("TOOL_CALL_STARTED"));
        assertTrue(events.contains("TOOL_CALL_COMPLETED"));
        assertEquals(2, toolEngine.callsOf("s1").size());
    }

    @Test
    void mutatingToolRequiresRunningSession() {
        var call = toolEngine.execute(context(SessionState.CREATED), "write_file",
                Map.of("path", "b.txt", "content", "x"));
        assertEquals(ExecutionState.FAILED, call.state());
        assertTrue(call.errorMessage().contains("RUNNING"));
    }

    @Test
    void readOnlyToolAllowedOnCreatedSession() throws Exception {
        Files.writeString(temp.resolve("c.txt"), "content");
        var call = toolEngine.execute(context(SessionState.CREATED), "read_file",
                Map.of("path", "c.txt"));
        assertEquals(ExecutionState.COMPLETED, call.state());
    }

    @Test
    void unknownToolRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> toolEngine.execute(context(SessionState.RUNNING), "nope", Map.of()));
    }

    @Test
    void pathContainmentRejected() {
        var traversal = toolEngine.execute(context(SessionState.RUNNING), "read_file",
                Map.of("path", "../outside.txt"));
        assertEquals(ExecutionState.FAILED, traversal.state());
        assertTrue(traversal.errorMessage().contains("escapes"));

        var absolute = toolEngine.execute(context(SessionState.RUNNING), "read_file",
                Map.of("path", "/etc/passwd"));
        assertEquals(ExecutionState.FAILED, absolute.state());
    }

    @Test
    void shellToolRunsAndReportsExitCodes() {
        var ok = toolEngine.execute(context(SessionState.RUNNING), "shell",
                Map.of("command", "echo hello"));
        assertTrue(ok.state() == ExecutionState.COMPLETED);

        var failed = toolEngine.execute(context(SessionState.RUNNING), "shell",
                Map.of("command", "exit 3"));
        assertEquals(ExecutionState.FAILED, failed.state());
        assertFalse(failed.errorMessage().isBlank());
    }

    @Test
    void searchCodeSkipsBinaryFiles() throws Exception {
        // A workspace with a text file and a binary file (invalid UTF-8, as a compiled
        // .class would be). The search must find the text match and ignore the binary
        // file instead of failing the whole search (regression for "Input length = 1").
        Files.writeString(temp.resolve("Source.java"),
                "class Source { int NEEDLE_42 = 1; }");
        Files.write(temp.resolve("Source.class"),
                new byte[]{0x00, (byte) 0xFF, (byte) 0xFE, 0x01, 0x02, 0x03});

        var call = toolEngine.execute(context(SessionState.RUNNING), "search_code",
                Map.of("pattern", "NEEDLE_42", "path", "."));
        assertEquals(ExecutionState.COMPLETED, call.state());
        assertTrue(call.resultJson().contains("Source.java"));
        assertTrue(call.resultJson().contains("NEEDLE_42"));
    }
}
