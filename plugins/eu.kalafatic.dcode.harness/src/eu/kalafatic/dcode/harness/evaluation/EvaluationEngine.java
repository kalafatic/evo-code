package eu.kalafatic.dcode.harness.evaluation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import eu.kalafatic.dcode.config.provider.ChatMessage;
import eu.kalafatic.dcode.config.provider.ChatRequest;
import eu.kalafatic.dcode.config.provider.ProviderException;
import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.config.routing.ModelRouter;
import eu.kalafatic.dcode.config.routing.ModelSelection;
import eu.kalafatic.dcode.core.model.AgentRole;

import java.util.List;

/**
 * Independent evaluation: an evidence-based verdict produced by a separate model
 * (the {@link AgentRole#EVALUATOR} role) rather than the implementing agent. The evaluator
 * reasons over actual evidence (the goal, the change sets, the implementer's summary) and
 * returns a structured verdict that drives acceptance, rework or rejection.
 *
 * <p>This is a single reasoning completion, deliberately distinct from the coding-agent
 * tool loop: the evaluator assesses evidence rather than performing work, so it has no
 * tool access and cannot modify the workspace.</p>
 */
public final class EvaluationEngine {

    /** Structured evaluation verdicts. */
    public enum Verdict {
        PASS,
        PASS_WITH_LIMITATIONS,
        NEEDS_REWORK,
        FAIL,
        BLOCKED,
        INCONCLUSIVE
    }

    /** One evaluation result with its provenance. */
    public record Evaluation(
            Verdict verdict,
            String findings,
            String confidence,
            String recommendedAction,
            String providerId,
            String modelId,
            String summary
    ) {
        public boolean accepted() {
            return verdict == Verdict.PASS || verdict == Verdict.PASS_WITH_LIMITATIONS;
        }

        public boolean needsRework() {
            return verdict == Verdict.NEEDS_REWORK;
        }
    }

    /** The evidence package the evaluator reasons over. */
    public record Evidence(
            String goal,
            String implementerSummary,
            String changeSummary
    ) {
    }

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ProviderRegistry providers;
    private final ModelRouter modelRouter;

    public EvaluationEngine(ProviderRegistry providers, ModelRouter modelRouter) {
        this.providers = providers;
        this.modelRouter = modelRouter;
    }

    /**
     * Produces an independent verdict for the given evidence. The evaluator model is the
     * {@link AgentRole#EVALUATOR} role override when configured, otherwise the given
     * fallback selection (the implementing agent's model).
     */
    public Evaluation evaluate(Evidence evidence, ModelSelection fallback) {
        ModelSelection selection = resolveModel(fallback);
        String prompt = buildPrompt(evidence);
        String content;
        try {
            content = providers.complete(selection.providerId(),
                    new ChatRequest(selection.modelId(),
                            List.of(ChatMessage.system(prompt)), 0.2, 2048))
                    .content().strip();
        } catch (ProviderException e) {
            // A failed evaluator must not corrupt the process: degrade to an
            // inconclusive verdict that keeps the task answerable rather than claiming
            // success or silently failing the implementation.
            return new Evaluation(Verdict.INCONCLUSIVE,
                    "Independent evaluation could not run: " + e.getMessage(),
                    "unknown", "Re-run evaluation after restoring the evaluator model",
                    selection.providerId(), selection.modelId(), "");
        }
        return parse(content, selection);
    }

    /** Resolves the evaluator model: EVALUATOR override, else the given fallback. */
    public ModelSelection resolveModel(ModelSelection fallback) {
        try {
            return modelRouter.resolve(AgentRole.EVALUATOR);
        } catch (ProviderException e) {
            return fallback;
        }
    }

    private String buildPrompt(Evidence evidence) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are an independent engineering evaluator. Assess whether the "
                + "implementation actually achieves the goal, using only the evidence "
                + "provided. You cannot modify the workspace.\n\n");
        prompt.append("## Goal\n").append(evidence.goal()).append("\n\n");
        prompt.append("## Implementer summary\n")
                .append(evidence.implementerSummary()).append("\n\n");
        prompt.append("## Actual changes\n").append(evidence.changeSummary()).append("\n\n");
        prompt.append("## Evaluation criteria\n");
        prompt.append("- Is the goal actually achieved, or only claimed?\n");
        prompt.append("- Are requirements met, and are any gaps explicit?\n");
        prompt.append("- Is the change correct, minimal and consistent with the codebase?\n");
        prompt.append("- Is verification evidence (build/test) present and plausible?\n\n");
        prompt.append("## Output contract\n");
        prompt.append("Respond with exactly one JSON object and nothing else:\n");
        prompt.append("{\"verdict\":\"PASS|PASS_WITH_LIMITATIONS|NEEDS_REWORK|FAIL|BLOCKED|"
                + "INCONCLUSIVE\",\"findings\":\"...\",\"confidence\":\"high|medium|low\","
                + "\"recommendedAction\":\"...\"}\n");
        prompt.append("- PASS: goal achieved with adequate evidence.\n");
        prompt.append("- PASS_WITH_LIMITATIONS: achieved, with explicit remaining limitations.\n");
        prompt.append("- NEEDS_REWORK: close but specific defects must be fixed.\n");
        prompt.append("- FAIL: the implementation does not achieve the goal.\n");
        prompt.append("- BLOCKED: a genuine blocker prevents completion.\n");
        prompt.append("- INCONCLUSIVE: insufficient evidence to decide.\n");
        return prompt.toString();
    }

    private Evaluation parse(String content, ModelSelection selection) {
        JsonNode node = extractJson(content);
        Verdict verdict = Verdict.INCONCLUSIVE;
        String findings = content;
        String confidence = "unknown";
        String recommendedAction = "";
        if (node != null && node.isObject()) {
            verdict = parseVerdict(node.path("verdict").asText(""));
            findings = node.path("findings").asText(content);
            confidence = node.path("confidence").asText("unknown");
            recommendedAction = node.path("recommendedAction").asText("");
        }
        return new Evaluation(verdict, findings, confidence, recommendedAction,
                selection.providerId(), selection.modelId(), content);
    }

    /** Extracts the first JSON object from the response (tolerates surrounding text). */
    private static JsonNode extractJson(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        String trimmed = content.strip();
        int start = trimmed.indexOf('{');
        int end = trimmed.lastIndexOf('}');
        if (start >= 0 && end > start) {
            trimmed = trimmed.substring(start, end + 1);
        }
        try {
            JsonNode node = MAPPER.readTree(trimmed);
            return node != null && node.isObject() ? node : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static Verdict parseVerdict(String raw) {
        if (raw == null || raw.isBlank()) {
            return Verdict.INCONCLUSIVE;
        }
        try {
            return Verdict.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return Verdict.INCONCLUSIVE;
        }
    }
}
