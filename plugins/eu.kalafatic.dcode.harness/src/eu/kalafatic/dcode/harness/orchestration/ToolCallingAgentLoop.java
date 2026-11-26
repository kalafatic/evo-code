package eu.kalafatic.dcode.harness.orchestration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.config.provider.ChatMessage;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ProviderException;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.runtime.RuntimeRegistry;
import eu.kalafatic.dcode.config.runtime.StreamHandle;
import eu.kalafatic.dcode.config.runtime.StreamListener;
import eu.kalafatic.dcode.config.runtime.StreamResult;
import eu.kalafatic.dcode.core.event.DCodeEventType;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.event.StandardEvent;
import eu.kalafatic.dcode.core.model.MessageRole;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.harness.context.ContextEngine;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolEngine;
import eu.kalafatic.dcode.harness.tool.ToolRegistry;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * Native DCode agent loop: plans implicitly through tool use, executes tools through the
 * tool engine, and terminates with a structured final message.
 *
 * <p>This is the native harness execution path. No external harness (OpenCode, Claude
 * Code) is involved anywhere.</p>
 */
public final class ToolCallingAgentLoop {

    /** Outcome of one loop execution. */
    public record Outcome(boolean success, String summary, int iterations, int toolCalls,
                          String error) {
    }

    private static final int DEFAULT_MAX_ITERATIONS = 20;

    private final ProviderRegistry providers;
    private final ToolEngine toolEngine;
    private final ToolRegistry toolRegistry;
    private final ContextEngine contextEngine;
    private final SessionService sessionService;
    private final ObjectMapper mapper = new ObjectMapper();
    private final int maxIterations;

    public ToolCallingAgentLoop(ProviderRegistry providers, ToolEngine toolEngine,
                                ToolRegistry toolRegistry, ContextEngine contextEngine,
                                SessionService sessionService) {
        this(providers, toolEngine, toolRegistry, contextEngine, sessionService,
                DEFAULT_MAX_ITERATIONS);
    }

    public ToolCallingAgentLoop(ProviderRegistry providers, ToolEngine toolEngine,
                                ToolRegistry toolRegistry, ContextEngine contextEngine,
                                SessionService sessionService, int maxIterations) {
        this.providers = providers;
        this.toolEngine = toolEngine;
        this.toolRegistry = toolRegistry;
        this.contextEngine = contextEngine;
        this.sessionService = sessionService;
        this.maxIterations = maxIterations;
    }

    /**
     * Runs the loop for one task until the model emits a final message, the iteration
     * budget is exhausted or cancellation is requested.
     */
    public Outcome run(Workspace workspace, Session session, String task, String providerId,
                       String modelId, java.util.Map<String, Object> services,
                       BooleanSupplier cancellationRequested) {
        SessionService loopSessionService = (SessionService) services.get("sessionService");
        if (loopSessionService == null) {
            loopSessionService = sessionService;
        }
        final SessionService effectiveSessionService = loopSessionService;
        String systemPrompt = contextEngine.buildSystemPrompt(workspace, session, task,
                toolRegistry.definitions());
        List<ChatMessage> messages = new ArrayList<>();
        messages.add(ChatMessage.system(systemPrompt));

        // Ingested platform context (EVO Cognitive Services) and agent profiles:
        // both are rendered as dedicated SYSTEM sections, never mixed with
        // user/assistant history.
        List<eu.kalafatic.dcode.core.model.Message> sessionMessages =
                effectiveSessionService.listMessages(session.getId());
        java.util.List<eu.kalafatic.dcode.core.model.Message> platformContexts = new java.util.ArrayList<>();
        java.util.List<eu.kalafatic.dcode.core.model.Message> agentProfiles = new java.util.ArrayList<>();
        java.util.List<eu.kalafatic.dcode.core.model.Message> conversationProfiles = new java.util.ArrayList<>();
        java.util.List<eu.kalafatic.dcode.core.model.Message> historyMessages = new java.util.ArrayList<>();
        for (eu.kalafatic.dcode.core.model.Message message : sessionMessages) {
            if (message.role() == MessageRole.SYSTEM && message.content() != null
                    && message.content().startsWith("{\"source\":\"EVO_COGNITIVE_SERVICES\"")) {
                platformContexts.add(message);
            } else if (message.role() == MessageRole.SYSTEM && message.content() != null
                    && message.content().startsWith("{\"source\":\"DCODE_AGENT_PROFILE\"")) {
                agentProfiles.add(message);
            } else if (message.role() == MessageRole.SYSTEM && message.content() != null
                    && message.content().startsWith("{\"source\":\"DCODE_CONVERSATION_PROFILE\"")) {
                conversationProfiles.add(message);
            } else {
                historyMessages.add(message);
            }
        }
        for (eu.kalafatic.dcode.core.model.Message platform : platformContexts) {
            try {
                com.fasterxml.jackson.databind.JsonNode envelope =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(platform.content());
                messages.add(ChatMessage.system("Platform context (EVO):\n"
                        + envelope.path("sections").toString()));
            } catch (Exception ignored) {
                messages.add(ChatMessage.system("Platform context (EVO):\n" + platform.content()));
            }
        }
        for (eu.kalafatic.dcode.core.model.Message profile : agentProfiles) {
            try {
                com.fasterxml.jackson.databind.JsonNode envelope =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(profile.content());
                String instructions = envelope.path("instructions").asText("");
                if (!instructions.isBlank()) {
                    messages.add(ChatMessage.system("Agent profile ("
                            + envelope.path("agentName").asText("agent")
                            + "):\n" + instructions));
                }
            } catch (Exception ignored) {
                // malformed profile: ignored, history assembly continues
            }
        }
        for (eu.kalafatic.dcode.core.model.Message profile : conversationProfiles) {
            try {
                com.fasterxml.jackson.databind.JsonNode envelope =
                        new com.fasterxml.jackson.databind.ObjectMapper().readTree(profile.content());
                String instructions = envelope.path("instructions").asText("");
                if (!instructions.isBlank()) {
                    messages.add(ChatMessage.system("Conversation profile ("
                            + envelope.path("name").asText("default")
                            + "):\n" + instructions));
                }
            } catch (Exception ignored) {
                // malformed profile: ignored, history assembly continues
            }
        }
        messages.add(ChatMessage.user("Task: " + task));
        String history = contextEngine.historySummary(historyMessages);
        if (!history.isBlank()) {
            messages.add(ChatMessage.system("Conversation history so far:\n" + history));
        }

        int toolCalls = 0;
        try {
            for (int iteration = 0; iteration < maxIterations; iteration++) {
                if (cancellationRequested.getAsBoolean()) {
                    return new Outcome(false, "Cancelled by user", iteration, toolCalls, null);
                }
                String text = providers instanceof RuntimeRegistry runtimes
                        ? streamCompletion(runtimes, providerId, modelId, messages,
                                session.getId(), services, cancellationRequested)
                        : providers.complete(providerId, new ChatRequest(modelId, messages,
                                0.2, 4096)).content().strip();
                if (text.isEmpty()) {
                    // Empty model output (typically an exhausted context or a model
                    // that cannot follow the agent protocol): fail explicitly with a
                    // diagnostic instead of pretending completion.
                    return new Outcome(false, "Empty response from model '" + modelId
                            + "' on runtime '" + providerId + "': the model may not "
                            + "support the coding-agent protocol or its context window "
                            + "is too small for the harness prompt", iteration + 1,
                            toolCalls, null);
                }
                effectiveSessionService.appendMessage(session.getId(), MessageRole.ASSISTANT, text);

                JsonNode action = parseAction(text);
                if (action == null) {
                    // Not JSON: treat as final answer (graceful degradation).
                    return new Outcome(true, text, iteration + 1, toolCalls, null);
                }
                JsonNode finalNode = action.get("final");
                if (finalNode != null) {
                    return new Outcome(true, finalNode.asText(text), iteration + 1,
                            toolCalls, null);
                }
                JsonNode toolNode = action.get("tool");
                if (toolNode == null) {
                    return new Outcome(false,
                            "Agent produced neither a tool call nor a final message",
                            iteration + 1, toolCalls, null);
                }
                String toolName = toolNode.asText("");
                Map<String, Object> arguments = toArguments(action.get("arguments"));
                messages.add(ChatMessage.assistant(text));

                ToolContext toolContext = new ToolContext(session, workspace,
                        java.nio.file.Path.of(workspace.rootPath()),
                        (eu.kalafatic.dcode.repositories.service.RepositoryOperations)
                                services.get("repositoryOperations"),
                        (eu.kalafatic.dcode.config.git.GitAccountManager)
                                services.get("gitAccounts"),
                        cancellationRequested,
                        message -> effectiveSessionService.appendMessage(session.getId(),
                                MessageRole.SYSTEM, message));
                var call = toolEngine.execute(toolContext, toolName, arguments);
                toolCalls++;
                String toolOutput = call.errorMessage() != null
                        ? "ERROR: " + call.errorMessage()
                        : call.resultJson() != null ? extractOutput(call.resultJson()) : "";
                messages.add(ChatMessage.user("Tool result (" + toolName + "):\n"
                        + toolOutput));
                effectiveSessionService.appendMessage(session.getId(), MessageRole.TOOL,
                        toolName + ": " + toolOutput);
            }
            return new Outcome(false, "Iteration budget exhausted (" + maxIterations
                    + " iterations)", maxIterations, toolCalls, null);
        } catch (Exception e) {
            return new Outcome(false, "Agent loop failed: " + e.getMessage(), 0, toolCalls,
                    e.getMessage());
        }
    }

    /**
     * Streams one completion through a runtime with token-level progress events
     * ({@code MESSAGE_PARTIAL}), blocking until the terminal callback or cancellation.
     * Partial tokens are batched (>= 256 chars or >= 100 ms) to keep the event stream
     * useful without flooding it.
     */
    private String streamCompletion(RuntimeRegistry runtimes, String providerId,
                                    String modelId, List<ChatMessage> messages,
                                    String sessionId, Map<String, Object> services,
                                    BooleanSupplier cancellationRequested) {
        StringBuilder buffer = new StringBuilder();
        StringBuilder delta = new StringBuilder();
        long[] lastFlush = {System.currentTimeMillis()};
        final boolean[] terminated = {false};
        final ProviderException[] failure = {null};
        EventBus eventBus = (EventBus) services.get("eventBus");
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        StreamHandle[] handle = {StreamHandle.noop()};
        handle[0] = runtimes.stream(providerId, new ChatRequest(modelId, messages, 0.2, 4096),
                new StreamListener() {
                    @Override
                    public void onToken(String token) {
                        if (token == null || token.isEmpty()) {
                            return;
                        }
                        synchronized (buffer) {
                            buffer.append(token);
                            delta.append(token);
                        }
                        if (eventBus != null) {
                            long now = System.currentTimeMillis();
                            synchronized (buffer) {
                                if (delta.length() >= 256 || now - lastFlush[0] >= 100) {
                                    publishPartial(eventBus, sessionId, delta.toString());
                                    delta.setLength(0);
                                    lastFlush[0] = now;
                                }
                            }
                        }
                    }

                    @Override
                    public void onComplete(StreamResult result) {
                        terminated[0] = true;
                        latch.countDown();
                    }

                    @Override
                    public void onError(ProviderException error) {
                        failure[0] = error;
                        terminated[0] = true;
                        latch.countDown();
                    }
                });
        try {
            while (!terminated[0]) {
                if (cancellationRequested.getAsBoolean()) {
                    handle[0].cancel();
                    if (!latch.await(2, java.util.concurrent.TimeUnit.SECONDS)) {
                        break;
                    }
                    break;
                }
                if (!latch.await(100, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                    continue;
                }
                break;
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            handle[0].cancel();
        }
        synchronized (buffer) {
            if (delta.length() > 0 && eventBus != null) {
                publishPartial(eventBus, sessionId, delta.toString());
                delta.setLength(0);
            }
        }
        if (failure[0] != null) {
            throw failure[0];
        }
        return buffer.toString().strip();
    }

    private static void publishPartial(EventBus eventBus, String sessionId, String delta) {
        if (delta == null || delta.isEmpty()) {
            return;
        }
        eventBus.publish(StandardEvent.of(DCodeEventType.MESSAGE_PARTIAL, sessionId,
                "{\"content\":\"" + escapeJson(delta) + "\"}"));
    }

    private static String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private JsonNode parseAction(String text) {
        String trimmed = text.strip();
        // DeepSeek (and DeepSeek-routed) models emit tool calls in their native DSML
        // markup rather than the plain JSON the harness instructs. Normalize DSML to
        // the same {"tool": ..., "arguments": {...}} shape before JSON parsing.
        if (trimmed.contains("<｜DSML｜")) {
            JsonNode dsml = parseDsml(trimmed);
            if (dsml != null) {
                return dsml;
            }
        }
        String json = trimmed;
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            json = trimmed.substring(start, end + 1);
        }
        try {
            JsonNode node = mapper.readTree(json);
            if (node == null || node.isMissingNode() || node.isNull()
                    || !node.isObject()) {
                // Not a structured tool/final action: treat as a plain final answer.
                return null;
            }
            return node;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Parses DeepSeek DSML function-call markup
     * ({@code <｜DSML｜ invoke name="tool">…<parameter name="arguments">JSON</parameter>})
     * into the harness action shape. Returns {@code null} when the text is not a
     * DSML tool invocation (e.g. a DSML thinking block or final answer).
     */
    private JsonNode parseDsml(String text) {
        String invokeMarker = "<｜DSML｜ invoke name=\"";
        int invokeStart = text.indexOf(invokeMarker);
        if (invokeStart < 0) {
            return null;
        }
        int nameStart = invokeStart + invokeMarker.length();
        int nameEnd = text.indexOf('"', nameStart);
        if (nameEnd < 0) {
            return null;
        }
        String toolName = text.substring(nameStart, nameEnd).trim();
        if (toolName.isBlank()) {
            return null;
        }
        JsonNode arguments = parseDsmlArguments(text);
        com.fasterxml.jackson.databind.node.ObjectNode node = mapper.createObjectNode();
        node.put("tool", toolName);
        node.set("arguments", arguments);
        return node;
    }

    /** Extracts the DSML {@code arguments} parameter (a JSON object) or individual params. */
    private JsonNode parseDsmlArguments(String text) {
        String marker = "<｜DSML｜ parameter name=\"arguments\"";
        int paramStart = text.indexOf(marker);
        if (paramStart >= 0) {
            int valueStart = text.indexOf('>', paramStart);
            if (valueStart >= 0) {
                int valueEnd = text.indexOf("</｜DSML｜ parameter>", valueStart);
                if (valueEnd < 0) {
                    valueEnd = text.length();
                }
                String argJson = text.substring(valueStart + 1, valueEnd).trim();
                try {
                    JsonNode node = mapper.readTree(argJson);
                    if (node != null && node.isObject()) {
                        return node;
                    }
                } catch (Exception ignored) {
                    // fall through to individual parameters
                }
            }
        }
        // Individual <parameter name="x" string="...">value</parameter> entries.
        com.fasterxml.jackson.databind.node.ObjectNode params = mapper.createObjectNode();
        int from = 0;
        String paramMarker = "<｜DSML｜ parameter name=\"";
        while ((from = text.indexOf(paramMarker, from)) >= 0) {
            int nameStart = from + paramMarker.length();
            int nameEnd = text.indexOf('"', nameStart);
            if (nameEnd < 0) {
                break;
            }
            String name = text.substring(nameStart, nameEnd);
            int valueStart = text.indexOf('>', nameEnd);
            if (valueStart < 0) {
                break;
            }
            int valueEnd = text.indexOf("</｜DSML｜ parameter>", valueStart);
            if (valueEnd < 0) {
                break;
            }
            String value = text.substring(valueStart + 1, valueEnd).trim();
            params.put(name, value);
            from = valueEnd + 1;
        }
        return params;
    }

    private Map<String, Object> toArguments(JsonNode node) {
        Map<String, Object> result = new LinkedHashMap<>();
        if (node == null || !node.isObject()) {
            return result;
        }
        node.fields().forEachRemaining(entry -> result.put(entry.getKey(),
                toValue(entry.getValue())));
        return result;
    }

    private Object toValue(JsonNode node) {
        if (node.isTextual()) {
            return node.asText();
        }
        if (node.isInt()) {
            return node.asInt();
        }
        if (node.isLong()) {
            return node.asLong();
        }
        if (node.isDouble() || node.isFloat()) {
            return node.asDouble();
        }
        if (node.isBoolean()) {
            return node.asBoolean();
        }
        return node.toString();
    }

    private String extractOutput(String resultJson) {
        try {
            JsonNode node = mapper.readTree(resultJson);
            if (node.hasNonNull("output")) {
                return node.get("output").asText("");
            }
            return resultJson;
        } catch (Exception e) {
            return resultJson;
        }
    }
}
