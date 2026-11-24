package eu.kalafatic.dcode.tests.client;

import eu.kalafatic.dcode.client.BackendProbe;
import eu.kalafatic.dcode.client.ConnectionState;
import eu.kalafatic.dcode.client.DCodeApiException;
import eu.kalafatic.dcode.tests.config.MockHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Connection probe contract: the probe distinguishes connectivity, API availability,
 * API compatibility and backend readiness — a wrong endpoint (e.g. ENR) is reported
 * as an ERROR with the detected identity instead of producing HTTP 404 noise.
 */
class BackendProbeTest {

    private MockHttpServer mock;
    private BackendProbe probe;

    @BeforeEach
    void setUp() throws IOException {
        mock = MockHttpServer.start();
        probe = new BackendProbe();
    }

    @AfterEach
    void tearDown() {
        mock.close();
    }

    @Test
    void readyDCodeBackendIsReportedAsReady() throws Exception {
        mock.on("GET", "/api/v1/health", exchange -> mock.respond(exchange, 200,
                "{\"application\":\"DCODE\",\"version\":\"2.6.11\",\"ready\":true,"
                        + "\"status\":\"UP\",\"stages\":{\"SERVER\":\"STARTED\"}}"));
        var result = probe.probe(mock.baseUrl()).get(10, TimeUnit.SECONDS);
        assertEquals(ConnectionState.READY, result.state());
        assertTrue(result.isDCode());
        assertEquals("DCODE", result.identity().application());
        assertEquals("2.6.11", result.identity().version());
    }

    @Test
    void startingBackendIsReportedAsInitializing() throws Exception {
        mock.on("GET", "/api/v1/health", exchange -> mock.respond(exchange, 200,
                "{\"application\":\"DCODE\",\"version\":\"2.6.11\",\"ready\":false,"
                        + "\"status\":\"UP\"}"));
        var result = probe.probe(mock.baseUrl()).get(10, TimeUnit.SECONDS);
        assertEquals(ConnectionState.INITIALIZING, result.state());
        assertTrue(result.isDCode());
    }

    @Test
    void degradedBackendIsReportedAsDegraded() throws Exception {
        mock.on("GET", "/api/v1/health", exchange -> mock.respond(exchange, 200,
                "{\"application\":\"DCODE\",\"version\":\"2.6.11\",\"ready\":false,"
                        + "\"status\":\"DEGRADED\"}"));
        var result = probe.probe(mock.baseUrl()).get(10, TimeUnit.SECONDS);
        assertEquals(ConnectionState.DEGRADED, result.state());
    }

    @Test
    void wrongServiceIsReportedAsErrorWithDetectedIdentity() throws Exception {
        // ENR's native health endpoint: HTTP 200 but no DCode identity fields
        mock.on("GET", "/api/v1/health", exchange -> mock.respond(exchange, 200,
                "{\"status\":\"healthy\",\"checks\":[]}"));
        var result = probe.probe(mock.baseUrl()).get(10, TimeUnit.SECONDS);
        assertEquals(ConnectionState.ERROR, result.state());
        assertFalse(result.isDCode());
        assertTrue(result.detail().contains("not an EVO-Code server"), result.detail());
    }

    @Test
    void endpointWithoutDCodeApiIsReportedAsError() throws Exception {
        mock.on("GET", "/api/v1/health", exchange -> mock.respond(exchange, 404,
                "{\"error\":{\"code\":\"NOT_FOUND\"}}"));
        var result = probe.probe(mock.baseUrl()).get(10, TimeUnit.SECONDS);
        assertEquals(ConnectionState.ERROR, result.state());
        assertTrue(result.detail().contains("does not provide the EVO-Code API"),
                result.detail());
    }

    @Test
    void unreachableEndpointIsReportedAsError() throws Exception {
        // port 1 on loopback: connection refused
        var result = probe.probe("http://127.0.0.1:1").get(15, TimeUnit.SECONDS);
        assertEquals(ConnectionState.ERROR, result.state());
        assertTrue(result.detail().contains("unreachable"), result.detail());
    }

    @Test
    void failureClassificationDrivesRetryPolicy() {
        assertEquals(BackendProbe.FailureClass.PERMANENT_CONTRACT,
                BackendProbe.classifyFailure(new DCodeApiException("NOT_FOUND",
                        "no route", 404)));
        assertEquals(BackendProbe.FailureClass.PERMANENT_CONTRACT,
                BackendProbe.classifyFailure(new DCodeApiException("METHOD_NOT_ALLOWED",
                        "use GET", 405)));
        assertEquals(BackendProbe.FailureClass.AUTHENTICATION,
                BackendProbe.classifyFailure(new DCodeApiException(
                        "AUTHENTICATION_FAILED", "token", 401)));
        assertEquals(BackendProbe.FailureClass.VALIDATION,
                BackendProbe.classifyFailure(new DCodeApiException("VALIDATION_FAILED",
                        "bad field", 400)));
        assertEquals(BackendProbe.FailureClass.TRANSIENT,
                BackendProbe.classifyFailure(new DCodeApiException("INTERNAL_ERROR",
                        "boom", 500)));
        assertEquals(BackendProbe.FailureClass.TRANSIENT,
                BackendProbe.classifyFailure(new java.io.IOException("connection refused")));
        assertEquals(BackendProbe.FailureClass.TRANSIENT,
                BackendProbe.classifyFailure(new java.net.ConnectException("refused")));
    }
}
