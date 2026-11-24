package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import eu.kalafatic.dcode.ui.FormFields;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import eu.kalafatic.dcode.ui.UiPreferences;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import java.util.ArrayList;
import java.util.List;

/**
 * Java Tools view: all registered tools (including the 13 WP5.6 JDT/PDE tools) are
 * listed from the backend and executed through the backend ToolEngine. Form fields are
 * generated from the tool schema; {@code java_rename} requires explicit confirmation.
 */
public final class JavaToolsView extends ViewPart implements Ui.Refreshable {

    private Composite grid;

    @Override
    public void createPartControl(Composite parent) {
        grid = new Composite(parent, SWT.NONE);
        grid.setLayout(new GridLayout(2, true));
        refreshContent();
    }

    @Override
    public void refreshContent() {
        Ui.async(() -> {
            for (org.eclipse.swt.widgets.Control child : grid.getChildren()) {
                child.dispose();
            }
            Ui.run(RuntimeConnection.instance().api().tools(), tools -> Ui.async(() -> {
                for (JsonNode tool : tools) {
                    Button button = new Button(grid, SWT.PUSH);
                    String name = tool.path("name").asText("?");
                    String description = tool.path("description").asText("");
                    boolean readOnly = tool.path("readOnly").asBoolean(true);
                    button.setText(name + (readOnly ? "" : "  ⚠ mutating"));
                    button.setToolTipText(description);
                    button.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
                    button.addListener(SWT.Selection,
                            event -> openToolDialog(tool));
                }
                grid.layout();
            }), "load tools");
        });
    }

    private void openToolDialog(JsonNode tool) {
        String name = tool.path("name").asText("?");
        boolean readOnly = tool.path("readOnly").asBoolean(true);
        String parameters = tool.path("parameters").asText("{}");
        List<String> argumentNames = extractArgumentNames(parameters);
        ToolArgsDialog dialog = new ToolArgsDialog(grid.getShell(), name,
                argumentNames, readOnly);
        dialog.open();
    }

    private List<String> extractArgumentNames(String schema) {
        List<String> names = new ArrayList<>();
        try {
            JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(schema);
            JsonNode properties = root.path("properties");
            properties.fieldNames().forEachRemaining(names::add);
        } catch (Exception ignored) {
            // schema not parseable: fall back to a generic key/value dialog
        }
        return names;
    }

    /** Argument dialog with schema-generated fields + rename confirmation. */
    private static final class ToolArgsDialog extends Dialog {

        private final String toolName;
        private final List<String> argumentNames;
        private final boolean mutating;
        private final List<Text> fields = new ArrayList<>();

        ToolArgsDialog(org.eclipse.swt.widgets.Shell shell, String toolName,
                       List<String> argumentNames, boolean mutating) {
            super(shell);
            this.toolName = toolName;
            this.argumentNames = argumentNames;
            this.mutating = mutating;
        }

        @Override
        protected org.eclipse.swt.widgets.Control createDialogArea(Composite parent) {
            Composite area = (Composite) super.createDialogArea(parent);
            area.setLayout(new GridLayout(2, false));
            if (mutating) {
                Label warning = new Label(area, SWT.WRAP);
                warning.setText("⚠ " + toolName + " MUTATES source files. Confirm the "
                        + "target and the new name before executing. READ_ONLY "
                        + "repositories reject this operation.");
                warning.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
                ((GridData) warning.getLayoutData()).horizontalSpan = 2;
            }
            if (argumentNames.isEmpty()) {
                Label label = new Label(area, SWT.NONE);
                label.setText("Argument (key=value):");
                Text field = new Text(area, SWT.BORDER);
                field.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
                fields.add(field);
            } else {
                for (String name : argumentNames) {
                    new Label(area, SWT.NONE).setText(name + ":");
                    Text field = new Text(area, SWT.BORDER);
                    field.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
                    field.setText(orEmpty(UiPreferences.get("tool." + toolName
                            + "." + name)));
                    field.setToolTipText(name);
                    fields.add(field);
                }
            }
            FormFields.hint(area, "Arguments are pre-filled with the last values used "
                    + "for this tool. Leave a field empty to omit it.", 2);
            return area;
        }

        @Override
        protected void configureShell(org.eclipse.swt.widgets.Shell shell) {
            super.configureShell(shell);
            shell.setText(toolName);
        }

        @Override
        protected void okPressed() {
            java.util.Map<String, Object> arguments = new java.util.LinkedHashMap<>();
            if (argumentNames.isEmpty()) {
                String raw = fields.get(0).getText().trim();
                if (!raw.isEmpty()) {
                    int equals = raw.indexOf('=');
                    if (equals > 0) {
                        arguments.put(raw.substring(0, equals).trim(),
                                raw.substring(equals + 1).trim());
                    }
                }
            } else {
                for (int i = 0; i < argumentNames.size(); i++) {
                    String value = fields.get(i).getText().trim();
                    if (!value.isEmpty()) {
                        arguments.put(argumentNames.get(i), value);
                    }
                }
            }
            var session = RuntimeConnection.instance().activeSession();
            if (session == null) {
                Ui.log("error", "Open a session first (tools execute inside a session)");
                return;
            }
            Ui.run(RuntimeConnection.instance().api().executeTool(toolName,
                            session.getId(), arguments),
                    json -> Ui.async(() -> {
                        JsonNode result = json.path("result");
                        if (!result.path("success").asBoolean(true)) {
                            Ui.log("error", toolName + ": "
                                    + result.path("error").asText("failed"));
                            return;
                        }
                        for (java.util.Map.Entry<String, Object> entry
                                : arguments.entrySet()) {
                            UiPreferences.put("tool." + toolName + "."
                                    + entry.getKey(), String.valueOf(entry.getValue()));
                        }
                        Ui.log("tool", toolName + " -> " + result.path("output").toString());
                        org.eclipse.jface.dialogs.MessageDialog.openInformation(
                                getShell(), toolName + " result",
                                result.path("output").toString());
                    }),
                    toolName);
            super.okPressed();
        }

        private static String orEmpty(String value) {
            return value == null ? "" : value;
        }
    }

    @Override
    public void setFocus() {
        grid.setFocus();
    }
}
