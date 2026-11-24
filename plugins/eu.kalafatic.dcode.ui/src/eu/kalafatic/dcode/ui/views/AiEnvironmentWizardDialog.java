package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.FormFields;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import eu.kalafatic.dcode.ui.UiPreferences;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.List;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.util.ArrayList;

/**
 * AI Environment Wizard — guided first-run (and re-runnable) configuration:
 *
 * <ol>
 *   <li>Choose local or cloud inference.</li>
 *   <li>Discover inference engines/providers (local scan or manual entry).</li>
 *   <li>Configure the endpoint.</li>
 *   <li>Configure authentication (stored in the backend vault).</li>
 *   <li>Test connectivity.</li>
 *   <li>Discover models.</li>
 *   <li>Select the default model.</li>
 *   <li>Select the default agent.</li>
 *   <li>Review the execution profile (harness × provider × model × agent).</li>
 *   <li>Save and activate.</li>
 * </ol>
 *
 * Everything runs against the shared backend services; the wizard is a thin client.
 */
public final class AiEnvironmentWizardDialog extends Dialog {

    private static final String[][] TYPES = {
            {"enr", "ENR (EVO Native Runtime)"},
            {"ollama", "Ollama"},
            {"openai", "OpenAI-compatible API"}};

    private static final String[] DEFAULT_ENDPOINTS = {
            "http://127.0.0.1:28780",       // enr
            "http://127.0.0.1:11434",       // ollama
            "https://api.example.com/v1"};  // openai

    private Combo locationCombo;
    private Combo typeCombo;
    private Text endpointText;
    private Text tokenText;
    private Button testButton;
    private Label testResult;
    private Button discoverButton;
    private List modelList;
    private Combo modelCombo;
    private Combo agentCombo;
    private Label summary;
    private final java.util.List<String> modelIds = new ArrayList<>();

    public AiEnvironmentWizardDialog(Shell parent) {
        super(parent);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("AI Environment Wizard");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite root = (Composite) super.createDialogArea(parent);
        root.setLayout(new GridLayout(3, false));
        GridData rootData = new GridData(GridData.FILL_BOTH);
        rootData.widthHint = 560;
        rootData.heightHint = 520;
        root.setLayoutData(rootData);

        // Step 1: local or cloud
        new Label(root, SWT.NONE).setText("1. Inference location");
        locationCombo = new Combo(root, SWT.READ_ONLY);
        locationCombo.setItems("Local machine (scan for runtimes)",
                "Cloud / remote endpoint (manual entry)");
        locationCombo.select(0);
        GridData locationData = new GridData(GridData.FILL_HORIZONTAL);
        locationData.horizontalSpan = 2;
        locationCombo.setLayoutData(locationData);
        locationCombo.addModifyListener(event -> applyLocationMode());

        // Step 2: engine type
        new Label(root, SWT.NONE).setText("2. Provider type");
        typeCombo = new Combo(root, SWT.READ_ONLY);
        for (String[] type : TYPES) {
            typeCombo.add(type[1]);
        }
        typeCombo.select(1);
        GridData typeData = new GridData(GridData.FILL_HORIZONTAL);
        typeData.horizontalSpan = 2;
        typeCombo.setLayoutData(typeData);
        typeCombo.addModifyListener(event -> {
            if (endpointText != null) {
                endpointText.setText(rememberedEndpoint(selectedType()));
            }
        });

        // Step 3: endpoint
        FormFields.required(root, "3. Endpoint");
        endpointText = new Text(root, SWT.BORDER);
        GridData endpointData = new GridData(GridData.FILL_HORIZONTAL);
        endpointData.horizontalSpan = 2;
        endpointText.setLayoutData(endpointData);
        endpointText.setText(rememberedEndpoint("ollama"));
        endpointText.setToolTipText("Base URL of the inference service");

        // Step 4: authentication
        new Label(root, SWT.NONE).setText("4. Token / API key (optional)");
        tokenText = new Text(root, SWT.BORDER | SWT.PASSWORD);
        GridData tokenData = new GridData(GridData.FILL_HORIZONTAL);
        tokenData.horizontalSpan = 2;
        tokenText.setLayoutData(tokenData);
        tokenText.setToolTipText("Stored in the backend credential vault, never on "
                + "this client");

        // Step 5: test connectivity
        testButton = new Button(root, SWT.PUSH);
        testButton.setText("5. Test connection");
        testButton.addListener(SWT.Selection, event -> testConnection());
        testResult = new Label(root, SWT.WRAP);
        GridData testResultData = new GridData(GridData.FILL_HORIZONTAL);
        testResultData.horizontalSpan = 2;
        testResult.setLayoutData(testResultData);
        testResult.setText("Run the test to validate the endpoint.");

        // Step 6: discover models
        discoverButton = new Button(root, SWT.PUSH);
        discoverButton.setText("6. Discover models");
        discoverButton.addListener(SWT.Selection, event -> discoverModels());
        modelList = new List(root, SWT.BORDER | SWT.V_SCROLL);
        GridData listData = new GridData(GridData.FILL_HORIZONTAL);
        listData.horizontalSpan = 2;
        listData.heightHint = 90;
        modelList.setLayoutData(listData);

        // Step 7: default model
        new Label(root, SWT.NONE).setText("7. Default model");
        modelCombo = new Combo(root, SWT.READ_ONLY);
        GridData modelData = new GridData(GridData.FILL_HORIZONTAL);
        modelData.horizontalSpan = 2;
        modelCombo.setLayoutData(modelData);
        modelCombo.setToolTipText("Model used by direct inference and new sessions");

        // Step 8: default agent
        new Label(root, SWT.NONE).setText("8. Default agent");
        agentCombo = new Combo(root, SWT.READ_ONLY);
        GridData agentData = new GridData(GridData.FILL_HORIZONTAL);
        agentData.horizontalSpan = 2;
        agentCombo.setLayoutData(agentData);
        loadAgents();

        // Step 9/10: summary
        new Label(root, SWT.NONE).setText("9. Profile");
        summary = new Label(root, SWT.WRAP);
        GridData summaryData = new GridData(GridData.FILL_HORIZONTAL);
        summaryData.horizontalSpan = 2;
        summary.setLayoutData(summaryData);
        updateSummary();

        applyLocationMode();
        return root;
    }

    private void applyLocationMode() {
        boolean local = locationCombo.getSelectionIndex() == 0;
        typeCombo.setEnabled(!local);
        endpointText.setEnabled(!local);
        if (local) {
            Ui.run(RuntimeConnection.instance().api().scanRuntimes(null), findings ->
                    Ui.async(() -> {
                        java.util.List<String[]> found = new ArrayList<>();
                        for (JsonNode finding : findings) {
                            if (finding.path("registered").asBoolean()) {
                                continue;
                            }
                            String type = finding.path("type").asText("");
                            if (type.isBlank()) {
                                continue;
                            }
                            found.add(new String[]{finding.path("kind").asText(""),
                                    finding.path("name").asText(""),
                                    finding.path("endpoint").asText("")});
                        }
                        if (found.isEmpty()) {
                            testResult.setText("No unregistered local runtimes found "
                                    + "(all detected services are already configured "
                                    + "or unrecognized).");
                            return;
                        }
                        StringBuilder text = new StringBuilder(
                                "Detected local runtimes:\n");
                        for (String[] entry : found) {
                            text.append("  · ").append(entry[1]).append(" (")
                                    .append(entry[0]).append(") — ")
                                    .append(entry[2]).append('\n');
                        }
                        text.append("Select one and press 5. Test connection to "
                                + "proceed.");
                        testResult.setText(text.toString());
                        // preselect the first detected type/endpoint
                        selectDetected(found.get(0));
                    }), "scan local runtimes");
        }
        updateSummary();
    }

    private void selectDetected(String[] finding) {
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i][0].equals(finding[0])) {
                typeCombo.select(i);
            }
        }
        endpointText.setText(finding[2]);
    }

    private String selectedType() {
        int index = typeCombo.getSelectionIndex();
        return TYPES[index >= 0 ? index : 0][0];
    }

    /** Last-used endpoint for a provider type, falling back to the type default. */
    private static String rememberedEndpoint(String type) {
        String persisted = UiPreferences.get("wizard.endpoint." + type);
        if (persisted != null) {
            return persisted;
        }
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i][0].equals(type)) {
                return DEFAULT_ENDPOINTS[i];
            }
        }
        return "";
    }

    private void testConnection() {
        String endpoint = endpointText.getText().trim();
        if (endpoint.isBlank()) {
            testResult.setText("Enter an endpoint first.");
            return;
        }
        testResult.setText("Testing " + endpoint + "…");
        Ui.run(RuntimeConnection.instance().api().preflightRuntime(selectedType(),
                        endpoint, tokenText.getText().trim().isEmpty() ? null
                                : tokenText.getText().trim(), null),
                result -> Ui.async(() -> {
                    testResult.setText(result.path("success").asBoolean()
                            ? "OK — " + result.path("message").asText()
                            : "FAILED — " + result.path("message").asText());
                    modelIds.clear();
                    modelList.removeAll();
                    modelCombo.removeAll();
                    for (JsonNode model : result.path("models")) {
                        modelIds.add(model.path("id").asText(""));
                        modelList.add(model.path("id").asText(""));
                        modelCombo.add(model.path("id").asText(""));
                    }
                    if (modelCombo.getItemCount() > 0) {
                        modelCombo.select(0);
                    }
                    updateSummary();
                }), "preflight runtime");
    }

    private void discoverModels() {
        testConnection();
    }

    private void loadAgents() {
        Ui.run(RuntimeConnection.instance().api().agents(), list -> Ui.async(() -> {
            agentCombo.removeAll();
            int defaultIndex = 0;
            for (int i = 0; i < list.size(); i++) {
                agentCombo.add(list.get(i).path("name").asText(""));
                agentCombo.setData(list.get(i).path("name").asText(""),
                        list.get(i).path("id").asText(""));
                if (list.get(i).path("default").asBoolean()) {
                    defaultIndex = i;
                }
            }
            if (agentCombo.getItemCount() > 0) {
                agentCombo.select(defaultIndex);
            }
        }), "list agents");
    }

    private void updateSummary() {
        String model = modelCombo.getText();
        String agent = agentCombo.getText();
        String endpoint = endpointText.getText().trim();
        summary.setText("Provider: " + typeCombo.getText() + " @ "
                + (endpoint.isBlank() ? "(endpoint pending)" : endpoint)
                + " · Model: " + (model.isBlank() ? "(pending)" : model)
                + " · Agent: " + (agent.isBlank() ? "(default)" : agent)
                + "\nHarness: EVO-Code (native coding harness — integrated).");
        summary.getParent().layout(true, true);
    }

    @Override
    protected void okPressed() {
        // Save and activate: register the runtime, set the default model, set the
        // default agent. Everything persists on the backend.
        String endpoint = endpointText.getText().trim();
        if (endpoint.isBlank()) {
            testResult.setText("An endpoint is required — run the scan or enter one.");
            return;
        }
        final String[] registered = {null};
        Ui.run(RuntimeConnection.instance().api().createRuntime(typeCombo.getText(),
                        selectedType(), endpoint,
                        tokenText.getText().trim().isEmpty() ? null
                                : tokenText.getText().trim(), null),
                created -> Ui.async(() -> {
                    registered[0] = created.path("id").asText("");
                    UiPreferences.put("wizard.endpoint." + selectedType(), endpoint);
                    UiPreferences.put("wizard.location",
                            String.valueOf(locationCombo.getSelectionIndex()));
                    Ui.log("success", "Runtime registered: " + registered[0]
                            + " (" + endpoint + ")");
                    String model = modelCombo.getText();
                    if (!model.isBlank()) {
                        Ui.run(RuntimeConnection.instance().api()
                                        .setDefaultRuntime(registered[0], model),
                                json -> Ui.log("success", "Default inference: "
                                        + registered[0] + " / " + model),
                                "set default runtime/model");
                    }
                    String agentId = agentNameToId(agentCombo.getText());
                    if (agentId != null) {
                        Ui.run(RuntimeConnection.instance().api()
                                        .setDefaultAgent(agentId),
                                json -> Ui.log("success", "Default agent: " + agentId),
                                "set default agent");
                    }
                    Ui.refreshAll();
                }), "register runtime");
        super.okPressed();
    }

    private String agentNameToId(String name) {
        if (name == null || name.isBlank()) {
            return null;
        }
        Object data = agentCombo.getData(name);
        return data != null ? data.toString() : null;
    }
}
