package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.client.DCodeApiClient;
import eu.kalafatic.dcode.core.model.Session;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.prefs.Preferences;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * AI Workspace: the primary interaction area of DCode RCP.
 *
 * <p>Two clearly separated modes:</p>
 * <ul>
 *   <li><b>Direct chat</b> — plain conversation with the selected runtime/model
 *       (streaming inference, no coding harness involved).</li>
 *   <li><b>AI coding</b> — task execution through the selected coding harness on the
 *       active session; execution state and messages come from the backend via SSE.</li>
 * </ul>
 *
 * <p>The view is a thin client: all inference, sessions and events live in the shared
 * backend; only the direct-chat conversation history and the system-prompt
 * preference are client-local.</p>
 */
public final class AiWorkspaceView extends ViewPart implements Ui.Refreshable,
        Ui.SessionListener, RuntimeConnection.ConnectionStateListener {

    private static final Pattern CODE_BLOCK = Pattern.compile(
            "```[a-zA-Z0-9+\\-]*[\\r\\n]+(.*?)```", Pattern.DOTALL);
    private static final String PREF_SYSTEM_PROMPT = "aiWorkspace.systemPrompt";
    private static final String PREF_HARNESS = "aiWorkspace.harness";

    private enum Mode {
        DIRECT, CODING
    }

    private StyledText transcript;
    private Text input;
    private Button directButton;
    private Button codingButton;
    private Combo harnessCombo;
    private Label sessionChip;
    private Label selectionChip;
    private Button send;
    private Button cancel;
    private Label status;

    private Mode mode = Mode.DIRECT;
    private final List<Map<String, String>> history = new ArrayList<>();
    private volatile DCodeApiClient.InferenceStreamSession activeStream;
    private final List<JsonNode> harnesses = new ArrayList<>();

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        Composite header = new Composite(root, SWT.NONE);
        header.setLayout(new GridLayout(9, false));
        header.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        directButton = new Button(header, SWT.RADIO);
        directButton.setText("Direct chat");
        directButton.setToolTipText("Plain conversation with the selected model — "
                + "no coding task, no harness");
        codingButton = new Button(header, SWT.RADIO);
        codingButton.setText("AI coding");
        codingButton.setToolTipText("Coding tasks through the selected harness on "
                + "the active session");
        directButton.setSelection(true);
        directButton.addListener(SWT.Selection, event -> {
            mode = Mode.DIRECT;
            renderMode();
        });
        codingButton.addListener(SWT.Selection, event -> {
            mode = Mode.CODING;
            renderMode();
        });

        new Label(header, SWT.NONE).setText("Harness:");
        harnessCombo = new Combo(header, SWT.READ_ONLY);
        GridData harnessData = new GridData();
        harnessData.widthHint = 150;
        harnessCombo.setLayoutData(harnessData);
        harnessCombo.setToolTipText("Coding harness for AI coding mode");

        sessionChip = new Label(header, SWT.NONE);
        GridData sessionData = new GridData();
        sessionData.widthHint = 220;
        sessionChip.setLayoutData(sessionData);
        sessionChip.setToolTipText("Active coding session");

        selectionChip = new Label(header, SWT.NONE);
        GridData selectionData = new GridData(GridData.FILL_HORIZONTAL);
        selectionChip.setLayoutData(selectionData);
        selectionChip.setToolTipText("Active runtime and model (selected in the top bar)");

        transcript = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY
                | SWT.WRAP);
        transcript.setLayoutData(new GridData(GridData.FILL_BOTH));

        status = new Label(root, SWT.NONE);
        status.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        Composite inputRow = new Composite(root, SWT.NONE);
        inputRow.setLayout(new GridLayout(6, false));
        inputRow.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        input = new Text(inputRow, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL | SWT.WRAP);
        GridData inputData = new GridData(GridData.FILL_HORIZONTAL);
        inputData.heightHint = 52;
        input.setLayoutData(inputData);
        input.addListener(SWT.Traverse, event -> {
            if (event.detail == SWT.TRAVERSE_RETURN && (event.stateMask & SWT.MOD1) != 0) {
                sendPrompt();
                event.doit = false;
            }
        });

        send = new Button(inputRow, SWT.PUSH);
        send.setText("Send");
        send.setToolTipText("Send the prompt (Ctrl+Enter)");
        send.addListener(SWT.Selection, event -> sendPrompt());
        cancel = new Button(inputRow, SWT.PUSH);
        cancel.setText("Cancel");
        cancel.setEnabled(false);
        cancel.setToolTipText("Cancel the running generation/task");
        cancel.addListener(SWT.Selection, event -> cancelGeneration());
        Button clear = new Button(inputRow, SWT.PUSH);
        clear.setText("Clear");
        clear.addListener(SWT.Selection, event -> clearConversation());
        Button retry = new Button(inputRow, SWT.PUSH);
        retry.setText("Retry");
        retry.setToolTipText("Re-generate the last response for the same prompt");
        retry.addListener(SWT.Selection, event -> retryLast());

        contributeActions();
        loadHarnesses();
        RuntimeConnection.instance().addStateListener(this);
        Ui.addSessionListener(this);
        renderMode();
    }

    private void contributeActions() {
        var bars = getViewSite().getActionBars().getToolBarManager();
        bars.add(new Action("Copy response") {
            @Override
            public void run() {
                copyToClipboard(transcript.getSelectionText().isBlank()
                        ? lastAssistantText() : transcript.getSelectionText());
            }
        });
        bars.add(new Action("Copy code") {
            @Override
            public void run() {
                String text = transcript.getText();
                Matcher matcher = CODE_BLOCK.matcher(text);
                StringBuilder code = new StringBuilder();
                while (matcher.find()) {
                    code.append(matcher.group(1).strip()).append('\n');
                }
                if (code.length() > 0) {
                    copyToClipboard(code.toString());
                }
            }
        });
        bars.add(new Action("System prompt…") {
            @Override
            public void run() {
                openSystemPromptDialog();
            }
        });
    }

    private void renderMode() {
        Ui.async(() -> {
            boolean coding = mode == Mode.CODING;
            directButton.setSelection(!coding);
            codingButton.setSelection(coding);
            harnessCombo.setEnabled(coding);
            updateSelectionChip();
            if (coding) {
                Session session = RuntimeConnection.instance().activeSession();
                sessionChip.setText(session != null
                        ? session.getTitle() + " [" + session.getState() + "]"
                        : "no active session");
                sessionChip.setVisible(true);
                input.setToolTipText("Task instructions for the active session; "
                        + "execution runs on the backend harness");
            } else {
                sessionChip.setText("");
                sessionChip.setVisible(false);
                input.setToolTipText("Prompt to the selected model (direct inference, "
                        + "no coding task)");
            }
            layoutHeader();
        });
    }

    private void layoutHeader() {
        Composite header = directButton.getParent();
        header.layout(true, true);
    }

    private void loadHarnesses() {
        if (!RuntimeConnection.instance().isUsableForRequests()) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().harnesses(), list -> Ui.async(() -> {
            harnesses.clear();
            harnessCombo.removeAll();
            String saved = Preferences.userNodeForPackage(AiWorkspaceView.class)
                    .get(PREF_HARNESS, "dcode");
            int select = 0;
            for (int i = 0; i < list.size(); i++) {
                JsonNode harness = list.get(i);
                harnesses.add(harness);
                String label = harness.path("name").asText("");
                if (!harness.path("usable").asBoolean()) {
                    label += harness.path("available").asBoolean()
                            ? " (not integrated)" : " (not installed)";
                }
                harnessCombo.add(label);
                harnessCombo.setData(label, harness.path("id").asText(""));
                if (harness.path("id").asText("").equals(saved)) {
                    select = i;
                }
            }
            harnessCombo.select(select);
        }), "list harnesses");
    }

    private void updateSelectionChip() {
        Ui.async(() -> {
            if (!RuntimeConnection.instance().isUsableForRequests()) {
                selectionChip.setText("runtime/model: backend unavailable");
                return;
            }
            Ui.run(RuntimeConnection.instance().api().runtimes(), list -> Ui.async(() -> {
                JsonNode defaultRuntime = null;
                String defaultModel = "";
                for (JsonNode runtime : list) {
                    if (runtime.path("default").asBoolean()) {
                        defaultRuntime = runtime;
                        defaultModel = runtime.path("defaultModel").asText("");
                    }
                }
                if (defaultRuntime == null) {
                    selectionChip.setText("runtime/model: (none selected)");
                    return;
                }
                String runtimeName = defaultRuntime.path("name").asText(
                        defaultRuntime.path("id").asText(""));
                String type = defaultRuntime.path("typeDisplayName").asText(
                        defaultRuntime.path("type").asText(""));
                selectionChip.setText("● " + runtimeName + " (" + type + ")"
                        + (defaultModel.isBlank() ? "" : " · " + defaultModel));
            }), "list runtimes");
        });
    }

    private void sendPrompt() {
        String prompt = input.getText().trim();
        if (prompt.isEmpty()) {
            return;
        }
        input.setText("");
        if (mode == Mode.DIRECT) {
            sendDirect(prompt);
        } else {
            sendCodingTask(prompt);
        }
    }

    private void sendDirect(String prompt) {
        append("user", "\n▶ " + prompt);
        history.add(Map.of("role", "user", "content", prompt));
        String systemPrompt = Preferences.userNodeForPackage(AiWorkspaceView.class)
                .get(PREF_SYSTEM_PROMPT, "").strip();
        List<Map<String, String>> messages = new ArrayList<>();
        if (!systemPrompt.isEmpty()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.addAll(history);
        startDirectStream(messages);
    }

    /** Re-generates the last assistant response for the same conversation. */
    private void retryLast() {
        if (mode != Mode.DIRECT || history.isEmpty()) {
            return;
        }
        if (!history.get(history.size() - 1).getOrDefault("role", "").equals("assistant")) {
            append("system", "Nothing to retry yet.");
            return;
        }
        history.remove(history.size() - 1);
        String systemPrompt = Preferences.userNodeForPackage(AiWorkspaceView.class)
                .get(PREF_SYSTEM_PROMPT, "").strip();
        List<Map<String, String>> messages = new ArrayList<>();
        if (!systemPrompt.isEmpty()) {
            messages.add(Map.of("role", "system", "content", systemPrompt));
        }
        messages.addAll(history);
        append("system", "(retrying)");
        append("assistant", "\n◆ ");
        startDirectStream(messages);
    }

    /** Resolves the default runtime/model and starts the streamed generation. */
    private void startDirectStream(List<Map<String, String>> messages) {
        Ui.run(RuntimeConnection.instance().api().runtimes(), runtimes -> {
            String runtimeId = null;
            String modelId = "";
            for (JsonNode runtime : runtimes) {
                if (runtime.path("default").asBoolean()) {
                    runtimeId = runtime.path("id").asText("");
                    modelId = runtime.path("defaultModel").asText("");
                }
            }
            if (runtimeId == null) {
                append("system", "Select a runtime and model in the top bar first.");
                return;
            }
            append("assistant", "\n◆ ");
            setBusy(true);
            activeStream = RuntimeConnection.instance().api().streamInference(
                    runtimeId, modelId, messages, 0.0, 0, new StreamUiListener());
        }, "list runtimes");
    }

    /** Stream listener that appends tokens to the transcript on the UI thread. */
    private final class StreamUiListener implements DCodeApiClient.InferenceStreamListener {
        @Override
        public void onStart(String requestId, String runtimeId, String model) {
            Ui.log("inference", "Inference started (runtime=" + runtimeId
                    + ", model=" + model + ")");
        }

        @Override
        public void onToken(String token) {
            Ui.async(() -> {
                transcript.append(token);
                scrollToEnd();
            });
        }

        @Override
        public void onDone(JsonNode result) {
            Ui.async(() -> {
                history.add(Map.of("role", "assistant",
                        "content", result.path("content").asText("")));
                appendRaw("\n");
                setBusy(false);
                activeStream = null;
            });
        }

        @Override
        public void onError(String message) {
            Ui.async(() -> {
                appendRaw("\n");
                append("system", "Inference error: " + message);
                setBusy(false);
                activeStream = null;
            });
        }
    }

    private void sendCodingTask(String prompt) {
        Session session = RuntimeConnection.instance().activeSession();
        if (session == null) {
            append("system", "AI coding needs a session — create one in the Sessions "
                    + "panel, then try again.");
            return;
        }
        String harnessId = selectedHarnessId();
        if (!isUsableHarness(harnessId)) {
            append("system", "Harness '" + harnessId + "' is not usable in this build "
                    + "(the EVO-Code native harness is available).");
            return;
        }
        append("user", "\n▶ " + prompt);
        Ui.log("task", "Submitting task to session " + session.getId() + "…");
        Ui.run(RuntimeConnection.instance().api().startTask(session.getId(), prompt),
                json -> {
                    Ui.log("task", "Task submitted — execution runs on the backend");
                    refreshContent();
                }, "start task");
    }

    private void setBusy(boolean busy) {
        Ui.async(() -> {
            send.setEnabled(!busy);
            cancel.setEnabled(busy);
            status.setText(busy ? "Generating… (Cancel stops the stream)" : "");
        });
    }

    private void cancelGeneration() {
        DCodeApiClient.InferenceStreamSession stream = activeStream;
        if (stream != null) {
            String requestId = stream.requestId();
            stream.cancel();
            if (requestId != null && !requestId.isBlank()) {
                Ui.run(RuntimeConnection.instance().api().cancelInference(requestId),
                        json -> Ui.log("inference", "Generation cancelled"),
                        "cancel inference");
            }
            activeStream = null;
            setBusy(false);
            append("system", "(cancelled)");
        } else {
            Session session = RuntimeConnection.instance().activeSession();
            if (session != null) {
                Ui.run(RuntimeConnection.instance().api().cancelSession(session.getId()),
                        json -> Ui.log("task", "Cancellation requested for "
                                + session.getId()), "cancel session");
            }
        }
    }

    private String selectedHarnessId() {
        int index = harnessCombo.getSelectionIndex();
        if (index < 0) {
            return "dcode";
        }
        return (String) harnessCombo.getData(harnessCombo.getItem(index));
    }

    private boolean isUsableHarness(String id) {
        for (JsonNode harness : harnesses) {
            if (harness.path("id").asText("").equals(id)) {
                return harness.path("usable").asBoolean();
            }
        }
        return false;
    }

    private void openSystemPromptDialog() {
        Dialog dialog = new Dialog(transcript.getShell()) {
            private Text promptText;

            @Override
            protected void configureShell(org.eclipse.swt.widgets.Shell shell) {
                super.configureShell(shell);
                shell.setText("System prompt (direct chat)");
            }

            @Override
            protected org.eclipse.swt.widgets.Control createDialogArea(Composite parent) {
                Composite area = (Composite) super.createDialogArea(parent);
                area.setLayout(new GridLayout(1, false));
                promptText = new Text(area, SWT.MULTI | SWT.BORDER | SWT.V_SCROLL);
                GridData data = new GridData(GridData.FILL_BOTH);
                data.widthHint = 460;
                data.heightHint = 160;
                promptText.setLayoutData(data);
                promptText.setText(Preferences.userNodeForPackage(AiWorkspaceView.class)
                        .get(PREF_SYSTEM_PROMPT, ""));
                return area;
            }

            @Override
            protected void okPressed() {
                Preferences.userNodeForPackage(AiWorkspaceView.class)
                        .put(PREF_SYSTEM_PROMPT, promptText.getText());
                Ui.log("info", "System prompt saved (used by direct chat)");
                super.okPressed();
            }
        };
        dialog.open();
    }

    private String lastAssistantText() {
        String text = transcript.getText();
        int marker = text.lastIndexOf("\n◆ ");
        return marker >= 0 ? text.substring(marker + 3) : text;
    }

    private void copyToClipboard(String content) {
        if (content == null || content.isBlank()) {
            return;
        }
        Clipboard clipboard = new Clipboard(transcript.getDisplay());
        clipboard.setContents(new Object[]{content},
                new Transfer[]{TextTransfer.getInstance()});
        clipboard.dispose();
        Ui.log("info", "Copied to clipboard");
    }

    private void clearConversation() {
        history.clear();
        transcript.setText("");
        append("system", "Conversation cleared. Mode: "
                + (mode == Mode.DIRECT ? "direct chat" : "AI coding") + ".");
    }

    private void append(String role, String content) {
        Ui.async(() -> {
            String prefix = switch (role) {
                case "user" -> "\n▶ ";
                case "assistant" -> "\n◆ ";
                case "system" -> "\n· ";
                default -> "";
            };
            transcript.append(prefix + content + "\n");
            scrollToEnd();
        });
    }

    private void appendRaw(String text) {
        Ui.async(() -> {
            transcript.append(text);
            scrollToEnd();
        });
    }

    private void scrollToEnd() {
        transcript.setTopIndex(Math.max(0, transcript.getLineCount() - 1));
    }

    @Override
    public void refreshContent() {
        if (mode == Mode.CODING) {
            loadSessionMessages();
        }
        updateSelectionChip();
    }

    private void loadSessionMessages() {
        Session session = RuntimeConnection.instance().activeSession();
        if (session == null) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().messages(session.getId()), messages ->
                Ui.async(() -> {
                    transcript.setText("");
                    for (JsonNode message : messages) {
                        String role = message.path("role").asText("").toLowerCase();
                        switch (role) {
                            case "user" -> append("user",
                                    message.path("content").asText(""));
                            case "assistant" -> append("assistant",
                                    message.path("content").asText(""));
                            case "tool" -> append("system", "▸ "
                                    + message.path("content").asText(""));
                            default -> {
                            }
                        }
                    }
                    scrollToEnd();
                }), "load session messages");
    }

    @Override
    public void sessionChanged(Session session) {
        renderMode();
        refreshContent();
    }

    @Override
    public void stateChanged(eu.kalafatic.dcode.client.ConnectionState state,
                             eu.kalafatic.dcode.client.BackendIdentity identity,
                             String diagnostic) {
        updateSelectionChip();
        if (state == eu.kalafatic.dcode.client.ConnectionState.READY) {
            loadHarnesses();
            refreshContent();
        }
    }

    @Override
    public void setFocus() {
        input.setFocus();
    }

    @Override
    public void dispose() {
        DCodeApiClient.InferenceStreamSession stream = activeStream;
        if (stream != null) {
            stream.cancel();
        }
        Ui.removeSessionListener(this);
        RuntimeConnection.instance().removeStateListener(this);
        super.dispose();
    }
}
