package eu.kalafatic.dcode.tests.server;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.osgi.framework.FrameworkUtil;

import eu.kalafatic.dcode.server.DCodeApi;

/**
 * Frontend delivery invariant: there is exactly ONE authoritative frontend source
 * (the {@code web/} tree of the server bundle), and every execution mode — PDE
 * workspace launch, packaged RCP product, headless and standalone web — resolves
 * the same resources from the bundle classpath. This test fails when the packaged
 * bundle is stale relative to the source tree or when the frontend build constant
 * drifts from the backend {@code DCodeApi.FRONTEND_VERSION}.
 */
class FrontendDeliveryTest {

    private static final List<String> FILES = List.of(
            "web/index.html", "web/js/api.js", "web/js/app.js", "web/js/chat.js",
            "web/js/markdown.js", "web/js/runtimes.js", "web/js/sessions.js",
            "web/css/app.css", "web/settings.html", "web/css/settings.css",
            "web/help.html");

    private static final String BUILD = "web-20261124-r5";

    private Path sourceRoot() {
        Path module = Path.of("").toAbsolutePath();
        for (Path candidate : List.of(
                module.resolve("../../plugins/eu.kalafatic.dcode.server"),
                module.resolve("../../../plugins/eu.kalafatic.dcode.server"),
                module.resolve("../../../../plugins/eu.kalafatic.dcode.server"))) {
            if (Files.isDirectory(candidate.resolve("web"))) {
                return candidate;
            }
        }
        return null;
    }

    @Test
    void bundledWebAssetsMatchTheSourceTree() throws Exception {
        var bundle = FrameworkUtil.getBundle(DCodeApi.class);
        assertNotNull(bundle, "server bundle must be resolvable in the test runtime");
        Path source = sourceRoot();
        for (String file : FILES) {
            InputStream stream = null;
            try {
                stream = bundle.getEntry(file).openStream();
                assertNotNull(stream, "bundle must contain " + file);
                String bundled = new String(stream.readAllBytes(),
                        StandardCharsets.UTF_8);
                if (source != null) {
                    String fromSource = Files.readString(source.resolve(file));
                    assertEquals(fromSource, bundled,
                            "stale frontend packaging detected for " + file
                                    + " — the server bundle must be rebuilt");
                }
            } finally {
                if (stream != null) {
                    stream.close();
                }
            }
        }
    }

    @Test
    void frontendBuildConstantIsConsistentEverywhere() throws Exception {
        assertEquals(BUILD, DCodeApi.FRONTEND_VERSION,
                "backend FRONTEND_VERSION must match the frontend build");
        var bundle = FrameworkUtil.getBundle(DCodeApi.class);
        assertNotNull(bundle);
        String api;
        try (InputStream in = bundle.getEntry("web/js/api.js").openStream()) {
            api = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(api.contains("DCODE_FRONTEND_BUILD = '" + BUILD + "'"),
                "api.js must declare DCODE_FRONTEND_BUILD = " + BUILD);
        String index;
        try (InputStream in = bundle.getEntry("web/index.html").openStream()) {
            index = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        assertTrue(index.contains("frontend-build"),
                "index.html must render the frontend build badge");
    }

    /**
     * Proves the PDE-layout guarantee: a directory-installed bundle (how Eclipse
     * installs workspace bundles in Run/Debug) exposes the SAME logical
     * {@code web/…} paths through the same OSGi entry API the server uses in
     * packaged products — no flattened structure, no output-folder dependency.
     */
    @Test
    void directoryBundleExposesTheSameLogicalWebPaths() throws Exception {
        org.osgi.framework.BundleContext context = FrameworkUtil.getBundle(
                FrontendDeliveryTest.class).getBundleContext();
        assertNotNull(context, "test runtime must provide a bundle context");
        Path dir = Files.createTempDirectory("web-bundle-");
        try {
            Files.createDirectories(dir.resolve("META-INF"));
            Files.createDirectories(dir.resolve("web/css"));
            Files.writeString(dir.resolve("META-INF/MANIFEST.MF"), """
                    Manifest-Version: 1.0
                    Bundle-ManifestVersion: 2
                    Bundle-SymbolicName: frontend.delivery.probe
                    Bundle-Version: 1.0.0

                    """);
            Files.writeString(dir.resolve("web/index.html"), "<html>probe</html>");
            Files.writeString(dir.resolve("web/css/style.css"), "body{}");
            org.osgi.framework.Bundle probe = context.installBundle(
                    dir.toUri().toString());
            try {
                probe.start();
                assertEquals("<html>probe</html>",
                        new String(probe.getEntry("web/index.html").openStream()
                                .readAllBytes(), StandardCharsets.UTF_8));
                assertEquals("body{}",
                        new String(probe.getEntry("web/css/style.css").openStream()
                                .readAllBytes(), StandardCharsets.UTF_8));
                assertTrue(probe.getEntry("index.html") == null,
                        "the web/ prefix must be preserved in the logical path");
            } finally {
                probe.uninstall();
            }
        } finally {
            Files.deleteIfExists(dir.resolve("web/css/style.css"));
            Files.deleteIfExists(dir.resolve("web/index.html"));
            Files.deleteIfExists(dir.resolve("META-INF/MANIFEST.MF"));
            Files.deleteIfExists(dir.resolve("web/css"));
            Files.deleteIfExists(dir.resolve("web"));
            Files.deleteIfExists(dir.resolve("META-INF"));
            Files.deleteIfExists(dir);
        }
    }
}
