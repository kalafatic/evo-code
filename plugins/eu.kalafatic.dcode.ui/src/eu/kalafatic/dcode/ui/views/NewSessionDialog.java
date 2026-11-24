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
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * New session dialog with per-session runtime/model selection. Leaving the runtime
 * empty keeps the platform default (resolved by the backend router). The last
 * successfully chosen runtime/model are restored, and a timestamped default title is
 * provided so creating a session is mostly a confirm action.
 */
public final class NewSessionDialog extends Dialog {

    private static final String PREF_RUNTIME = "session.runtime";
    private static final String PREF_MODEL = "session.model";
    private static final DateTimeFormatter TITLE_FORMAT =
            DateTimeFormatter.ofPattern("MMM d, HH:mm");

    private Text titleText;
    private Combo runtimeCombo;
    private Combo modelCombo;
    private final List<JsonNode> runtimes = new ArrayList<>();
    private final List<String> modelIds = new ArrayList<>();
    private final String lastRuntimeId;
    private final String lastModelId;

    public NewSessionDialog(Shell parent) {
        super(parent);
        this.lastRuntimeId = UiPreferences.get(PREF_RUNTIME);
        this.lastModelId = UiPreferences.get(PREF_MODEL);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("New session");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite root = (Composite) super.createDialogArea(parent);
        root.setLayout(new GridLayout(2, false));

        FormFields.required(root, "Title");
        titleText = new Text(root, SWT.BORDER);
        titleText.setText("Session " + LocalDateTime.now().format(TITLE_FORMAT));
        titleText.setToolTipText("A short name shown in the session lists.");
        titleText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        FormFields.optional(root, "Runtime");
        runtimeCombo = new Combo(root, SWT.READ_ONLY);
        runtimeCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        runtimeCombo.setToolTipText("Inference runtime for this session; "
                + "(platform default) uses the configured default runtime.");
        runtimeCombo.add("(platform default)");
        runtimeCombo.addModifyListener(event -> loadModels());

        FormFields.optional(root, "Model");
        modelCombo = new Combo(root, SWT.READ_ONLY);
        modelCombo.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        modelCombo.setToolTipText("Model for this session; (runtime default) uses the "
                + "runtime's default model.");
        modelCombo.add("(runtime default)");
        modelCombo.select(0);

        loadRuntimes();
        return root;
    }

    private void loadRuntimes() {
        Ui.run(RuntimeConnection.instance().api().runtimes(), list -> Ui.async(() -> {
            runtimes.clear();
            runtimeCombo.removeAll();
            runtimeCombo.add("(platform default)");
            runtimeCombo.setData("(platform default)", null);
            for (JsonNode runtime : list) {
                if (!runtime.path("enabled").asBoolean()) {
                    continue;
                }
                String label = runtime.path("name").asText(runtime.path("id").asText(""));
                runtimeCombo.add(label);
                runtimeCombo.setData(label, runtime.path("id").asText(""));
                runtimes.add(runtime);
            }
            if (!selectRuntimeById(lastRuntimeId)) {
                runtimeCombo.select(0);
            }
        }), "list runtimes");
    }

    /** Selects the runtime with the given id; loads its models when matched. */
    private boolean selectRuntimeById(String runtimeId) {
        if (runtimeId == null || runtimeId.isBlank()) {
            return false;
        }
        for (int i = 0; i < runtimeCombo.getItemCount(); i++) {
            Object id = runtimeCombo.getData(runtimeCombo.getItem(i));
            if (runtimeId.equals(id)) {
                runtimeCombo.select(i);
                loadModels();
                return true;
            }
        }
        return false;
    }

    private void loadModels() {
        String runtimeId = selectedRuntimeId();
        modelCombo.removeAll();
        modelCombo.add("(runtime default)");
        modelCombo.select(0);
        modelIds.clear();
        if (runtimeId == null) {
            return;
        }
        Ui.run(RuntimeConnection.instance().api().runtimeModels(runtimeId), models ->
                Ui.async(() -> {
                    for (JsonNode model : models) {
                        modelIds.add(model.path("id").asText(""));
                        modelCombo.add(model.path("id").asText(""));
                    }
                    if (modelIds.isEmpty()) {
                        modelCombo.add("(no models published)");
                    }
                    selectModelById(lastModelId);
                }), "list models of " + runtimeId);
    }

    /** Selects the model with the given id, or falls back to the runtime default. */
    private void selectModelById(String modelId) {
        if (modelId != null) {
            int index = modelIds.indexOf(modelId);
            if (index >= 0) {
                modelCombo.select(index + 1);
                return;
            }
        }
        modelCombo.select(0);
    }

    private String selectedRuntimeId() {
        int index = runtimeCombo.getSelectionIndex();
        if (index <= 0) {
            return null;
        }
        return (String) runtimeCombo.getData(runtimeCombo.getItem(index));
    }

    public String title() {
        return titleText.getText().trim();
    }

    /** Returns the selected runtime id or null for the platform default. */
    public String runtimeId() {
        int index = runtimeCombo.getSelectionIndex();
        if (index <= 0) {
            return null;
        }
        return (String) runtimeCombo.getData(runtimeCombo.getItem(index));
    }

    /** Returns the selected model id or null for the runtime default. */
    public String modelId() {
        int index = modelCombo.getSelectionIndex();
        if (index <= 0 || index - 1 >= modelIds.size()) {
            return null;
        }
        return modelIds.get(index - 1);
    }

    @Override
    protected void okPressed() {
        if (titleText.getText().trim().isBlank()) {
            Ui.log("error", "Title is required — enter a name for the session.");
            return;
        }
        // Persist the chosen runtime/model only after the form validated.
        UiPreferences.put(PREF_RUNTIME, runtimeId());
        UiPreferences.put(PREF_MODEL, modelId());
        super.okPressed();
    }
}
