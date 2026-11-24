package eu.kalafatic.dcode.ui;

import eu.kalafatic.dcode.client.BackendProbe;
import eu.kalafatic.dcode.client.DCodeApiException;
import eu.kalafatic.dcode.client.DCodeSseClient;
import eu.kalafatic.dcode.core.model.Session;
import org.eclipse.swt.widgets.Display;
import org.eclipse.ui.IViewReference;
import org.eclipse.ui.IWorkbenchPage;
import org.eclipse.ui.PlatformUI;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * Shared UI helpers: display marshalling, console sink, view refresh fan-out and the
 * workbench-level SSE wiring (events are processed off the UI thread and dispatched
 * to the display; duplicate ids are dropped by the client).
 *
 * <p>Failure reporting is structured and deduplicated: the first occurrence of a
 * failure logs the full human-readable diagnostic (with the technical detail retained
 * at DEBUG level); identical repeats within a short window are aggregated into a
 * single counter instead of spamming the console. Permanent contract errors (404/405)
 * are never auto-retried.</p>
 */
public final class Ui {

    /** Listener invoked with the newly active session (on the UI thread). */
    public interface SessionListener {
        void sessionChanged(Session session);
    }

    private static final List<SessionListener> SESSION_LISTENERS = new CopyOnWriteArrayList<>();

    /** Raw SSE event consumers (registered by the inference workspace view). */
    public interface SseEventListener {
        void onEvent(DCodeSseClient.SseEvent event);
    }

    private static final List<SseEventListener> SSE_EVENT_LISTENERS = new CopyOnWriteArrayList<>();

    /** Registers a raw SSE event consumer; invoked on the display thread. */
    public static void addSseEventListener(SseEventListener listener) {
        SSE_EVENT_LISTENERS.add(listener);
    }

    public static void removeSseEventListener(SseEventListener listener) {
        SSE_EVENT_LISTENERS.remove(listener);
    }

    /** Aggregated failures: key -> (lastTimestamp, count). */
    private static final Map<String, long[]> FAILURE_AGGREGATES = new ConcurrentHashMap<>();
    private static final long DEDUP_WINDOW_MILLIS = 30_000;

    private static final DCodeSseClient.Listener SSE_LISTENER = new DCodeSseClient.Listener() {
        @Override
        public void onEvent(DCodeSseClient.SseEvent event) {
            Display.getDefault().asyncExec(() -> {
                logEvent(event.type(), event.data());
                for (SseEventListener listener : SSE_EVENT_LISTENERS) {
                    try {
                        listener.onEvent(event);
                    } catch (RuntimeException listenerError) {
                        System.out.println("[EVO-Code:ui] SSE listener failed: "
                                + listenerError.getMessage());
                    }
                }
                refreshSessionViews();
            });
        }

        @Override
        public void onStatus(DCodeSseClient.Status status, String detail) {
            Display.getDefault().asyncExec(() -> {
                log("network", "Stream " + status.name().toLowerCase()
                        + (detail.isEmpty() ? "" : " (" + detail + ")"));
            });
        }

        @Override
        public void onReconnected() {
            Display.getDefault().asyncExec(() -> {
                log("info", "Stream reconnected; re-fetching authoritative state…");
                refreshSessionViews();
            });
        }
    };

    private Ui() {
    }

    public static void async(Runnable runnable) {
        Display display = Display.getCurrent();
        if (display != null) {
            runnable.run();
        } else {
            Display.getDefault().asyncExec(runnable);
        }
    }

    public static void log(String kind, String message) {
        async(() -> {
            IWorkbenchPage page = activePage();
            if (page != null) {
                IViewReference reference = page.findViewReference(
                        "eu.kalafatic.dcode.ui.views.console");
                if (reference != null && reference.getView(false) instanceof
                        eu.kalafatic.dcode.ui.views.ConsoleView console) {
                    console.append(
                            eu.kalafatic.dcode.ui.views.ConsoleView.normalize(kind),
                            message);
                    return;
                }
            }
            System.out.println("[EVO-Code:" + kind + "] " + message);
        });
    }

    /** Logs a plain informational message (legacy convenience overload). */
    public static void log(String message) {
        log("system", message);
    }

    public static void logEvent(String type, String data) {
        async(() -> {
            IWorkbenchPage page = activePage();
            if (page != null) {
                IViewReference reference = page.findViewReference(
                        "eu.kalafatic.dcode.ui.views.console");
                if (reference != null && reference.getView(false) instanceof
                        eu.kalafatic.dcode.ui.views.ConsoleView console) {
                    console.appendEvent(type, data);
                    return;
                }
            }
            System.out.println("[EVO-Code:" + type + "] " + data);
        });
    }

    /** Opens/activates the per-session SSE stream for the active session. */
    public static void connectSessionStream(Session session) {
        RuntimeConnection.instance().sse().connect(session.getId(), SSE_LISTENER);
    }

    /** Refreshes all DCode views (used after reconnect / connection changes). */
    public static void refreshAll() {
        async(() -> {
            IWorkbenchPage page = activePage();
            if (page == null) {
                return;
            }
            for (IViewReference reference : page.getViewReferences()) {
                if (reference.getView(false) instanceof Refreshable refreshable) {
                    refreshable.refreshContent();
                }
            }
        });
    }

    public static void refreshSessionViews() {
        async(() -> {
            IWorkbenchPage page = activePage();
            if (page == null) {
                return;
            }
            for (String id : new String[]{
                    "eu.kalafatic.dcode.ui.views.sessions",
                    "eu.kalafatic.dcode.ui.views.chat",
                    "eu.kalafatic.dcode.ui.views.changes",
                    "eu.kalafatic.dcode.ui.views.activity",
                    "eu.kalafatic.dcode.ui.views.aiworkspace",
                    "eu.kalafatic.dcode.ui.views.sessionnavigator"}) {
                IViewReference reference = page.findViewReference(id);
                if (reference != null && reference.getView(false) instanceof Refreshable refreshable) {
                    refreshable.refreshContent();
                }
            }
        });
    }

    public static void broadcastSessionChanged(Session session) {
        async(() -> {
            for (SessionListener listener : SESSION_LISTENERS) {
                listener.sessionChanged(session);
            }
        });
    }

    public static void addSessionListener(SessionListener listener) {
        SESSION_LISTENERS.add(listener);
    }

    public static void removeSessionListener(SessionListener listener) {
        SESSION_LISTENERS.remove(listener);
    }

    public static IWorkbenchPage activePage() {
        if (PlatformUI.getWorkbench() == null
                || PlatformUI.getWorkbench().getActiveWorkbenchWindow() == null) {
            return null;
        }
        return PlatformUI.getWorkbench().getActiveWorkbenchWindow().getActivePage();
    }

    /** Marker for views that re-load authoritative backend state on demand. */
    public interface Refreshable {
        void refreshContent();
    }

    /**
     * Runs an async API call with structured failure reporting. Permanent contract
     * errors are reported once and never auto-retried; transient failures are reported
     * as retryable.
     */
    public static <T> void run(CompletableFuture<T> future, Consumer<T> onSuccess,
                               String errorContext) {
        run(future, onSuccess, errorContext, null);
    }

    /**
     * Runs an async API call with structured failure reporting plus an optional
     * failure consumer (dialogs use it to update their own status instead of
     * leaving a "loading" indicator up forever).
     */
    public static <T> void run(CompletableFuture<T> future, Consumer<T> onSuccess,
                               String errorContext, Consumer<Throwable> onFailure) {
        future.whenComplete((result, throwable) -> async(() -> {
            if (throwable != null) {
                reportFailure(errorContext, throwable);
                if (onFailure != null) {
                    try {
                        onFailure.accept(throwable);
                    } catch (RuntimeException failureReportError) {
                        // a broken status update must not mask the original failure
                    }
                }
                return;
            }
            onSuccess.accept(result);
        }));
    }

    /**
     * Reports one API failure as a structured, human-readable diagnostic. Identical
     * failures inside the dedup window are aggregated into a single counter; the
     * original technical error always remains in the console buffer.
     */
    public static void reportFailure(String context, Throwable throwable) {
        String endpoint = RuntimeConnection.instance().endpoint();
        BackendProbe.FailureClass failureClass = BackendProbe.classifyFailure(throwable);
        String key = context + "|" + throwable.getClass().getSimpleName() + "|"
                + (throwable instanceof DCodeApiException apiError
                ? apiError.status() : "n/a") + "|"
                + String.valueOf(throwable.getMessage()).hashCode();

        long now = System.currentTimeMillis();
        long[] aggregate = FAILURE_AGGREGATES.get(key);
        if (aggregate != null && now - aggregate[0] < DEDUP_WINDOW_MILLIS) {
            aggregate[1]++;
            log("warning", context + " failed (" + aggregate[1]
                    + " repeats within the dedup window; see first diagnostic)");
            return;
        }
        FAILURE_AGGREGATES.put(key, new long[]{now, 1});
        FAILURE_AGGREGATES.values().removeIf(value -> now - value[0] > DEDUP_WINDOW_MILLIS);

        List<String> details = new ArrayList<>();
        String headline;
        switch (failureClass) {
            case PERMANENT_CONTRACT -> {
                headline = context + " failed — the server does not provide this operation.";
                details.add("Server: " + endpoint);
                details.add("Operation: " + context);
                if (throwable instanceof DCodeApiException apiError) {
                    details.add("HTTP status: " + apiError.status());
                }
                details.add("Possible cause: client/server API contract mismatch or "
                        + "wrong endpoint (is this an EVO-Code server?).");
                details.add("Action: Retry · Connection Details (Window ▸ Connection) "
                        + "· Diagnose endpoint");
            }
            case AUTHENTICATION -> {
                headline = context + " failed — authentication required.";
                details.add("Server: " + endpoint);
                details.add("Operation: " + context);
                if (throwable instanceof DCodeApiException apiError) {
                    details.add("HTTP status: " + apiError.status());
                }
                details.add("Action: configure the bearer token in "
                        + "File ▸ Connect to Runtime…");
            }
            case VALIDATION -> {
                headline = context + " was rejected by the backend.";
                details.add("Server: " + endpoint);
                details.add("Operation: " + context);
                details.add("Action: correct the request parameters and retry");
            }
            case TRANSIENT -> {
                headline = context + " failed — the backend is temporarily unavailable.";
                details.add("Server: " + endpoint);
                details.add("Operation: " + context);
                details.add("Action: Retry · check the server status");
            }
            default -> {
                headline = context + " failed.";
                details.add("Server: " + endpoint);
                details.add("Operation: " + context);
                details.add("Action: Retry · Connection Details · Diagnostics");
            }
        }
        details.add("Detail: " + throwable.getClass().getSimpleName() + ": "
                + String.valueOf(throwable.getMessage()));
        if (throwable.getCause() != null) {
            details.add("Cause: " + throwable.getCause().getClass().getSimpleName()
                    + ": " + String.valueOf(throwable.getCause().getMessage()));
        }
        logDetailed("error", headline, details);
    }

    /** Logs a headline plus indented detail lines as separate console entries. */
    public static void logDetailed(String kind, String headline, List<String> details) {
        log(kind, headline);
        if (details != null) {
            for (String detail : details) {
                log("debug", "    " + detail);
            }
        }
    }

    /** Clears the dedup window (e.g. after a successful reconnect). */
    public static void clearFailureAggregates() {
        FAILURE_AGGREGATES.clear();
    }
}
