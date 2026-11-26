package eu.kalafatic.dcode.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.config.model.ModelDefinition;
import eu.kalafatic.dcode.config.provider.ChatMessage;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ChatResponse;
import eu.kalafatic.dcode.config.provider.ConnectionTestResult;
import eu.kalafatic.dcode.config.provider.ModelManagementClient;
import eu.kalafatic.dcode.config.provider.ProviderException;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.runtime.RuntimeInfo;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistration;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistry;
import eu.kalafatic.dcode.config.runtime.RuntimeType;
import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.event.DCodeEvent;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.model.AgentRole;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.RepositoryAccessPolicy;
import eu.kalafatic.dcode.core.model.RepositoryRef;
import eu.kalafatic.dcode.core.model.WorkspaceMode;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.harness.execution.SessionExecutor;
import eu.kalafatic.dcode.harness.tool.ToolRegistry;
import fi.iki.elonen.NanoHTTPD;

import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * REST dispatch for the DCode HTTP API. No business logic: every operation delegates to
 * application services; JSON is the only wire format.
 */
public final class DCodeApi {

    /** Frontend revision the web UI reports; must match web/js/api.js
     *  DCODE_FRONTEND_BUILD (asserted by FrontendDeliveryTest). */
    public static final String FRONTEND_VERSION = "web-20261124-r5";

    private final Supplier<SessionService> sessionSupplier;
    private final Supplier<WorkspaceService> workspaceSupplier;
    private final Supplier<ReviewService> reviewSupplier;
    private final ProviderRegistry providerRegistry;
    private final RuntimeRegistry runtimes;
    private final ToolRegistry toolRegistry;
    private final SessionExecutor sessionExecutor;
    private final DCodeLifecycle lifecycle;
    private final ConfigurationService configuration;
    private final SseHub sseHub;
    private final InferenceStreamHub inferenceStreams;
    private final ObjectMapper mapper = new ObjectMapper();
    private final java.util.function.Supplier<eu.kalafatic.dcode.harness.tool.ToolEngine> toolEngineSupplier;
    private final java.util.function.Supplier<eu.kalafatic.dcode.repositories.service.RepositoryOperations> repositoryOperationsSupplier;
    private final java.util.function.Supplier<eu.kalafatic.dcode.config.git.GitAccountManager> gitAccountsSupplier;
    private final java.util.function.Supplier<eu.kalafatic.dcode.harness.registry.HarnessRegistry> harnessSupplier;
    private final java.util.function.Supplier<eu.kalafatic.dcode.config.agent.AgentRegistry> agentSupplier;
    private final java.util.function.Supplier<eu.kalafatic.dcode.config.profile.ProfileRegistry> profileSupplier;
    private volatile int httpPortActual;
    private volatile String httpPortBindAddress = "";
    private volatile Runnable shutdownAction;

    public DCodeApi(Supplier<SessionService> sessionSupplier,
                    Supplier<WorkspaceService> workspaceSupplier,
                    Supplier<ReviewService> reviewSupplier,
                    ProviderRegistry providerRegistry, ToolRegistry toolRegistry,
                    SessionExecutor sessionExecutor, DCodeLifecycle lifecycle,
                    ConfigurationService configuration, EventBus eventBus) {
        this(sessionSupplier, workspaceSupplier, reviewSupplier, providerRegistry,
                null, toolRegistry, sessionExecutor, lifecycle, configuration, eventBus,
                () -> null, () -> null, () -> null, () -> null);
    }

    public DCodeApi(Supplier<SessionService> sessionSupplier,
                    Supplier<WorkspaceService> workspaceSupplier,
                    Supplier<ReviewService> reviewSupplier,
                    ProviderRegistry providerRegistry, ToolRegistry toolRegistry,
                    SessionExecutor sessionExecutor, DCodeLifecycle lifecycle,
                    ConfigurationService configuration, EventBus eventBus,
                    java.util.function.Supplier<eu.kalafatic.dcode.harness.tool.ToolEngine> toolEngineSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.repositories.service.RepositoryOperations> repositoryOperationsSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.config.git.GitAccountManager> gitAccountsSupplier) {
        this(sessionSupplier, workspaceSupplier, reviewSupplier, providerRegistry, null,
                toolRegistry, sessionExecutor, lifecycle, configuration, eventBus,
                toolEngineSupplier, repositoryOperationsSupplier, gitAccountsSupplier,
                () -> null);
    }

    public DCodeApi(Supplier<SessionService> sessionSupplier,
                    Supplier<WorkspaceService> workspaceSupplier,
                    Supplier<ReviewService> reviewSupplier,
                    ProviderRegistry providerRegistry, RuntimeRegistry runtimes,
                    ToolRegistry toolRegistry,
                    SessionExecutor sessionExecutor, DCodeLifecycle lifecycle,
                    ConfigurationService configuration, EventBus eventBus) {
        this(sessionSupplier, workspaceSupplier, reviewSupplier, providerRegistry,
                runtimes, toolRegistry, sessionExecutor, lifecycle, configuration,
                eventBus, () -> null, () -> null, () -> null, () -> null);
    }

    public DCodeApi(Supplier<SessionService> sessionSupplier,
                    Supplier<WorkspaceService> workspaceSupplier,
                    Supplier<ReviewService> reviewSupplier,
                    ProviderRegistry providerRegistry, RuntimeRegistry runtimes,
                    ToolRegistry toolRegistry,
                    SessionExecutor sessionExecutor, DCodeLifecycle lifecycle,
                    ConfigurationService configuration, EventBus eventBus,
                    java.util.function.Supplier<eu.kalafatic.dcode.harness.tool.ToolEngine> toolEngineSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.repositories.service.RepositoryOperations> repositoryOperationsSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.config.git.GitAccountManager> gitAccountsSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.harness.registry.HarnessRegistry> harnessSupplier) {
        this(sessionSupplier, workspaceSupplier, reviewSupplier, providerRegistry,
                runtimes, toolRegistry, sessionExecutor, lifecycle, configuration,
                eventBus, toolEngineSupplier, repositoryOperationsSupplier,
                gitAccountsSupplier, harnessSupplier, () -> null, () -> null);
    }

    public DCodeApi(Supplier<SessionService> sessionSupplier,
                    Supplier<WorkspaceService> workspaceSupplier,
                    Supplier<ReviewService> reviewSupplier,
                    ProviderRegistry providerRegistry, RuntimeRegistry runtimes,
                    ToolRegistry toolRegistry,
                    SessionExecutor sessionExecutor, DCodeLifecycle lifecycle,
                    ConfigurationService configuration, EventBus eventBus,
                    java.util.function.Supplier<eu.kalafatic.dcode.harness.tool.ToolEngine> toolEngineSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.repositories.service.RepositoryOperations> repositoryOperationsSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.config.git.GitAccountManager> gitAccountsSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.harness.registry.HarnessRegistry> harnessSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.config.agent.AgentRegistry> agentSupplier) {
        this(sessionSupplier, workspaceSupplier, reviewSupplier, providerRegistry,
                runtimes, toolRegistry, sessionExecutor, lifecycle, configuration,
                eventBus, toolEngineSupplier, repositoryOperationsSupplier,
                gitAccountsSupplier, harnessSupplier, agentSupplier, () -> null);
    }

    public DCodeApi(Supplier<SessionService> sessionSupplier,
                    Supplier<WorkspaceService> workspaceSupplier,
                    Supplier<ReviewService> reviewSupplier,
                    ProviderRegistry providerRegistry, RuntimeRegistry runtimes,
                    ToolRegistry toolRegistry,
                    SessionExecutor sessionExecutor, DCodeLifecycle lifecycle,
                    ConfigurationService configuration, EventBus eventBus,
                    java.util.function.Supplier<eu.kalafatic.dcode.harness.tool.ToolEngine> toolEngineSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.repositories.service.RepositoryOperations> repositoryOperationsSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.config.git.GitAccountManager> gitAccountsSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.harness.registry.HarnessRegistry> harnessSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.config.agent.AgentRegistry> agentSupplier,
                    java.util.function.Supplier<eu.kalafatic.dcode.config.profile.ProfileRegistry> profileSupplier) {
        this.sessionSupplier = Objects.requireNonNull(sessionSupplier, "sessionSupplier");
        this.workspaceSupplier = Objects.requireNonNull(workspaceSupplier, "workspaceSupplier");
        this.reviewSupplier = Objects.requireNonNull(reviewSupplier, "reviewSupplier");
        this.providerRegistry = Objects.requireNonNull(providerRegistry, "providerRegistry");
        this.runtimes = runtimes; // optional in embedded/test deployments
        this.toolRegistry = Objects.requireNonNull(toolRegistry, "toolRegistry");
        this.sessionExecutor = sessionExecutor; // optional in embedded/test deployments
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.configuration = Objects.requireNonNull(configuration, "configuration");
        this.sseHub = new SseHub(eventBus);
        this.inferenceStreams = runtimes != null ? new InferenceStreamHub(runtimes) : null;
        this.toolEngineSupplier = Objects.requireNonNull(toolEngineSupplier, "toolEngineSupplier");
        this.repositoryOperationsSupplier = Objects.requireNonNull(repositoryOperationsSupplier,
                "repositoryOperationsSupplier");
        this.gitAccountsSupplier = Objects.requireNonNull(gitAccountsSupplier, "gitAccountsSupplier");
        this.harnessSupplier = Objects.requireNonNull(harnessSupplier, "harnessSupplier");
        this.agentSupplier = Objects.requireNonNull(agentSupplier, "agentSupplier");
        this.profileSupplier = Objects.requireNonNull(profileSupplier, "profileSupplier");
        this.modelManagementClient = new ModelManagementClient(
                resolveModelManagementEndpoint(), null);
    }

    /**
     * Endpoint for the shared ENR model management service: the explicit
     * {@code dcode.enr.endpoint} system property, then the registered ENR
     * runtime (kept in sync with live endpoint detection by the
     * RuntimeManager), then the client default probing.
     */
    private String resolveModelManagementEndpoint() {
        String configured = System.getProperty("dcode.enr.endpoint");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        if (runtimes != null) {
            var enr = runtimes.info(eu.kalafatic.dcode.config.provider.EnrProvider
                    .DEFAULT_PROVIDER_ID);
            if (enr.isPresent() && enr.get().endpoint() != null
                    && !enr.get().endpoint().isBlank()) {
                return enr.get().endpoint();
            }
        }
        return null;
    }

    /** Authorization: bearer token required only when a server token is configured. */
    public boolean isAuthorized(String authorizationHeader) {
        var expected = configuration.getSecret("server.authToken");
        if (expected.isEmpty()) {
            return true; // development mode: loopback without a token
        }
        String token = expected.get();
        return authorizationHeader != null
                && authorizationHeader.strip().equals("Bearer " + token);
    }

    /** Records the actually bound HTTP port (set by the server activator after start). */
    public void setHttpPortActual(int port, String bindAddress) {
        this.httpPortActual = port;
        this.httpPortBindAddress = bindAddress == null ? "" : bindAddress;
    }

    /**
     * Registers the graceful-shutdown action invoked by {@code POST /api/v1/server/shutdown}.
     * Set by the owning runtime (headless application) after wiring; when unset the
     * endpoint acknowledges but does not terminate the process.
     */
    public void setShutdownAction(Runnable action) {
        this.shutdownAction = action;
    }

    /** Opens an SSE stream for one session (or all events when sessionId is null). */
    public InputStream openEventStream(String sessionId) {
        return sseHub.open(sessionId);
    }

    /** Dispatch result: HTTP status + JSON body. */
    public record ApiPayload(int status, String body) {
    }

    public ApiPayload dispatch(String method, String path, NanoHTTPD.IHTTPSession session) {
        String normalized = path.startsWith("/health")
                ? API + "/health" : path;
        try {
            if (method.equals("GET") && normalized.equals(API + "/health")) {
                return payload(health());
            }
            if (!normalized.startsWith(API)) {
                throw new NotFoundException("Unknown route: " + path);
            }
            String route = normalized.substring(API.length());
            String[] parts = route.split("/");
            // route: ["", "workspaces", ...]
            if (parts.length >= 2 && parts[1].equals("status")) {
                return payload(requireGet(method, status()));
            }
            if (parts.length >= 2 && parts[1].equals("workspaces")) {
                return payload(handleWorkspaces(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("sessions")) {
                return payload(handleSessions(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("profiles")) {
                return payload(handleProfiles(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("reviews")) {
                return payload(handleReviews(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("providers")) {
                return payload(handleProviders(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("runtimes")) {
                return payload(handleRuntimes(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("inference")) {
                return payload(handleInference(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("harnesses")) {
                return payload(handleHarnesses(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("ports")) {
                return payload(requireGet(method, ports()));
            }
            if (parts.length >= 2 && parts[1].equals("server")) {
                return payload(handleServer(method, parts));
            }
            if (parts.length >= 2 && parts[1].equals("agents")) {
                return payload(handleAgents(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("models")) {
                return payload(handleModels(method, parts, session));
            }
            if (parts.length >= 2 && parts[1].equals("tools")) {
                if (parts.length == 4 && parts[3].equals("execute") && method.equals("POST")) {
                    return payload(executeTool(parts[2], session));
                }
                return payload(requireGet(method, tools()));
            }
            throw new NotFoundException("Unknown route: " + path);
        } catch (NotFoundException e) {
            return errorPayload(404, "NOT_FOUND", e.getMessage(), false);
        } catch (ProviderException e) {
            return errorPayload(503, "UPSTREAM_UNAVAILABLE", e.getMessage(), true);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return errorPayload(400, "VALIDATION_FAILED", e.getMessage(), false);
        } catch (eu.kalafatic.dcode.core.service.InvalidStateTransitionException e) {
            return errorPayload(409, "CONFLICT", e.getMessage(), false);
        } catch (Exception e) {
            return errorPayload(500, "INTERNAL_ERROR", e.getClass().getSimpleName() + ": "
                    + e.getMessage(), false);
        }
    }

    // ---- workspaces ----

    private String handleWorkspaces(String method, String[] parts,
                                    NanoHTTPD.IHTTPSession session) throws IOException {
        WorkspaceService workspaces = workspaceSupplier.get();
        if (parts.length == 2) {
            if (method.equals("GET")) {
                ArrayNode array = mapper.createArrayNode();
                for (var ws : workspaces.listWorkspaces()) {
                    array.add(workspaceNode(ws));
                }
                return ok(array);
            }
            if (method.equals("POST")) {
                JsonNode body = parseBody(session);
                String name = body.path("name").asText("");
                String rootPath = body.path("rootPath").asText(null);
                if (name.isBlank()) {
                    throw new IllegalArgumentException("Field 'name' is required");
                }
                var ws = workspaces.createWorkspace(name, WorkspaceMode.LOCAL, rootPath);
                return ok(workspaceNode(ws));
            }
        }
        if (parts.length == 3 && method.equals("GET")) {
            var ws = workspaces.getWorkspace(parts[2])
                    .orElseThrow(() -> new NotFoundException("Unknown workspace"));
            return ok(workspaceNode(ws));
        }
        if (parts.length == 4 && parts[3].equals("repositories")) {
            if (method.equals("POST")) {
                JsonNode body = parseBody(session);
                String name = body.path("name").asText("");
                String localPath = body.path("localPath").asText("");
                if (name.isBlank() || localPath.isBlank()) {
                    throw new IllegalArgumentException("Fields 'name' and 'localPath' are required");
                }
                RepositoryRef repository = new RepositoryRef(UUID.randomUUID().toString(),
                        name, localPath, body.path("remoteUrl").asText(null),
                        body.path("branch").asText(""),
                        body.path("accessPolicy").asText("READ_WRITE")
                                .equals("READ_ONLY")
                                ? RepositoryAccessPolicy.READ_ONLY
                                : RepositoryAccessPolicy.READ_WRITE,
                        Instant.now());
                return ok(workspaceNode(workspaces.addRepository(parts[2], repository)));
            }
            if (method.equals("GET")) {
                var ws = workspaces.getWorkspace(parts[2])
                        .orElseThrow(() -> new NotFoundException("Unknown workspace"));
                return ok(workspaceNode(ws));
            }
        }
        if (parts.length == 5 && parts[3].equals("repositories") && method.equals("DELETE")) {
            return ok(workspaceNode(workspaces.removeRepository(parts[2], parts[4])));
        }
        if (parts.length == 6 && parts[3].equals("repositories")) {
            return repositoryOperation(workspaces, parts[2], parts[4], parts[5], method,
                    session);
        }
        if (parts.length == 4 && parts[3].equals("tree") && method.equals("GET")) {
            return tree(workspaces, parts[2], session);
        }
        if (parts.length == 4 && parts[3].equals("files") && method.equals("GET")) {
            return readFile(workspaces, parts[2], session);
        }
        throw new NotFoundException("Unknown route segment");
    }

    // ---- sessions ----

    private String handleSessions(String method, String[] parts,
                                  NanoHTTPD.IHTTPSession session) throws IOException {
        SessionService sessions = sessionSupplier.get();
        if (parts.length == 2) {
            if (method.equals("GET")) {
                String workspaceId = session.getParms().get("workspaceId");
                ArrayNode array = mapper.createArrayNode();
                if (workspaceId != null) {
                    for (var s : sessions.listSessions(workspaceId)) {
                        array.add(sessionNode(s));
                    }
                } else {
                    for (var ws : workspaceSupplier.get().listWorkspaces()) {
                        for (var s : sessions.listSessions(ws.id())) {
                            array.add(sessionNode(s));
                        }
                    }
                }
                return ok(array);
            }
            if (method.equals("POST")) {
                JsonNode body = parseBody(session);
                String workspaceId = body.path("workspaceId").asText(null);
                if (workspaceId == null) {
                    throw new IllegalArgumentException("Field 'workspaceId' is required");
                }
                AgentRole role = null;
                String roleText = body.path("agentRole").asText(null);
                if (roleText != null && !roleText.isBlank()) {
                    try {
                        role = AgentRole.valueOf(roleText);
                    } catch (IllegalArgumentException e) {
                        throw new IllegalArgumentException("Unknown agentRole: " + roleText);
                    }
                }
                var created = sessions.createSession(new SessionService.CreateSessionRequest(
                        body.path("title").asText(null), workspaceId,
                        body.path("providerId").asText(null),
                        body.path("modelId").asText(null), role, null));
                bindAgent(created, body.path("agentId").asText(null));
                bindProfile(created, body.path("profileId").asText(null));
                return ok(sessionNode(created));
            }
        }
        if (parts.length == 3 && method.equals("GET")) {
            return ok(sessionNode(requireSession(sessions, parts[2])));
        }
        if (parts.length == 4 && parts[3].equals("rename") && method.equals("POST")) {
            JsonNode body = parseBody(session);
            String title = body.path("title").asText(null);
            if (title == null || title.isBlank()) {
                throw new IllegalArgumentException("Field 'title' is required");
            }
            return ok(sessionNode(sessions.renameSession(parts[2], title)));
        }
        if (parts.length == 3 && method.equals("DELETE")) {
            if (sessionExecutor != null) {
                sessionExecutor.cancel(parts[2]);
            }
            sessions.deleteSession(parts[2]);
            ObjectNode node = mapper.createObjectNode();
            node.put("id", parts[2]);
            node.put("deleted", true);
            return ok(node);
        }
        if (parts.length == 4 && parts[3].equals("start") && method.equals("POST")) {
            if (sessionExecutor == null) {
                throw new IllegalStateException("Execution engine is not available");
            }
            JsonNode body = parseBody(session);
            String task = body.path("task").asText(null);
            if (task == null || task.isBlank()) {
                throw new IllegalArgumentException("Field 'task' is required");
            }
            String providerId = body.path("providerId").asText(null);
            String modelId = body.path("modelId").asText(null);
            eu.kalafatic.dcode.core.model.Session target = requireSession(sessions, parts[2]);
            bindAgentIfChanged(target, body.path("agentId").asText(null));
            bindProfileIfChanged(target, body.path("profileId").asText(null));
            sessionExecutor.submit(parts[2], task, providerId, modelId);
            return ok(statusNode("accepted", "Execution submitted"));
        }
        if (parts.length == 4 && parts[3].equals("cancel") && method.equals("POST")) {
            if (sessionExecutor == null) {
                throw new IllegalStateException("Execution engine is not available");
            }
            sessionExecutor.cancel(parts[2]);
            return ok(statusNode("accepted", "Cancellation requested"));
        }
        if (parts.length == 4 && parts[3].equals("messages") && method.equals("GET")) {
            ArrayNode array = mapper.createArrayNode();
            for (var message : sessions.listMessages(parts[2])) {
                ObjectNode node = array.addObject();
                node.put("id", message.id());
                node.put("role", message.role().name());
                node.put("content", message.content());
                node.put("sequence", message.sequence());
                node.put("timestamp", message.timestamp().toString());
            }
            return ok(array);
        }
        if (parts.length == 4 && parts[3].equals("context") && method.equals("POST")) {
            return ingestPlatformContext(sessions, parts[2], session);
        }
        if (parts.length == 4 && parts[3].equals("reviews") && method.equals("GET")) {
            ArrayNode array = mapper.createArrayNode();
            for (var review : reviewSupplier.get().reviewsOf(parts[2])) {
                array.add(reviewNode(review));
            }
            return ok(array);
        }
        if (parts.length == 4 && parts[3].equals("changes") && method.equals("GET")) {
            return changes(sessions, parts[2]);
        }
        if (parts.length == 4 && parts[3].equals("toolcalls") && method.equals("GET")) {
            var toolEngine = toolEngineSupplier.get();
            if (toolEngine == null) {
                throw new IllegalStateException("Tool engine is not available");
            }
            ArrayNode array = mapper.createArrayNode();
            for (var call : toolEngine.callsOf(parts[2])) {
                ObjectNode node = array.addObject();
                node.put("id", call.id());
                node.put("toolName", call.toolName());
                node.put("state", call.state().name());
                node.put("errorMessage", call.errorMessage() != null
                        ? call.errorMessage() : "");
                node.put("startedAt", call.startedAt() != null
                        ? call.startedAt().toString() : "");
                node.put("completedAt", call.completedAt() != null
                        ? call.completedAt().toString() : "");
                node.put("argumentsJson", call.argumentsJson() != null
                        ? call.argumentsJson() : "");
                node.put("resultJson", call.resultJson() != null
                        ? call.resultJson() : "");
            }
            return ok(array);
        }
        throw new NotFoundException("Unknown route segment");
    }

    /**
     * Ingests a reconstructed platform context (EVO Cognitive Services) for
     * a session. Persisted as a SYSTEM message with the context id and
     * sections, so it survives restart and participates in history assembly.
     * Transport-neutral: no EVO classes are referenced.
     */
    private String ingestPlatformContext(SessionService sessions, String sessionId,
                                         NanoHTTPD.IHTTPSession session) throws IOException {
        requireSession(sessions, sessionId);
        JsonNode body = parseBody(session);
        String contextId = body.path("contextId").asText(null);
        if (contextId == null || contextId.isBlank()) {
            throw new IllegalArgumentException("Field 'contextId' is required");
        }
        String schemaVersion = body.path("schemaVersion").asText("1.0");
        JsonNode sections = body.path("sections");
        if (sections == null || sections.isMissingNode() || sections.isNull()) {
            throw new IllegalArgumentException("Field 'sections' is required");
        }
        long tokenEstimate = body.path("tokenEstimate").asLong(0);
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("source", "EVO_COGNITIVE_SERVICES");
        envelope.put("contextId", contextId);
        envelope.put("schemaVersion", schemaVersion);
        envelope.put("tokenEstimate", tokenEstimate);
        envelope.set("sections", sections);
        var message = sessions.appendMessage(sessionId, MessageRole.SYSTEM, envelope.toString());
        ObjectNode node = mapper.createObjectNode();
        node.put("accepted", true);
        node.put("messageId", message.id());
        node.put("contextId", contextId);
        node.put("tokenEstimate", tokenEstimate);
        return ok(node);
    }

    // ---- reviews ----

    private String handleReviews(String method, String[] parts,
                                 NanoHTTPD.IHTTPSession session) throws IOException {
        if (parts.length == 4 && (parts[3].equals("approve") || parts[3].equals("reject"))) {
            if (!method.equals("POST")) {
                throw new NotFoundException("Use POST");
            }
            ReviewService reviews = reviewSupplier.get();
            JsonNode body = parseBody(session);
            String approver = body.path("approverId").asText("web");
            String comment = body.path("comment").asText(null);
            var review = parts[3].equals("approve")
                    ? reviews.approve(parts[2], approver, comment)
                    : reviews.reject(parts[2], approver, comment);
            return ok(reviewNode(review));
        }
        if (parts.length == 3 && method.equals("GET")) {
            var review = reviewSupplier.get().find(parts[2])
                    .orElseThrow(() -> new NotFoundException("Unknown review"));
            return ok(reviewNode(review));
        }
        throw new NotFoundException("Unknown route segment");
    }



    // ---- repository git operations (user-initiated, policy-enforced) ----

    private String repositoryOperation(WorkspaceService workspaces, String workspaceId,
                                       String repositoryId, String operation, String method,
                                       NanoHTTPD.IHTTPSession session) {
        var workspace = workspaces.getWorkspace(workspaceId)
                .orElseThrow(() -> new NotFoundException("Unknown workspace"));
        RepositoryRef repository = null;
        for (RepositoryRef candidate : workspace.repositories()) {
            if (candidate.id().equals(repositoryId)) {
                repository = candidate;
            }
        }
        if (repository == null) {
            throw new NotFoundException("Unknown repository: " + repositoryId);
        }
        var repositories = repositoryOperationsSupplier.get();
        if (repositories == null) {
            throw new IllegalStateException("Repository engine is not available");
        }
        switch (operation) {
            case "status" -> {
                if (!method.equals("GET")) {
                    throw new NotFoundException("Use GET");
                }
                var info = repositories.info(workspace, repository);
                var changed = repositories.changedFiles(workspace, repository);
                ObjectNode node = mapper.createObjectNode();
                node.put("name", repository.name());
                node.put("branch", info.branch());
                node.put("remoteUrl", info.remoteUrl() != null ? info.remoteUrl() : "");
                node.put("clean", info.clean());
                node.put("commitCount", info.commitCount());
                node.put("accessPolicy", repository.accessPolicy().name());
                ArrayNode files = node.putArray("changedFiles");
                changed.forEach(files::add);
                node.put("changedCount", changed.size());
                return ok(node);
            }
            case "diff" -> {
                if (!method.equals("GET")) {
                    throw new NotFoundException("Use GET");
                }
                ObjectNode node = mapper.createObjectNode();
                node.put("name", repository.name());
                node.put("diff", repositories.diff(workspace, repository));
                return ok(node);
            }
            case "commit" -> {
                if (!method.equals("POST")) {
                    throw new NotFoundException("Use POST");
                }
                JsonNode body;
                try {
                    body = parseBody(session);
                } catch (java.io.IOException e) {
                    throw new IllegalStateException("Cannot parse request body: "
                            + e.getMessage());
                }
                String message = body.path("message").asText(null);
                if (message == null || message.isBlank()) {
                    throw new IllegalArgumentException("Field 'message' is required");
                }
                String authorName = "EVO-Code RCP";
                String authorEmail = "rcp@dcode.local";
                var gitAccounts = gitAccountsSupplier.get();
                String accountId = body.path("accountId").asText(null);
                if (accountId != null && gitAccounts != null) {
                    var account = gitAccounts.find(accountId);
                    if (account.isPresent()) {
                        var identity = gitAccounts.commitIdentity(accountId);
                        authorName = identity.userName();
                        authorEmail = identity.userEmail();
                    }
                }
                try {
                    String hash = repositories.commit(workspace, repository, message,
                            authorName, authorEmail);
                    ObjectNode node = mapper.createObjectNode();
                    node.put("name", repository.name());
                    node.put("commit", hash);
                    return ok(node);
                } catch (eu.kalafatic.dcode.repositories.service.RepositoryAccessException e) {
                    throw new IllegalStateException(e.getMessage());
                }
            }
            case "pull", "push" -> {
                if (!method.equals("POST")) {
                    throw new NotFoundException("Use POST");
                }
                try {
                    if (operation.equals("pull")) {
                        repositories.pull(workspace, repository);
                    } else {
                        repositories.push(workspace, repository);
                    }
                    ObjectNode node = mapper.createObjectNode();
                    node.put("name", repository.name());
                    node.put("operation", operation);
                    node.put("done", true);
                    return ok(node);
                } catch (eu.kalafatic.dcode.repositories.service.RepositoryAccessException e) {
                    throw new IllegalStateException(e.getMessage());
                }
            }
            default -> throw new NotFoundException("Unknown repository operation: "
                    + operation);
        }
    }

    // ---- workspace file browsing (read-only, server-side containment) ----

    private String tree(WorkspaceService workspaces, String workspaceId,
                        NanoHTTPD.IHTTPSession session) {
        var workspace = workspaces.getWorkspace(workspaceId)
                .orElseThrow(() -> new NotFoundException("Unknown workspace"));
        String rootPath = workspace.rootPath();
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalStateException("Workspace has no root path");
        }
        java.nio.file.Path root = java.nio.file.Path.of(rootPath);
        String relative = session.getParms().getOrDefault("path", "");
        int depth = Math.min(parseInt(session.getParms().getOrDefault("depth", "2"), 2), 6);
        java.nio.file.Path base;
        try {
            base = relative.isBlank() ? root
                    : eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(root, relative);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid path: " + e.getMessage());
        }
        if (!java.nio.file.Files.isDirectory(base)) {
            throw new NotFoundException("Not a directory: " + relative);
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("workspaceId", workspaceId);
        node.put("path", relative);
        ArrayNode entries = node.putArray("entries");
        java.util.List<String[]> listing = new java.util.ArrayList<>();
        collectTree(base, base, depth, listing);
        listing.sort(java.util.Comparator.comparing(a -> (a[1].equals("dir") ? "0" : "1")
                + a[0]));
        for (String[] entry : listing) {
            ObjectNode entryNode = entries.addObject();
            entryNode.put("name", entry[0]);
            entryNode.put("path", entry[2]);
            entryNode.put("type", entry[1]);
            entryNode.put("size", Long.parseLong(entry[3]));
        }
        node.put("entryCount", entries.size());
        return ok(node);
    }

    private void collectTree(java.nio.file.Path base, java.nio.file.Path dir, int depth,
                             java.util.List<String[]> out) {
        if (out.size() >= 500) {
            return;
        }
        try (var stream = java.nio.file.Files.list(dir)) {
            for (java.nio.file.Path child : stream.sorted().toList()) {
                if (out.size() >= 500) {
                    return;
                }
                String name = child.getFileName().toString();
                boolean isDirectory = java.nio.file.Files.isDirectory(child);
                if (isDirectory && isSkippedDirectory(name)) {
                    continue;
                }
                String rel = base.relativize(child).toString();
                long size = 0;
                try {
                    size = isDirectory ? 0 : java.nio.file.Files.size(child);
                } catch (Exception ignored) {
                    // unreadable entry: report size 0
                }
                out.add(new String[]{name, isDirectory ? "dir" : "file", rel,
                        String.valueOf(size)});
                if (isDirectory && depth > 1) {
                    collectTree(base, child, depth - 1, out);
                }
            }
        } catch (Exception ignored) {
            // unreadable directory: skip
        }
    }

    private static boolean isSkippedDirectory(String name) {
        return name.equals(".git") || name.equals("target") || name.equals("build")
                || name.equals("node_modules") || name.equals(".mvn") || name.equals("bin")
                || name.equals("out") || name.equals(".settings");
    }

    private String readFile(WorkspaceService workspaces, String workspaceId,
                            NanoHTTPD.IHTTPSession session) {
        var workspace = workspaces.getWorkspace(workspaceId)
                .orElseThrow(() -> new NotFoundException("Unknown workspace"));
        String rootPath = workspace.rootPath();
        if (rootPath == null || rootPath.isBlank()) {
            throw new IllegalStateException("Workspace has no root path");
        }
        String relative = session.getParms().get("path");
        if (relative == null || relative.isBlank()) {
            throw new IllegalArgumentException("Query parameter 'path' is required");
        }
        java.nio.file.Path file;
        try {
            file = eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                    java.nio.file.Path.of(rootPath), relative);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid path: " + e.getMessage());
        }
        if (!java.nio.file.Files.isRegularFile(file)) {
            throw new NotFoundException("No such file: " + relative);
        }
        try {
            long size = java.nio.file.Files.size(file);
            if (size > MAX_FILE_READ_BYTES) {
                throw new IllegalStateException("File too large to view (" + size
                        + " bytes, limit " + MAX_FILE_READ_BYTES + ")");
            }
            ObjectNode node = mapper.createObjectNode();
            node.put("workspaceId", workspaceId);
            node.put("path", relative);
            node.put("size", size);
            node.put("content", java.nio.file.Files.readString(file));
            return ok(node);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot read file: " + e.getMessage());
        }
    }

    private static final long MAX_FILE_READ_BYTES = 512 * 1024;

    // ---- tool execution through the single ToolEngine ----

    private String executeTool(String toolName, NanoHTTPD.IHTTPSession session) {
        var toolEngine = toolEngineSupplier.get();
        if (toolEngine == null) {
            throw new IllegalStateException("Tool engine is not available");
        }
        var tool = toolRegistry.find(toolName)
                .orElseThrow(() -> new NotFoundException("Unknown tool: " + toolName));
        JsonNode body;
        try {
            body = parseBody(session);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Cannot parse request body: " + e.getMessage());
        }
        String sessionId = body.path("sessionId").asText(null);
        if (sessionId == null || sessionId.isBlank()) {
            throw new IllegalArgumentException("Field 'sessionId' is required");
        }
        SessionService sessions = sessionSupplier.get();
        var dcodeSession = requireSession(sessions, sessionId);
        var workspace = workspaceSupplier.get().getWorkspace(dcodeSession.getWorkspaceId())
                .orElseThrow(() -> new NotFoundException("Workspace of session not found"));
        if (workspace.rootPath() == null || workspace.rootPath().isBlank()) {
            throw new IllegalStateException("Workspace has no root path");
        }
        var repositories = repositoryOperationsSupplier.get();
        var gitAccounts = gitAccountsSupplier.get();

        boolean mutating = !tool.definition().readOnly();
        var state = dcodeSession.getState();
        if (mutating) {
            switch (state) {
                case CREATED, SUSPENDED, WAITING_FOR_APPROVAL ->
                        dcodeSession = sessions.transition(sessionId,
                                eu.kalafatic.dcode.core.model.SessionState.RUNNING);
                case RUNNING -> { /* already active */ }
                default -> throw new IllegalStateException(
                        "Session " + sessionId + " is " + state
                                + "; mutating tools require a resumable session");
            }
        }

        java.util.Map<String, Object> arguments = new java.util.LinkedHashMap<>();
        body.fields().forEachRemaining(entry -> {
            if (!entry.getKey().equals("sessionId")) {
                arguments.put(entry.getKey(), toPlainValue(entry.getValue()));
            }
        });

        var toolContext = new eu.kalafatic.dcode.harness.tool.ToolContext(
                dcodeSession, workspace, java.nio.file.Path.of(workspace.rootPath()),
                repositories, gitAccounts, () -> false,
                message -> sessions.appendMessage(sessionId,
                        eu.kalafatic.dcode.core.model.MessageRole.SYSTEM, message));
        var call = toolEngine.execute(toolContext, toolName, arguments);

        if (mutating) {
            sessions.transition(sessionId,
                    eu.kalafatic.dcode.core.model.SessionState.SUSPENDED);
        }

        ObjectNode node = mapper.createObjectNode();
        ObjectNode toolCall = node.putObject("toolCall");
        toolCall.put("id", call.id());
        toolCall.put("toolName", call.toolName());
        toolCall.put("state", call.state().name());
        toolCall.put("errorMessage", call.errorMessage() != null ? call.errorMessage() : "");
        toolCall.put("startedAt", call.startedAt().toString());
        toolCall.put("completedAt", call.completedAt() != null
                ? call.completedAt().toString() : "");
        node.put("output", call.resultJson() != null ? call.resultJson() : "");
        ObjectNode result = mapper.createObjectNode();
        try {
            JsonNode wrapper = call.resultJson() != null && !call.resultJson().isBlank()
                    ? mapper.readTree(call.resultJson()) : mapper.createObjectNode();
            result.put("success", wrapper.path("success").asBoolean(false));
            result.put("error", wrapper.path("error").asText(""));
            // structured tools return their payload as a nested JSON string; unwrap it
            // so API clients (the web UI) can consume structured fields directly.
            JsonNode output = wrapper.path("output");
            if (output.isTextual()) {
                String outputText = output.asText();
                try {
                    result.set("output", outputText.isBlank()
                            ? mapper.createObjectNode() : mapper.readTree(outputText));
                } catch (Exception parseFailure) {
                    result.put("output", outputText);
                }
            } else {
                result.set("output", output);
            }
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", String.valueOf(e.getMessage()));
        }
        node.set("result", result);
        return ok(node);
    }

    private static Object toPlainValue(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        }
        if (value.isInt()) {
            return value.asInt();
        }
        if (value.isLong()) {
            return value.asLong();
        }
        if (value.isDouble() || value.isFloat()) {
            return value.asDouble();
        }
        if (value.isBoolean()) {
            return value.asBoolean();
        }
        return value.toString();
    }

    // ---- live per-repository changes + diffs ----

    private String changes(SessionService sessions, String sessionId) {
        var dcodeSession = requireSession(sessions, sessionId);
        var workspace = workspaceSupplier.get().getWorkspace(dcodeSession.getWorkspaceId())
                .orElseThrow(() -> new NotFoundException("Workspace of session not found"));
        var repositories = repositoryOperationsSupplier.get();
        if (repositories == null) {
            throw new IllegalStateException("Repository engine is not available");
        }
        ObjectNode node = mapper.createObjectNode();
        node.put("sessionId", sessionId);
        node.put("state", dcodeSession.getState().name());
        ArrayNode repositoryNodes = node.putArray("repositories");
        for (var repository : workspace.repositories()) {
            try {
                var info = repositories.info(workspace, repository);
                var changed = repositories.changedFiles(workspace, repository);
                ObjectNode repositoryNode = repositoryNodes.addObject();
                repositoryNode.put("name", repository.name());
                repositoryNode.put("branch", info.branch());
                repositoryNode.put("accessPolicy", repository.accessPolicy().name());
                repositoryNode.put("clean", info.clean());
                ArrayNode files = repositoryNode.putArray("changedFiles");
                changed.forEach(files::add);
                repositoryNode.put("changedCount", changed.size());
                repositoryNode.put("diff", repositories.diff(workspace, repository));
            } catch (Exception e) {
                // Plain-folder workspaces (no .git) or broken repositories: report the
                // session's own tool-call record — the files the engine really wrote —
                // with a synthesized diff, instead of an empty error entry.
                var toolEngine = toolEngineSupplier.get();
                List<String> files = eu.kalafatic.dcode.harness.change.ChangeManager
                        .sessionToolFiles(toolEngine, sessionId);
                ObjectNode repositoryNode = repositoryNodes.addObject();
                repositoryNode.put("name", repository.name());
                repositoryNode.put("branch", "");
                repositoryNode.put("accessPolicy", repository.accessPolicy().name());
                repositoryNode.put("clean", true);
                ArrayNode fileNodes = repositoryNode.putArray("changedFiles");
                files.forEach(fileNodes::add);
                repositoryNode.put("changedCount", files.size());
                repositoryNode.put("diff", toolDiff(toolEngine, sessionId));
                repositoryNode.put("source", "session-tool-record");
            }
        }
        return ok(node);
    }

    /** Synthesized unified diff from the session's write_file tool calls. */
    private String toolDiff(eu.kalafatic.dcode.harness.tool.ToolEngine toolEngine,
                            String sessionId) {
        if (toolEngine == null) {
            return "";
        }
        StringBuilder diff = new StringBuilder();
        for (eu.kalafatic.dcode.core.model.ToolCall call : toolEngine.callsOf(sessionId)) {
            if (!"write_file".equals(call.toolName())) {
                continue;
            }
            try {
                JsonNode arguments = mapper.readTree(call.argumentsJson());
                String path = arguments.path("path").asText("");
                String content = arguments.path("content").asText("");
                if (path.isBlank()) {
                    continue;
                }
                diff.append("--- /dev/null\n+++ b/").append(path).append('\n');
                for (String line : content.split("\n", -1)) {
                    diff.append('+').append(line).append('\n');
                }
            } catch (Exception ignored) {
                // unparseable tool call: skip
            }
        }
        return diff.toString();
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    // ---- providers / runtimes / models / tools ----

    /**
     * Provider listing, runtime-backed when a runtime registry is present (the
     * production wiring). The response keeps the legacy fields (id/name/baseUrl/local)
     * for existing web clients and adds runtime management fields.
     */
    private String handleProviders(String method, String[] parts,
                                   NanoHTTPD.IHTTPSession session) {
        if (parts.length == 2 && method.equals("GET")) {
            ArrayNode array = mapper.createArrayNode();
            if (runtimes != null) {
                for (RuntimeInfo info : runtimes.runtimes()) {
                    array.add(runtimeNode(info));
                }
            } else {
                for (var provider : providerRegistry.providers()) {
                    ObjectNode node = array.addObject();
                    node.put("id", provider.providerId());
                    node.put("name", provider.displayName());
                    node.put("baseUrl", provider.baseUrl());
                    node.put("local", provider.isLocal());
                }
            }
            return ok(array);
        }
        if (parts.length == 4 && parts[3].equals("test") && method.equals("POST")) {
            ConnectionTestResult result = runtimes != null
                    ? runtimes.testConnection(parts[2])
                    : providerRegistry.testConnection(parts[2]);
            ObjectNode node = mapper.createObjectNode();
            node.put("providerId", parts[2]);
            node.put("success", result.success());
            node.put("message", result.message());
            return ok(node);
        }
        throw new NotFoundException("Unknown route segment");
    }

    // ---- runtime management ----

    private String handleRuntimes(String method, String[] parts,
                                  NanoHTTPD.IHTTPSession session) throws IOException {
        requireRuntimes();
        if (parts.length == 3 && parts[2].equals("preflight") && method.equals("POST")) {
            return preflightRuntime(session);
        }
        if (parts.length == 3 && parts[2].equals("scan") && method.equals("POST")) {
            return scanRuntimes(session);
        }
        if (parts.length == 2 && method.equals("GET")) {
            if (runtimes instanceof eu.kalafatic.dcode.config.runtime.RuntimeManager manager) {
                manager.refreshAvailabilityIfStale();
            }
            ArrayNode array = mapper.createArrayNode();
            for (RuntimeInfo info : runtimes.runtimes()) {
                array.add(runtimeNode(info));
            }
            return ok(array);
        }
        if (parts.length == 2 && method.equals("POST")) {
            JsonNode body = parseBody(session);
            RuntimeType type = RuntimeType.fromWire(body.path("type").asText(null));
            String endpoint = body.path("endpoint").asText(null);
            if (endpoint == null || endpoint.isBlank()) {
                throw new IllegalArgumentException("Field 'endpoint' is required");
            }
            validateEndpoint(endpoint);
            String id = body.path("id").asText(null);
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            if (runtimes.isRegistered(id)) {
                throw new IllegalArgumentException("Runtime already registered: " + id);
            }
            String name = body.path("name").asText(null);
            RuntimeRegistration registration = new RuntimeRegistration(id,
                    name != null && !name.isBlank() ? name : type.displayName(),
                    type, endpoint, body.path("enabled").asBoolean(true),
                    body.path("default").asBoolean(false),
                    stringMap(body.path("configuration")));
            if (runtimes instanceof eu.kalafatic.dcode.config.runtime.RuntimeManager manager) {
                manager.register(registration, body.path("authToken").asText(null),
                        body.path("apiKey").asText(null));
            } else {
                runtimes.register(registration);
            }
            return ok(runtimeNode(runtimes.info(id).orElseThrow()));
        }
        if (parts.length >= 3) {
            String id = parts[2];
            if (parts.length == 3 && method.equals("GET")) {
                ObjectNode node = runtimeNode(requireRuntime(id));
                node.set("models", modelArray(runtimes.listModels(id)));
                return ok(node);
            }
            if (parts.length == 3 && method.equals("DELETE")) {
                runtimes.unregister(id);
                ObjectNode node = mapper.createObjectNode();
                node.put("id", id);
                node.put("removed", true);
                node.put("note", "Registration removed; external runtime data untouched");
                return ok(node);
            }
            if (parts.length == 3 && method.equals("POST")) {
                JsonNode body = parseBody(session);
                RuntimeInfo current = requireRuntime(id);
                String endpoint = body.path("endpoint").asText(current.endpoint());
                if (body.hasNonNull("endpoint")) {
                    validateEndpoint(endpoint);
                }
                RuntimeRegistration registration = new RuntimeRegistration(
                        current.id(),
                        body.path("name").asText(current.name()),
                        current.type(),
                        endpoint,
                        body.path("enabled").asBoolean(current.enabled()),
                        body.path("default").asBoolean(current.defaultRuntime()),
                        mergeConfiguration(current.configuration(),
                                body.path("configuration")));
                if (runtimes instanceof eu.kalafatic.dcode.config.runtime.RuntimeManager manager) {
                    manager.update(registration, body.path("authToken").asText(null),
                            body.path("apiKey").asText(null));
                } else {
                    runtimes.update(registration);
                }
                return ok(runtimeNode(runtimes.info(id).orElseThrow()));
            }
            if (parts.length == 4 && method.equals("POST")) {
                JsonNode body = parseBody(session);
                switch (parts[3]) {
                    case "test" -> {
                        ConnectionTestResult result = runtimes.testConnection(id);
                        ObjectNode node = mapper.createObjectNode();
                        node.put("id", id);
                        node.put("success", result.success());
                        node.put("message", result.message());
                        return ok(node);
                    }
                    case "enable" -> {
                        runtimes.setEnabled(id, true);
                        return ok(runtimeNode(requireRuntime(id)));
                    }
                    case "disable" -> {
                        runtimes.setEnabled(id, false);
                        return ok(runtimeNode(requireRuntime(id)));
                    }
                    case "default" -> {
                        runtimes.setDefault(id, body.path("modelId").asText(null));
                        return ok(runtimeNode(requireRuntime(id)));
                    }
                    case "status" -> {
                        var status = runtimes.runtimeStatus(id);
                        ObjectNode node = mapper.createObjectNode();
                        node.put("id", id);
                        node.put("reachable", status.reachable());
                        node.put("healthy", status.healthy());
                        node.put("message", status.message());
                        node.put("checkedAt", status.checkedAt().toString());
                        return ok(node);
                    }
                    default -> {
                    }
                }
            }
            if (parts.length == 4 && parts[3].equals("models") && method.equals("GET")) {
                return ok(modelArray(runtimes.listModels(id)));
            }
            if (parts.length == 6 && parts[3].equals("models") && method.equals("POST")) {
                String modelId = java.net.URLDecoder.decode(parts[4],
                        java.nio.charset.StandardCharsets.UTF_8);
                return switch (parts[5]) {
                    case "load" -> ok(modelNode(runtimes.loadModel(id, modelId)));
                    case "unload" -> ok(modelNode(runtimes.unloadModel(id, modelId)));
                    case "pull" -> ok(modelNode(runtimes.pullModel(id, modelId)));
                    default -> throw new NotFoundException("Unknown model operation: "
                            + parts[5]);
                };
            }
        }
        throw new NotFoundException("Unknown route segment");
    }

    // ---- unified model management (shared ENR infrastructure) ----

    private final ModelManagementClient modelManagementClient;

    /**
     * Parses a raw JSON document returned by the shared model management
     * client (Jackson types never cross the OSGi bundle boundary; each
     * bundle parses with its own mapper).
     */
    private JsonNode parseUpstream(String json) {
        try {
            return json == null || json.isBlank() ? mapper.createObjectNode() : mapper.readTree(json);
        } catch (IOException e) {
            throw new ProviderException("ENR model management returned invalid JSON", e);
        }
    }

    /**
     * /api/v1/models — runtime model listing (unchanged), plus the unified
     * model management surface consumed from the shared ENR service:
     * providers, per-provider installed/available models, pull jobs with
     * progress, cancellation and removal. No model management state or job
     * tracking lives in DCode.
     */
    private String handleModels(String method, String[] parts,
                                NanoHTTPD.IHTTPSession session) throws IOException {
        if (parts.length == 2) {
            return requireGet(method, models(session.getParms().get("runtimeId")));
        }
        String sub = parts[2];
        switch (sub) {
            case "providers" -> {
                if (parts.length == 3 && method.equals("GET")) {
                    return ok(parseUpstream(modelManagementClient.providersJson()));
                }
            }
            case "jobs" -> {
                if (parts.length == 3 && method.equals("GET")) {
                    return ok(parseUpstream(modelManagementClient.downloadsJson()));
                }
                if (parts.length == 4) {
                    String jobId = java.net.URLDecoder.decode(parts[3],
                            java.nio.charset.StandardCharsets.UTF_8);
                    switch (method) {
                        case "GET" -> {
                            return ok(parseUpstream(modelManagementClient.statusJson(jobId)));
                        }
                        case "DELETE" -> {
                            return ok(parseUpstream(modelManagementClient.cancelJson(jobId)));
                        }
                        default -> {
                        }
                    }
                }
            }
            default -> {
                String provider = java.net.URLDecoder.decode(sub,
                        java.nio.charset.StandardCharsets.UTF_8);
                if (parts.length == 4) {
                    String action = parts[3];
                    switch (action) {
                        case "installed" -> {
                            return requireGet(method,
                                    ok(parseUpstream(modelManagementClient.installedJson(provider))));
                        }
                        case "available" -> {
                            return requireGet(method,
                                    ok(parseUpstream(modelManagementClient.availableJson(provider))));
                        }
                        case "pull" -> {
                            if (!method.equals("POST")) {
                                throw new NotFoundException("POST expected for model pull");
                            }
                            JsonNode body = parseBody(session);
                            String model = body.path("model").asText("");
                            if (model.isBlank()) {
                                throw new IllegalArgumentException("Field 'model' is required");
                            }
                            return ok(parseUpstream(modelManagementClient.pullJson(provider, model)));
                        }
                        default -> {
                            if (method.equals("DELETE")) {
                                String modelId = java.net.URLDecoder.decode(action,
                                        java.nio.charset.StandardCharsets.UTF_8);
                                return ok(parseUpstream(
                                        modelManagementClient.removeJson(provider, modelId)));
                            }
                        }
                    }
                }
            }
        }
        throw new NotFoundException("Unknown model management route");
    }

    // ---- direct inference chat (no coding task involved) ----

    private String handleInference(String method, String[] parts,
                                   NanoHTTPD.IHTTPSession session) throws IOException {
        requireRuntimes();
        if (parts.length >= 3 && parts[2].equals("cancel") && method.equals("POST")) {
            JsonNode body = parseBody(session);
            String requestId = body.path("requestId").asText(null);
            if (requestId == null || requestId.isBlank()) {
                throw new IllegalArgumentException("Field 'requestId' is required");
            }
            boolean cancelled = inferenceStreams != null
                    && inferenceStreams.cancel(requestId);
            ObjectNode node = mapper.createObjectNode();
            node.put("requestId", requestId);
            node.put("cancelled", cancelled);
            return ok(node);
        }
        if (parts.length >= 3 && parts[2].equals("chat") && method.equals("POST")) {
            JsonNode body = parseBody(session);
            String runtimeId = resolveRuntimeId(body);
            ChatRequest request = parseChatRequest(body);
            if (!runtimes.isRegistered(runtimeId)) {
                throw new NotFoundException("Unknown runtime: " + runtimeId);
            }
            if (!runtimes.isEnabled(runtimeId)) {
                throw new IllegalStateException("Runtime '" + runtimeId + "' is disabled");
            }
            try {
                ChatResponse response = runtimes.complete(runtimeId, request);
                ObjectNode node = mapper.createObjectNode();
                node.put("runtimeId", runtimeId);
                node.put("model", request.model());
                node.put("content", response.content());
                node.put("finishReason", response.finishReason() != null
                        ? response.finishReason() : "");
                node.put("promptTokens", response.promptTokens());
                node.put("completionTokens", response.completionTokens());
                return ok(node);
            } catch (ProviderException e) {
                throw new IllegalStateException("Inference failed on runtime '"
                        + runtimeId + "': " + e.getMessage());
            }
        }
        throw new NotFoundException("Unknown route segment");
    }

    /**
     * Opens the SSE stream for {@code POST /api/v1/inference/chat/stream}. Parsing and
     * validation happen here; the HTTP server serves the returned stream.
     */
    public InputStream openInferenceStream(String body) {
        requireRuntimes();
        try {
            JsonNode parsed = mapper.readTree(body == null || body.isBlank()
                    ? "{}" : body);
            String runtimeId = resolveRuntimeId(parsed);
            if (!runtimes.isRegistered(runtimeId)) {
                throw new IllegalArgumentException("Unknown runtime: " + runtimeId);
            }
            if (!runtimes.isEnabled(runtimeId)) {
                throw new IllegalArgumentException("Runtime '" + runtimeId
                        + "' is disabled");
            }
            ChatRequest request = parseChatRequest(parsed);
            var started = inferenceStreams.start(runtimeId, request);
            return started.stream();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid request body: " + e.getMessage());
        }
    }

    /** Cancels a streamed direct inference generation by request id. */
    public boolean cancelInference(String requestId) {
        return inferenceStreams != null && inferenceStreams.cancel(requestId);
    }

    private String resolveRuntimeId(JsonNode body) {
        String runtimeId = body.path("runtimeId").asText(null);
        if (runtimeId == null || runtimeId.isBlank()) {
            var defaultRuntime = runtimes.defaultRuntime();
            if (defaultRuntime.isEmpty()) {
                throw new IllegalArgumentException(
                        "No runtimeId given and no default runtime configured");
            }
            runtimeId = defaultRuntime.get().id();
        }
        return runtimeId;
    }

    /** Parses a chat request from an inference API body. */
    public ChatRequest parseChatRequest(JsonNode body) {
        String model = body.path("model").asText(null);
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("Field 'model' is required");
        }
        List<ChatMessage> messages = new ArrayList<>();
        JsonNode messageNodes = body.path("messages");
        if (messageNodes.isArray()) {
            for (JsonNode node : messageNodes) {
                String role = node.path("role").asText("user").toLowerCase();
                String content = node.path("content").asText("");
                switch (role) {
                    case "system" -> messages.add(ChatMessage.system(content));
                    case "assistant" -> messages.add(ChatMessage.assistant(content));
                    default -> messages.add(ChatMessage.user(content));
                }
            }
        }
        double temperature = body.path("temperature").asDouble(0.0);
        int maxTokens = body.path("maxTokens").asInt(body.path("max_tokens").asInt(0));
        return new ChatRequest(model, messages, temperature, maxTokens);
    }

    /**
     * Scans local endpoints for inference services (DCode, ENR, Ollama,
     * OpenAI-compatible). One shared implementation for the RCP and Web IDEs.
     */
    private String scanRuntimes(NanoHTTPD.IHTTPSession session) throws IOException {
        JsonNode body = parseBody(session);
        List<String> endpoints = new ArrayList<>();
        JsonNode list = body.path("endpoints");
        if (list.isArray()) {
            for (JsonNode entry : list) {
                String endpoint = entry.asText("");
                if (!endpoint.isBlank()) {
                    endpoints.add(endpoint);
                }
            }
        }
        eu.kalafatic.dcode.config.runtime.RuntimeScanner scanner =
                new eu.kalafatic.dcode.config.runtime.RuntimeScanner();
        List<eu.kalafatic.dcode.config.runtime.RuntimeScanner.Finding> findings =
                endpoints.isEmpty() ? scanner.scanLocal() : scanner.scan(endpoints);
        ArrayNode array = mapper.createArrayNode();
        for (var finding : findings) {
            ObjectNode node = array.addObject();
            node.put("endpoint", finding.endpoint());
            node.put("kind", finding.kind());
            node.put("name", finding.name());
            node.put("description", finding.description());
            node.put("reachable", finding.reachable());
            node.put("modelCount", finding.modelCount());
            ArrayNode samples = node.putArray("sampleModels");
            finding.sampleModels().forEach(samples::add);
            node.put("type", finding.runtimeType() != null
                    ? finding.runtimeType().wireName() : "");
            boolean registered = runtimes.runtimes().stream()
                    .anyMatch(info -> info.endpoint().equals(finding.endpoint()));
            node.put("registered", registered);
        }
        return ok(array);
    }

    private RuntimeInfo requireRuntime(String id) {
        return runtimes.info(id)
                .orElseThrow(() -> new NotFoundException("Unknown runtime: " + id));
    }

    /**
     * Connection preflight for a runtime that is not saved yet: builds a temporary
     * adapter with the supplied credentials (never stored) and probes the endpoint.
     */
    private String preflightRuntime(NanoHTTPD.IHTTPSession session) throws IOException {
        JsonNode body = parseBody(session);
        RuntimeType type = RuntimeType.fromWire(body.path("type").asText(null));
        String endpoint = body.path("endpoint").asText(null);
        if (endpoint == null || endpoint.isBlank()) {
            throw new IllegalArgumentException("Field 'endpoint' is required");
        }
        validateEndpoint(endpoint);
        String authToken = body.path("authToken").asText(null);
        String apiKey = body.path("apiKey").asText(null);
        RuntimeRegistration probe = new RuntimeRegistration("preflight",
                type.displayName(), type, endpoint, true, false, Map.of());
        var adapter = new eu.kalafatic.dcode.config.runtime.RuntimeAdapterFactory()
                .create(probe,
                        () -> authToken != null && !authToken.isBlank() ? authToken : null,
                        () -> apiKey != null && !apiKey.isBlank() ? apiKey : null);
        ConnectionTestResult result = adapter.testConnection();
        ObjectNode node = mapper.createObjectNode();
        node.put("success", result.success());
        node.put("message", result.message());
        ArrayNode models = node.putArray("models");
        try {
            for (ModelDefinition model : adapter.listModels()) {
                models.add(modelNode(model));
            }
        } catch (ProviderException ignored) {
            // model discovery unsupported or failed: the connection result stands
        }
        return ok(node);
    }

    private void requireRuntimes() {
        if (runtimes == null) {
            throw new IllegalStateException("Runtime management is not available");
        }
    }

    private static void validateEndpoint(String endpoint) {
        try {
            java.net.URI uri = java.net.URI.create(endpoint);
            if (!"http".equalsIgnoreCase(uri.getScheme())
                    && !"https".equalsIgnoreCase(uri.getScheme())) {
                throw new IllegalArgumentException(
                        "Endpoint must be an http(s) URL: " + endpoint);
            }
        } catch (java.lang.IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid endpoint URL: " + endpoint);
        }
    }

    private Map<String, String> stringMap(JsonNode node) {
        Map<String, String> result = new java.util.LinkedHashMap<>();
        if (node != null && node.isObject()) {
            node.fields().forEachRemaining(entry ->
                    result.put(entry.getKey(), entry.getValue().asText("")));
        }
        return result;
    }

    private Map<String, String> mergeConfiguration(Map<String, String> current,
                                                   JsonNode patch) {
        Map<String, String> merged = new java.util.LinkedHashMap<>(current);
        if (patch != null && patch.isObject()) {
            patch.fields().forEachRemaining(entry ->
                    merged.put(entry.getKey(), entry.getValue().asText("")));
        }
        return merged;
    }

    private ObjectNode runtimeNode(RuntimeInfo info) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", info.id());
        node.put("name", info.name());
        node.put("type", info.type().wireName());
        node.put("typeDisplayName", info.type().displayName());
        node.put("baseUrl", info.endpoint());
        node.put("endpoint", info.endpoint());
        node.put("local", info.local());
        node.put("enabled", info.enabled());
        node.put("default", info.defaultRuntime());
        if (info.defaultRuntime()) {
            node.put("defaultModel", runtimes.defaultModel().orElse(""));
        }
        node.put("modelCount", info.modelCount());
        ObjectNode capabilities = node.putObject("capabilities");
        capabilities.put("modelDiscovery", info.capabilities().modelDiscovery());
        capabilities.put("streaming", info.capabilities().streaming());
        capabilities.put("cancellation", info.capabilities().cancellation());
        capabilities.put("modelLoad", info.capabilities().modelLoad());
        capabilities.put("modelUnload", info.capabilities().modelUnload());
        capabilities.put("modelPull", info.capabilities().modelPull());
        capabilities.put("health", info.capabilities().health());
        capabilities.put("runtimeStatus", info.capabilities().runtimeStatus());
        ArrayNode reported = capabilities.putArray("reported");
        info.capabilities().reported().forEach(reported::add);
        ObjectNode status = node.putObject("status");
        status.put("reachable", info.status().reachable());
        status.put("healthy", info.status().healthy());
        status.put("message", info.status().message());
        status.put("checkedAt", info.status().checkedAt().toString());
        ObjectNode credentials = node.putObject("credentials");
        Map<String, String> credentialState = runtimes != null
                ? runtimes.credentialState(info.id()) : Map.of();
        credentialState.forEach((key, value) -> credentials.put(key,
                value == null || value.isBlank() ? "" : "***"));
        ObjectNode configuration = node.putObject("configuration");
        info.configuration().forEach(configuration::put);
        return node;
    }

    private ArrayNode modelArray(List<ModelDefinition> models) {
        ArrayNode array = mapper.createArrayNode();
        for (ModelDefinition model : models) {
            array.add(modelNode(model));
        }
        return array;
    }

    private ObjectNode modelNode(ModelDefinition model) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", model.id());
        node.put("name", model.name());
        node.put("providerId", model.providerId());
        node.put("runtimeId", model.providerId());
        node.put("contextWindowTokens", model.contextWindowTokens());
        node.put("sizeBytes", model.sizeBytes());
        node.put("quantization", model.quantization());
        node.put("available", model.available());
        node.put("loaded", model.loaded());
        ArrayNode capabilities = node.putArray("capabilities");
        model.capabilities().forEach(capabilities::add);
        return node;
    }

    private String models(String runtimeIdFilter) {
        List<ModelDefinition> models = runtimeIdFilter != null
                && !runtimeIdFilter.isBlank()
                ? providerRegistry.listModels(runtimeIdFilter)
                : providerRegistry.listAllModels();
        return ok(modelArray(models));
    }

    private String tools() {
        ArrayNode array = mapper.createArrayNode();
        for (var definition : toolRegistry.definitions()) {
            ObjectNode node = array.addObject();
            node.put("name", definition.name());
            node.put("description", definition.description());
            node.put("readOnly", definition.readOnly());
            node.put("scope", definition.scope().name());
            node.put("parameters", definition.parametersJson());
        }
        return ok(array);
    }

    private String health() {
        ObjectNode node = mapper.createObjectNode();
        node.put("application", "DCODE");
        node.put("version", "2.6.11");
        // Must match web/js/api.js DCODE_FRONTEND_BUILD (FrontendDeliveryTest).
        node.put("frontendVersion", FRONTEND_VERSION);
        // Diagnostic only — never selects a serving path or implementation.
        node.put("resourceSource", "osgi-bundle");
        node.put("executionMode",
                System.getProperty("eclipse.pde.launch") != null ? "pde" : "product");
        node.put("ready", lifecycle.isReady());
        node.put("status", lifecycle.isReady() ? "UP" : "DEGRADED");
        node.put("timestamp", Instant.now().toString());
        ObjectNode stages = node.putObject("stages");
        for (eu.kalafatic.dcode.core.lifecycle.LifecycleStage stage
                : eu.kalafatic.dcode.core.lifecycle.LifecycleStage.values()) {
            stages.put(stage.name(), lifecycle.getStageState(stage).name());
        }
        return ok(node);
    }

    private String status() {
        ObjectNode node = mapper.createObjectNode();
        node.put("ready", lifecycle.isReady());
        ObjectNode stages = node.putObject("stages");
        for (eu.kalafatic.dcode.core.lifecycle.LifecycleStage stage
                : eu.kalafatic.dcode.core.lifecycle.LifecycleStage.values()) {
            stages.put(stage.name(), lifecycle.getStageState(stage).name());
        }
        return ok(node);
    }

    /**
     * Port & network status of the DCode services: expected family port, actual
     * bound port, bind address, protocol, runtime state and conflict diagnostics
     * (including the occupying process where the OS allows identification).
     */
    private String ports() {
        String bindAddress = configuration.getConfiguration().bindAddress();
        ArrayNode array = mapper.createArrayNode();
        array.add(portNode("http", "EVO-Code HTTP API (REST + SSE + web UI)",
                eu.kalafatic.dcode.core.port.DCodePorts.HTTP, httpPortActual,
                httpPortBindAddress.isBlank() ? bindAddress : httpPortBindAddress,
                "HTTP", httpPortActual > 0, bindAddress));
        array.add(portNode("management", "Management server (reserved)",
                eu.kalafatic.dcode.core.port.DCodePorts.MANAGEMENT, 0, bindAddress,
                "HTTP", false, bindAddress));
        array.add(portNode("auxiliary", "Auxiliary services (reserved)",
                eu.kalafatic.dcode.core.port.DCodePorts.AUX, 0, bindAddress,
                "HTTP", false, bindAddress));
        return ok(array);
    }

    private ObjectNode portNode(String id, String name, int expectedPort, int actualPort,
                                String bindAddress, String protocol, boolean listening,
                                String configuredBind) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", id);
        node.put("name", name);
        node.put("expectedPort", expectedPort);
        node.put("actualPort", actualPort);
        node.put("bindAddress", bindAddress);
        node.put("protocol", protocol);
        node.put("state", listening ? "LISTENING" : "STOPPED");
        node.put("listening", listening);
        if (!listening && expectedPort > 0
                && !eu.kalafatic.dcode.core.port.PortInspector.available(expectedPort)) {
            node.put("conflict", true);
            var occupant = eu.kalafatic.dcode.core.port.PortInspector.occupant(
                    expectedPort);
            if (occupant.isPresent()) {
                node.put("occupantPid", occupant.get().pid());
                node.put("occupantProcess", occupant.get().command());
            }
        } else {
            node.put("conflict", false);
        }
        return node;
    }

    /**
     * Binds an agent profile to a freshly created session: the profile instructions
     * are stored as a dedicated SYSTEM message rendered by the harness as part of the
     * system prompt. An explicitly requested agent wins; otherwise the registered
     * default agent is bound.
     */
    private void bindAgent(eu.kalafatic.dcode.core.model.Session created,
                           String agentId) {
        eu.kalafatic.dcode.config.agent.AgentRegistry registry = agentSupplier.get();
        if (registry == null) {
            return;
        }
        eu.kalafatic.dcode.config.agent.AgentDefinition agent = null;
        if (agentId != null && !agentId.isBlank()) {
            agent = registry.find(agentId).orElse(null);
        }
        if (agent == null) {
            agent = registry.defaultAgent().orElse(null);
        }
        if (agent == null) {
            return;
        }
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("source", "DCODE_AGENT_PROFILE");
        envelope.put("agentId", agent.id());
        envelope.put("agentName", agent.name());
        envelope.put("instructions", agent.systemInstructions());
        sessionSupplier.get().appendMessage(created.getId(), MessageRole.SYSTEM,
                envelope.toString());
    }

    /**
     * Binds a conversation profile to a freshly created session: the profile
     * instructions are stored as a dedicated SYSTEM message rendered by the harness
     * as part of the system prompt. An explicitly requested profile wins; otherwise
     * the registered default profile is bound.
     */
    private void bindProfile(eu.kalafatic.dcode.core.model.Session created,
                             String profileId) {
        eu.kalafatic.dcode.config.profile.ProfileRegistry registry = profileSupplier.get();
        if (registry == null) {
            return;
        }
        eu.kalafatic.dcode.config.profile.ConversationProfile profile = null;
        if (profileId != null && !profileId.isBlank()) {
            profile = registry.find(profileId).orElse(null);
        }
        if (profile == null) {
            profile = registry.defaultProfile().orElse(null);
        }
        if (profile == null) {
            return;
        }
        ObjectNode envelope = mapper.createObjectNode();
        envelope.put("source", "DCODE_CONVERSATION_PROFILE");
        envelope.put("profileId", profile.id());
        envelope.put("name", profile.name());
        envelope.put("instructions", profile.systemInstructions());
        sessionSupplier.get().appendMessage(created.getId(), MessageRole.SYSTEM,
                envelope.toString());
    }

    /** Binds an agent only when it differs from the most recent bound agent. */
    private void bindAgentIfChanged(eu.kalafatic.dcode.core.model.Session session,
                                    String agentId) {
        eu.kalafatic.dcode.config.agent.AgentRegistry registry = agentSupplier.get();
        if (registry == null) {
            return;
        }
        eu.kalafatic.dcode.config.agent.AgentDefinition agent = null;
        if (agentId != null && !agentId.isBlank()) {
            agent = registry.find(agentId).orElse(null);
        }
        if (agent == null) {
            agent = registry.defaultAgent().orElse(null);
        }
        if (agent == null) {
            return;
        }
        if (agent.id().equals(lastEnvelopeId(session.getId(), "DCODE_AGENT_PROFILE",
                "agentId"))) {
            return;
        }
        bindAgent(session, agent.id());
    }

    /** Binds a profile only when it differs from the most recent bound profile. */
    private void bindProfileIfChanged(eu.kalafatic.dcode.core.model.Session session,
                                      String profileId) {
        eu.kalafatic.dcode.config.profile.ProfileRegistry registry = profileSupplier.get();
        if (registry == null) {
            return;
        }
        eu.kalafatic.dcode.config.profile.ConversationProfile profile = null;
        if (profileId != null && !profileId.isBlank()) {
            profile = registry.find(profileId).orElse(null);
        }
        if (profile == null) {
            profile = registry.defaultProfile().orElse(null);
        }
        if (profile == null) {
            return;
        }
        if (profile.id().equals(lastEnvelopeId(session.getId(),
                "DCODE_CONVERSATION_PROFILE", "profileId"))) {
            return;
        }
        bindProfile(session, profile.id());
    }

    /** Id of the most recent bound envelope of the given source, or "". */
    private String lastEnvelopeId(String sessionId, String source, String idField) {
        List<eu.kalafatic.dcode.core.model.Message> messages =
                sessionSupplier.get().listMessages(sessionId);
        for (int i = messages.size() - 1; i >= 0; i--) {
            eu.kalafatic.dcode.core.model.Message message = messages.get(i);
            if (message.role() == MessageRole.SYSTEM && message.content() != null
                    && message.content().startsWith("{\"source\":\"" + source + "\"")) {
                try {
                    JsonNode envelope = new com.fasterxml.jackson.databind.ObjectMapper()
                            .readTree(message.content());
                    return envelope.path(idField).asText("");
                } catch (Exception ignored) {
                    return "";
                }
            }
        }
        return "";
    }

    private String handleProfiles(String method, String[] parts,
                                  NanoHTTPD.IHTTPSession session) throws IOException {
        eu.kalafatic.dcode.config.profile.ProfileRegistry registry = profileSupplier.get();
        if (registry == null) {
            throw new IllegalStateException("Profile registry is not available");
        }
        if (parts.length == 2 && method.equals("GET")) {
            ArrayNode array = mapper.createArrayNode();
            for (var profile : registry.profiles()) {
                array.add(profileNode(profile));
            }
            return ok(array);
        }
        if (parts.length == 2 && method.equals("POST")) {
            JsonNode body = parseBody(session);
            String id = body.path("id").asText(null);
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            if (registry.find(id).isPresent() && registry.find(id).get().builtin()) {
                throw new IllegalArgumentException("Built-in profiles are immutable");
            }
            var profile = new eu.kalafatic.dcode.config.profile.ConversationProfile(id,
                    body.path("name").asText(id),
                    body.path("description").asText(""),
                    body.path("systemInstructions").asText(""),
                    false);
            return ok(profileNode(registry.register(profile)));
        }
        if (parts.length == 3 && method.equals("DELETE")) {
            registry.unregister(parts[2]);
            ObjectNode node = mapper.createObjectNode();
            node.put("id", parts[2]);
            node.put("removed", true);
            return ok(node);
        }
        if (parts.length == 4 && parts[3].equals("default") && method.equals("POST")) {
            registry.setDefaultProfile(parts[2]);
            return ok(profileNode(registry.find(parts[2]).orElseThrow()));
        }
        throw new NotFoundException("Unknown route segment");
    }

    private ObjectNode profileNode(eu.kalafatic.dcode.config.profile.ConversationProfile profile) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", profile.id());
        node.put("name", profile.name());
        node.put("description", profile.description());
        node.put("systemInstructions", profile.systemInstructions());
        node.put("builtin", profile.builtin());
        boolean isDefault = profileSupplier.get() != null
                && profileSupplier.get().defaultProfile().map(
                        eu.kalafatic.dcode.config.profile.ConversationProfile::id)
                .orElse("").equals(profile.id());
        node.put("default", isDefault);
        return node;
    }

    private String handleAgents(String method, String[] parts,
                                NanoHTTPD.IHTTPSession session) throws IOException {
        eu.kalafatic.dcode.config.agent.AgentRegistry registry = agentSupplier.get();
        if (registry == null) {
            throw new IllegalStateException("Agent registry is not available");
        }
        if (parts.length == 2 && method.equals("GET")) {
            ArrayNode array = mapper.createArrayNode();
            for (var agent : registry.agents()) {
                array.add(agentNode(agent));
            }
            return ok(array);
        }
        if (parts.length == 2 && method.equals("POST")) {
            JsonNode body = parseBody(session);
            String id = body.path("id").asText(null);
            if (id == null || id.isBlank()) {
                id = UUID.randomUUID().toString();
            }
            var agent = new eu.kalafatic.dcode.config.agent.AgentDefinition(id,
                    body.path("name").asText(id),
                    body.path("description").asText(""),
                    body.path("role").asText("MAIN"),
                    body.path("systemInstructions").asText(""),
                    stringList(body.path("toolPermissions")),
                    stringList(body.path("restrictions")),
                    body.path("preferredRuntimeId").asText(""),
                    body.path("preferredModel").asText(""),
                    false);
            if (registry.find(id).isPresent() && registry.find(id).get().builtin()) {
                throw new IllegalArgumentException("Built-in agents are immutable");
            }
            return ok(agentNode(registry.register(agent)));
        }
        if (parts.length == 3 && method.equals("DELETE")) {
            registry.unregister(parts[2]);
            ObjectNode node = mapper.createObjectNode();
            node.put("id", parts[2]);
            node.put("removed", true);
            return ok(node);
        }
        if (parts.length == 4 && parts[3].equals("default") && method.equals("POST")) {
            registry.setDefaultAgent(parts[2]);
            return ok(agentNode(registry.find(parts[2]).orElseThrow()));
        }
        throw new NotFoundException("Unknown route segment");
    }

    private ObjectNode agentNode(eu.kalafatic.dcode.config.agent.AgentDefinition agent) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", agent.id());
        node.put("name", agent.name());
        node.put("description", agent.description());
        node.put("role", agent.role());
        node.put("systemInstructions", agent.systemInstructions());
        node.put("preferredRuntimeId", agent.preferredRuntimeId());
        node.put("preferredModel", agent.preferredModel());
        node.put("builtin", agent.builtin());
        ArrayNode permissions = node.putArray("toolPermissions");
        agent.toolPermissions().forEach(permissions::add);
        ArrayNode restrictions = node.putArray("restrictions");
        agent.restrictions().forEach(restrictions::add);
        boolean isDefault = agentSupplier.get() != null
                && agentSupplier.get().defaultAgent().map(
                        eu.kalafatic.dcode.config.agent.AgentDefinition::id)
                .orElse("").equals(agent.id());
        node.put("default", isDefault);
        return node;
    }

    private List<String> stringList(JsonNode node) {
        List<String> result = new ArrayList<>();
        if (node != null && node.isArray()) {
            node.forEach(entry -> result.add(entry.asText("")));
        }
        return result;
    }

    private String handleHarnesses(String method, String[] parts,
                                   NanoHTTPD.IHTTPSession session) {
        eu.kalafatic.dcode.harness.registry.HarnessRegistry registry =
                harnessSupplier.get();
        if (registry == null) {
            registry = new eu.kalafatic.dcode.harness.registry.DefaultHarnessRegistry();
        }
        if (parts.length == 2 && method.equals("GET")) {
            ArrayNode array = mapper.createArrayNode();
            String selected = registry.defaultHarnessId().orElse("");
            for (var harness : registry.harnesses()) {
                array.add(harnessNode(harness, registry, selected));
            }
            return ok(array);
        }
        if (parts.length == 4 && method.equals("POST")) {
            switch (parts[3]) {
                case "default" -> registry.selectDefaultHarness(parts[2]);
                case "enable" -> registry.setHarnessEnabled(parts[2], true);
                case "disable" -> registry.setHarnessEnabled(parts[2], false);
                default -> throw new NotFoundException("Unknown route segment");
            }
            return ok(harnessNode(registry.find(parts[2]).orElseThrow(), registry,
                    registry.defaultHarnessId().orElse("")));
        }
        throw new NotFoundException("Unknown route segment");
    }

    private ObjectNode harnessNode(
            eu.kalafatic.dcode.harness.registry.HarnessDescriptor harness,
            eu.kalafatic.dcode.harness.registry.HarnessRegistry registry,
            String selected) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", harness.id());
        node.put("name", harness.name());
        node.put("description", harness.description());
        node.put("integrated", harness.integrated());
        node.put("available", harness.available());
        boolean enabled = registry.isEnabled(harness.id());
        node.put("enabled", enabled);
        node.put("usable", harness.usable() && enabled);
        node.put("default", harness.id().equals(selected));
        ArrayNode capabilities = node.putArray("capabilities");
        harness.capabilities().forEach(capabilities::add);
        return node;
    }

    // ---- helpers ----

    private static final String API = "/api/v1";

    private static String requireGet(String method, String payload) {
        if (!method.equals("GET")) {
            throw new NotFoundException("Use GET");
        }
        return payload;
    }

    private JsonNode parseBody(NanoHTTPD.IHTTPSession session) throws IOException {
        String body = DCodeHttpServer.readBody(session);
        if (body == null || body.isBlank()) {
            return mapper.createObjectNode();
        }
        return mapper.readTree(body);
    }

    private eu.kalafatic.dcode.core.model.Session requireSession(SessionService sessions,
                                                                 String id) {
        return sessions.getSession(id)
                .orElseThrow(() -> new NotFoundException("Unknown session: " + id));
    }

    private ObjectNode workspaceNode(eu.kalafatic.dcode.core.model.Workspace ws) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", ws.id());
        node.put("name", ws.name());
        node.put("mode", ws.mode().name());
        node.put("rootPath", ws.rootPath() != null ? ws.rootPath() : "");
        node.put("createdAt", ws.createdAt().toString());
        ArrayNode repositories = node.putArray("repositories");
        for (RepositoryRef repo : ws.repositories()) {
            ObjectNode repoNode = repositories.addObject();
            repoNode.put("id", repo.id());
            repoNode.put("name", repo.name());
            repoNode.put("localPath", repo.localPath());
            repoNode.put("remoteUrl", repo.remoteUrl() != null ? repo.remoteUrl() : "");
            repoNode.put("branch", repo.branch());
            repoNode.put("accessPolicy", repo.accessPolicy().name());
        }
        return node;
    }

    private ObjectNode sessionNode(eu.kalafatic.dcode.core.model.Session s) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", s.getId());
        node.put("title", s.getTitle());
        node.put("workspaceId", s.getWorkspaceId());
        node.put("state", s.getState().name());
        node.put("createdAt", s.getCreatedAt().toString());
        node.put("updatedAt", s.getUpdatedAt().toString());
        node.put("providerId", s.getProviderId() != null ? s.getProviderId() : "");
        node.put("modelId", s.getModelId() != null ? s.getModelId() : "");
        node.put("agentRole", s.getAgentRole().name());
        return node;
    }

    private ObjectNode reviewNode(ReviewService.Review review) {
        ObjectNode node = mapper.createObjectNode();
        node.put("id", review.id());
        node.put("sessionId", review.sessionId());
        node.put("changeSetId", review.changeSetId());
        node.put("status", review.status().name());
        node.put("approverId", review.approverId() != null ? review.approverId() : "");
        node.put("comment", review.comment() != null ? review.comment() : "");
        node.put("createdAt", review.createdAt().toString());
        if (review.decidedAt() != null) {
            node.put("decidedAt", review.decidedAt().toString());
        }
        return node;
    }

    private ObjectNode statusNode(String status, String message) {
        ObjectNode node = mapper.createObjectNode();
        node.put("status", status);
        node.put("message", message);
        return node;
    }

    /**
     * Minimal lifecycle surface: {@code POST /api/v1/server/shutdown} triggers a graceful
     * shutdown of the owning runtime. Loopback-bound; a server token (when configured)
     * still gates it like every other route.
     */
    private String handleServer(String method, String[] parts) {
        if (parts.length == 3 && parts[2].equals("shutdown") && method.equals("POST")) {
            return ok(serverShutdown());
        }
        throw new NotFoundException("Unknown route: /api/v1/server");
    }

    private JsonNode serverShutdown() {
        ObjectNode node = mapper.createObjectNode();
        Runnable action = shutdownAction;
        node.put("status", "shutting_down");
        node.put("scheduled", action != null);
        if (action != null) {
            // Acknowledge first, then stop after the response has been flushed.
            Thread shutdown = new Thread(() -> {
                try {
                    Thread.sleep(300);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                action.run();
            }, "dcode-api-shutdown");
            shutdown.setDaemon(true);
            shutdown.start();
        }
        return node;
    }

    String ok(JsonNode node) {
        return node.toString();
    }

    private ApiPayload payload(String body) {
        return new ApiPayload(200, body);
    }

    private ApiPayload errorPayload(int status, String code, String message, boolean retryable) {
        return new ApiPayload(status, error(code, message, null, retryable));
    }

    /** Top-level error envelope (family TEIP error model shape). */
    public String error(String code, String message, String requestId, boolean retryable) {
        ObjectNode node = mapper.createObjectNode();
        node.put("code", code);
        node.put("message", message != null ? message : "");
        node.put("retryable", retryable);
        node.put("timestamp", Instant.now().toString());
        return node.toString();
    }

    /** 404 inside dispatch. */
    public static final class NotFoundException extends RuntimeException {
        public NotFoundException(String message) {
            super(message);
        }
    }
}
