package eu.kalafatic.dcode.ui.views;

import com.fasterxml.jackson.databind.JsonNode;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Table;
import org.eclipse.swt.widgets.TableColumn;
import org.eclipse.swt.widgets.TableItem;

import java.util.ArrayList;
import java.util.List;

/**
 * Local inference-service scanner: probes well-known endpoints (Ollama, ENR, DCode,
 * OpenAI-compatible), classifies what each serves, and registers selected findings as
 * runtimes in one click. Never registers endpoints that are already configured.
 */
public final class ScanRuntimesDialog extends Dialog {

    private Table table;
    private Label status;
    private final List<JsonNode> findings = new ArrayList<>();

    public ScanRuntimesDialog(Shell parent) {
        super(parent);
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Scan local runtimes");
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite root = (Composite) super.createDialogArea(parent);
        root.setLayout(new GridLayout(1, false));
        ((GridLayout) root.getLayout()).numColumns = 1;

        table = new Table(root, SWT.BORDER | SWT.CHECK | SWT.FULL_SELECTION
                | SWT.V_SCROLL);
        table.setLayoutData(new GridData(GridData.FILL_BOTH));
        table.setHeaderVisible(true);
        addColumn("Service", 190);
        addColumn("Endpoint", 230);
        addColumn("Detail", 260);
        addColumn("Models", 60);
        GridData tableData = new GridData(GridData.FILL_BOTH);
        tableData.widthHint = 780;
        tableData.heightHint = 260;
        table.setLayoutData(tableData);

        status = new Label(root, SWT.WRAP);
        status.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        status.setText("Scanning local endpoints…");

        scan();
        return root;
    }

    private void addColumn(String title, int width) {
        TableColumn column = new TableColumn(table, SWT.LEFT);
        column.setText(title);
        column.setWidth(width);
    }

    private void scan() {
        if (!eu.kalafatic.dcode.ui.RuntimeConnection.instance().isUsableForRequests()) {
            status.setText("Backend not usable (state "
                    + eu.kalafatic.dcode.ui.RuntimeConnection.instance().state().name()
                    + ") — fix the connection, then scan again.");
            table.removeAll();
            findings.clear();
            return;
        }
        status.setText("Scanning local endpoints (11434 Ollama, 8111/28780 ENR, "
                + "58080 EVO-Code)…");
        Ui.run(RuntimeConnection.instance().api().scanRuntimes(null), list -> {
            findings.clear();
            list.forEach(findings::add);
            render();
        }, "scan local runtimes", this::showScanFailure);
    }

    /** Failure path of the scan: the dialog must never spin forever. */
    private void showScanFailure(Throwable throwable) {
        Ui.async(() -> {
            if (status == null || status.isDisposed()) {
                return;
            }
            status.setText("Scan failed: " + (throwable.getMessage() == null
                    ? throwable.getClass().getSimpleName() : throwable.getMessage())
                    + " — use 'Scan again' to retry.");
        });
    }

    private void render() {
        Ui.async(() -> {
            table.removeAll();
            int runtimes = 0;
            int registered = 0;
            int reachable = 0;
            for (JsonNode finding : findings) {
                boolean isRuntime = !finding.path("type").asText("").isBlank();
                boolean alreadyRegistered = finding.path("registered").asBoolean();
                boolean isDCode = "dcode".equals(finding.path("kind").asText(""));
                if (finding.path("reachable").asBoolean()) {
                    reachable++;
                }
                if (isRuntime && alreadyRegistered) {
                    registered++;
                }
                if (!isRuntime || alreadyRegistered) {
                    continue;
                }
                runtimes++;
                TableItem item = new TableItem(table, SWT.NONE);
                item.setText(new String[]{
                        finding.path("name").asText(finding.path("kind").asText("")),
                        finding.path("endpoint").asText(""),
                        finding.path("description").asText(""),
                        String.valueOf(finding.path("modelCount").asInt(0))});
                item.setData(finding);
                item.setChecked(!isDCode);
            }
            status.setText(reachable + " endpoint(s) reachable · " + runtimes
                    + " runtime(s) ready to register · " + registered
                    + " already registered. Checked items are added on OK.");
        });
    }

    @Override
    protected void createButtonsForButtonBar(Composite parent) {
        Button rescan = createButton(parent, 11, "Scan again", false);
        rescan.addListener(SWT.Selection, event -> scan());
        super.createButtonsForButtonBar(parent);
    }

    @Override
    protected void okPressed() {
        int added = 0;
        for (TableItem item : table.getItems()) {
            if (!item.getChecked() || !(item.getData() instanceof JsonNode finding)) {
                continue;
            }
            String type = finding.path("type").asText("");
            String endpoint = finding.path("endpoint").asText("");
            String name = finding.path("name").asText("");
            if (type.isBlank() || endpoint.isBlank()) {
                continue;
            }
            added++;
            Ui.run(RuntimeConnection.instance().api().createRuntime(name, type,
                            endpoint, null, null),
                    json -> Ui.log("success", "Runtime registered: "
                            + json.path("id").asText("") + " (" + endpoint + ")"),
                    "register runtime " + endpoint);
        }
        if (added == 0) {
            Ui.log("info", "No new runtimes selected");
        }
        Ui.refreshAll();
        super.okPressed();
    }
}
