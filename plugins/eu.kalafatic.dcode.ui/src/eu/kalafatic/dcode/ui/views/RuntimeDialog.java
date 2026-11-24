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
 * Guided runtime configuration dialog:
 *
 * <ol>
 *   <li>Select runtime type.</li>
 *   <li>Enter display name.</li>
 *   <li>Enter endpoint/base URL.</li>
 *   <li>Configure authentication where required (stored in the backend vault).</li>
 *   <li>Test connection.</li>
 *   <li>Discover available models.</li>
 *   <li>Save registration.</li>
 * </ol>
 *
 * Credentials typed here are sent once to the backend and stored in its encrypted
 * credential vault; they are never persisted on the client.
 */
public final class RuntimeDialog extends Dialog {

    private static final String[][] TYPES = {
            {"enr", "ENR (EVO Native Runtime)", "http://127.0.0.1:28780"},
            {"ollama", "Ollama", "http://127.0.0.1:11434"},
            {"openai", "OpenAI-compatible API", "https://api.example.com/v1"}};

    private final JsonNode existing;

    private Combo typeCombo;
    private Text nameText;
    private Text endpointText;
    private Text authTokenText;
    private Text apiKeyText;
    private Button testButton;
    private Label testResult;
    private Button discoverButton;
    private List modelsList;

    public RuntimeDialog(Shell parent, JsonNode existing) {
        super(parent);
        this.existing = existing;
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText(existing == null ? "Add Runtime" : "Edit Runtime");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite root = (Composite) super.createDialogArea(parent);
        root.setLayout(new GridLayout(3, false));

        new Label(root, SWT.NONE).setText("Runtime type");
        typeCombo = new Combo(root, SWT.READ_ONLY);
        for (String[] type : TYPES) {
            typeCombo.add(type[1]);
        }
        typeCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        typeCombo.addModifyListener(event -> {
            if (existing == null && endpointText != null) {
                int index = typeCombo.getSelectionIndex();
                if (index < 0) {
                    index = 0;
                }
                endpointText.setText(rememberedEndpoint(TYPES[index][0]));
            }
        });
        new Label(root, SWT.NONE);

        FormFields.optional(root, "Display name");
        nameText = new Text(root, SWT.BORDER);
        nameText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        nameText.setToolTipText("Label shown in the runtime lists; defaults to the "
                + "type when left empty.");
        new Label(root, SWT.NONE);

        FormFields.required(root, "Endpoint / base URL");
        endpointText = new Text(root, SWT.BORDER);
        endpointText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        endpointText.setToolTipText("Base URL of the inference service.");
        new Label(root, SWT.NONE);

        new Label(root, SWT.NONE).setText("Bearer token (optional)");
        authTokenText = new Text(root, SWT.BORDER | SWT.PASSWORD);
        authTokenText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        new Label(root, SWT.NONE).setText("stored in backend vault");

        new Label(root, SWT.NONE).setText("API key (optional)");
        apiKeyText = new Text(root, SWT.BORDER | SWT.PASSWORD);
        apiKeyText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        new Label(root, SWT.NONE).setText("stored in backend vault");

        testButton = new Button(root, SWT.PUSH);
        testButton.setText("Test Connection");
        testResult = new Label(root, SWT.WRAP);
        testResult.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        discoverButton = new Button(root, SWT.PUSH);
        discoverButton.setText("Discover Models");

        testButton.addListener(SWT.Selection, event -> testConnection());
        discoverButton.addListener(SWT.Selection, event -> discoverModels());

        new Label(root, SWT.NONE).setText("Models");
        new Label(root, SWT.NONE);
        new Label(root, SWT.NONE);
        modelsList = new List(root, SWT.BORDER | SWT.V_SCROLL);
        GridData listData = new GridData(GridData.FILL_BOTH);
        listData.horizontalSpan = 3;
        listData.heightHint = 120;
        modelsList.setLayoutData(listData);

        loadExisting();
        return root;
    }

    private void loadExisting() {
        if (existing == null) {
            typeCombo.select(0);
            endpointText.setText(rememberedEndpoint(TYPES[0][0]));
            nameText.setText(orEmpty(rememberedName(TYPES[0][0])));
            return;
        }
        String wireType = existing.path("type").asText("");
        for (int i = 0; i < TYPES.length; i++) {
            if (TYPES[i][0].equals(wireType)) {
                typeCombo.select(i);
            }
        }
        nameText.setText(existing.path("name").asText(""));
        endpointText.setText(existing.path("endpoint").asText(""));
        discoverModels();
    }

    private String[] selection() {
        int index = typeCombo.getSelectionIndex();
        return index >= 0 ? TYPES[index] : TYPES[0];
    }

    /** Last-used endpoint for a runtime type, falling back to the type default. */
    private static String rememberedEndpoint(String type) {
        String persisted = UiPreferences.get("runtime.endpoint." + type);
        if (persisted != null) {
            return persisted;
        }
        for (String[] entry : TYPES) {
            if (entry[0].equals(type)) {
                return entry[2];
            }
        }
        return "";
    }

    private static String rememberedName(String type) {
        return UiPreferences.get("runtime.name." + type);
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }

    private void testConnection() {
        testResult.setText("Testing\u2026");
        String endpoint = endpointText.getText().trim();
        if (endpoint.isBlank()) {
            testResult.setText("Enter an endpoint first");
            return;
        }
        String[] type = selection();
        String authToken = authTokenText.getText().trim();
        String apiKey = apiKeyText.getText().trim();
        if (existing == null) {
            Ui.run(RuntimeConnection.instance().api().preflightRuntime(
                            type[0], endpoint,
                            authToken.isEmpty() ? null : authToken,
                            apiKey.isEmpty() ? null : apiKey),
                    result -> {
                        testResult.setText(result.path("success").asBoolean()
                                ? "OK \u2014 " + result.path("message").asText()
                                : "FAILED \u2014 " + result.path("message").asText());
                        showModels(result.path("models"));
                    }, "preflight runtime");
        } else {
            Ui.run(RuntimeConnection.instance().api()
                            .testRuntime(existing.path("id").asText("")),
                    result -> testResult.setText(result.path("success").asBoolean()
                            ? "OK \u2014 " + result.path("message").asText()
                            : "FAILED \u2014 " + result.path("message").asText()),
                    "test runtime");
        }
    }

    private void showModels(JsonNode models) {
        if (models == null || !models.isArray()) {
            return;
        }
        Ui.async(() -> {
            modelsList.removeAll();
            for (JsonNode model : models) {
                modelsList.add(model.path("id").asText(""));
            }
            if (modelsList.getItemCount() == 0) {
                modelsList.add("(no models published)");
            }
        });
    }

    private void discoverModels() {
        String endpoint = endpointText.getText().trim();
        if (endpoint.isBlank()) {
            testResult.setText("Enter an endpoint first");
            return;
        }
        if (existing == null) {
            testConnection();
            return;
        }
        Ui.run(RuntimeConnection.instance().api()
                        .runtimeModels(existing.path("id").asText("")),
                models -> showModels(models), "discover models");
    }

    @Override
    protected void okPressed() {
        String name = nameText.getText().trim();
        String endpoint = endpointText.getText().trim();
        if (endpoint.isBlank()) {
            testResult.setText("Endpoint is required — enter the base URL of the "
                    + "inference service (e.g. http://127.0.0.1:11434).");
            return;
        }
        String[] type = selection();
        String authToken = authTokenText.getText().trim();
        String apiKey = apiKeyText.getText().trim();
        if (existing == null) {
            Ui.run(RuntimeConnection.instance().api().createRuntime(
                            name, type[0], endpoint,
                            authToken.isEmpty() ? null : authToken,
                            apiKey.isEmpty() ? null : apiKey),
                    json -> {
                        UiPreferences.put("runtime.endpoint." + type[0], endpoint);
                        UiPreferences.put("runtime.name." + type[0], name);
                        Ui.log("Runtime registered: "
                                + json.path("id").asText(""));
                        Ui.refreshAll();
                    }, "create runtime");
        } else {
            Ui.run(RuntimeConnection.instance().api().updateRuntime(
                            existing.path("id").asText(""), name, endpoint,
                            authToken.isEmpty() ? null : authToken,
                            apiKey.isEmpty() ? null : apiKey),
                    json -> {
                        UiPreferences.put("runtime.endpoint." + type[0], endpoint);
                        UiPreferences.put("runtime.name." + type[0], name);
                        Ui.log("Runtime updated: "
                                + existing.path("id").asText(""));
                        Ui.refreshAll();
                    }, "update runtime");
        }
        super.okPressed();
    }
}
