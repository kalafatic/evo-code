package eu.kalafatic.dcode.harness.tools;

import eu.kalafatic.dcode.harness.tool.Tool;
import eu.kalafatic.dcode.harness.tool.ToolContext;
import eu.kalafatic.dcode.harness.tool.ToolDefinition;
import eu.kalafatic.dcode.harness.tool.ToolResult;
import eu.kalafatic.dcode.harness.tool.ToolScope;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/** Text search inside the workspace (bounded result count). */
public final class SearchTool implements Tool {

    private static final int MAX_RESULTS = 100;

    @Override
    public ToolDefinition definition() {
        return new ToolDefinition("search_code",
                "Search file contents in the workspace with a regular expression (bounded).",
                "{\"type\":\"object\",\"properties\":{\"pattern\":{\"type\":\"string\","
                        + "\"description\":\"regular expression\"},"
                        + "\"path\":{\"type\":\"string\",\"description\":\"relative directory, default root\"}},"
                        + "\"required\":[\"pattern\"]}",
                true, ToolScope.WORKSPACE);
    }

    @Override
    public ToolResult execute(ToolContext context, Map<String, Object> arguments) {
        String patternText = ToolArgs.string(arguments, "pattern", null);
        if (patternText == null) {
            return ToolResult.failure("Argument 'pattern' is required");
        }
        String relative = ToolArgs.string(arguments, "path", "");
        Path root;
        try {
            root = relative.isBlank()
                    ? context.workspaceRoot()
                    : eu.kalafatic.dcode.harness.tool.ToolEngine.resolveContained(
                            context.workspaceRoot(), relative);
        } catch (IllegalArgumentException e) {
            return ToolResult.failure(e.getMessage());
        }
        Pattern pattern;
        try {
            pattern = Pattern.compile(patternText);
        } catch (PatternSyntaxException e) {
            return ToolResult.failure("Invalid regular expression: " + e.getMessage());
        }
        List<String> results = new ArrayList<>();
        try (Stream<Path> stream = Files.walk(root)) {
            for (Path path : stream.filter(Files::isRegularFile)
                    .filter(p -> !isSkipped(p))
                    .toList()) {
                if (results.size() >= MAX_RESULTS) {
                    break;
                }
                String content;
                try {
                    content = Files.readString(path);
                } catch (IOException e) {
                    // Binary or unreadable file (e.g. a compiled .class throws
                    // MalformedInputException): skip it instead of failing the
                    // whole search. Workspaces routinely contain build output.
                    continue;
                }
                int lineNo = 0;
                for (String line : content.split("\n", -1)) {
                    lineNo++;
                    if (pattern.matcher(line).find()) {
                        results.add(root.relativize(path) + ":" + lineNo + ": " + line);
                        if (results.size() >= MAX_RESULTS) {
                            break;
                        }
                    }
                }
            }
        } catch (IOException e) {
            return ToolResult.failure("Search failed: " + e.getMessage());
        }
        if (results.isEmpty()) {
            return ToolResult.ok("No matches.");
        }
        return ToolResult.ok(String.join("\n", results));
    }

    private static boolean isSkipped(Path path) {
        String name = path.getFileName().toString();
        return name.equals("target") || name.equals("node_modules") || name.equals(".git")
                || name.equals("build") || name.equals("bin") || name.equals("out");
    }
}
