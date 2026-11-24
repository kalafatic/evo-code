package eu.kalafatic.dcode.ui.views;

import eu.kalafatic.dcode.client.BackendIdentity;
import eu.kalafatic.dcode.client.ConnectionState;
import eu.kalafatic.dcode.ui.ConnectionDialog;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.ui.part.ViewPart;

import java.util.Map;

/**
 * Connection view: the diagnostic hub of the connection lifecycle. Shows the explicit
 * connection state, the detected backend identity (application, version, readiness
 * and lifecycle stages), the endpoint, and recovery actions.
 */
public final class ConnectionView extends ViewPart implements Ui.Refreshable,
        RuntimeConnection.ConnectionStateListener {

    private Label stateLabel;
    private StyledText content;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        stateLabel = new Label(root, SWT.NONE);
        stateLabel.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        content = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.READ_ONLY);
        content.setLayoutData(new GridData(GridData.FILL_BOTH));

        getViewSite().getActionBars().getToolBarManager().add(new Action("Recheck") {
            @Override
            public void run() {
                RuntimeConnection.instance().recheck();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Connect…") {
            @Override
            public void run() {
                new ConnectionDialog(getSite().getShell()).open();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Scan local…") {
            @Override
            public void run() {
                new ScanRuntimesDialog(getSite().getShell()).open();
                RuntimeConnection.instance().recheck();
            }
        });
        if (RuntimeConnection.instance().hasLocalServer()) {
            getViewSite().getActionBars().getToolBarManager().add(new Action("Use local server") {
                @Override
                public void run() {
                    Ui.log("network", "Switching to the local embedded EVO-Code server…");
                    RuntimeConnection.instance().useLocalServer();
                }
            });
        }
        RuntimeConnection.instance().addStateListener(this);
        render();
    }

    @Override
    public void stateChanged(ConnectionState state, BackendIdentity identity,
                             String diagnostic) {
        render();
    }

    @Override
    public void refreshContent() {
        render();
    }

    private void render() {
        Ui.async(() -> {
            if (content == null || content.isDisposed()) {
                return;
            }
            RuntimeConnection connection = RuntimeConnection.instance();
            ConnectionState state = connection.state();
            BackendIdentity identity = connection.identity();

            stateLabel.setText("State: " + state.name()
                    + (identity != null && identity.isDCode()
                    ? "  ·  " + identity.describe() : ""));
            Color color = switch (state) {
                case READY -> parent().getDisplay().getSystemColor(SWT.COLOR_DARK_GREEN);
                case ERROR -> parent().getDisplay().getSystemColor(SWT.COLOR_RED);
                case CONNECTING, CONNECTED, INITIALIZING ->
                        parent().getDisplay().getSystemColor(SWT.COLOR_DARK_YELLOW);
                case DEGRADED -> parent().getDisplay().getSystemColor(SWT.COLOR_DARK_MAGENTA);
                default -> parent().getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND);
            };
            stateLabel.setForeground(color);

            StringBuilder text = new StringBuilder();
            text.append("Endpoint:   ").append(connection.endpoint()).append('\n');
            text.append("Mode:       ").append(connection.isLocalMode()
                    ? "local (embedded runtime)" : "remote").append('\n');
            text.append("State:      ").append(state.name()).append('\n');
            if (identity != null && identity.isDCode()) {
                text.append("Backend:    ").append(identity.application())
                        .append(' ').append(identity.version()).append('\n');
                text.append("Health:     ").append(identity.status())
                        .append(identity.ready() ? " (ready)" : " (not ready)")
                        .append('\n');
                Map<String, String> stages = identity.stages();
                if (!stages.isEmpty()) {
                    text.append("Stages:\n");
                    for (Map.Entry<String, String> stage : stages.entrySet()) {
                        text.append("  ").append(stage.getKey()).append(": ")
                                .append(stage.getValue()).append('\n');
                    }
                }
            } else if (state == ConnectionState.ERROR) {
                text.append("Diagnostic: ").append(connection.lastDiagnostic())
                        .append('\n');
            }
            text.append('\n');
            text.append("Recovery actions:\n");
            text.append("  · Recheck        re-probe the endpoint (manual retry)\n");
            text.append("  · Connect…       change endpoint or bearer token\n");
            text.append("  · Scan local…    find Ollama/ENR/EVO-Code services on this machine\n");
            if (RuntimeConnection.instance().hasLocalServer()) {
                text.append("  · Use local server  connect to the embedded EVO-Code server\n");
            }
            if (state == ConnectionState.ERROR) {
                text.append("  · If this is not an EVO-Code endpoint (e.g. ENR or Ollama), "
                        + "correct the server URL.\n");
            }
            text.append('\n');
            text.append("The RCP workbench consumes the same authoritative backend "
                    + "services as the web UI — sessions, tools, repositories, runtimes "
                    + "and events are shared across clients.");
            content.setText(text.toString());
        });
    }

    private Composite parent() {
        return content.getParent();
    }

    @Override
    public void setFocus() {
        content.setFocus();
    }

    @Override
    public void dispose() {
        RuntimeConnection.instance().removeStateListener(this);
        super.dispose();
    }
}
