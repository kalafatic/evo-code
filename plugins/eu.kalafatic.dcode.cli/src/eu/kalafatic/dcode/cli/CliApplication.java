package eu.kalafatic.dcode.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import eu.kalafatic.dcode.client.BackendProbe;
import eu.kalafatic.dcode.client.ConnectionState;
import eu.kalafatic.dcode.client.DCodeApiClient;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Thin CLI client for the DCode server. Communicates exclusively over the existing
 * {@code /api/v1} HTTP API; it never initializes the DCode runtime, never opens SWT,
 * never binds an HTTP port and never touches persistence.
 *
 * <p>Endpoint discovery: {@code <dataDir>/server-endpoint.json} (the actual bound
 * endpoint written by the server), then {@code dcode-config.json httpPort}, then the
 * family default port — each probed for a live, ready DCode server.</p>
 *
 * <p>Lifecycle: {@code start} spawns the {@code dcode-headless} server; {@code stop}
 * sends {@code POST /api/v1/server/shutdown}; {@code restart} is stop then start.</p>
 */
public final class CliApplication implements IApplication {

    private final PrintStream out = System.out;

    @Override
    public Object start(IApplicationContext context) {
        List<String> args = applicationArgs(context);
        String command = args.isEmpty() ? "help" : normalize(args.get(0));
        try {
            return Integer.valueOf(dispatch(command, args.subList(1, args.size())));
        } catch (ServerUnavailableException e) {
            out.println(e.getMessage());
            out.println("Start it with `dcode-cli start` (or `dcode-headless` as a daemon), then retry.");
            return Integer.valueOf(1);
        } catch (Exception e) {
            out.println("dcode-cli: " + command + " failed: " + rootMessage(e));
            return Integer.valueOf(1);
        }
    }

    @Override
    public void stop() {
        // nothing to release
    }

    // ------------------------------------------------------------ dispatch

    private int dispatch(String command, List<String> rest) throws Exception {
        return switch (command) {
            case "status" -> cmdStatus();
            case "runtimes" -> cmdRuntimes();
            case "providers" -> cmdProviders();
            case "models" -> cmdModels();
            case "sessions" -> cmdSessions();
            case "agents" -> cmdAgents();
            case "config" -> cmdConfig();
            case "start" -> cmdStart();
            case "stop" -> cmdStop();
            case "restart" -> cmdRestart();
            case "help", "--help", "-h" -> {
                cmdHelp();
                yield 0;
            }
            default -> {
                out.println("Unknown command '" + command + "'.");
                cmdHelp();
                yield 2;
            }
        };
    }

    // ------------------------------------------------------------ read-only commands

    private int cmdStatus() throws Exception {
        String endpoint = discoverEndpoint();
        if (endpoint == null) {
            return unavailable("status");
        }
        DCodeApiClient client = new DCodeApiClient(endpoint);
        JsonNode status = client.get("/api/v1/status").get(15, TimeUnit.SECONDS);
        JsonNode ports = client.get("/api/v1/ports").get(15, TimeUnit.SECONDS);
        out.println("EVO-Code status: ready=" + status.path("ready").asBoolean());
        status.path("stages").fields().forEachRemaining(entry ->
                out.println("  " + entry.getKey() + ": " + entry.getValue().asText()));
        for (JsonNode port : ports) {
            out.println("  port " + port.path("id").asText() + ": "
                    + port.path("actualPort").asInt() + " ("
                    + port.path("state").asText() + ")");
        }
        out.println("  endpoint: " + endpoint);
        return 0;
    }

    private int cmdRuntimes() throws Exception {
        JsonNode runtimes = requireList("/api/v1/runtimes", "runtimes");
        out.println("Runtimes (" + runtimes.size() + "):");
        for (JsonNode runtime : runtimes) {
            JsonNode status = runtime.path("status");
            out.println("  " + runtime.path("name").asText(runtime.path("id").asText(""))
                    + "  " + runtime.path("type").asText("")
                    + "  " + runtime.path("endpoint").asText("")
                    + "  enabled=" + runtime.path("enabled").asBoolean()
                    + "  default=" + runtime.path("default").asBoolean()
                    + "  reachable=" + status.path("reachable").asBoolean()
                    + "  models=" + runtime.path("modelCount").asInt(-1));
        }
        return 0;
    }

    private int cmdProviders() throws Exception {
        JsonNode providers = requireList("/api/v1/providers", "providers");
        out.println("Providers (" + providers.size() + "):");
        for (JsonNode provider : providers) {
            out.println("  " + provider.path("name").asText(provider.path("id").asText(""))
                    + "  " + provider.path("type").asText("")
                    + "  " + provider.path("endpoint").asText(""));
        }
        return 0;
    }

    private int cmdModels() throws Exception {
        JsonNode models = requireList("/api/v1/models", "models");
        out.println("Models (" + models.size() + "):");
        for (JsonNode model : models) {
            out.println("  " + model.path("id").asText("")
                    + "  runtime=" + model.path("runtimeId").asText("")
                    + "  available=" + model.path("available").asBoolean());
        }
        return 0;
    }

    private int cmdSessions() throws Exception {
        String endpoint = discoverEndpoint();
        if (endpoint == null) {
            return unavailable("sessions");
        }
        DCodeApiClient client = new DCodeApiClient(endpoint);
        JsonNode workspaces = client.get("/api/v1/workspaces").get(15, TimeUnit.SECONDS);
        int total = 0;
        for (JsonNode workspace : workspaces) {
            String workspaceId = workspace.path("id").asText("");
            JsonNode sessions = client.get("/api/v1/sessions?workspaceId="
                    + urlEncode(workspaceId)).get(15, TimeUnit.SECONDS);
            if (sessions.size() > 0) {
                out.println("Workspace " + workspace.path("name").asText(""));
            }
            for (JsonNode session : sessions) {
                out.println("  " + session.path("id").asText("") + "  "
                        + session.path("title").asText("") + "  ["
                        + session.path("state").asText("") + "]");
                total++;
            }
        }
        out.println("(" + total + " session(s))");
        return 0;
    }

    private int cmdAgents() throws Exception {
        JsonNode agents = requireList("/api/v1/agents", "agents");
        out.println("Agents (" + agents.size() + "):");
        for (JsonNode agent : agents) {
            out.println("  " + agent.path("name").asText(agent.path("id").asText(""))
                    + "  " + agent.path("role").asText("")
                    + (agent.path("default").asBoolean() ? "  [default]" : ""));
        }
        return 0;
    }

    private int cmdConfig() {
        Path dataDir = EndpointResolver.dataDir();
        String endpoint = discoverEndpoint();
        out.println("EVO-Code configuration:");
        out.println("  data dir:       " + dataDir);
        out.println("  endpoint:       " + (endpoint != null ? endpoint
                : "(no running server)"));
        int port = EndpointResolver.configuredHttpPort(dataDir);
        out.println("  configured port: " + (port > 0 ? port
                : EndpointResolver.DEFAULT_PORT + " (default)"));
        return 0;
    }

    // ------------------------------------------------------------ lifecycle commands

    private int cmdStart() throws Exception {
        String endpoint = discoverEndpoint();
        if (endpoint != null) {
            out.println("EVO-Code server already running at " + endpoint);
            return 0;
        }
        String launcher = serverLauncher();
        String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
        out.println("Starting EVO-Code server: " + launcher);
        List<String> command = new ArrayList<>(List.of(
                launcher, "-vm", java, "-nosplash", "--launcher.suppressErrors"));
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        // Fully detach the spawned server: its output is discarded and its stdin is
        // closed, so it survives the CLI process exiting and runs as an independent
        // service (no inherited terminal, no SIGHUP from the launching shell).
        pb.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        pb.redirectInput(ProcessBuilder.Redirect.from(
                new java.io.File("/dev/null")));
        try {
            pb.start();
        } catch (Exception e) {
            out.println("Failed to start the server: " + rootMessage(e));
            out.println("Set -Ddcode.server.launcher=<path-to-dcode-headless> or ensure "
                    + "`dcode-headless` is on PATH.");
            return 1;
        }
        String ready = waitForReady(30);
        if (ready != null) {
            out.println("EVO-Code server started at " + ready);
            return 0;
        }
        out.println("Server did not become ready within 30s.");
        return 1;
    }

    private int cmdStop() throws Exception {
        String endpoint = discoverEndpoint();
        if (endpoint == null) {
            out.println("EVO-Code server is not running.");
            return 1;
        }
        DCodeApiClient client = new DCodeApiClient(endpoint);
        JsonNode result = client.post("/api/v1/server/shutdown",
                JsonNodeFactory.instance.objectNode()).get(15, TimeUnit.SECONDS);
        out.println("Shutdown requested: " + result.path("status").asText());
        if (waitForStop(endpoint, 20)) {
            out.println("Server stopped.");
            return 0;
        }
        out.println("Server did not stop within 20s.");
        return 1;
    }

    private int cmdRestart() throws Exception {
        int stopped = cmdStop();
        if (stopped != 0) {
            out.println("(server was not running; starting fresh)");
        }
        return cmdStart();
    }

    private void cmdHelp() {
        out.println("dcode-cli — thin client for the EVO-Code server");
        out.println();
        out.println("Commands:");
        out.println("  status       backend readiness, lifecycle stages and ports");
        out.println("  runtimes     runtime registrations with live availability");
        out.println("  providers    LLM providers of the backend");
        out.println("  models       unified model catalogue");
        out.println("  sessions     persisted sessions grouped by workspace");
        out.println("  agents       coding agents with the default marker");
        out.println("  config       effective configuration summary (no secrets)");
        out.println("  start        start the EVO-Code server (no-op when already running)");
        out.println("  stop         stop the running EVO-Code server gracefully");
        out.println("  restart      stop then start the EVO-Code server");
        out.println("  help         this help");
        out.println();
        out.println("Endpoint discovery: <dataDir>/server-endpoint.json, then");
        out.println("  dcode-config.json httpPort, then the default port 58080.");
        out.println("Server launcher for `start`: -Ddcode.server.launcher=<path> or");
        out.println("  `dcode-headless` on PATH.");
    }

    // ------------------------------------------------------------ endpoint discovery

    /**
     * Resolves the endpoint of a live, ready DCode server, or {@code null} when none
     * is reachable. Sources, in order: the persisted actual endpoint
     * ({@code server-endpoint.json}), the configured {@code httpPort}, the default port.
     */
    private String discoverEndpoint() {
        for (String endpoint : EndpointResolver.candidates(EndpointResolver.dataDir())) {
            if (isReady(endpoint)) {
                return endpoint;
            }
        }
        return null;
    }

    private boolean isReady(String endpoint) {
        try {
            BackendProbe.ProbeResult result = new BackendProbe()
                    .probe(endpoint).get(3, TimeUnit.SECONDS);
            return result.state() == ConnectionState.READY;
        } catch (Exception e) {
            return false;
        }
    }

    private String waitForReady(int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            String endpoint = discoverEndpoint();
            if (endpoint != null) {
                return endpoint;
            }
            sleep(500);
        }
        return null;
    }

    private boolean waitForStop(String endpoint, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            if (!isReady(endpoint)) {
                return true;
            }
            sleep(400);
        }
        return false;
    }

    // ------------------------------------------------------------ helpers

    /** Read-only command requiring a running server: fetch a JSON array. */
    private JsonNode requireList(String path, String name) throws Exception {
        String endpoint = discoverEndpoint();
        if (endpoint == null) {
            throw new ServerUnavailableException(name);
        }
        return new DCodeApiClient(endpoint).get(path).get(15, TimeUnit.SECONDS);
    }

    private int unavailable(String command) {
        out.println("EVO-Code server is not running or cannot be reached.");
        out.println("Start it with `dcode-cli start` (or `dcode-headless` as a daemon),");
        out.println("then retry `dcode-cli " + command + "`.");
        return 1;
    }

    private String serverLauncher() {
        String configured = System.getProperty("dcode.server.launcher");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        return "dcode-headless";
    }

    private static List<String> applicationArgs(IApplicationContext context) {
        Object raw = context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        if (raw instanceof String[] array) {
            return List.of(array);
        }
        return List.of();
    }

    private static String normalize(String word) {
        String value = word == null ? "" : word.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private static String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        return current.getMessage() != null && !current.getMessage().isBlank()
                ? current.getMessage() : current.getClass().getSimpleName();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Raised when a read-only command needs a running server that is not reachable. */
    private static final class ServerUnavailableException extends RuntimeException {
        ServerUnavailableException(String command) {
            super("EVO-Code server is not running or cannot be reached (command: " + command + ")");
        }
    }
}
