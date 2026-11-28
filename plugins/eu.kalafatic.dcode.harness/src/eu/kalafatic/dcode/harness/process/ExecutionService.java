package eu.kalafatic.dcode.harness.process;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;

import eu.kalafatic.dcode.core.model.ProcessExecution;
import eu.kalafatic.dcode.core.service.ExecutionAuditService;

/**
 * Single authoritative OS-process execution service of the DCode harness.
 *
 * <p>Every process is spawned here and only here. Tools are stateless adapters that build
 * a command and delegate. Each {@link Execution} owns its own process, output buffer and
 * cancellation flag — there is no shared mutable process state between sessions.</p>
 *
 * <p>Output is captured incrementally into a bounded buffer (tail retained); a monotonic
 * emitted-character counter supports cursor-based reads while the process is still
 * running. Cancellation is graceful (SIGTERM) then forced (SIGKILL) and best-effort
 * covers the process tree via {@link ProcessHandle#descendants()}.</p>
 */
public final class ExecutionService {

    /** Known secret-bearing environment variables stripped from every execution. */
    private static final Set<String> SANITIZED_ENV_KEYS = Set.of(
            "OPENAI_API_KEY", "ANTHROPIC_API_KEY", "GITHUB_TOKEN", "GH_TOKEN",
            "AWS_ACCESS_KEY_ID", "AWS_SECRET_ACCESS_KEY", "AWS_SESSION_TOKEN",
            "NPM_TOKEN", "NUGET_API_KEY", "GOOGLE_API_KEY", "GOOGLE_APPLICATION_CREDENTIALS");

    public static final int DEFAULT_MAX_OUTPUT_CHARS = 256 * 1024;

    private final ConcurrentHashMap<String, Execution> executions = new ConcurrentHashMap<>();
    private final int maxOutputChars;
    private final java.util.function.Supplier<ExecutionAuditService> audit;

    public ExecutionService() {
        this(DEFAULT_MAX_OUTPUT_CHARS, (ExecutionAuditService) null);
    }

    public ExecutionService(int maxOutputChars) {
        this(maxOutputChars, (ExecutionAuditService) null);
    }

    public ExecutionService(int maxOutputChars, ExecutionAuditService audit) {
        this(maxOutputChars, audit == null ? () -> null : () -> audit);
    }

    public ExecutionService(int maxOutputChars,
                            java.util.function.Supplier<ExecutionAuditService> audit) {
        this.maxOutputChars = Math.max(4096, maxOutputChars);
        this.audit = audit == null ? () -> null : audit;
    }

    /** Immutable description of one process execution. */
    public record ExecutionSpec(
            List<String> command,
            Path cwd,
            Map<String, String> environment,
            long timeoutSeconds,
            String sessionId,
            String agentRunId,
            String toolCallId,
            String label,
            BooleanSupplier cancellationRequested
    ) {
        public ExecutionSpec {
            command = List.copyOf(Objects.requireNonNull(command, "command"));
            environment = environment == null ? Map.of() : Map.copyOf(environment);
        }
    }

    /** Terminal outcome of an awaited execution. */
    public record AwaitResult(boolean timedOut, boolean cancelled, int exitCode) {
        /** True when the command completed normally with exit code 0. */
        public boolean ok() {
            return !timedOut && !cancelled && exitCode == 0;
        }
    }

    /**
     * Starts a process for the given specification. Returns immediately; output is
     * captured asynchronously. The caller blocks via {@link Execution#await()}.
     */
    public Execution start(ExecutionSpec spec) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(spec.command());
        if (spec.cwd() != null) {
            builder.directory(spec.cwd().toFile());
        }
        for (String key : SANITIZED_ENV_KEYS) {
            builder.environment().remove(key);
        }
        builder.environment().putAll(spec.environment());
        builder.redirectErrorStream(true);

        Process process = builder.start();
        Execution execution = new Execution(UUID.randomUUID().toString(), spec, process,
                maxOutputChars);
        executions.put(execution.id(), execution);
        execution.recordState("RUNNING", null);
        execution.beginCapture();
        return execution;
    }

    /** Returns an execution by id, or {@code null} when unknown or already pruned. */
    public Execution find(String id) {
        return executions.get(id);
    }

    /** All executions of one session, oldest first. */
    public List<Execution> list(String sessionId) {
        List<Execution> result = new ArrayList<>();
        for (Execution execution : executions.values()) {
            if (sessionId == null || sessionId.equals(execution.sessionId())) {
                result.add(execution);
            }
        }
        result.sort((a, b) -> a.startedAt().compareTo(b.startedAt()));
        return result;
    }

    /** Requests cancellation of one execution (graceful, then forced). Idempotent. */
    public boolean cancel(String id) {
        Execution execution = executions.get(id);
        if (execution == null) {
            return false;
        }
        return execution.cancel();
    }

    /** One running or finished process. */
    public final class Execution {

        private final String id;
        private final ExecutionSpec spec;
        private final Process process;
        private final Instant startedAt = Instant.now();
        private final AtomicBoolean cancelRequested = new AtomicBoolean();
        private final BoundedOutput output;
        private final Thread drain;
        private volatile AwaitResult terminal;

        private Execution(String id, ExecutionSpec spec, Process process, int maxChars) {
            this.id = id;
            this.spec = spec;
            this.process = process;
            this.output = new BoundedOutput(maxChars);
            this.drain = new Thread(this::drainLoop, "dcode-exec-drain-" + id);
            this.drain.setDaemon(true);
        }

        private void beginCapture() {
            drain.start();
        }

        private void drainLoop() {
            try (var in = process.getInputStream()) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    output.append(new String(buffer, 0, read, StandardCharsets.UTF_8));
                }
            } catch (IOException ignored) {
                // process died or the stream closed; the output captured so far is enough
            }
        }

        public String id() {
            return id;
        }

        public long pid() {
            return process.pid();
        }

        public Instant startedAt() {
            return startedAt;
        }

        public String sessionId() {
            return spec.sessionId();
        }

        public String agentRunId() {
            return spec.agentRunId();
        }

        public String toolCallId() {
            return spec.toolCallId();
        }

        public String label() {
            return spec.label();
        }

        public List<String> command() {
            return spec.command();
        }

        public boolean cancelRequested() {
            return cancelRequested.get();
        }

        public boolean isAlive() {
            return process.isAlive();
        }

        public AwaitResult terminalResult() {
            return terminal;
        }

        /**
         * Blocks until the process exits, the timeout elapses, or cancellation is
         * requested. Responsive to the cancellation supplier every 500 ms.
         */
        public AwaitResult await() throws InterruptedException {
            AwaitResult existing = terminal;
            if (existing != null) {
                return existing;
            }
            long timeoutSeconds = spec.timeoutSeconds();
            BooleanSupplier cancelSignal = spec.cancellationRequested();
            long deadline = timeoutSeconds <= 0
                    ? Long.MAX_VALUE
                    : System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);

            while (true) {
                if (process.waitFor(500, TimeUnit.MILLISECONDS)) {
                    int exit = process.exitValue();
                    AwaitResult result = new AwaitResult(false, cancelRequested.get(), exit);
                    terminal = result;
                    recordState(cancelRequested.get() ? "CANCELLED" : "EXITED", exit);
                    return result;
                }
                if (cancelSignal != null && cancelSignal.getAsBoolean()) {
                    cancelRequested.set(true);
                    terminateTree();
                    try {
                        process.waitFor(5, TimeUnit.SECONDS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    int exit = process.isAlive() ? -1 : process.exitValue();
                    AwaitResult result = new AwaitResult(false, true, exit);
                    terminal = result;
                    recordState("CANCELLED", null);
                    return result;
                }
                if (System.currentTimeMillis() >= deadline) {
                    terminateTree();
                    AwaitResult result = new AwaitResult(true, false, -1);
                    terminal = result;
                    recordState("TIMED_OUT", null);
                    return result;
                }
            }
        }

        /** Graceful cancellation followed by forced process-tree termination. */
        public boolean cancel() {
            cancelRequested.set(true);
            terminateTree();
            recordState("CANCELLED", null);
            return true;
        }

        /** Persists the current state (start = RUNNING, terminal = final state). */
        private void recordState(String state, Integer exitCode) {
            ExecutionAuditService service = audit.get();
            if (service == null) {
                return;
            }
            try {
                service.recordProcessExecution(new ProcessExecution(
                        id, spec.sessionId(), spec.agentRunId(), spec.toolCallId(),
                        String.join(" ", spec.command()),
                        spec.cwd() == null ? null : spec.cwd().toString(),
                        state, pid(), process.toHandle().info().startInstant()
                                .map(Instant::toString).orElse(null),
                        exitCode, spec.label(), startedAt,
                        "RUNNING".equals(state) ? null : Instant.now()));
            } catch (RuntimeException e) {
                // audit persistence is best-effort; never fail an execution for it
            }
        }

        /** SIGTERM, short grace, then SIGKILL for the process and its descendants. */
        private void terminateTree() {
            ProcessHandle handle = process.toHandle();
            try {
                process.destroy();
            } catch (RuntimeException ignored) {
                // already gone
            }
            try {
                handle.descendants().forEach(ProcessHandle::destroy);
            } catch (RuntimeException ignored) {
                // descendants not enumerable
            }
            try {
                if (process.waitFor(2, TimeUnit.SECONDS)) {
                    return;
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            try {
                process.destroyForcibly();
            } catch (RuntimeException ignored) {
                // already gone
            }
            try {
                handle.descendants().forEach(ProcessHandle::destroyForcibly);
            } catch (RuntimeException ignored) {
                // best-effort
            }
        }

        /** The retained (bounded) output captured so far. */
        public String output() {
            return output.snapshot();
        }

        /** Output from a cursor offset (characters); data trimmed from the head is lost. */
        public String outputFrom(long offset) {
            return output.from(offset);
        }

        /** Monotonic total of emitted output characters (cursor upper bound). */
        public long outputLength() {
            return output.totalEmitted();
        }

        /** The last {@code chars} characters of the retained output. */
        public String tail(int chars) {
            return output.tail(chars);
        }
    }

    /** Thread-safe bounded output buffer keeping the most recent characters. */
    private static final class BoundedOutput {

        private final int max;
        private final StringBuilder buffer = new StringBuilder();
        private final AtomicLong emitted = new AtomicLong();

        private BoundedOutput(int max) {
            this.max = max;
        }

        private synchronized void append(String text) {
            buffer.append(text);
            emitted.addAndGet(text.length());
            int excess = buffer.length() - max;
            if (excess > 0) {
                buffer.delete(0, excess);
            }
        }

        private synchronized String snapshot() {
            return buffer.toString();
        }

        private synchronized String from(long offset) {
            long dropped = emitted.get() - buffer.length();
            int start = (int) Math.max(0, offset - dropped);
            if (start >= buffer.length()) {
                return "";
            }
            return buffer.substring(start);
        }

        private synchronized String tail(int chars) {
            int length = buffer.length();
            if (length <= chars) {
                return buffer.toString();
            }
            return buffer.substring(length - chars);
        }

        private long totalEmitted() {
            return emitted.get();
        }
    }
}
