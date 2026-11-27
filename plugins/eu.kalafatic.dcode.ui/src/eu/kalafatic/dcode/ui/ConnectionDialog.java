package eu.kalafatic.dcode.ui;

import eu.kalafatic.dcode.client.BackendProbe;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.swt.widgets.Text;

/**
 * Endpoint/token configuration dialog for local/remote runtime switching. The typed
 * endpoint can be tested before saving: the probe reports the detected backend
 * identity, so pointing DCode at a non-DCode service (e.g. ENR or Ollama) is caught
 * here instead of turning into a stream of HTTP 404 errors.
 */
public final class ConnectionDialog extends Dialog {

    private Text endpointText;
    private Text tokenText;
    private Label probeResult;

    public ConnectionDialog(Shell parent) {
        super(parent);
    }

    @Override
    protected Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));

        FormFields.required(area, "Server endpoint");
        endpointText = new Text(area, SWT.BORDER);
        endpointText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        endpointText.setText(RuntimeConnection.instance().endpoint() != null
                ? RuntimeConnection.instance().endpoint() : "http://127.0.0.1:48080");
        endpointText.setToolTipText("Leave the local endpoint to use the embedded "
                + "runtime; set a remote URL to connect to an independent headless "
                + "EVO-Code server (not an ENR/Ollama endpoint).");

        FormFields.optional(area, "Bearer token");
        tokenText = new Text(area, SWT.BORDER | SWT.PASSWORD);
        tokenText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        tokenText.setToolTipText("Optional; only needed when the server requires "
                + "authentication. Stored in the platform secure storage, never "
                + "displayed.");

        FormFields.hint(area, "The endpoint is pre-filled with the current "
                + "connection. Change it only to switch to another EVO-Code server.", 2);

        Button testButton = new Button(area, SWT.PUSH);
        testButton.setText("Test connection");
        testButton.setToolTipText("Probe the typed endpoint without saving");
        probeResult = new Label(area, SWT.WRAP);
        probeResult.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        if (RuntimeConnection.instance().hasLocalServer()) {
            Button localButton = new Button(area, SWT.PUSH);
            localButton.setText("Use local server");
            localButton.setToolTipText("Reconnect to the embedded EVO-Code server of this "
                    + "application (drops the persisted remote endpoint)");
            localButton.addListener(SWT.Selection, event -> {
                RuntimeConnection.instance().useLocalServer();
                Ui.log("network", "Switched to the local embedded EVO-Code server.");
                close();
            });
            new Label(area, SWT.NONE);
        }

        testButton.addListener(SWT.Selection, event -> testEndpoint());
        return area;
    }

    private void testEndpoint() {
        String endpoint = endpointText.getText().trim();
        if (endpoint.isBlank()) {
            probeResult.setText("Enter an endpoint first.");
            return;
        }
        probeResult.setText("Probing " + endpoint + " …");
        BackendProbe probe = new BackendProbe();
        probe.probe(endpoint).whenComplete((result, throwable) -> Ui.async(() -> {
            if (throwable != null) {
                probeResult.setText("Probe failed: " + throwable.getMessage());
                return;
            }
            switch (result.state()) {
                case READY -> probeResult.setText("OK — EVO-Code backend ready: "
                        + result.identity().describe());
                case INITIALIZING -> probeResult.setText("OK — EVO-Code backend starting: "
                        + result.identity().describe());
                case DEGRADED -> probeResult.setText("Warning — " + result.describe());
                default -> probeResult.setText("Failed — " + result.describe());
            }
        }));
    }

    @Override
    protected void configureShell(Shell shell) {
        super.configureShell(shell);
        shell.setText("Connect to EVO-Code Runtime");
    }

    @Override
    protected void okPressed() {
        String endpoint = endpointText.getText().trim();
        if (endpoint.isEmpty()) {
            probeResult.setText("Server endpoint is required — enter the URL of the "
                    + "EVO-Code server.");
            return;
        }
        String token = tokenText.getText();
        if (token.isBlank()) {
            token = null;
        }
        RuntimeConnection.instance().reconnect(endpoint.isEmpty() ? null : endpoint,
                token);
        Ui.log("network", "Connecting to " + RuntimeConnection.instance().endpoint()
                + " …");
        Ui.refreshAll();
        super.okPressed();
    }
}
