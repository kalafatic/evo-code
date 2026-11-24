package eu.kalafatic.dcode.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.server.WebServerControl;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.osgi.framework.FrameworkUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * DCode headless application: starts the full server runtime without SWT, without a
 * Workbench and without a display (WP5.8 gate), and provides an ollama/opencode-style
 * command-line interface on top of the same server: {@code status}, {@code runtimes},
 * {@code providers}, {@code models}, {@code sessions}, {@code logs}, {@code web},
 * {@code terminal}, {@code agents}, {@code config}, {@code start}, {@code stop},
 * {@code restart}, {@code help} — all plain or slash-prefixed ({@code /status}).
 *
 * <p>Commands are defined once in a {@link #commands} table that both dispatches and
 * generates help — there is no separate static help text. With a command argument the
 * command runs and the process exits (0 clean, 1 error). Without a command and with an
 * interactive terminal the application starts a {@code dcode>} REPL dispatching through
 * the same table until {@code /exit} or EOF; without a terminal (daemon/service) the
 * server runs in the foreground until interrupted.</p>
 */
public final class HeadlessApplication implements IApplication {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final List<String[]> LOG_BUFFER = new ArrayList<>();
    private static final int LOG_BUFFER_SIZE = 500;

    /**
     * OS-level stdout (via the raw file descriptor), captured at class-load time:
     * the Equinox/OSGi console replaces the live {@code System.out} stream shortly
     * after application start, which would silently swallow every later log line of
     * the runtime.
     */
    private static final java.io.PrintStream CONSOLE = new java.io.PrintStream(
            new java.io.FileOutputStream(java.io.FileDescriptor.out), true);

    /** One registered CLI command: dispatch target and help source. */
    private record Command(String name, String syntax, String shortHelp,
                           String topicHelp, CommandHandler handler) {
    }

    /** Command implementation; returns the process exit code. */
    private interface CommandHandler {
        int run(List<String> args, String endpoint) throws Exception;
    }

    private final List<Command> commands = List.of(
            new Command("status", "status [runtime]",
                    "backend readiness and lifecycle stages",
                    """
                    status [runtime]
                      Backend readiness and lifecycle stages.
                      runtime    print the runtime registrations summary instead.""",
                    this::cmdStatus),
            new Command("runtimes", "runtimes",
                    "runtime registrations with live availability",
                    """
                    runtimes
                      Every registered runtime with type, endpoint, enabled/default
                      flags, reachability and model count (GET /api/v1/runtimes).""",
                    this::cmdRuntimes),
            new Command("providers", "providers [list|test <id>]",
                    "provider listing and connection tests",
                    """
                    providers [list|test <id>]
                      LLM providers of the backend configuration.
                      list          list all providers (default)
                      test <id>     test one provider connection
                                    (POST /api/v1/providers/<id>/test)""",
                    this::cmdProviders),
            new Command("models", "models [list|show <id>]",
                    "unified model catalogue",
                    """
                    models [list|show <id>]
                      The unified model catalogue (GET /api/v1/models).
                      list          list every model (default)
                      show <id>     details of one catalogue entry""",
                    this::cmdModels),
            new Command("sessions", "sessions [list|show <id>|delete <id>]",
                    "sessions per workspace",
                    """
                    sessions [list|show <id>|delete <id>]
                      Persisted sessions of every workspace.
                      list          sessions grouped by workspace (default)
                      show <id>     one session with its current state
                      delete <id>   delete the session (DELETE /api/v1/sessions/<id>)""",
                    this::cmdSessions),
            new Command("logs", "logs [show]",
                    "recent buffered log entries",
                    """
                    logs [show]
                      The recent log entries buffered by this process (up to 500).
                      show          same output (explicit form)""",
                    this::cmdLogs),
            new Command("web", "web",
                    "open the web interface in the system browser",
                    """
                    web
                      Opens the DCode web interface (the served SPA) in the system
                      browser (xdg-open).""",
                    this::cmdWeb),
            new Command("terminal", "terminal [dir]",
                    "open a system terminal (optionally in a directory)",
                    """
                    terminal [dir]
                      Opens a system terminal, optionally in the given directory
                      (default: the current directory).""",
                    this::cmdTerminal),
            new Command("agents", "agents [list]",
                    "agent list with the default marker",
                    """
                    agents [list]
                      Coding agents of the backend (GET /api/v1/agents) with role and
                      the default marker.
                      list          list all agents (default)""",
                    this::cmdAgents),
            new Command("config", "config [show]",
                    "effective config summary (no secrets)",
                    """
                    config [show]
                      Effective configuration summary: HTTP port, data directory and
                      workspaces directory. Secrets are never printed.
                      show          same output (explicit form)""",
                    this::cmdConfig),
            new Command("start", "start",
                    "verify/start the EVO-Code server on the configured port",
                    """
                    start
                      The DCode server starts automatically with the headless
                      application (daemon or interactive REPL). This command
                      verifies the server on the configured port and prints its
                      readiness; when nothing is listening it prints how to start
                      the server (launch dcode-headless without a command).""",
                    this::cmdStart),
            new Command("stop", "stop",
                    "stop the EVO-Code server process on this port",
                    """
                    stop
                      Stops the DCode server process listening on the configured port
                      (SIGTERM, escalating after 10 s). When this process owns the
                      port, send SIGTERM/Ctrl+C instead or run stop from another
                      process.""",
                    this::cmdStop),
            new Command("restart", "restart",
                    "stop the server (start it again afterwards)",
                    """
                    restart
                      Stops the DCode server process on the configured port; start
                      DCode again (the dcode-headless launcher or your service
                      manager) once the port is released.""",
                    this::cmdRestart),
            new Command("server-status", "server-status",
                    "local web server lifecycle state",
                    """
                    server-status
                      State of the web server running inside this process
                      (DISABLED / STOPPED / STARTING / RUNNING / STOPPING / FAILED)
                      with the bound URL when running.""",
                    this::cmdServerStatus),
            new Command("server-start", "server-start",
                    "start the web server in this process",
                    """
                    server-start
                      Starts the web server of this process. Use it when
                      webServer.autoStart=false in dcode-config.json. Fails when
                      webServer.enabled=false.""",
                    this::cmdServerStart),
            new Command("server-stop", "server-stop",
                    "stop the web server in this process",
                    """
                    server-stop
                      Stops the web server of this process, releasing its port.
                      The backend keeps running; server-start starts it again.""",
                    this::cmdServerStop),
            new Command("server-restart", "server-restart",
                    "restart the web server in this process",
                    """
                    server-restart
                      Clean stop/start sequence of the web server in this process.""",
                    this::cmdServerRestart),
            new Command("exit", "exit",
                    "leave the interactive REPL",
                    """
                    exit
                      Leaves the interactive dcode> prompt. As a launch argument it is
                      a no-op: the REPL only exists when DCode starts without a
                      command on an interactive terminal.""",
                    this::cmdExit),
            new Command("help", "help [topic]",
                    "this help, or the help of one command",
                    """
                    help [topic]
                      Without a topic: every command with syntax and short help.
                      With a topic: the detailed help of that one command.
                      /help /status /start /stop /restart /web /terminal /models
                      /sessions /providers /agents /config /logs /exit — slash
                      forms are equivalent.""",
                    this::cmdHelp));

    private volatile boolean stopped;

    @Override
    public Object start(IApplicationContext context) {
        List<String> args = applicationArgs(context);
        String first = args.isEmpty() ? "" : args.get(0);
        // Console output is preserved (captured stream — the OSGi console redirects
        // the live System.out after startup) AND the ring buffer for the `logs`
        // command records the same entries.
        DCodeLog.setSink((level, source, message, throwable) -> {
            CONSOLE.println(java.time.Instant.now() + " " + level.name() + " ["
                    + source + "] " + message);
            if (throwable != null) {
                throwable.printStackTrace(CONSOLE);
            }
            rememberLog(level, source, message);
        });
        CONSOLE.println("[EVO-Code] Headless runtime starting (no UI dependencies)");
        // The server itself is started by the ServerActivator at the SERVER lifecycle
        // stage; commands run against it once the port is published.
        // Commands always target the authoritative DCode port (see commandPort):
        // a second process issuing `status`/`stop` must observe the running server,
        // not its own transient instance.
        int configuredPort = commandPort();
        String endpoint = "http://127.0.0.1:" + configuredPort;
        if (!first.isBlank()) {
            // Thin-client separation: this binary is now the authoritative server only.
            // Command-line commands live in the thin `dcode-cli` client, which talks to
            // the server over HTTP without booting a runtime.
            CONSOLE.println("[EVO-Code] '" + first + "' is not a server command. "
                    + "Use the thin client: `dcode-cli " + first + "` (see dcode-cli help). "
                    + "`dcode-headless` runs the server only (daemon or interactive REPL).");
            return Integer.valueOf(2);
        }
        // Interactive terminal without a subcommand: dcode> REPL over the same table.
        if (System.console() != null) {
            if (awaitServer(configuredPort)) {
                return Integer.valueOf(1);
            }
            runRepl(endpoint);
            CONSOLE.println("[EVO-Code] Headless runtime stopped");
            return IApplication.EXIT_OK;
        }
        // Daemon/service: no TTY, no subcommand — run the server in the foreground.
        CONSOLE.println("[EVO-Code] No terminal and no subcommand — running as a service. "
                + "Commands: status runtimes providers models sessions logs web terminal "
                + "agents config start stop restart help");
        while (!stopped) {
            try {
                synchronized (this) {
                    wait();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        CONSOLE.println("[EVO-Code] Headless runtime stopped");
        return IApplication.EXIT_OK;
    }

    /**
     * Waits for this instance to bind the primary port (or for the port to be
     * occupied by another running DCode). Returns true when interrupted.
     */
    private boolean awaitServer(int configuredPort) {
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline
                && (System.getProperty("dcode.server.port") == null)
                && !portOccupied(configuredPort)) {
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return true;
            }
        }
        return false;
    }

    /** Interactive REPL: banner, prompt loop, dispatch through the command table. */
    private void runRepl(String endpoint) {
        java.io.Console console = System.console();
        CONSOLE.println("EVO-Code interactive console — type /help for commands, "
                + "/exit (or Ctrl+D) to quit.");
        while (!stopped) {
            String line = console.readLine("evo-code> ");
            if (line == null) { // EOF
                break;
            }
            line = line.trim();
            if (line.isEmpty()) {
                continue;
            }
            List<String> tokens = tokenize(line);
            String command = tokens.get(0);
            if (normalize(command).equals("exit")) {
                break;
            }
            dispatch(command, tokens.subList(1, tokens.size()), endpoint);
        }
        CONSOLE.println("[EVO-Code] Interactive console closed");
    }

    /**
     * Normalizes a command word: leading slash dropped, lowercased — plain and
     * slash forms are equivalent.
     */
    private static String normalize(String word) {
        String value = word == null ? "" : word.trim();
        while (value.startsWith("/")) {
            value = value.substring(1);
        }
        return value.toLowerCase(Locale.ROOT);
    }

    /** Dispatches one command line through the table; returns the exit code. */
    private int dispatch(String rawCommand, List<String> args, String endpoint) {
        String command = normalize(rawCommand);
        for (Command candidate : commands) {
            if (candidate.name().equals(command)) {
                try {
                    return candidate.handler().run(args, endpoint);
                } catch (Exception e) {
                    CONSOLE.println("[EVO-Code] " + rawCommand + " failed: " + e.getMessage());
                    return 1;
                }
            }
        }
        CONSOLE.println("Unknown command '" + rawCommand + "'." + suggestion(command));
        return 1;
    }

    /** "Did you mean" over the registered table: prefix matches + edit distance. */
    private String suggestion(String command) {
        if (command.isBlank()) {
            return "";
        }
        List<Command> candidates = new ArrayList<>();
        for (Command candidate : commands) {
            String name = candidate.name();
            if (name.startsWith(command)) {
                candidates.add(candidate);
                continue;
            }
            if (editDistance(command, name) <= 2) {
                candidates.add(candidate);
            }
        }
        candidates.sort(Comparator
                .comparingInt((Command c) -> editDistance(command, c.name()))
                .thenComparing(Command::name));
        if (candidates.isEmpty()) {
            return "";
        }
        List<String> names = candidates.stream().limit(3).map(Command::name).toList();
        return " Did you mean: " + String.join(", ", names) + "?";
    }

    /** Classic Levenshtein distance (unbounded; words are short). */
    private static int editDistance(String a, String b) {
        int[] previous = new int[b.length() + 1];
        int[] current = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            previous[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            current[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int substitution = previous[j - 1] + (a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1);
                current[j] = Math.min(Math.min(previous[j] + 1, current[j - 1] + 1),
                        substitution);
            }
            int[] swap = previous;
            previous = current;
            current = swap;
        }
        return previous[b.length()];
    }

    /** Splits a REPL line into tokens (double quotes group words with spaces). */
    private static List<String> tokenize(String line) {
        List<String> tokens = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                quoted = !quoted;
            } else if (Character.isWhitespace(c) && !quoted) {
                if (current.length() > 0) {
                    tokens.add(current.toString());
                    current.setLength(0);
                }
            } else {
                current.append(c);
            }
        }
        if (current.length() > 0) {
            tokens.add(current.toString());
        }
        return tokens;
    }

    // ---- command handlers (the single implementation per command) ----

    private int cmdStatus(List<String> args, String endpoint) throws Exception {
        String topic = normalize(firstArg(args, ""));
        if (topic.equals("runtime")) {
            return cmdRuntimes(List.of(), endpoint);
        }
        JsonNode status = getJson(endpoint + "/api/v1/status");
        JsonNode ports = getJson(endpoint + "/api/v1/ports");
        CONSOLE.println("EVO-Code status: ready=" + status.path("ready").asBoolean());
        status.path("stages").properties().forEach(entry ->
                CONSOLE.println("  " + entry.getKey() + ": "
                        + entry.getValue().asText()));
        for (JsonNode port : ports) {
            CONSOLE.println("  port " + port.path("id").asText() + ": "
                    + port.path("actualPort").asInt() + " ("
                    + port.path("state").asText() + ")");
        }
        return 0;
    }

    private int cmdRuntimes(List<String> args, String endpoint) throws Exception {
        JsonNode runtimes = getJson(endpoint + "/api/v1/runtimes");
        CONSOLE.println("Runtimes (" + runtimes.size() + "):");
        for (JsonNode runtime : runtimes) {
            CONSOLE.println("  " + runtime.path("name").asText(
                    runtime.path("id").asText("")) + "  " + runtime.path("type")
                    .asText("") + "  " + runtime.path("endpoint").asText("")
                    + "  enabled=" + runtime.path("enabled").asBoolean()
                    + "  default=" + runtime.path("default").asBoolean()
                    + "  reachable=" + runtime.path("status").path("reachable")
                    .asBoolean() + "  models=" + runtime.path("modelCount").asInt(-1)
                    + (runtime.path("status").path("message").asText("").isBlank()
                    ? "" : "  [" + runtime.path("status").path("message").asText("")
                    + "]"));
        }
        return 0;
    }

    private int cmdProviders(List<String> args, String endpoint) throws Exception {
        String sub = normalize(firstArg(args, "list"));
        if (sub.equals("list") || sub.isBlank()) {
            JsonNode providers = getJson(endpoint + "/api/v1/providers");
            CONSOLE.println("Providers (" + providers.size() + "):");
            for (JsonNode provider : providers) {
                CONSOLE.println("  " + provider.path("name").asText(
                        provider.path("id").asText("")) + "  " + provider.path("type")
                        .asText("") + "  " + provider.path("endpoint").asText(""));
            }
            return 0;
        }
        if (sub.equals("test")) {
            String id = args.size() > 1 ? args.get(1) : "";
            if (id.isBlank()) {
                CONSOLE.println("Usage: providers test <id>");
                return 1;
            }
            JsonNode result = postJson(endpoint + "/api/v1/providers/"
                    + java.net.URLEncoder.encode(id, java.nio.charset.StandardCharsets.UTF_8)
                    + "/test");
            CONSOLE.println("Provider " + id + " test: "
                    + (result.path("success").asBoolean() ? "OK" : "FAILED")
                    + " — " + result.path("message").asText());
            return result.path("success").asBoolean() ? 0 : 1;
        }
        CONSOLE.println("Usage: providers [list|test <id>]");
        return 1;
    }

    private int cmdModels(List<String> args, String endpoint) throws Exception {
        String sub = normalize(firstArg(args, "list"));
        JsonNode models = getJson(endpoint + "/api/v1/models");
        if (sub.equals("show")) {
            String id = args.size() > 1 ? args.get(1) : "";
            if (id.isBlank()) {
                CONSOLE.println("Usage: models show <id>");
                return 1;
            }
            for (JsonNode model : models) {
                if (model.path("id").asText("").equals(id)) {
                    CONSOLE.println("Model " + id);
                    CONSOLE.println("  runtime:   " + model.path("runtimeId").asText(""));
                    CONSOLE.println("  size:      " + model.path("sizeBytes").asLong(0));
                    CONSOLE.println("  quant:     " + model.path("quantization").asText("n/a"));
                    CONSOLE.println("  context:   " + model.path("contextWindowTokens").asLong(0));
                    CONSOLE.println("  available: " + model.path("available").asBoolean());
                    CONSOLE.println("  loaded:    " + model.path("loaded").asBoolean());
                    JsonNode capabilities = model.path("capabilities");
                    CONSOLE.println("  caps:      " + (capabilities.isArray()
                            ? capabilities.toString() : "n/a"));
                    return 0;
                }
            }
            CONSOLE.println("No model '" + id + "' in the catalogue.");
            return 1;
        }
        if (!sub.equals("list") && !sub.isBlank()) {
            CONSOLE.println("Usage: models [list|show <id>]");
            return 1;
        }
        CONSOLE.println("Models (" + models.size() + "):");
        for (JsonNode model : models) {
            CONSOLE.println("  " + model.path("id").asText("") + "  runtime="
                    + model.path("runtimeId").asText("") + "  size="
                    + model.path("sizeBytes").asLong(0) + "  available="
                    + model.path("available").asBoolean());
        }
        return 0;
    }

    private int cmdSessions(List<String> args, String endpoint) throws Exception {
        String sub = normalize(firstArg(args, "list"));
        if (sub.equals("show")) {
            String id = args.size() > 1 ? args.get(1) : "";
            if (id.isBlank()) {
                CONSOLE.println("Usage: sessions show <id>");
                return 1;
            }
            JsonNode session = getJson(endpoint + "/api/v1/sessions/" + id);
            CONSOLE.println("Session " + id);
            CONSOLE.println("  title:     " + session.path("title").asText(""));
            CONSOLE.println("  state:     " + session.path("state").asText(""));
            CONSOLE.println("  workspace: " + session.path("workspaceId").asText(""));
            CONSOLE.println("  agentRole: " + session.path("agentRole").asText(""));
            CONSOLE.println("  provider:  " + session.path("providerId").asText(""));
            CONSOLE.println("  model:     " + session.path("modelId").asText(""));
            return 0;
        }
        if (sub.equals("delete")) {
            String id = args.size() > 1 ? args.get(1) : "";
            if (id.isBlank()) {
                CONSOLE.println("Usage: sessions delete <id>");
                return 1;
            }
            JsonNode result = deleteJson(endpoint + "/api/v1/sessions/" + id);
            CONSOLE.println("Session " + result.path("id").asText(id) + " deleted: "
                    + result.path("deleted").asBoolean());
            return 0;
        }
        if (!sub.equals("list") && !sub.isBlank()) {
            CONSOLE.println("Usage: sessions [list|show <id>|delete <id>]");
            return 1;
        }
        JsonNode workspaces = getJson(endpoint + "/api/v1/workspaces");
        for (JsonNode workspace : workspaces) {
            JsonNode sessions = getJson(endpoint + "/api/v1/sessions?workspaceId="
                    + java.net.URLEncoder.encode(workspace.path("id").asText(""),
                    java.nio.charset.StandardCharsets.UTF_8));
            if (sessions.size() > 0) {
                CONSOLE.println("Workspace " + workspace.path("name").asText(""));
            }
            for (JsonNode session : sessions) {
                CONSOLE.println("  " + session.path("id").asText("") + "  "
                        + session.path("title").asText("") + "  ["
                        + session.path("state").asText("") + "]");
            }
        }
        return 0;
    }

    private int cmdLogs(List<String> args, String endpoint) {
        String sub = normalize(firstArg(args, "show"));
        if (!sub.equals("show") && !sub.isBlank()) {
            CONSOLE.println("Usage: logs [show]");
            return 1;
        }
        synchronized (LOG_BUFFER) {
            if (LOG_BUFFER.isEmpty()) {
                CONSOLE.println("(no buffered log entries)");
            }
            for (String[] entry : LOG_BUFFER) {
                CONSOLE.println(entry[0] + " [" + entry[1] + "] " + entry[2]);
            }
        }
        return 0;
    }

    private int cmdWeb(List<String> args, String endpoint) {
        openInSystem(endpoint + "/", false);
        return 0;
    }

    private int cmdTerminal(List<String> args, String endpoint) {
        String directory = firstArg(args, "");
        openInSystem(directory.isBlank() ? "." : directory, true);
        return 0;
    }

    private int cmdAgents(List<String> args, String endpoint) throws Exception {
        String sub = normalize(firstArg(args, "list"));
        if (!sub.equals("list") && !sub.isBlank()) {
            CONSOLE.println("Usage: agents [list]");
            return 1;
        }
        JsonNode agents = getJson(endpoint + "/api/v1/agents");
        CONSOLE.println("Agents (" + agents.size() + "):");
        for (JsonNode agent : agents) {
            CONSOLE.println("  " + agent.path("name").asText(
                    agent.path("id").asText("")) + "  " + agent.path("role").asText("")
                    + (agent.path("default").asBoolean() ? "  [default]" : "")
                    + (agent.path("builtin").asBoolean() ? "  (built-in)" : ""));
        }
        return 0;
    }

    private int cmdConfig(List<String> args, String endpoint) {
        String sub = normalize(firstArg(args, "show"));
        if (!sub.equals("show") && !sub.isBlank()) {
            CONSOLE.println("Usage: config [show]");
            return 1;
        }
        int configuredPort = commandPort();
        String boundPort = System.getProperty("dcode.server.port");
        java.nio.file.Path dataDir = java.nio.file.Path.of(System.getProperty("dcode.home",
                java.nio.file.Path.of(System.getProperty("user.home", "."), ".dcode")
                        .toString()));
        CONSOLE.println("EVO-Code configuration:");
        CONSOLE.println("  port:       " + configuredPort
                + (boundPort == null ? " (not bound in this process)"
                : " (bound: " + boundPort + ")"));
        WebServerControl control = localServerControl();
        CONSOLE.println("  web server: " + (control == null
                ? "not controlled from this process"
                : control.state() + (control.isRunning()
                        ? " (" + control.url() + ")" : "")));
        CONSOLE.println("  data dir:   " + dataDir);
        CONSOLE.println("  workspaces: " + dataDir.resolve("workspaces"));
        CONSOLE.println("  endpoint:   " + endpoint);
        return 0;
    }

    private int cmdStart(List<String> args, String endpoint) throws Exception {
        int configuredPort = commandPort();
        if (portOccupied(configuredPort)) {
            JsonNode status = getJson(endpoint + "/api/v1/status");
            CONSOLE.println("[EVO-Code] Server already running on port " + configuredPort
                    + " — ready=" + status.path("ready").asBoolean());
            return 0;
        }
        // auto-start disabled: start the server inside this process instead.
        WebServerControl control = localServerControl();
        if (control != null) {
            try {
                control.start();
                CONSOLE.println("[EVO-Code] Web server started in this process: "
                        + control.url());
                return 0;
            } catch (RuntimeException e) {
                CONSOLE.println("[EVO-Code] Failed: " + e.getMessage());
                return 1;
            }
        }
        CONSOLE.println("[EVO-Code] Nothing is listening on port " + configuredPort
                + ". Start EVO-Code with:");
        CONSOLE.println("  dcode-headless   (daemon or interactive evo-code> REPL)");
        return 1;
    }

    private int cmdStop(List<String> args, String endpoint) throws Exception {
        stopServerProcess();
        return 0;
    }

    private int cmdRestart(List<String> args, String endpoint) throws Exception {
        stopServerProcess();
        CONSOLE.println("[EVO-Code] Restart: start EVO-Code again (e.g. the "
                + "dcode-headless launcher or your service manager) once the "
                + "port is released.");
        return 0;
    }

    private int cmdServerStatus(List<String> args, String endpoint) {
        WebServerControl control = localServerControl();
        if (control == null) {
            CONSOLE.println("[EVO-Code] No local server control in this process.");
            return 1;
        }
        CONSOLE.println("[EVO-Code] Web server state: " + control.state()
                + (control.isRunning() ? " (" + control.url() + ")" : ""));
        return 0;
    }

    private int cmdServerStart(List<String> args, String endpoint) {
        WebServerControl control = localServerControl();
        if (control == null) {
            CONSOLE.println("[EVO-Code] No local server control in this process.");
            return 1;
        }
        try {
            control.start();
            CONSOLE.println("[EVO-Code] Web server started: " + control.url());
            return 0;
        } catch (RuntimeException e) {
            CONSOLE.println("[EVO-Code] Failed to start web server: " + e.getMessage());
            return 1;
        }
    }

    private int cmdServerStop(List<String> args, String endpoint) {
        WebServerControl control = localServerControl();
        if (control == null) {
            CONSOLE.println("[EVO-Code] No local server control in this process.");
            return 1;
        }
        control.stop();
        CONSOLE.println("[EVO-Code] Web server stopped (state: " + control.state() + ").");
        return 0;
    }

    private int cmdServerRestart(List<String> args, String endpoint) {
        WebServerControl control = localServerControl();
        if (control == null) {
            CONSOLE.println("[EVO-Code] No local server control in this process.");
            return 1;
        }
        try {
            control.restart();
            CONSOLE.println("[EVO-Code] Web server restarted: " + control.url());
            return 0;
        } catch (RuntimeException e) {
            CONSOLE.println("[EVO-Code] Failed to restart web server: " + e.getMessage());
            return 1;
        }
    }

    /** Local in-process server control (the WebServerControl OSGi service). */
    private WebServerControl localServerControl() {
        try {
            var bundle = FrameworkUtil.getBundle(HeadlessApplication.class);
            if (bundle == null) {
                return null;
            }
            var context = bundle.getBundleContext();
            if (context == null) {
                return null;
            }
            var reference = context.getServiceReference(WebServerControl.class);
            return reference == null ? null : context.getService(reference);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private int cmdExit(List<String> args, String endpoint) {
        CONSOLE.println("[EVO-Code] /exit only applies to the interactive prompt — start "
                + "EVO-Code without a command on a terminal to enter the REPL.");
        return 0;
    }

    private int cmdHelp(List<String> args, String endpoint) {
        String topic = normalize(firstArg(args, ""));
        if (!topic.isBlank()) {
            for (Command command : commands) {
                if (command.name().equals(topic)) {
                    CONSOLE.println(command.topicHelp());
                    return 0;
                }
            }
            CONSOLE.println("Unknown command '" + topic + "'." + suggestion(topic));
            return 1;
        }
        CONSOLE.println("EVO-Code headless commands (against the same backend as RCP and Web):");
        for (Command command : commands) {
            CONSOLE.printf("  %-38s %s%n", command.syntax(), command.shortHelp());
        }
        CONSOLE.println("Configuration: -Ddcode.port=N, -Ddcode.home=…, "
                + "-Ddcode.enr.endpoint=…");
        return 0;
    }

    private static String firstArg(List<String> args, String defaultValue) {
        return args == null || args.isEmpty() ? defaultValue : args.get(0);
    }

    private static boolean portOccupied(int port) {
        return eu.kalafatic.dcode.core.port.PortInspector.occupant(port).isPresent();
    }

    /**
     * Authoritative port for CLI commands: the port the embedded server actually
     * bound ({@code dcode.server.port}, published by the server bundle), else the
     * explicit CLI override ({@code dcode.port}), else the persisted
     * configuration ({@code httpPort} in dcode-config.json — covers fallback
     * allocations), else the family default. The CLI never invents its own port.
     */
    private static int commandPort() {
        String bound = System.getProperty("dcode.server.port");
        if (bound != null && !bound.isBlank()) {
            try {
                return Integer.parseInt(bound.trim());
            } catch (NumberFormatException e) {
                // fall through to the next source
            }
        }
        String override = System.getProperty("dcode.port");
        if (override != null && !override.isBlank()) {
            try {
                return Integer.parseInt(override.trim());
            } catch (NumberFormatException e) {
                // fall through to the next source
            }
        }
        int persisted = persistedHttpPort();
        return persisted > 0 ? persisted
                : eu.kalafatic.dcode.core.port.DCodePorts.HTTP;
    }

    /** Reads the persisted primary port from dcode-config.json (0 when absent). */
    private static int persistedHttpPort() {
        try {
            java.nio.file.Path dataDir = java.nio.file.Path.of(
                    System.getProperty("dcode.home",
                            java.nio.file.Path.of(
                                    System.getProperty("user.home", "."), ".dcode")
                                    .toString()));
            java.nio.file.Path configFile = dataDir.resolve("dcode-config.json");
            if (!java.nio.file.Files.isRegularFile(configFile)) {
                return 0;
            }
            String content = java.nio.file.Files.readString(configFile);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"httpPort\"\\s*:\\s*(\\d+)").matcher(content);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private void stopServerProcess() throws Exception {
        int port = commandPort();
        var occupant = eu.kalafatic.dcode.core.port.PortInspector.occupant(port);
        if (occupant.isEmpty()) {
            CONSOLE.println("[EVO-Code] Nothing is listening on port " + port);
            return;
        }
        long pid = occupant.get().pid();
        if (pid == ProcessHandle.current().pid()) {
            CONSOLE.println("[EVO-Code] This process owns port " + port
                    + " — send SIGTERM (Ctrl+C) or run stop from another process.");
            return;
        }
        CONSOLE.println("[EVO-Code] Stopping EVO-Code (pid " + pid + " on port " + port
                + ")…");
        ProcessHandle.of(pid).ifPresent(handle -> {
            handle.destroy();
            try {
                if (!handle.onExit().get(10, java.util.concurrent.TimeUnit.SECONDS).equals(handle)) {
                    // did not exit within 10s: escalate
                    handle.destroyForcibly();
                }
            } catch (Exception ignored) {
                handle.destroyForcibly();
            }
        });
    }

    private static void openInSystem(String target, boolean terminal) {
        String[] command;
        if (terminal) {
            command = new String[]{"x-terminal-emulator", "--working-directory=" + target};
        } else {
            command = new String[]{"xdg-open", target};
        }
        try {
            new ProcessBuilder(command).inheritIO().start();
            CONSOLE.println("[EVO-Code] Opened " + target);
        } catch (Exception e) {
            CONSOLE.println("[EVO-Code] Cannot open " + target + ": " + e.getMessage());
        }
    }

    private static void rememberLog(DCodeLog.Level level, String source, String message) {
        synchronized (LOG_BUFFER) {
            LOG_BUFFER.add(new String[]{Instant.now().toString(),
                    level.name() + "/" + source, message});
            while (LOG_BUFFER.size() > LOG_BUFFER_SIZE) {
                LOG_BUFFER.remove(0);
            }
        }
    }

    private static JsonNode getJson(String url) throws Exception {
        return requestJson("GET", url, null);
    }

    private static JsonNode postJson(String url) throws Exception {
        return requestJson("POST", url, "{}");
    }

    private static JsonNode deleteJson(String url) throws Exception {
        return requestJson("DELETE", url, null);
    }

    private static JsonNode requestJson(String method, String url, String body)
            throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10));
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        } else {
            builder.header("Content-Type", "application/json");
            builder.method(method, HttpRequest.BodyPublishers.ofString(body));
        }
        HttpResponse<String> response = HttpClient.newHttpClient().send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("HTTP " + response.statusCode() + " from "
                    + url + ": " + response.body());
        }
        return MAPPER.readTree(response.body());
    }

    private static List<String> applicationArgs(IApplicationContext context) {
        Object raw = context.getArguments().get(IApplicationContext.APPLICATION_ARGS);
        if (raw instanceof String[] array) {
            return List.of(array);
        }
        return List.of();
    }

    @Override
    public void stop() {
        stopped = true;
        synchronized (this) {
            notifyAll();
        }
    }
}
