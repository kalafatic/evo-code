package eu.kalafatic.dcode.tests.cli;

import eu.kalafatic.dcode.cli.CliApplication;
import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Enforces the thin-client property at the bundle boundary: the CLI bundle must depend
 * only on the HTTP client and the Equinox application framework — never on the runtime
 * bundles (config/repositories/harness/persistence/java.tooling/server/ui).
 */
class CliBundleManifestTest {

    @Test
    void cliBundleDoesNotRequireRuntimeBundles() {
        var bundle = FrameworkUtil.getBundle(CliApplication.class);
        assertNotNull(bundle, "CLI bundle must be resolvable");
        String requireBundle = bundle.getHeaders().get("Require-Bundle");
        assertNotNull(requireBundle, "CLI bundle must declare Require-Bundle");

        for (String runtimeBundle : new String[]{
                "eu.kalafatic.dcode.config", "eu.kalafatic.dcode.repositories",
                "eu.kalafatic.dcode.harness", "eu.kalafatic.dcode.persistence",
                "eu.kalafatic.dcode.java.tooling", "eu.kalafatic.dcode.server",
                "eu.kalafatic.dcode.ui"}) {
            assertFalse(requireBundle.contains(runtimeBundle),
                    "CLI must not require runtime bundle " + runtimeBundle);
        }
        assertTrue(requireBundle.contains("eu.kalafatic.dcode.client"),
                "CLI must reuse the existing HTTP client bundle");
        assertTrue(requireBundle.contains("org.eclipse.equinox.app"),
                "CLI must depend on the Equinox application framework");
    }
}
