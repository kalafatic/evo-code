package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.client.BackendIdentity;
import eu.kalafatic.dcode.client.ConnectionState;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.List;

/**
 * AI Selection toolbar: the immediately accessible selection bar of the workbench —
 * four independent selectors (harness, provider/runtime, model, agent) plus status,
 * workspace focus and runtime-management actions.
 *
 * <p>Each dimension loads and changes independently: switching a harness never
 * resets the model, switching the provider never resets the harness, and so on.
 * Provider/model changes persist the platform default on the backend (used by new
 * sessions and direct inference). Changes affect the next execution or an
 * explicitly selected session — never an ongoing request.</p>
 */
public final class RuntimeSelectorView extends ViewPart implements Ui.Refreshable,
        RuntimeConnection.ConnectionStateListener {

    private Combo harnessCombo;
    private Combo runtimeCombo;
    private Combo modelCombo;
    private Combo agentCombo;
    private Label status;
    private Label connectionChip;
    private final List<JsonNode> runtimes = new ArrayList<>();
    private final List<JsonNode> harnesses = new ArrayList<>();
    private final List<JsonNode> agents = new ArrayList<>();
    private final List<String> modelIds = new ArrayList<>();
    private boolean loading;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(14, false);
        layout.marginHeight = 4;
        layout.marginWidth = 6;
        root.setLayout(layout);

        Label harnessLabel = new Label(root, SWT.NONE);
        harnessLabel.setText("Harness:");
        harnessCombo = new Combo(root, SWT.READ_ONLY);
        GridData harnessData = new GridData();
        harnessData.widthHint = 130;
        harnessCombo.setLayoutData(harnessData);
        harnessCombo.setToolTipText("Coding harness (independent from the provider)");
        harnessCombo.addModifyListener(event -> onHarnessSelected());

        Label runtimeLabel = new Label(root, SWT.NONE);
        runtimeLabel.setText("Provider:");
        runtimeCombo = new Combo(root, SWT.READ_ONLY);
        GridData runtimeData = new GridData();
        runtimeData.widthHint = 150;
        runtimeCombo.setLayoutData(runtimeData);
        runtimeCombo.setToolTipText("Inference runtime/provider used by new sessions "
                + "and direct inference (sets the platform default)");
        runtimeCombo.addModifyListener(event -> onRuntimeSelected());

        Label modelLabel = new Label(root, SWT.NONE);
        modelLabel.setText("Model:");
        modelCombo = new Combo(root, SWT.READ_ONLY);
        GridData modelData = new GridData();
        modelData.widthHint = 190;
        modelCombo.setLayoutData(modelData);
        modelCombo.setToolTipText("Model served by the selected provider");
        modelCombo.addModifyListener(event -> onModelSelected());

        Label agentLabel = new Label(root, SWT.NONE);
        agentLabel.setText("Agent:");
        agentCombo = new Combo(root, SWT.READ_ONLY);
        GridData agentData = new GridData();
        agentData.widthHint = 150;
        agentCombo.setLayoutData(agentData);
        agentCombo.setToolTipText("Agent profile bound to new coding sessions "
                + "(independent from harness/provider/model)");
        agentCombo.addModifyListener(event -> onAgentSelected());

        status = new Label(root, SWT.NONE);
        GridData statusData = new GridData();
        statusData.widthHint = 200;
        status.setLayoutData(statusData);
        status.setToolTipText("Provider health and model availability");

        Button chatButton = new Button(root, SWT.PUSH);
        chatButton.setText("AI Workspace");
        chatButton.setToolTipText("Focus the AI Workspace (direct chat / AI coding)");
        chatButton.addListener(SWT.Selection, event -> {
            try {
                getSite().getPage().showView(
                        "eu.kalafatic.dcode.ui.views.aiworkspace");
            } catch (Exception e) {
                Ui.log("error", "Cannot open AI Workspace: " + e.getMessage());
            }
        });

        Button manageButton = new Button(root, SWT.PUSH);
        manageButton.setText("Manage…");
        manageButton.setToolTipText("Runtime Manager: add/edit/remove/test runtimes");
        manageButton.addListener(SWT.Selection, event ->
                new RuntimeManagerDialog(parent.getShell()).open());

        Button scanButton = new Button(root, SWT.PUSH);
        scanButton.setText("Scan…");
        scanButton.setToolTipText("Scan local endpoints for Ollama, ENR and other "
                + "inference runtimes and register them");
        scanButton.addListener(SWT.Selection, event ->
                new ScanRuntimesDialog(parent.getShell()).open());

        Button wizardButton = new Button(root, SWT.PUSH);
        wizardButton.setText("Wizard…");
        wizardButton.setToolTipText("Re-run the AI environment configuration wizard");
        wizardButton.addListener(SWT.Selection, event ->
                new AiEnvironmentWizardDialog(parent.getShell()).open());

        connectionChip = new Label(root, SWT.NONE);
        connectionChip.setToolTipText("EVO-Code backend connection state");

        getViewSite().getActionBars().getToolBarManager().add(new Action("Refresh") {
            @Override
            public void run() {
                refreshContent();
            }
        });

        RuntimeConnection.instance().addStateListener(this);
        refreshContent();
    }

    @Override
    public void stateChanged(ConnectionState state, BackendIdentity identity,
                             String diagnostic) {
        renderConnectionChip();
        if (state == ConnectionState.READY) {
            refreshContent();
        }
    }

    private void renderConnectionChip() {
        Ui.async(() -> {
            if (connectionChip == null || connectionChip.isDisposed()) {
                return;
            }
            ConnectionState state = RuntimeConnection.instance().state();
            String text = "backend: " + state.name().toLowerCase();
            connectionChip.setText(text);
            connectionChip.setForeground(parent().getDisplay().getSystemColor(
                    switch (state) {
                        case READY -> SWT.COLOR_DARK_GREEN;
                        case ERROR -> SWT.COLOR_RED;
                        case CONNECTING, CONNECTED, INITIALIZING -> SWT.COLOR_DARK_YELLOW;
                        default -> SWT.COLOR_WIDGET_FOREGROUND;
                    }));
        });
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            if (!RuntimeConnection.instance().isUsableForRequests()) {
                runtimeCombo.removeAll();
                modelCombo.removeAll();
                harnessCombo.removeAll();
                agentCombo.removeAll();
                status.setText("backend unavailable");
                renderConnectionChip();
                return;
            }
            status.setText("Loading…");
            loadHarnesses();
            loadAgents();
            Ui.run(RuntimeConnection.instance().api().runtimes(), list -> Ui.async(() -> {
                runtimes.clear();
                runtimeCombo.removeAll();
                String current = runtimeCombo.getText();
                int defaultIndex = 0;
                for (int i = 0; i < list.size(); i++) {
                    JsonNode runtime = list.get(i);
                    runtimes.add(runtime);
                    String label = runtime.path("name").asText(
                            runtime.path("id").asText(""));
                    runtimeCombo.add(label);
                    runtimeCombo.setData(label, runtime.path("id").asText(""));
                    if (runtime.path("default").asBoolean()) {
                        defaultIndex = i;
                    }
                }
                if (runtimes.isEmpty()) {
                    modelCombo.removeAll();
                    status.setText("No providers — use Scan… or the Wizard…");
                    return;
                }
                int index = current.isBlank() ? defaultIndex
                        : Math.max(runtimeCombo.indexOf(current), 0);
                runtimeCombo.select(index);
                loadModels();
                renderConnectionChip();
            }), "list runtimes");
        });
    }

    /** Harness selection is independent: loading it never touches runtime/model. */
    private void loadHarnesses() {
        String current = harnessCombo.getText();
        Ui.run(RuntimeConnection.instance().api().harnesses(), list -> Ui.async(() -> {
            harnesses.clear();
            harnessCombo.removeAll();
            int usableIndex = 0;
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
                if (harness.path("usable").asBoolean()) {
                    usableIndex = i;
                }
            }
            int index = current.isBlank() ? usableIndex
                    : Math.max(harnessCombo.indexOf(current), 0);
            if (harnessCombo.getItemCount() > 0) {
                harnessCombo.select(index);
            }
        }), "list harnesses");
    }

    /** Agent selection is independent: loading it never touches harness/runtime/model. */
    private void loadAgents() {
        String current = agentCombo.getText();
        Ui.run(RuntimeConnection.instance().api().agents(), list -> Ui.async(() -> {
            agents.clear();
            agentCombo.removeAll();
            int defaultIndex = 0;
            for (int i = 0; i < list.size(); i++) {
                JsonNode agent = list.get(i);
                agents.add(agent);
                String label = agent.path("name").asText("");
                agentCombo.add(label);
                agentCombo.setData(label, agent.path("id").asText(""));
                if (agent.path("default").asBoolean()) {
                    defaultIndex = i;
                }
            }
            int index = current.isBlank() ? defaultIndex
                    : Math.max(agentCombo.indexOf(current), 0);
            if (agentCombo.getItemCount() > 0) {
                agentCombo.select(index);
            }
        }), "list agents");
    }

    private void onHarnessSelected() {
        if (loading) {
            return;
        }
        String id = selectedHarnessId();
        Ui.log("harness", "Selected harness: " + id
                + (isUsableHarness(id) ? "" : " (not usable for execution)"));
    }

    private boolean isUsableHarness(String id) {
        for (JsonNode harness : harnesses) {
            if (harness.path("id").asText("").equals(id)) {
                return harness.path("usable").asBoolean();
            }
        }
        return false;
    }

    private String selectedHarnessId() {
        int index = harnessCombo.getSelectionIndex();
        if (index < 0) {
            return null;
        }
        return (String) harnessCombo.getData(harnessCombo.getItem(index));
    }

    private void onAgentSelected() {
        if (loading) {
            return;
        }
        String id = selectedAgentId();
        if (id == null) {
            return;
        }
        // The selector is authoritative: persist the default agent on the backend.
        Ui.run(RuntimeConnection.instance().api().setDefaultAgent(id),
                json -> Ui.log("harness", "Default agent: " + id),
                "set default agent");
    }

    private String selectedAgentId() {
        int index = agentCombo.getSelectionIndex();
        if (index < 0) {
            return null;
        }
        return (String) agentCombo.getData(agentCombo.getItem(index));
    }

    private void onRuntimeSelected() {
        if (loading) {
            return;
        }
        loadModels();
    }

    private void loadModels() {
        String runtimeId = selectedRuntimeId();
        if (runtimeId == null) {
            modelCombo.removeAll();
            status.setText("");
            return;
        }
        loading = true;
        String previous = modelCombo.getText();
        modelCombo.removeAll();
        status.setText("Loading models…");
        Ui.run(RuntimeConnection.instance().api().runtimeModels(runtimeId), models ->
                Ui.async(() -> {
                    modelIds.clear();
                    for (JsonNode model : models) {
                        modelIds.add(model.path("id").asText(""));
                        modelCombo.add(model.path("id").asText(""));
                    }
                    if (modelIds.isEmpty()) {
                        modelCombo.add("(no models published)");
                        modelCombo.select(0);
                        updateStatus(models);
                    } else {
                        int index = previous.isBlank() ? 0
                                : Math.max(modelCombo.indexOf(previous), 0);
                        modelCombo.select(index);
                        updateStatus(models);
                    }
                    loading = false;
                }), "list models of " + runtimeId);
    }

    private void onModelSelected() {
        if (loading) {
            return;
        }
        String runtimeId = selectedRuntimeId();
        String modelId = selectedModelId();
        if (runtimeId == null || modelId == null) {
            return;
        }
        // The selector is authoritative: persist the choice as the platform default.
        Ui.run(RuntimeConnection.instance().api().setDefaultRuntime(runtimeId, modelId),
                json -> {
                    Ui.log("runtime", "Default inference: " + runtimeId + " / "
                            + modelId);
                    Ui.refreshAll();
                }, "set default runtime/model");
    }

    private void updateStatus(JsonNode models) {
        String runtimeId = selectedRuntimeId();
        if (runtimeId == null) {
            return;
        }
        JsonNode runtime = null;
        for (JsonNode candidate : runtimes) {
            if (runtimeId.equals(candidate.path("id").asText(""))) {
                runtime = candidate;
            }
        }
        if (runtime == null) {
            return;
        }
        JsonNode runtimeStatus = runtime.path("status");
        boolean reachable = runtimeStatus.path("reachable").asBoolean();
        boolean modelsPublished = models.isArray() && models.size() > 0;
        status.setText((reachable ? "● reachable" : "○ not tested")
                + (modelsPublished ? " · " + models.size() + " model(s)"
                : " · no models published"));
        status.setForeground(parent().getDisplay().getSystemColor(
                reachable ? SWT.COLOR_DARK_GREEN : SWT.COLOR_WIDGET_FOREGROUND));
    }

    private String selectedRuntimeId() {
        int index = runtimeCombo.getSelectionIndex();
        if (index < 0) {
            return null;
        }
        return (String) runtimeCombo.getData(runtimeCombo.getItem(index));
    }

    private String selectedModelId() {
        int index = modelCombo.getSelectionIndex();
        if (index < 0 || index >= modelIds.size()) {
            return null;
        }
        return modelIds.get(index);
    }

    private Composite parent() {
        return status.getParent();
    }

    @Override
    public void setFocus() {
        runtimeCombo.setFocus();
    }

    @Override
    public void dispose() {
        RuntimeConnection.instance().removeStateListener(this);
        super.dispose();
    }
}
