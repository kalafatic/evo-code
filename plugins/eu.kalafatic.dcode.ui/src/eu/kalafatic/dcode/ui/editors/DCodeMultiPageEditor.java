package eu.kalafatic.dcode.ui.editors;

import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.browser.Browser;
import org.eclipse.swt.browser.LocationEvent;
import org.eclipse.swt.browser.LocationListener;
import org.eclipse.swt.browser.ProgressEvent;
import org.eclipse.swt.browser.ProgressListener;
import org.eclipse.swt.browser.TitleEvent;
import org.eclipse.swt.browser.TitleListener;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.MultiPageEditorPart;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * DCode harness editor — the main working area of the workbench, opened
 * automatically at startup.
 *
 * <ul>
 *   <li><b>Web</b> (initial page): an SWT Browser hosting the <em>actual</em> DCode
 *       web application over its HTTP endpoint — the same frontend, harness,
 *       inference, sessions, streaming and tool calling as an external browser.</li>
 *   <li><b>Settings</b>: the settings web page served by the same backend — a pure
 *       HTTP page (no JavaScript-to-Java bridge); it works identically in an
 *       external browser.</li>
 * </ul>
 *
 * <p>Both pages use the same bounded, non-blocking readiness sequence: resolve the
 * configured web endpoint, wait for the page to become reachable, then navigate on
 * the UI thread. The editor follows the live connection — when the backend changes,
 * the pages reload.</p>
 */
public final class DCodeMultiPageEditor extends MultiPageEditorPart {

    public static final String EDITOR_ID = "eu.kalafatic.dcode.ui.editors.multi";
    private static final int READINESS_ATTEMPTS = 90;
    private static final long READINESS_INTERVAL_MILLIS = 1000;

    private final ExecutorService readinessExecutor =
            Executors.newSingleThreadExecutor(r -> {
                Thread thread = new Thread(r, "dcode-editor-readiness");
                thread.setDaemon(true);
                return thread;
            });
    private final AtomicLong readinessGeneration = new AtomicLong();
    private volatile boolean disposed;
    private volatile boolean webLoaded;
    private volatile String resolvedWebUrl;
    private volatile String webStatusSnapshot = "Connecting to the EVO-Code web "
            + "application…";
    private volatile String lastEndpointSnapshot;
    private volatile boolean settingsReady;
    private volatile String settingsDiag = "settings page not opened yet";
    private RuntimeConnection.ConnectionStateListener connectionListener;

    private Browser webBrowser;
    private Label webStatus;
    private Button webReload;
    private Color errorColor;

    private Browser settingsBrowser;
    private boolean settingsLoaded;

    @Override
    protected void createPages() {
        Ui.log("info", "Creating the harness editor pages (Web + Settings)…");
        Composite webComposite = new Composite(getContainer(), SWT.NONE);
        webComposite.setLayout(new GridLayout(1, false));

        Composite webBar = new Composite(webComposite, SWT.NONE);
        webBar.setLayout(new GridLayout(3, false));
        webBar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        webStatus = new Label(webBar, SWT.NONE);
        webStatus.setText("Connecting to the EVO-Code web application…");
        webStatus.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        Button openBrowser = new Button(webBar, SWT.PUSH);
        openBrowser.setText("Open in Browser");
        openBrowser.setToolTipText("Open the full EVO-Code web UI in your system browser");
        openBrowser.addListener(SWT.Selection,
                event -> eu.kalafatic.dcode.ui.DesktopActions.openWebInterface());
        webReload = new Button(webBar, SWT.PUSH);
        webReload.setText("Reload");
        webReload.setToolTipText("Re-check the EVO-Code web application and reload it");
        webReload.addListener(SWT.Selection, event -> reloadWeb());

        try {
            webBrowser = new Browser(webComposite, SWT.NONE);
            webBrowser.setJavascriptEnabled(true);
            webBrowser.setLayoutData(new GridData(GridData.FILL_BOTH));
            installWebListeners(webBrowser);
            System.out.println("[EVO-Code:editor] Web Browser created (WebKit).");
        } catch (org.eclipse.swt.SWTError | RuntimeException error) {
            webBrowser = null;
            Label fallback = new Label(webComposite, SWT.WRAP);
            fallback.setLayoutData(new GridData(GridData.FILL_BOTH));
            fallback.setText("The embedded web application needs WebKit "
                    + "(SWT Browser).\nIt could not be created on this system: "
                    + error.getMessage());
            webStatus.setText("SWT Browser unavailable.");
            System.err.println("[EVO-Code:editor] SWT Browser creation failed: "
                    + error);
        }
        addPage(webComposite);
        setPageText(0, "Web");

        Composite settingsComposite = new Composite(getContainer(), SWT.NONE);
        settingsComposite.setLayout(new GridLayout(1, false));
        addPage(settingsComposite);
        setPageText(1, "Settings");

        setActivePage(0);
        connectWebApplication();
        installBackendTracking();
    }

    @Override
    protected void pageChange(int newPageIndex) {
        super.pageChange(newPageIndex);
        if (newPageIndex == 1 && !settingsLoaded && settingsBrowser == null) {
            settingsLoaded = true;
            settingsDiag = "creating settings browser…";
            Composite parent = (Composite) getControl(1);
            try {
                settingsBrowser = new Browser(parent, SWT.NONE);
                settingsBrowser.setJavascriptEnabled(true);
                settingsBrowser.setLayoutData(new GridData(GridData.FILL_BOTH));
                settingsBrowser.addTitleListener(new TitleListener() {
                    @Override
                    public void changed(TitleEvent event) {
                        if (event.title != null
                                && event.title.contains("EVO-Code Settings")) {
                            settingsReady = true;
                            settingsDiag = "settings page ready (title)";
                            Ui.log("success", "Settings page loaded its "
                                    + "configuration from the backend.");
                        }
                    }
                });
                settingsDiag = "waiting for the settings page endpoint…";
                loadSettingsPage();
            } catch (org.eclipse.swt.SWTError | RuntimeException error) {
                settingsDiag = "settings browser failed: " + error;
                Label fallback = new Label(parent, SWT.WRAP);
                fallback.setLayoutData(new GridData(GridData.FILL_BOTH));
                fallback.setText("The settings page needs WebKit (SWT Browser).\n"
                        + error.getMessage());
            }
            parent.layout(true, true);
        }
    }

    // ---- Web application page ----

    private void installWebListeners(Browser browser) {
        browser.addLocationListener(new LocationListener() {
            @Override
            public void changing(LocationEvent event) {
                // normal navigation is allowed (the web app is the same frontend an
                // external browser uses)
            }

            @Override
            public void changed(LocationEvent event) {
                String location = event.location;
                if (location == null || location.isBlank()) {
                    return;
                }
                if (location.startsWith("http://") || location.startsWith("https://")) {
                    webLoaded = true;
                    Ui.log("info", "Web application navigated to " + location);
                }
            }
        });
        browser.addProgressListener(new ProgressListener() {
            @Override
            public void changed(ProgressEvent event) {
                // progressive state; completion is logged below
            }

            @Override
            public void completed(ProgressEvent event) {
                Ui.log("info", "Web application page loaded.");
            }
        });
        browser.addTitleListener(new TitleListener() {
            @Override
            public void changed(TitleEvent event) {
                if (webLoaded) {
                    setWebStatus(event.title == null || event.title.isBlank()
                            ? "Web application loaded." : event.title);
                }
                Ui.log("info", "Web application title: " + event.title);
            }
        });
    }

    /**
     * Non-blocking readiness sequence: resolve the configured web endpoint, wait for
     * the actual frontend availability (bounded retry) and navigate the Browser on
     * the UI thread. A wrong/unverified connection endpoint never wins over the
     * verified DCode backend; the local server is started on demand.
     */
    private void connectWebApplication() {
        if (webBrowser == null || disposed) {
            return;
        }
        long generation = readinessGeneration.incrementAndGet();
        setWebStatus("Connecting to the EVO-Code web application…");
        readinessExecutor.execute(() -> {
            startLocalServerIfNeeded();
            for (int attempt = 1; attempt <= READINESS_ATTEMPTS && !disposed
                    && generation == readinessGeneration.get(); attempt++) {
                String baseUrl;
                try {
                    baseUrl = RuntimeConnection.instance().webBaseUrl();
                } catch (RuntimeException resolutionError) {
                    baseUrl = null;
                    System.err.println("[EVO-Code:editor] web endpoint resolution failed: "
                            + resolutionError);
                }
                if (baseUrl != null && !baseUrl.isBlank()) {
                    String url = normalize(baseUrl);
                    lastEndpointSnapshot = url;
                    if (frontendReachable(url)) {
                        final String target = url;
                        async(() -> {
                            if (!disposed && webBrowser != null
                                    && !webBrowser.isDisposed()
                                    && generation == readinessGeneration.get()) {
                                resolvedWebUrl = target;
                                setWebStatus("Loading " + target + " …");
                                webBrowser.setUrl(target);
                                Ui.log("success", "Loading the EVO-Code web application "
                                        + "from " + target);
                            }
                        });
                        return;
                    }
                }
                if (baseUrl == null && attempt % 10 == 0) {
                    // The embedded server is not visible yet (slow bundle wiring or a
                    // recovery pass): keep the boot hook alive — it retries until the
                    // WebServerControl registers.
                    eu.kalafatic.dcode.ui.ServerRuntimeBoot
                            .ensureServerBundleActive();
                    // The control can be registered while the HTTP server is stopped
                    // (e.g. a lifecycle stage failed before the server stage, or
                    // auto-start was turned off): start it explicitly so the Web tab
                    // does not wait forever on a control that will never run.
                    var control = eu.kalafatic.dcode.ui.WebServerControlAccess.get();
                    if (control != null && !control.isRunning()
                            && control.state()
                            != eu.kalafatic.dcode.core.server.WebServerState.DISABLED) {
                        Ui.log("info", "Local web server registered but not running "
                                + "(state=" + control.state()
                                + "); starting it for the Web tab.");
                        try {
                            control.start();
                        } catch (RuntimeException startError) {
                            Ui.log("error", "Cannot start the local web server: "
                                    + startError.getMessage());
                        }
                    }
                }
                if (attempt % 10 == 0) {
                    String message = "Waiting for the EVO-Code web application (attempt "
                            + attempt + "/" + READINESS_ATTEMPTS
                            + (baseUrl == null ? ", no endpoint yet)" : ", not reachable yet)");
                    setStatusAsync(message);
                    Ui.log("info", message);
                }
                try {
                    Thread.sleep(READINESS_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            final String endpoint = RuntimeConnection.instance().webBaseUrl();
            async(() -> {
                if (disposed || generation != readinessGeneration.get()) {
                    return;
                }
                setWebStatus("The EVO-Code web application is not reachable"
                        + (endpoint == null ? " (no endpoint)." : " at " + endpoint + "."));
                Ui.logDetailed("error", "EVO-Code web application did not become "
                        + "reachable", java.util.List.of(
                        "Endpoint: " + (endpoint == null ? "none" : endpoint),
                        "Action: check the connection (Window ▸ Connection) and the "
                                + "server state (Web Interface ▸ Check Status), then "
                                + "Reload."));
            });
        });
    }

    /**
     * The Settings page is served by the same backend origin as the web application;
     * it loads over HTTP once that origin is known (bounded wait, UI thread
     * navigation).
     */
    private void loadSettingsPage() {
        long generation = readinessGeneration.incrementAndGet();
        readinessExecutor.execute(() -> {
            StringBuilder trail = new StringBuilder();
            for (int attempt = 1; attempt <= READINESS_ATTEMPTS && !disposed
                    && generation == readinessGeneration.get(); attempt++) {
                String base = resolvedWebUrl != null ? resolvedWebUrl
                        : RuntimeConnection.instance().webBaseUrl();
                trail.setLength(0);
                trail.append("attempt ").append(attempt).append(" base=")
                        .append(base == null ? "null" : base)
                        .append(" (src=").append(resolvedWebUrl != null
                                ? "resolved" : "webBaseUrl").append(")");
                if (base != null && !base.isBlank()) {
                    String url = normalize(base) + "settings.html";
                    boolean reachable = frontendReachable(url);
                    trail.append(" reachable=").append(reachable);
                    if (reachable) {
                        final String target = url;
                        final String diag = trail.toString();
                        async(() -> {
                            if (!disposed && settingsBrowser != null
                                    && !settingsBrowser.isDisposed()
                                    && generation == readinessGeneration.get()) {
                                settingsDiag = "settings page loading " + target;
                                settingsBrowser.setUrl(target);
                            }
                        });
                        return;
                    }
                }
                settingsDiag = trail.toString();
                try {
                    Thread.sleep(READINESS_INTERVAL_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            async(() -> settingsDiag = "settings page not reachable");
        });
    }

    private boolean frontendReachable(String url) {
        try {
            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(2)).build();
            HttpResponse<String> response = client.send(
                    HttpRequest.newBuilder(URI.create(url))
                            .timeout(Duration.ofSeconds(4)).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (IOException | InterruptedException | RuntimeException e) {
            return false;
        }
    }

    /**
     * The Web tab must show the application even when the local server was configured
     * not to auto-start: start it on demand (background) so the tab can load.
     */
    private void startLocalServerIfNeeded() {
        try {
            var control = eu.kalafatic.dcode.ui.WebServerControlAccess.get();
            if (control == null || control.isRunning()) {
                return;
            }
            if (control.state()
                    == eu.kalafatic.dcode.core.server.WebServerState.DISABLED) {
                System.out.println("[EVO-Code:editor] local web server is disabled by "
                        + "configuration (webServer.enabled=false); the Web tab "
                        + "cannot load the application.");
                return;
            }
            System.out.println("[EVO-Code:editor] local web server is " + control.state()
                    + "; starting it for the Web tab.");
            control.start();
        } catch (RuntimeException e) {
            System.err.println("[EVO-Code:editor] cannot start the local web server: "
                    + e);
        }
    }

    /** Manual reload: re-runs the bounded readiness sequence for both pages. */
    public void reloadWeb() {
        Ui.log("info", "Manual reload of the EVO-Code web application requested.");
        webLoaded = false;
        resolvedWebUrl = null;
        settingsReady = false;
        connectWebApplication();
        if (settingsBrowser != null && !settingsBrowser.isDisposed()) {
            loadSettingsPage();
        }
    }

    /**
     * The embedded browser must show the same backend as an external browser: when
     * the connection becomes READY against a different DCode backend than the one
     * currently loaded, reload both pages.
     */
    private void installBackendTracking() {
        connectionListener = (state, identity, diagnostic) -> {
            if (state != eu.kalafatic.dcode.client.ConnectionState.READY) {
                return;
            }
            String endpoint = RuntimeConnection.instance().endpoint();
            String current = resolvedWebUrl;
            if (endpoint != null && current != null
                    && !current.startsWith(endpoint)) {
                Ui.log("info", "Backend changed to " + endpoint
                        + " — reloading the web application.");
                reloadWeb();
            }
        };
        RuntimeConnection.instance().addStateListener(connectionListener);
    }

    private void setWebStatus(String text) {
        webStatusSnapshot = text;
        if (webStatus == null || webStatus.isDisposed()) {
            return;
        }
        boolean error = text.contains("not reachable");
        if (error) {
            if (errorColor == null || errorColor.isDisposed()) {
                errorColor = new Color(webStatus.getDisplay(), 200, 60, 60);
            }
            webStatus.setForeground(errorColor);
        } else {
            webStatus.setForeground(webStatus.getDisplay()
                    .getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));
        }
        webStatus.setText(text);
    }

    /** Thread-safe status update from background readiness threads. */
    private void setStatusAsync(String text) {
        async(() -> setWebStatus(text));
    }

    private static String normalize(String baseUrl) {
        return baseUrl.endsWith("/") ? baseUrl : baseUrl + "/";
    }

    private static void async(Runnable runnable) {
        Display display = Display.getCurrent();
        if (display != null) {
            runnable.run();
        } else {
            Display.getDefault().asyncExec(runnable);
        }
    }

    // ---- Public page introspection (workbench automation / smoke tests) ----

    /** Number of pages (Web + Settings). */
    public int pageCount() {
        return getPageCount();
    }

    /** Title of the page at the given index. */
    public String pageTitle(int index) {
        return getPageText(index);
    }

    /** Activates the page at the given index (workbench automation). */
    public void showPage(int index) {
        if (index >= 0 && index < getPageCount()) {
            setActivePage(index);
        }
    }

    /** True once the Browser navigated to the real web application. */
    public boolean isWebLoaded() {
        return webLoaded;
    }

    /** Whether the SWT Browser widget exists (WebKit available). */
    public boolean isWebBrowserCreated() {
        return webBrowser != null;
    }

    /** Current readiness/status text (diagnostics; thread-safe snapshot). */
    public String webStatusText() {
        return webStatusSnapshot;
    }

    /** The resolved web application URL (null until readiness succeeded). */
    public String webUrl() {
        return resolvedWebUrl;
    }

    /** Last endpoint the readiness loop checked (diagnostics). */
    public String lastEndpoint() {
        return lastEndpointSnapshot;
    }

    /** True once the Settings page loaded (page title observed). */
    public boolean isSettingsReady() {
        return settingsReady;
    }

    /** Diagnostics of the Settings page readiness (thread-safe snapshot). */
    public String settingsDiagnostics() {
        return settingsDiag;
    }

    // ---- Lifecycle ----

    @Override
    public void doSave(IProgressMonitor monitor) {
        // backend-owned state; nothing to save locally
    }

    @Override
    public void doSaveAs() {
        // not supported
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }

    @Override
    public boolean isDirty() {
        return false;
    }

    @Override
    public void dispose() {
        disposed = true;
        readinessGeneration.incrementAndGet();
        if (connectionListener != null) {
            RuntimeConnection.instance().removeStateListener(connectionListener);
            connectionListener = null;
        }
        readinessExecutor.shutdownNow();
        try {
            readinessExecutor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (webBrowser != null && !webBrowser.isDisposed()) {
            webBrowser.dispose();
        }
        if (settingsBrowser != null && !settingsBrowser.isDisposed()) {
            settingsBrowser.dispose();
        }
        if (errorColor != null && !errorColor.isDisposed()) {
            errorColor.dispose();
        }
        Ui.log("info", "Harness editor disposed (Browsers and readiness released).");
        super.dispose();
    }
}
