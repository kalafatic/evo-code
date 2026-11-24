package eu.kalafatic.dcode.ui;

import org.eclipse.core.runtime.Platform;
import org.osgi.framework.Bundle;

/**
 * Layering-safe boot hook for the embedded DCode platform runtime.
 *
 * <p>Every DCode platform bundle (core, config, repositories, persistence, harness,
 * java.tooling, server) declares a lazy activation policy; its activator registers
 * the bundle's OSGi services, and the server bundle additionally registers the
 * {@link eu.kalafatic.dcode.core.server.WebServerControl} service (which drives the
 * Web Interface menu and the embedded HTTP server). The ui bundle never imports the
 * downstream bundles' classes, so activation happens through pure framework APIs:
 * locate each bundle by symbolic name through
 * {@link Platform#getBundle(String)} (the {@code org.eclipse.core.runtime} bundle is
 * always started), call {@link Bundle#start()} and load the activator class to trigger
 * the lazy activation. Runs on a background thread — the workbench never blocks on
 * the HTTP server.</p>
 *
 * <p>The boot deliberately does not use the ui bundle's own
 * {@code BundleContext}: in PDE development launches the ui bundle can serve the
 * application while remaining only RESOLVED (no context available), and the platform
 * runtime must come up regardless of the launch configuration's start levels.</p>
 *
 * <p>The boot never gives up while the framework is alive: the main editor's Web tab
 * depends on the embedded server endpoint, and the server bundle's own startup gate
 * can legitimately wait for services that register late (slow startup, workspace
 * restoration, recovery passes). A bounded give-up would strand the editor with
 * "no endpoint" even though the server would come up moments later.</p>
 */
public final class ServerRuntimeBoot {

    private static final long RETRY_INTERVAL_MILLIS = 1000;

    /** DCode platform bundles in activation order (dependencies first). */
    private static final String[][] PLATFORM_BUNDLES = {
            {"eu.kalafatic.dcode.core",
                    "eu.kalafatic.dcode.core.internal.CoreActivator"},
            {"eu.kalafatic.dcode.config",
                    "eu.kalafatic.dcode.config.internal.ConfigActivator"},
            {"eu.kalafatic.dcode.repositories",
                    "eu.kalafatic.dcode.repositories.internal.RepositoriesActivator"},
            {"eu.kalafatic.dcode.persistence",
                    "eu.kalafatic.dcode.persistence.internal.PersistenceActivator"},
            {"eu.kalafatic.dcode.harness",
                    "eu.kalafatic.dcode.harness.internal.HarnessActivator"},
            {"eu.kalafatic.dcode.java.tooling",
                    "eu.kalafatic.dcode.java.tooling.internal.JavaToolingActivator"},
            {"eu.kalafatic.dcode.server",
                    "eu.kalafatic.dcode.server.internal.ServerActivator"},
    };

    private ServerRuntimeBoot() {
    }

    /**
     * Ensures the embedded DCode platform bundles are active so the WebServerControl
     * service is registered before the workbench menus are used. Idempotent and
     * non-blocking.
     */
    public static void ensureServerBundleActive() {
        Thread thread = new Thread(ServerRuntimeBoot::activate, "dcode-server-boot");
        thread.setDaemon(true);
        thread.start();
    }

    private static void activate() {
        boolean[] lastOk = new boolean[PLATFORM_BUNDLES.length];
        int pass = 0;
        while (true) {
            pass++;
            for (int i = 0; i < PLATFORM_BUNDLES.length; i++) {
                boolean ok = activateBundle(PLATFORM_BUNDLES[i][0],
                        PLATFORM_BUNDLES[i][1]);
                if (!ok && (lastOk[i] || pass % 30 == 0)) {
                    System.err.println("[EVO-Code:boot] platform bundle "
                            + PLATFORM_BUNDLES[i][0] + " is not active");
                } else if (ok && !lastOk[i] && pass > 1) {
                    System.out.println("[EVO-Code:boot] platform bundle "
                            + PLATFORM_BUNDLES[i][0] + " active");
                }
                lastOk[i] = ok;
            }
            var control = WebServerControlAccess.get();
            if (control != null) {
                System.out.println("[EVO-Code:boot] embedded EVO-Code server runtime "
                        + "active; web server state=" + control.state()
                        + (control.isRunning() ? " url=" + control.url() : ""));
                return;
            }
            try {
                Thread.sleep(RETRY_INTERVAL_MILLIS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Starts one platform bundle and triggers its lazy activation by loading its
     * activator class. Returns true when the bundle is ACTIVE afterwards.
     */
    private static boolean activateBundle(String symbolicName, String activatorClass) {
        try {
            Bundle bundle = Platform.getBundle(symbolicName);
            if (bundle == null) {
                return false;
            }
            if (bundle.getState() != Bundle.ACTIVE) {
                // With a lazy activation policy start() alone does not run the
                // activator — loading the activator class triggers it.
                bundle.start();
                try {
                    bundle.loadClass(activatorClass);
                } catch (ClassNotFoundException e) {
                    // Eager activation: the activator already ran inside start().
                }
            }
            return bundle.getState() == Bundle.ACTIVE;
        } catch (Exception e) {
            System.err.println("[EVO-Code:boot] cannot activate " + symbolicName
                    + ": " + e);
            return false;
        }
    }
}
