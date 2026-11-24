package eu.kalafatic.dcode.core.config;

import java.nio.file.Path;
import java.util.Objects;

/**
 * Central runtime configuration of a DCode instance. Shared by headless, RCP and web modes;
 * there is deliberately no per-client configuration duplication.
 *
 * @param dataDir           root data directory of this instance (database, artifacts, logs)
 * @param workspacesDir     where workspace metadata and repository worktrees live
 * @param artifactsDir      large session artifacts
 * @param logsDir           log files
 * @param httpPort          primary HTTP port (0 = allocate from the default range)
 * @param managementPort    management port (0 = allocate)
 * @param bindAddress       bind address (default 127.0.0.1)
 * @param nodeId            stable node identity
 * @param appVersion        application version string
 * @param defaultProvider   default provider type (e.g. "ollama", "openai")
 * @param providerEndpoint  configured endpoint for the default provider
 * @param harnessType       selected harness type (e.g. "dcode", "opencode", "aider")
 * @param agentRole         default agent role for executions
 * @param defaultModel      default model name or id
 * @param webServerEnabled  whether the web/API server may run at all
 * @param webServerAutoStart whether the web/API server starts automatically
 *                          at startup (headless and RCP modes alike)
 */
public record CoreConfiguration(
        Path dataDir,
        Path workspacesDir,
        Path artifactsDir,
        Path logsDir,
        int httpPort,
        int managementPort,
        String bindAddress,
        String nodeId,
        String appVersion,
        String defaultProvider,
        String providerEndpoint,
        String harnessType,
        String agentRole,
        String defaultModel,
        boolean webServerEnabled,
        boolean webServerAutoStart
) {
    public CoreConfiguration {
        Objects.requireNonNull(dataDir, "dataDir");
        Objects.requireNonNull(workspacesDir, "workspacesDir");
        Objects.requireNonNull(artifactsDir, "artifactsDir");
        Objects.requireNonNull(logsDir, "logsDir");
        bindAddress = bindAddress == null ? "127.0.0.1" : bindAddress;
        nodeId = nodeId == null ? "dcode-node-01" : nodeId;
    }

    /** Copy with a different primary HTTP port (used to persist allocated ports). */
    public CoreConfiguration withHttpPort(int port) {
        return new CoreConfiguration(dataDir, workspacesDir, artifactsDir, logsDir,
                port, managementPort, bindAddress, nodeId, appVersion, defaultProvider,
                providerEndpoint, harnessType, agentRole, defaultModel,
                webServerEnabled, webServerAutoStart);
    }

    /**
     * Default configuration with sensible defaults.
     */
    public static CoreConfiguration defaults(Path homeDir) {
        return new CoreConfiguration(
                homeDir,
                homeDir.resolve("workspaces"),
                homeDir.resolve("artifacts"),
                homeDir.resolve("logs"),
                0,
                0,
                "127.0.0.1",
                "dcode-node-01",
                "2.6.11",
                "ollama",           // defaultProvider
                "http://127.0.0.1:11434", // providerEndpoint
                "dcode",            // harnessType
                "MAIN",             // agentRole
                "",                  // defaultModel (runtime registry default is authoritative;
                                     // a hardcoded model name goes stale as soon as it is
                                     // uninstalled, so leave it empty)
                true,               // webServerEnabled
                true);              // webServerAutoStart
    }
}
