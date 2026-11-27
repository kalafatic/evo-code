package eu.kalafatic.dcode.tests.ui;

import eu.kalafatic.dcode.client.BackendIdentity;
import eu.kalafatic.dcode.client.ConnectionState;
import eu.kalafatic.dcode.client.DCodeApiClient;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Unit test of the web-surface resolver: the web interface and help must open the
 * DCode web UI only — never an unverified endpoint (e.g. an ENR runtime address
 * stored as the connection endpoint). Deterministic: the singleton state is seeded
 * via reflection, no network access.
 */
class WebBaseUrlResolutionTest {

    private static final String LOCAL_PORT_PROPERTY = "dcode.server.port";
    private String previousPortProperty;

    @BeforeEach
    void capture() {
        previousPortProperty = System.getProperty(LOCAL_PORT_PROPERTY);
    }

    @AfterEach
    void restore() {
        if (previousPortProperty != null) {
            System.setProperty(LOCAL_PORT_PROPERTY, previousPortProperty);
        } else {
            System.clearProperty(LOCAL_PORT_PROPERTY);
        }
    }

    @Test
    void verifiedDCodeBackendUsesItsEndpoint() throws Exception {
        RuntimeConnection connection = RuntimeConnection.instance();
        seed(connection, ConnectionState.READY,
                new BackendIdentity("DCODE", "2.6.11", true, "UP", Map.of()),
                new DCodeApiClient("http://127.0.0.1:48080"));
        assertEquals("http://127.0.0.1:48080", connection.webBaseUrl());
    }

    @Test
    void wrongEndpointFallsBackToLocalServerPort() throws Exception {
        previousPortProperty = System.getProperty(LOCAL_PORT_PROPERTY);
        RuntimeConnection connection = RuntimeConnection.instance();
        seed(connection, ConnectionState.ERROR,
                new BackendIdentity("ENR", "1.0", false, "", Map.of()),
                new DCodeApiClient("http://127.0.0.1:28780"));
        System.setProperty(LOCAL_PORT_PROPERTY, "48082");
        assertEquals("http://127.0.0.1:48082", connection.webBaseUrl(),
                "an unverified endpoint (ENR) must never be opened; the local DCode "
                        + "server wins");
    }

    @Test
    void noVerifiedBackendAndNoLocalServerYieldsNull() throws Exception {
        previousPortProperty = System.getProperty(LOCAL_PORT_PROPERTY);
        RuntimeConnection connection = RuntimeConnection.instance();
        seed(connection, ConnectionState.ERROR, null,
                new DCodeApiClient("http://127.0.0.1:28780"));
        try {
            System.clearProperty(LOCAL_PORT_PROPERTY);
            assertNull(connection.webBaseUrl(),
                    "without a verified DCode backend or a local server nothing may "
                            + "be launched");
        } finally {
            if (previousPortProperty != null) {
                System.setProperty(LOCAL_PORT_PROPERTY, previousPortProperty);
            }
        }
    }

    private static void seed(RuntimeConnection connection, ConnectionState state,
                             BackendIdentity identity, DCodeApiClient client)
            throws Exception {
        setField(connection, "state", new AtomicReference<>(state));
        setField(connection, "identity", identity);
        setField(connection, "api", new AtomicReference<>(client));
    }

    private static void setField(Object target, String name, Object value)
            throws Exception {
        Field field = RuntimeConnection.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(target, value);
    }
}
