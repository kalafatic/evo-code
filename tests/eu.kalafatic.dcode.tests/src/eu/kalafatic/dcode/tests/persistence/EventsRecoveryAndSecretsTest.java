package eu.kalafatic.dcode.tests.persistence;

import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.model.SessionState;
import eu.kalafatic.dcode.persistence.config.DurableConfigurationService;
import eu.kalafatic.dcode.persistence.db.SchemaMigrationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;
import eu.kalafatic.dcode.persistence.events.EventLog;
import eu.kalafatic.dcode.persistence.recovery.SessionRecoveryService;
import eu.kalafatic.dcode.persistence.service.SqliteSessionService;
import eu.kalafatic.dcode.persistence.service.SqliteWorkspaceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EventsRecoveryAndSecretsTest {

    @TempDir
    Path temp;

    @Test
    void eventsPersistWithSequenceAndReplay() throws Exception {
        SqliteDatabaseManager database = new SqliteDatabaseManager(temp.resolve("e.db"));
        new SchemaMigrationService(database).migrate();
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        EventLog log = new EventLog(database, bus);

        bus.publish(StandardEvent.of(DCodeEventType.SESSION_CREATED, "s1", "{}"));
        bus.publish(StandardEvent.of(DCodeEventType.MESSAGE_APPENDED, "s1", "{}"));
        bus.publish(StandardEvent.of(DCodeEventType.SESSION_STATE_CHANGED, "s1", "{}"));
        bus.publish(StandardEvent.of(DCodeEventType.SESSION_CREATED, "s2", "{}"));

        var replay = log.replay("s1", 0, 100);
        assertEquals(3, replay.size());
        assertEquals(1, replay.get(0).sequence());
        assertEquals(3, replay.get(2).sequence());
        assertEquals(DCodeEventType.SESSION_STATE_CHANGED.name(), replay.get(2).type());

        assertEquals(1, log.replay("s1", 2, 100).size());
        assertTrue(log.replay("s2", 0, 100).get(0).sequence() == 1);

        // Sequences survive a new EventLog instance (restart).
        log.close();
        EventLog restarted = new EventLog(database, bus);
        bus.publish(StandardEvent.of(DCodeEventType.MESSAGE_APPENDED, "s1", "{}"));
        assertEquals(4, restarted.replay("s1", 0, 100).size());
        restarted.close();
    }

    @Test
    void recoveryMarksInterruptedSessionsFailed() {
        SqliteDatabaseManager database = new SqliteDatabaseManager(temp.resolve("r.db"));
        new SchemaMigrationService(database).migrate();
        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        SqliteWorkspaceService workspaces = new SqliteWorkspaceService(database, bus);
        SqliteSessionService sessions = new SqliteSessionService(database, bus, workspaces);

        var ws = workspaces.createWorkspace("rec", eu.kalafatic.dcode.core.model.WorkspaceMode.LOCAL,
                null);
        var running = sessions.createSession(new eu.kalafatic.dcode.core.service.SessionService.CreateSessionRequest(
                "in flight", ws.id(), null, null, null, null));
        sessions.transition(running.getId(), SessionState.RUNNING);
        var suspended = sessions.createSession(new eu.kalafatic.dcode.core.service.SessionService.CreateSessionRequest(
                "paused", ws.id(), null, null, null, null));
        sessions.transition(suspended.getId(), SessionState.RUNNING);
        sessions.transition(suspended.getId(), SessionState.SUSPENDED);
        var completed = sessions.createSession(new eu.kalafatic.dcode.core.service.SessionService.CreateSessionRequest(
                "done", ws.id(), null, null, null, null));
        sessions.transition(completed.getId(), SessionState.RUNNING);
        sessions.transition(completed.getId(), SessionState.COMPLETED);
        var pendingApproval = sessions.createSession(new eu.kalafatic.dcode.core.service.SessionService.CreateSessionRequest(
                "awaiting review", ws.id(), null, null, null, null));
        sessions.transition(pendingApproval.getId(), SessionState.RUNNING);
        sessions.transition(pendingApproval.getId(), SessionState.WAITING_FOR_APPROVAL);

        new SessionRecoveryService(sessions, workspaces).recover();

        assertEquals(SessionState.FAILED,
                sessions.getSession(running.getId()).orElseThrow().getState());
        assertEquals(SessionState.SUSPENDED,
                sessions.getSession(suspended.getId()).orElseThrow().getState());
        assertEquals(SessionState.COMPLETED,
                sessions.getSession(completed.getId()).orElseThrow().getState());
        // A pending human approval must survive restart unchanged.
        assertEquals(SessionState.WAITING_FOR_APPROVAL,
                sessions.getSession(pendingApproval.getId()).orElseThrow().getState());
    }

    @Test
    void secretsEncryptedAtRest() throws Exception {
        Path dataDir = temp.resolve("cfg");
        Files.createDirectories(dataDir);
        DurableConfigurationService service = new DurableConfigurationService(dataDir,
                CoreConfiguration.defaults(dataDir));
        service.setSecret("provider.deepseek.apiKey", "sk-supersecret123");

        // Restart: new instance over the same files decrypts the same secret.
        DurableConfigurationService restarted = new DurableConfigurationService(dataDir,
                CoreConfiguration.defaults(dataDir));
        assertEquals("sk-supersecret123",
                restarted.getSecret("provider.deepseek.apiKey").orElseThrow());

        // Raw files never contain the plaintext secret.
        Path secretsFile = dataDir.resolve("secrets.enc");
        assertTrue(Files.exists(secretsFile));
        String raw = Files.readString(secretsFile);
        assertFalse(raw.contains("sk-supersecret123"));
        assertFalse(Files.readString(dataDir.resolve("dcode-config.json"))
                .contains("sk-supersecret123"));
    }
}
