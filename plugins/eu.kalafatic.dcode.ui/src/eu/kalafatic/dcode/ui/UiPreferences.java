package eu.kalafatic.dcode.ui;

import org.eclipse.core.runtime.preferences.IEclipsePreferences;
import org.eclipse.core.runtime.preferences.InstanceScope;

/**
 * Centralized UI form preferences: last-used values and per-project context.
 *
 * <p>Reuses the Eclipse preference store (InstanceScope) — the same mechanism the
 * workbench and the existing preference page already use — so no second persistence
 * architecture is introduced. Values are split into a global namespace and a
 * per-workspace namespace keyed by the EVO-Code workspace id.</p>
 *
 * <p>Priority resolution follows the UX standard order:
 * per-workspace value, then global value (see {@link #resolve}).</p>
 *
 * <p>Security: this store is for non-sensitive form state only (selected runtimes,
 * models, repository paths, tool arguments, …). Passwords, tokens and API keys must
 * never be written here; they go through the backend credential vault or Eclipse
 * secure storage (see {@link RuntimeConnection}).</p>
 */
public final class UiPreferences {

    private static final String NODE = "eu.kalafatic.dcode.forms";
    private static final String GLOBAL_PREFIX = "global.";
    private static final String PROJECT_PREFIX = "workspace.";

    private UiPreferences() {
    }

    private static IEclipsePreferences node() {
        return InstanceScope.INSTANCE.getNode(NODE);
    }

    /** Global last-used value (shared across all workspaces/projects). */
    public static String get(String key) {
        return node().get(GLOBAL_PREFIX + key, null);
    }

    /** Stores a global last-used value and flushes to disk. */
    public static void put(String key, String value) {
        IEclipsePreferences prefs = node();
        if (value == null || value.isBlank()) {
            prefs.remove(GLOBAL_PREFIX + key);
        } else {
            prefs.put(GLOBAL_PREFIX + key, value);
        }
        flush(prefs);
    }

    /** Per-workspace (project-specific) value. */
    public static String get(String workspaceId, String key) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return null;
        }
        return node().get(PROJECT_PREFIX + workspaceId + "." + key, null);
    }

    /** Stores a per-workspace (project-specific) value. */
    public static void put(String workspaceId, String key, String value) {
        if (workspaceId == null || workspaceId.isBlank()) {
            return;
        }
        IEclipsePreferences prefs = node();
        if (value == null || value.isBlank()) {
            prefs.remove(PROJECT_PREFIX + workspaceId + "." + key);
        } else {
            prefs.put(PROJECT_PREFIX + workspaceId + "." + key, value);
        }
        flush(prefs);
    }

    /**
     * Resolves the best value for a field: per-workspace first, then global.
     * Returns {@code null} when neither is set (callers fall back to their own
     * auto-detected or application default).
     */
    public static String resolve(String workspaceId, String key) {
        String project = get(workspaceId, key);
        return project != null ? project : get(key);
    }

    /** Clears all stored form preferences (restore application defaults). */
    public static void resetAll() {
        IEclipsePreferences prefs = node();
        try {
            prefs.clear();
            prefs.flush();
        } catch (Exception ignored) {
            // best effort: in-memory node is still cleared
        }
    }

    private static void flush(IEclipsePreferences prefs) {
        try {
            prefs.flush();
        } catch (Exception ignored) {
            // non-fatal: the in-memory value remains in effect for this session
        }
    }
}
