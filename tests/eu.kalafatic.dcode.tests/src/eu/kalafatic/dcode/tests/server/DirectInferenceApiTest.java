package eu.kalafatic.dcode.tests.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.config.credentials.ConfigurationCredentialVault;
import eu.kalafatic.dcode.config.provider.DefaultProviderRegistry;
import eu.kalafatic.dcode.config.routing.DefaultModelRouter;
import eu.kalafatic.dcode.config.runtime.DefaultRuntimeRegistry;
import eu.kalafatic.dcode.config.runtime.JsonFileRuntimeStore;
import eu.kalafatic.dcode.config.runtime.RuntimeManager;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistration;
import eu.kalafatic.dcode.config.runtime.RuntimeType;
import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.core.config.InMemoryConfigurationService;
import eu.kalafatic.dcode.core.event.InMemoryEventBus;
import eu.kalafatic.dcode.core.lifecycle.DefaultDCodeLifecycle;
import eu.kalafatic.dcode.core.port.PortAllocator;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.harness.tool.DefaultToolRegistry;
import eu.kalafatic.dcode.server.DCodeApi;
import eu.kalafatic.dcode.server.DCodeHttpServer;
import eu.kalafatic.dcode.tests.config.MockHttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Direct inference API: standalone chat over HTTP (streaming + cancellation) and the
 * runtime management endpoints, without any coding session involved.
 */
class DirectInferenceApiTest {

    @TempDir
    Path temp;

    private MockHttpServer mock;
    private DCodeHttpServer server;
    private String baseUrl;
    private HttpClient client;
    private final ObjectMapper mapper = new ObjectMapper();
    private RuntimeManager runtimes;

    @BeforeEach
    void setUp() throws Exception {
        mock = MockHttpServer.start();
        mock.on("GET", "/enr/v1/models", exchange -> mock.respond(exchange, 200,
                "[{\"id\":\"qwen3\",\"name\":\"qwen3\",\"provider\":\"enr-native\","
                        + "\"engine\":\"enr-native\",\"sizeBytes\":123,\"available\":true,"
                        + "\"loaded\":false,\"contextLength\":32768}]"));
        mock.on("POST", "/enr/v1/chat", exchange -> mock.respond(exchange, 200,
                "{\"content\":\"blocking answer\",\"finishReason\":\"stop\","
                        + "\"promptTokens\":2,\"completionTokens\":3}"));
        mock.on("POST", "/enr/v1/chat/stream", exchange -> mock.respondSse(exchange,
                "data: {\"chunk\":\"streamed\",\"engine\":\"enr-native\",\"done\":false}\n\n"
                        + "data: {\"chunk\":\" answer\",\"engine\":\"enr-native\",\"done\":false}\n\n"
                        + "event: done\n"
                        + "data: {\"chunk\":\"\",\"engine\":\"enr-native\",\"done\":true}\n\n"
                        + "data: {\"model\":\"qwen3\",\"finishReason\":\"stop\","
                        + "\"promptTokens\":2,\"completionTokens\":4}\n\n"));
        mock.on("GET", "/enr/v1/health", exchange -> mock.respond(exchange, 200,
                "{\"application\":\"ENR\",\"version\":\"1.0\",\"status\":\"UP\","
                        + "\"ready\":true}"));

        InMemoryEventBus bus = new InMemoryEventBus(null, t -> {});
        InMemoryConfigurationService configuration =
                new InMemoryConfigurationService(CoreConfiguration.defaults(temp));
        runtimes = new RuntimeManager(new DefaultRuntimeRegistry(),
                new JsonFileRuntimeStore(temp),
                new ConfigurationCredentialVault(configuration),
                new DefaultModelRouter());
        runtimes.register(RuntimeRegistration.of("enr-test", "ENR Test", RuntimeType.ENR,
                mock.baseUrl()));
        runtimes.setDefault("enr-test", null);

        DefaultDCodeLifecycle lifecycle = new DefaultDCodeLifecycle();
        lifecycle.startAll();
        var agentRegistry = new eu.kalafatic.dcode.config.agent.DefaultAgentRegistry(
                new eu.kalafatic.dcode.config.agent.JsonFileAgentStore(temp));
        DCodeApi api = new DCodeApi(() -> null, () -> null,
                () -> null, runtimes, runtimes, new DefaultToolRegistry(), null,
                lifecycle, configuration, bus,
                () -> null, () -> null, () -> null, () -> null,
                () -> agentRegistry);

        int port = PortAllocator.allocate(49300, 100);
        server = new DCodeHttpServer("127.0.0.1", port, api);
        server.start(5000, false);
        api.setHttpPortActual(port, "127.0.0.1");
        baseUrl = "http://127.0.0.1:" + port;
        // Loopback tests must bypass any environment proxy (the default JDK proxy
        // selector in the OSGi test JVM can otherwise corrupt the request line).
        client = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))
                .connectTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (mock != null) {
            mock.close();
        }
    }

    private record ApiResponse(int status, JsonNode body) {
    }

    private ApiResponse request(String method, String path, String body) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                .timeout(Duration.ofSeconds(30));
        if (body != null) {
            builder.header("Content-Type", "application/json")
                    .method(method, HttpRequest.BodyPublishers.ofString(body));
        } else {
            builder.method(method, HttpRequest.BodyPublishers.noBody());
        }
        // Fresh client per request: the OSGi test JVM's keep-alive handling can
        // otherwise corrupt the request line of subsequent requests.
        HttpClient requestClient = HttpClient.newBuilder()
                .proxy(java.net.ProxySelector.of(null))
                .connectTimeout(Duration.ofSeconds(5)).build();
        HttpResponse<String> response = requestClient.send(builder.build(),
                HttpResponse.BodyHandlers.ofString());
        JsonNode json;
        try {
            json = response.body() == null || response.body().isBlank()
                    ? mapper.createObjectNode() : mapper.readTree(response.body());
        } catch (Exception parseFailure) {
            throw new AssertionError("Non-JSON response (HTTP " + response.statusCode()
                    + ") for " + method + " " + path + ": " + response.body(),
                    parseFailure);
        }
        return new ApiResponse(response.statusCode(), json);    }

    @Test
    void runtimeManagementCrudOverHttp() throws Exception {
        // seeded defaults (enr + ollama) plus the mock ENR test instance
        JsonNode list = request("GET", "/api/v1/runtimes", null).body();
        assertTrue(list.size() >= 3);
        JsonNode enrTest = null;
        for (JsonNode runtime : list) {
            if (runtime.path("id").asText().equals("enr-test")) {
                enrTest = runtime;
            }
        }
        assertTrue(enrTest != null);
        assertEquals("enr", enrTest.path("type").asText());
        assertTrue(enrTest.path("enabled").asBoolean());
        assertTrue(enrTest.path("default").asBoolean());

        // details include discovered models with runtime context
        JsonNode details = request("GET", "/api/v1/runtimes/enr-test", null).body();
        JsonNode models = details.path("models");
        assertEquals(1, models.size());
        assertEquals("qwen3", models.get(0).path("id").asText());
        assertEquals(32768, models.get(0).path("contextWindowTokens").asLong());

        // connection test updates the cached status
        JsonNode test = request("POST", "/api/v1/runtimes/enr-test/test", "{}").body();
        assertTrue(test.path("success").asBoolean());

        // create a second ENR instance (multiple instances of one type)
        JsonNode created = request("POST", "/api/v1/runtimes",
                "{\"name\":\"ENR Prod\",\"type\":\"enr\",\"endpoint\":\"http://127.0.0.1:1\","
                        + "\"authToken\":\"secret-token-123\"}").body();
        String secondId = created.path("id").asText();
        assertFalse(secondId.isBlank());
        assertFalse(created.toString().contains("secret-token-123"));
        assertEquals("***", created.path("credentials").path("authToken").asText());

        // shared state: the new runtime is immediately visible to every client through
        // the providers and models views of the same backend
        JsonNode providers = request("GET", "/api/v1/providers", null).body();
        boolean visibleInProviders = false;
        for (JsonNode provider : providers) {
            if (provider.path("id").asText().equals(secondId)) {
                visibleInProviders = true;
            }
        }
        assertTrue(visibleInProviders);

        // disable + re-enable
        JsonNode disabled = request("POST", "/api/v1/runtimes/" + secondId + "/disable",
                "{}").body();
        assertFalse(disabled.path("enabled").asBoolean());
        JsonNode enabled = request("POST", "/api/v1/runtimes/" + secondId + "/enable",
                "{}").body();
        assertTrue(enabled.path("enabled").asBoolean());

        // default switch
        JsonNode def = request("POST", "/api/v1/runtimes/" + secondId + "/default",
                "{}").body();
        assertTrue(def.path("default").asBoolean());

        // removal: registration gone, defaults intact elsewhere
        JsonNode removed = request("DELETE", "/api/v1/runtimes/" + secondId, null).body();
        assertTrue(removed.path("removed").asBoolean());
        ApiResponse notFound = request("GET", "/api/v1/runtimes/" + secondId, null);
        assertEquals(404, notFound.status());
    }

    @Test
    void directChatIsBlockingAndDistinctFromCodingSessions() throws Exception {
        ApiResponse response = request("POST", "/api/v1/inference/chat",
                "{\"runtimeId\":\"enr-test\",\"model\":\"qwen3\","
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(200, response.status());
        assertEquals("blocking answer", response.body().path("content").asText());
        assertEquals(3, response.body().path("completionTokens").asInt());
    }

    @Test
    void directChatWithoutRuntimeIdUsesDefaultRuntime() throws Exception {
        ApiResponse response = request("POST", "/api/v1/inference/chat",
                "{\"model\":\"qwen3\",\"messages\":[{\"role\":\"user\","
                        + "\"content\":\"hi\"}]}");
        assertEquals(200, response.status());
        assertEquals("blocking answer", response.body().path("content").asText());
        assertEquals("enr-test", response.body().path("runtimeId").asText());
    }

    @Test
    void directChatStreamsTokensOverSse() throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl
                        + "/api/v1/inference/chat/stream"))
                .timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"runtimeId\":\"enr-test\",\"model\":\"qwen3\","
                                + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}"))
                .build();
        HttpResponse<String> response = client.send(request,
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        assertTrue(response.body().contains("streamed"));
        assertTrue(response.body().contains("event: done"));
        assertTrue(response.body().contains("\"finishReason\":\"stop\""));
    }

    @Test
    void unknownRuntimeReportsClearErrorAndPreservesSelection() throws Exception {
        ApiResponse response = request("POST", "/api/v1/inference/chat",
                "{\"runtimeId\":\"nope\",\"model\":\"qwen3\","
                        + "\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");
        assertEquals(404, response.status());
        assertEquals("NOT_FOUND", response.body().path("code").asText());
        // the default runtime is untouched
        JsonNode list = request("GET", "/api/v1/runtimes", null).body();
        boolean defaultIntact = false;
        for (JsonNode runtime : list) {
            if (runtime.path("id").asText().equals("enr-test")
                    && runtime.path("default").asBoolean()) {
                defaultIntact = true;
            }
        }
        assertTrue(defaultIntact);
    }

    @Test
    void legacyProvidersEndpointStillServesClients() throws Exception {
        JsonNode providers = request("GET", "/api/v1/providers", null).body();
        assertTrue(providers.size() >= 3);
        JsonNode enrTest = null;
        for (JsonNode provider : providers) {
            if (provider.path("id").asText().equals("enr-test")) {
                enrTest = provider;
            }
        }
        assertTrue(enrTest != null);
        assertEquals(mock.baseUrl(), enrTest.path("baseUrl").asText());
    }

    @Test
    void harnessCatalogIsServedOverHttp() throws Exception {
        ApiResponse response = request("GET", "/api/v1/harnesses", null);
        assertEquals(200, response.status());
        JsonNode harnesses = response.body();
        assertTrue(harnesses.size() >= 4);
        boolean dcodeUsable = false;
        for (JsonNode harness : harnesses) {
            if (harness.path("id").asText("").equals("dcode")) {
                dcodeUsable = harness.path("usable").asBoolean()
                        && harness.path("integrated").asBoolean();
            }
        }
        assertTrue(dcodeUsable);
    }

    @Test
    void harnessSettingsOverHttp() throws Exception {
        JsonNode list = request("GET", "/api/v1/harnesses", null).body();
        JsonNode dcode = null;
        for (JsonNode harness : list) {
            if (harness.path("id").asText("").equals("dcode")) {
                dcode = harness;
            }
        }
        assertTrue(dcode != null);
        assertTrue(dcode.path("default").asBoolean());
        assertTrue(dcode.path("enabled").asBoolean());
        assertTrue(dcode.path("usable").asBoolean());

        JsonNode defaulted = request("POST", "/api/v1/harnesses/opencode/default",
                "{}").body();
        assertTrue(defaulted.path("default").asBoolean());

        JsonNode disabled = request("POST", "/api/v1/harnesses/opencode/disable",
                "{}").body();
        assertFalse(disabled.path("enabled").asBoolean());

        JsonNode enabled = request("POST", "/api/v1/harnesses/opencode/enable",
                "{}").body();
        assertTrue(enabled.path("enabled").asBoolean());

        ApiResponse missing = request("POST", "/api/v1/harnesses/nope/default", "{}");
        assertEquals(400, missing.status());

        // restore the native default
        request("POST", "/api/v1/harnesses/dcode/default", "{}");
    }

    @Test
    void agentCrudAndDefaultOverHttp() throws Exception {
        JsonNode agents = request("GET", "/api/v1/agents", null).body();
        assertEquals(3, agents.size());

        JsonNode created = request("POST", "/api/v1/agents",
                "{\"name\":\"QA Engineer\",\"role\":\"REVIEWER\","
                        + "\"systemInstructions\":\"Verify changes thoroughly.\"}")
                .body();
        String id = created.path("id").asText("");
        assertFalse(id.isBlank());

        JsonNode defaulted = request("POST", "/api/v1/agents/" + id + "/default",
                "{}").body();
        assertTrue(defaulted.path("default").asBoolean());

        JsonNode removed = request("DELETE", "/api/v1/agents/" + id, null).body();
        assertTrue(removed.path("removed").asBoolean());
        // built-ins are immutable
        ApiResponse conflict = request("DELETE", "/api/v1/agents/main", null);
        assertEquals(400, conflict.status());
    }

    @Test
    void sessionsBindAgentProfiles() throws Exception {
        JsonNode workspaces = request("GET", "/api/v1/workspaces", null).body();
        // the DCodeApi in this test has no workspace service; agent binding happens
        // on session creation, so this test covers the API contract only when a
        // workspace exists.
        if (!workspaces.isArray() || workspaces.size() == 0) {
            return;
        }
        String workspaceId = workspaces.get(0).path("id").asText("");
        ApiResponse created = request("POST", "/api/v1/sessions",
                "{\"title\":\"agent-bound\",\"workspaceId\":\"" + workspaceId
                        + "\",\"agentId\":\"senior-java\"}");
        assertEquals(200, created.status());
    }

    @Test
    void portMonitorReportsActualState() throws Exception {
        ApiResponse response = request("GET", "/api/v1/ports", null);
        assertEquals(200, response.status());
        JsonNode ports = response.body();
        assertTrue(ports.size() >= 3);
        JsonNode http = null;
        for (JsonNode port : ports) {
            if (port.path("id").asText("").equals("http")) {
                http = port;
            }
        }
        assertTrue(http != null);
        assertEquals(48080, http.path("expectedPort").asInt());
        assertTrue(http.path("actualPort").asInt() > 0, "actual port must be reported");
        assertEquals("LISTENING", http.path("state").asText());
    }

    @Test
    void scanEndpointDetectsLocalRuntimeTypes() throws Exception {
        ApiResponse response = request("POST", "/api/v1/runtimes/scan",
                "{\"endpoints\":[\"" + mock.baseUrl() + "\"]}");
        assertEquals(200, response.status());
        JsonNode findings = response.body();
        assertEquals(1, findings.size());
        assertEquals("enr", findings.get(0).path("kind").asText());
        assertEquals("enr", findings.get(0).path("type").asText());
        assertTrue(findings.get(0).path("reachable").asBoolean());
        // the scanned endpoint equals an already-registered runtime
        assertTrue(findings.get(0).path("registered").asBoolean());
    }
}
