package eu.kalafatic.dcode.ui;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * Desktop-facing actions shared by the menu bar and views: open the web interface
 * in the system browser and open a system terminal in the active workspace. The
 * endpoint/port is always taken from the live connection state, never hardcoded.
 */
public final class DesktopActions {

    private DesktopActions() {
    }

    /**
     * Opens the DCode web interface in the system browser. Only verified DCode
     * backends are opened — a wrong/unverified endpoint (e.g. an ENR runtime address
     * stored as the connection endpoint) never results in launching a foreign web
     * surface. The local embedded server wins over unverified endpoints and is
     * started on demand when the local control exists but the server is stopped.
     */
    public static void openWebInterface() {
        String baseUrl = RuntimeConnection.instance().webBaseUrl();
        if (baseUrl != null && !baseUrl.isBlank()) {
            launchBrowser(baseUrl + "/");
            return;
        }
        var control = WebServerControlAccess.get();
        if (control != null) {
            try {
                if (!control.isRunning()) {
                    control.start();
                }
            } catch (RuntimeException e) {
                Ui.log("warning", "Cannot start the local EVO-Code web server: "
                        + e.getMessage());
            }
            String url = control.url();
            if (url != null) {
                launchBrowser(url);
                return;
            }
            Ui.log("error", "The local EVO-Code web server is " + control.state()
                    + " and cannot be opened. Check the configuration "
                    + "(webServer.enabled) or use Web Interface ▸ Check Status.");
            return;
        }
        Ui.log("error", "No EVO-Code web server known — the connected endpoint is "
                + "not a verified EVO-Code backend and no local server is running. "
                + "Fix the connection (Window ▸ Connection) first.");
    }

    /** Opens a system terminal in the active workspace directory. */
    public static void openTerminal() {
        String directory = activeWorkspaceDirectory();
        if (directory == null || directory.isBlank()) {
            directory = System.getProperty("user.home", ".");
        }
        openTerminalIn(directory);
    }

    /**
     * Opens a system terminal in the given directory (same single implementation
     * used by the Tools menu and the explorer context menus).
     */
    public static void openTerminalIn(String directory) {
        if (directory == null || directory.isBlank()) {
            directory = System.getProperty("user.home", ".");
        }
        String[] command = terminalCommand(directory);
        if (command == null) {
            Ui.log("error", "No supported terminal emulator found on this system "
                    + "(tried x-terminal-emulator, gnome-terminal, konsole, xterm).");
            return;
        }
        try {
            new ProcessBuilder(command).start();
            Ui.log("terminal", "Opened terminal in " + directory);
        } catch (IOException e) {
            Ui.log("error", "Failed to open terminal: " + e.getMessage());
        }
    }

    /** Resolves the root path of the workspace of the active session, if any. */
    public static String activeWorkspaceDirectory() {
        var session = RuntimeConnection.instance().activeSession();
        if (session == null) {
            return null;
        }
        try {
            var workspace = RuntimeConnection.instance().api()
                    .getWorkspace(session.getWorkspaceId()).get(5,
                            java.util.concurrent.TimeUnit.SECONDS);
            return workspace.rootPath();
        } catch (Exception e) {
            return null;
        }
    }

    /** Opens a URL in the system browser (shared with {@link HelpLauncher}). */
    static void launchBrowser(String url) {
        org.eclipse.swt.program.Program.launch(url);
    }

    private static String[] terminalCommand(String directory) {
        if (hasCommand("x-terminal-emulator")) {
            return new String[]{"x-terminal-emulator", "--working-directory="
                    + directory};
        }
        if (hasCommand("gnome-terminal")) {
            return new String[]{"gnome-terminal", "--working-directory=" + directory};
        }
        if (hasCommand("konsole")) {
            return new String[]{"konsole", "--workdir", directory};
        }
        if (hasCommand("xterm")) {
            return new String[]{"xterm", "-e", "cd " + shellQuote(directory)
                    + " && $SHELL"};
        }
        return null;
    }

    private static boolean hasCommand(String command) {
        String path = System.getenv("PATH");
        if (path == null) {
            return false;
        }
        for (String dir : List.of(path.split(":"))) {
            if (java.nio.file.Files.isExecutable(Path.of(dir, command))) {
                return true;
            }
        }
        return false;
    }

    private static String shellQuote(String value) {
        return "'" + value.replace("'", "'\\''") + "'";
    }
}
