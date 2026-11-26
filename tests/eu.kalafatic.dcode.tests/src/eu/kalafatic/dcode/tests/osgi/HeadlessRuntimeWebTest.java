package eu.kalafatic.dcode.tests.osgi;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Real runtime evidence for WP5.9: all DCode bundles start in the Equinox runtime,
 * the NanoHTTPD server binds, serves the packaged web UI and answers the health
 * endpoint — the assembled headless product, not a mocked stack.
 */
class HeadlessRuntimeWebTest {

    @BeforeAll
    static void startBundles() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(HeadlessRuntimeWebTest.class)
                .getBundleContext();
        for (Bundle bundle : context.getBundles()) {
            String name = bundle.getSymbolicName();
            if (name != null && name.startsWith("eu.kalafatic.dcode")) {
                bundle.start();
            }
        }
    }

    @AfterAll
    static void stopBundles() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(HeadlessRuntimeWebTest.class)
                .getBundleContext();
        for (Bundle bundle : context.getBundles()) {
            String name = bundle.getSymbolicName();
            if (name != null && name.equals("eu.kalafatic.dcode.server")) {
                bundle.stop();
            }
        }
    }

    @Test
    void webAssetsArePackagedInTheServerBundle() {
        Bundle serverBundle = null;
        for (Bundle bundle : FrameworkUtil.getBundle(HeadlessRuntimeWebTest.class)
                .getBundleContext().getBundles()) {
            if ("eu.kalafatic.dcode.server".equals(bundle.getSymbolicName())) {
                serverBundle = bundle;
            }
        }
        assertNotNull(serverBundle, "server bundle must be present");
        for (String asset : new String[]{
                "web/index.html", "web/css/app.css", "web/js/api.js", "web/js/sse.js",
                "web/js/app.js", "web/js/chat.js", "web/js/explorer.js", "web/js/editor.js",
                "web/js/sessions.js", "web/js/tooling.js", "web/js/diff.js"}) {
            assertNotNull(serverBundle.getEntry(asset), "missing web asset: " + asset);
        }
    }

    @Test
    void headlessRuntimeServesHealthAndWebUi() throws Exception {
        String port = System.getProperty("dcode.server.port");
        assertNotNull(port, "server bundle must have published dcode.server.port");

        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5)).build();

        HttpResponse<String> health = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/api/v1/health"))
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, health.statusCode(), health.body());
        assertTrue(health.body().contains("DCODE"), health.body());
        assertTrue(health.body().contains("\"ready\":true"), health.body());

        HttpResponse<String> index = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/"))
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, index.statusCode());
        assertTrue(index.body().contains("<html"), index.body());
        assertTrue(index.body().contains("EVO-Code"));

        HttpResponse<String> apiClient = client.send(HttpRequest.newBuilder(
                        URI.create("http://127.0.0.1:" + port + "/js/api.js"))
                .timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, apiClient.statusCode());
        assertTrue(apiClient.body().contains("window.DCodeApi"));
    }

    @Test
    void webServerControlServiceDrivesTheHttpServer() throws Exception {
        BundleContext context = FrameworkUtil.getBundle(HeadlessRuntimeWebTest.class)
                .getBundleContext();
        var reference = context.getServiceReference(
                eu.kalafatic.dcode.core.server.WebServerControl.class);
        assertNotNull(reference, "WebServerControl service must be registered");
        eu.kalafatic.dcode.core.server.WebServerControl control =
                context.getService(reference);
        assertNotNull(control);
        try {
            String port = System.getProperty("dcode.server.port");
            assertEquals(eu.kalafatic.dcode.core.server.WebServerState.RUNNING,
                    control.state(), "server auto-starts by default");
            assertEquals(control.port(), Integer.parseInt(port));
            assertEquals("http://127.0.0.1:" + port + "/", control.url());

            control.stop();
            assertEquals(eu.kalafatic.dcode.core.server.WebServerState.STOPPED,
                    control.state());
            assertEquals(-1, control.port());

            control.start();
            assertEquals(eu.kalafatic.dcode.core.server.WebServerState.RUNNING,
                    control.state());
            assertTrue(control.url().startsWith("http://"), control.url());
        } finally {
            // Leave the runtime in its default state for the remaining tests.
            if (control.state() != eu.kalafatic.dcode.core.server.WebServerState.RUNNING) {
                control.start();
            }
        }
    }
}
