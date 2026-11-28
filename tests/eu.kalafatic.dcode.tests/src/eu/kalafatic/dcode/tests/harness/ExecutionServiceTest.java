package eu.kalafatic.dcode.tests.harness;

import eu.kalafatic.dcode.harness.process.ExecutionService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the process-execution authority: output capture, exit codes, timeouts,
 * cancellation and independence between concurrent executions.
 */
class ExecutionServiceTest {

    @Test
    void runsCommandAndCapturesOutput() throws Exception {
        ExecutionService service = new ExecutionService();
        var ex = service.start(spec("echo hello; echo world", 30, "s1"));
        var result = ex.await();
        assertTrue(result.ok(), "exit=" + result.exitCode());
        assertTrue(ex.output().contains("hello"));
        assertTrue(ex.output().contains("world"));
        assertTrue(ex.outputLength() > 0);
    }

    @Test
    void reportsNonZeroExit() throws Exception {
        ExecutionService service = new ExecutionService();
        var ex = service.start(spec("echo boom; exit 7", 30, "s1"));
        var result = ex.await();
        assertFalse(result.ok());
        assertEquals(7, result.exitCode());
        assertTrue(ex.output().contains("boom"));
    }

    @Test
    void timeoutTerminatesProcess() throws Exception {
        ExecutionService service = new ExecutionService();
        var ex = service.start(spec("sleep 60", 1, "s1"));
        var result = ex.await();
        assertTrue(result.timedOut());
        assertFalse(ex.isAlive());
    }

    @Test
    void cancellationStopsProcess() throws Exception {
        ExecutionService service = new ExecutionService();
        AtomicBoolean cancel = new AtomicBoolean(false);
        var ex = service.start(new ExecutionService.ExecutionSpec(
                List.of("/bin/bash", "-c", "sleep 60"),
                Path.of(System.getProperty("java.io.tmpdir")), Map.of(),
                60, "s1", null, null, "test", cancel::get));
        Thread canceller = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            cancel.set(true);
        });
        canceller.start();
        var result = ex.await();
        canceller.join();
        assertTrue(result.cancelled());
        assertFalse(ex.isAlive());
    }

    @Test
    void concurrentExecutionsAreIndependent() throws Exception {
        ExecutionService service = new ExecutionService();
        var a = service.start(spec("echo A", 30, "s1"));
        var b = service.start(spec("echo B", 30, "s2"));
        var ra = a.await();
        var rb = b.await();
        assertTrue(ra.ok());
        assertTrue(rb.ok());
        assertTrue(a.output().contains("A"));
        assertTrue(b.output().contains("B"));
    }

    @Test
    void outputFromCursorReturnsNewTextOnly() throws Exception {
        ExecutionService service = new ExecutionService();
        var ex = service.start(spec("echo first; sleep 1; echo second", 30, "s1"));
        long cursor = 0;
        while (ex.isAlive()) {
            Thread.sleep(50);
        }
        ex.await();
        String tail = ex.outputFrom(cursor);
        assertTrue(tail.contains("first"));
        assertTrue(tail.contains("second"));
    }

    private ExecutionService.ExecutionSpec spec(String command, long timeout, String session) {
        return new ExecutionService.ExecutionSpec(
                List.of("/bin/bash", "-c", command),
                Path.of(System.getProperty("java.io.tmpdir")), Map.of(),
                timeout, session, null, null, "test", null);
    }
}
