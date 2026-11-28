package eu.kalafatic.dcode.tests.persistence;

import eu.kalafatic.dcode.core.model.Process;
import eu.kalafatic.dcode.core.model.Strategy;
import eu.kalafatic.dcode.persistence.db.SchemaMigrationService;
import eu.kalafatic.dcode.persistence.db.SqliteDatabaseManager;
import eu.kalafatic.dcode.persistence.service.SqliteProcessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the persistent process-intelligence store: goal, strategies (with lineage and
 * lifecycle), and decisions survive a database reopen.
 */
class ProcessServicePersistenceTest {

    @TempDir
    Path temp;

    private SqliteDatabaseManager database;
    private SqliteProcessService service;

    @BeforeEach
    void setUp() {
        database = new SqliteDatabaseManager(temp.resolve("process.db"));
        new SchemaMigrationService(database).migrate();
        service = new SqliteProcessService(database);
    }

    @Test
    void processStrategyAndDecisionSurviveRestart() {
        Process process = service.ensureProcess("s1", "Refactor UserService");
        assertEquals(Process.ACTIVE, process.state());

        Strategy strategy = service.recordStrategy(process.id(), null, "Strategy A",
                "Direct refactor", Strategy.EXECUTING);
        assertEquals(Strategy.EXECUTING, strategy.status());

        service.recordDecision(process.id(), "s1", "STRATEGY_SELECTED",
                "Chose Strategy A", "repo is small", "Strategy B (rewrite)");

        // Simulated restart: new service over the same database.
        SqliteProcessService reopened = new SqliteProcessService(database);
        Process loaded = reopened.getProcess("s1").orElseThrow();
        assertEquals("Refactor UserService", loaded.goal());
        assertEquals(1, reopened.strategiesOf(process.id()).size());
        assertEquals(1, reopened.decisionsOf(process.id()).size());

        // Strategy evolution: the strategy fails, a derived strategy succeeds.
        reopened.updateStrategy(strategy.id(), Strategy.FAILED, "compilation error");
        Strategy derived = reopened.recordStrategy(process.id(), strategy.id(),
                "Strategy B", "Alternative approach", Strategy.EXECUTING);
        assertEquals(strategy.id(), derived.parentStrategyId());

        reopened.updateProcess("s1", Process.COMPLETED, "done", derived.id());
        assertEquals(Process.COMPLETED, reopened.getProcess("s1").orElseThrow().state());
        assertEquals(2, reopened.strategiesOf(process.id()).size());
        assertTrue(reopened.strategiesOf(process.id()).stream()
                .anyMatch(s -> s.status().equals(Strategy.FAILED)));
    }
}
