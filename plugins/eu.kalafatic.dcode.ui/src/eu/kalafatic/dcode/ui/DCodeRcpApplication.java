package eu.kalafatic.dcode.ui;

import org.eclipse.core.runtime.Platform;
import org.eclipse.equinox.app.IApplication;
import org.eclipse.equinox.app.IApplicationContext;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IWorkbench;
import org.eclipse.ui.PlatformUI;

/**
 * DCode RCP application entry point.
 *
 * <p>The platform bundles (core → server) initialize in this OSGi runtime exactly as
 * they do in the headless product; this class only creates the Eclipse workbench on
 * top. The UI talks to the platform through the HTTP API of the local server bundle
 * (or a user-configured remote endpoint) — never through duplicated services.</p>
 */
public final class DCodeRcpApplication implements IApplication {

    @Override
    public Object start(IApplicationContext context) {
        Display display = PlatformUI.createDisplay();
        // Start the embedded server runtime early (background): its WebServerControl
        // service drives the Web Interface menu and its HTTP server serves the web
        // application shown in the main editor's SWT Browser. The workbench never
        // blocks on this activation or on the HTTP bind.
        ServerRuntimeBoot.ensureServerBundleActive();
        try {
            int code = PlatformUI.createAndRunWorkbench(display,
                    new DCodeWorkbenchAdvisor());
            if (code == PlatformUI.RETURN_RESTART) {
                return IApplication.EXIT_RESTART;
            }
            return IApplication.EXIT_OK;
        } catch (Throwable t) {
            t.printStackTrace();
            MessageDialog.openError(display.getActiveShell(),
                    "EVO-Code startup failed", String.valueOf(t.getMessage()));
            return IApplication.EXIT_OK;
        } finally {
            display.dispose();
        }
    }

    @Override
    public void stop() {
        IWorkbench workbench = PlatformUI.getWorkbench();
        if (workbench != null) {
            Display display = workbench.getDisplay();
            display.syncExec(() -> {
                if (!workbench.isClosing()) {
                    workbench.close();
                }
            });
        }
    }

    /** Local-mode endpoint: the embedded server publishes its port as a system property. */
    public static String localEndpoint() {
        String port = System.getProperty("dcode.server.port");
        if (port != null && !port.isBlank()) {
            return "http://127.0.0.1:" + port;
        }
        String configured = Platform.getPreferencesService() != null
                ? null : null;
        return configured;
    }
}
