package eu.kalafatic.dcode.ui;

/**
 * Help launcher: opens the served {@code /help.html} page of the DCode backend at a
 * topic anchor. Used by the Help menu and the F1 context-help key. The endpoint is
 * always taken from the live connection state (the same mechanism as
 * {@link DesktopActions#openWebInterface()}), never hardcoded.
 */
public final class HelpLauncher {

    /** Relative path of the served help page. */
    public static final String HELP_PATH = "/help.html";

    /** Default anchor of the help page (overview). */
    public static final String TOPIC_TOP = "top";

    /** Fallback anchor for views without a dedicated section. */
    public static final String TOPIC_CONTEXT = "context";

    private HelpLauncher() {
    }

    /** Opens the help page at the given anchor (e.g. {@code getting-started}). */
    public static void openHelpTopic(String anchor) {
        String topic = anchor == null || anchor.isBlank() ? TOPIC_TOP : anchor;
        String baseUrl = baseUrl();
        if (baseUrl == null) {
            Ui.log("error", "No EVO-Code web server known — help is served by the "
                    + "verified EVO-Code backend only. Fix the connection "
                    + "(Window ▸ Connection) first.");
            return;
        }
        DesktopActions.launchBrowser(baseUrl + HELP_PATH + "#" + topic);
    }

    /** Opens the help overview ("Help Contents"). */
    public static void openHelpContents() {
        openHelpTopic(TOPIC_TOP);
    }

    /** Opens the context-help section for the given part (view/editor) id. */
    public static void contextHelpFor(String partId) {
        openHelpTopic(anchorFor(partId));
    }

    /** Maps a workbench part id to its help anchor (unknown parts fall back). */
    static String anchorFor(String partId) {
        if (partId == null || partId.isBlank()) {
            return TOPIC_CONTEXT;
        }
        return switch (partId) {
            case "eu.kalafatic.dcode.ui.views.workspace" -> "context-workspace";
            case "eu.kalafatic.dcode.ui.views.sessions",
                    "eu.kalafatic.dcode.ui.views.sessionnavigator" -> "context-sessions";
            case "eu.kalafatic.dcode.ui.views.chat",
                    "eu.kalafatic.dcode.ui.views.aiworkspace",
                    "eu.kalafatic.dcode.ui.views.directchat",
                    "eu.kalafatic.dcode.ui.views.inference" -> "context-chat";
            case "eu.kalafatic.dcode.ui.views.console" -> "context-console";
            case "eu.kalafatic.dcode.ui.views.runtimes",
                    "eu.kalafatic.dcode.ui.views.runtimeexplorer",
                    "eu.kalafatic.dcode.ui.views.runtimeselector",
                    "eu.kalafatic.dcode.ui.views.models" -> "context-runtimes-models";
            case "eu.kalafatic.dcode.ui.views.providers" -> "context-providers";
            case "eu.kalafatic.dcode.ui.views.agentexplorer" -> "context-agents";
            case "eu.kalafatic.dcode.ui.views.activity" -> "context-activity";
            case "eu.kalafatic.dcode.ui.views.changes" -> "context-changes";
            default -> TOPIC_CONTEXT;
        };
    }

    /**
     * Resolves the base URL of the DCode web surface through the single shared
     * resolver ({@link RuntimeConnection#webBaseUrl()}): the verified DCode backend
     * or the embedded local server. Returns {@code null} when neither exists —
     * callers show guidance instead of opening a foreign endpoint.
     */
    static String baseUrl() {
        return RuntimeConnection.instance().webBaseUrl();
    }
}
