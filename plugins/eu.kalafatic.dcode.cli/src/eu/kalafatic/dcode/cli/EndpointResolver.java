package eu.kalafatic.dcode.cli;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Resolves candidate server endpoints for the thin CLI, in discovery order:
 * the persisted actual endpoint ({@code server-endpoint.json}), the configured
 * {@code httpPort} from {@code dcode-config.json}, then the family default port.
 *
 * <p>Pure filesystem logic — no HTTP, no OSGi — so it is unit-testable in
 * isolation. The caller probes each candidate for a live server.</p>
 */
public final class EndpointResolver {

    public static final String DEFAULT_HOST = "127.0.0.1";
    /** Family default HTTP port (DCode 58xxx block). */
    public static final int DEFAULT_PORT = 58080;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private EndpointResolver() {
    }

    /** Resolves the DCode data directory ({@code dcode.home} or {@code ~/.dcode}). */
    public static Path dataDir() {
        String home = System.getProperty("dcode.home");
        if (home != null && !home.isBlank()) {
            return Path.of(home);
        }
        return Path.of(System.getProperty("user.home", "."), ".dcode");
    }

    /** Candidate endpoints in discovery order (no reachability check). */
    public static List<String> candidates(Path dataDir) {
        List<String> result = new ArrayList<>();
        Path endpointFile = dataDir.resolve("server-endpoint.json");
        if (Files.isRegularFile(endpointFile)) {
            String endpoint = fromEndpointFile(endpointFile);
            if (endpoint != null) {
                result.add(endpoint);
            }
        }
        int port = configuredHttpPort(dataDir);
        if (port > 0) {
            result.add("http://" + DEFAULT_HOST + ":" + port);
        }
        result.add("http://" + DEFAULT_HOST + ":" + DEFAULT_PORT);
        return result;
    }

    /** Parses {@code server-endpoint.json} into an endpoint URL, or {@code null}. */
    public static String fromEndpointFile(Path file) {
        try {
            JsonNode node = MAPPER.readTree(Files.readString(file));
            String host = node.path("host").asText(DEFAULT_HOST);
            int port = node.path("port").asInt(-1);
            if (port > 0) {
                return "http://" + host + ":" + port;
            }
        } catch (Exception ignored) {
            // stale or malformed endpoint file: no endpoint
        }
        return null;
    }

    /** Reads the configured {@code httpPort} from {@code dcode-config.json} (0 when absent). */
    public static int configuredHttpPort(Path dataDir) {
        try {
            Path configFile = dataDir.resolve("dcode-config.json");
            if (!Files.isRegularFile(configFile)) {
                return 0;
            }
            String content = Files.readString(configFile);
            java.util.regex.Matcher matcher = java.util.regex.Pattern
                    .compile("\"httpPort\"\\s*:\\s*(\\d+)").matcher(content);
            return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
        } catch (Exception e) {
            return 0;
        }
    }
}
