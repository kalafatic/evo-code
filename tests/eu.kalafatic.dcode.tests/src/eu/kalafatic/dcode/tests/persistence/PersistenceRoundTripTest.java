package eu.kalafatic.dcode.tests.persistence;

import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.InvalidStateTransitionException;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.persistence.db.SchemaMigrationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;
import eu.kalafatic.dcode.persistence.service.SqliteSessionService;
import eu.kalafatic.dcode.persistence.service.SqliteWorkspaceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * WP5.7 gate: sessions and workspaces survive database reopen (simulated restart).
 */
class PersistenceRoundTripTest {

    @TempDir
    Path temp;

    private SqliteDatabaseManager database;
    private InMemoryEventBus bus;
    private SqliteWorkspaceService workspaceService;
    private SqliteSessionService sessionService;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabaseManager(temp.resolve("dcode.db"));
        new SchemaMigrationService(database).migrate();
        bus = new InMemoryEventBus(null, t -> {});
        workspaceService = new SqliteWorkspaceService(database, bus);
        sessionService = new SqliteSessionService(database, bus, workspaceService);
    }

    @Test
    void migrationIsIdempotentAndVersioned() {
        new SchemaMigrationService(database).migrate();
        new SchemaMigrationService(database).migrate();
        assertEquals(4, new SchemaMigrationService(database).currentVersion());
    }

    @Test
    void sessionsAndMessagesSurviveRestart() {
        var workspace = workspaceService.createWorkspace("family", WorkspaceMode.LOCAL,
                temp.resolve("ws").toString());
        workspaceService.addRepository(workspace.id(), new RepositoryRef("r1", "evo",
                temp.resolve("evo").toString(), null, "master",
                RepositoryAccessPolicy.READ_WRITE, Instant.now()));

        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "persist me", workspace.id(), "ollama", "qwen", AgentRole.MAIN, null));
        sessionService.transition(session.getId(), SessionState.RUNNING);
        sessionService.appendMessage(session.getId(), MessageRole.USER, "hello");
        sessionService.appendMessage(session.getId(), MessageRole.ASSISTANT, "hi there");
        sessionService.transition(session.getId(), SessionState.COMPLETED);

        // Simulated restart: fresh service instances over the same database file.
        SqliteWorkspaceService restartedWorkspace =
                new SqliteWorkspaceService(database, bus);
        SqliteSessionService restartedSession =
                new SqliteSessionService(database, bus, restartedWorkspace);

        var loaded = restartedSession.getSession(session.getId()).orElseThrow();
        assertEquals("persist me", loaded.getTitle());
        assertEquals(SessionState.COMPLETED, loaded.getState());

        var messages = restartedSession.listMessages(session.getId());
        assertEquals(2, messages.size());
        assertEquals("hello", messages.get(0).content());
        assertEquals(1, messages.get(0).sequence());
        assertEquals(2, messages.get(1).sequence());

        var loadedWorkspace = restartedWorkspace.getWorkspace(workspace.id()).orElseThrow();
        assertEquals(1, loadedWorkspace.repositories().size());
        assertEquals("evo", loadedWorkspace.repositories().get(0).name());
    }

    @Test
    void invalidTransitionRejected() {
        var workspace = workspaceService.createWorkspace("w2", WorkspaceMode.LOCAL, null);
        var session = sessionService.createSession(new SessionService.CreateSessionRequest(
                "t", workspace.id(), null, null, null, null));
        assertThrows(InvalidStateTransitionException.class,
                () -> sessionService.transition(session.getId(), SessionState.COMPLETED));
        assertEquals(SessionState.CREATED,
                sessionService.getSession(session.getId()).orElseThrow().getState());
    }

    @Test
    void unknownWorkspaceRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> sessionService.createSession(new SessionService.CreateSessionRequest(
                        "t", "nope", null, null, null, null)));
    }

    @Test
    void workspaceRegistryPersistence() {
        var ws = workspaceService.createWorkspace("multi", WorkspaceMode.LOCAL,
                temp.resolve("root").toString());
        workspaceService.addRepository(ws.id(), new RepositoryRef("a", "repo-a",
                temp.resolve("a").toString(), "https://example.org/a.git", "main",
                RepositoryAccessPolicy.READ_ONLY, Instant.now()));
        workspaceService.addRepository(ws.id(), new RepositoryRef("b", "repo-b",
                temp.resolve("b").toString(), null, "master",
                RepositoryAccessPolicy.READ_WRITE, Instant.now()));

        SqliteWorkspaceService restarted = new SqliteWorkspaceService(database, bus);
        var loaded = restarted.getWorkspace(ws.id()).orElseThrow();
        assertEquals(2, loaded.repositories().size());
        assertTrue(loaded.repositories().stream()
                .anyMatch(r -> r.name().equals("repo-a") && r.isReadOnly()));

        var removed = restarted.removeRepository(ws.id(), "a");
        assertEquals(1, removed.repositories().size());
        assertThrows(IllegalArgumentException.class,
                () -> restarted.addRepository(ws.id(), new RepositoryRef("b2", "repo-b",
                        temp.resolve("b2").toString(), null, null,
                        RepositoryAccessPolicy.READ_WRITE, Instant.now())));
    }
}
