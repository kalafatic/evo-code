package eu.kalafatic.dcode.ui.views;

import eu.kalafatic.dcode.core.model.Workspace;
import eu.kalafatic.dcode.ui.FormFields;
import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import eu.kalafatic.dcode.ui.UiPreferences;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/** Dialog to add a repository to a workspace through the backend API. */
public final class AddRepositoryDialog
        extends org.eclipse.jface.dialogs.Dialog {

    private static final String PREF_NAME = "repo.name";
    private static final String PREF_PATH = "repo.path";
    private static final String PREF_REMOTE = "repo.remote";

    private final Workspace workspace;
    private Text nameText;
    private Text pathText;
    private Text remoteText;
    private org.eclipse.swt.widgets.Button readOnlyButton;

    public AddRepositoryDialog(org.eclipse.swt.widgets.Shell shell, Workspace workspace) {
        super(shell);
        this.workspace = workspace;
    }

    @Override
    protected org.eclipse.swt.widgets.Control createDialogArea(Composite parent) {
        Composite area = (Composite) super.createDialogArea(parent);
        area.setLayout(new GridLayout(2, false));

        FormFields.required(area, "Name");
        nameText = new Text(area, SWT.BORDER);
        nameText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        nameText.setToolTipText("Short name for this repository inside the workspace.");
        nameText.setText(orEmpty(UiPreferences.get(workspace.id(), PREF_NAME)));

        FormFields.required(area, "Local path");
        pathText = new Text(area, SWT.BORDER);
        pathText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        pathText.setToolTipText("Server-side repository directory on this machine.");
        pathText.setText(orEmpty(UiPreferences.get(workspace.id(), PREF_PATH)));
        pathText.addModifyListener(event -> deriveNameFromPath());

        FormFields.optional(area, "Remote URL");
        remoteText = new Text(area, SWT.BORDER);
        remoteText.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        remoteText.setToolTipText("Optional upstream remote URL (git).");
        remoteText.setText(orEmpty(UiPreferences.get(workspace.id(), PREF_REMOTE)));

        readOnlyButton = new org.eclipse.swt.widgets.Button(area, SWT.CHECK);
        readOnlyButton.setText("Read-only repository");
        readOnlyButton.setToolTipText("When checked, the repository rejects mutations.");
        new org.eclipse.swt.widgets.Label(area, SWT.NONE);

        FormFields.hint(area, "Name and local path are required. The last used values "
                + "for this workspace are pre-filled.", 2);

        return area;
    }

    /** Derives a repository name from the last path segment when none is typed. */
    private void deriveNameFromPath() {
        if (!nameText.getText().trim().isEmpty()) {
            return;
        }
        String path = pathText.getText().trim();
        int separator = Math.max(path.lastIndexOf('/'), path.lastIndexOf('\\'));
        if (separator >= 0 && separator < path.length() - 1) {
            nameText.setText(path.substring(separator + 1));
        }
    }

    @Override
    protected void configureShell(org.eclipse.swt.widgets.Shell shell) {
        super.configureShell(shell);
        shell.setText("Add repository to " + workspace.name());
    }

    @Override
    protected void okPressed() {
        String name = nameText.getText().trim();
        String path = pathText.getText().trim();
        String remote = remoteText.getText().trim();
        if (name.isEmpty()) {
            Ui.log("error", "Repository name is required.");
            return;
        }
        if (path.isEmpty()) {
            Ui.log("error", "Local path is required for the repository.");
            return;
        }
        RuntimeConnection.instance().api().addRepository(workspace.id(),
                        name, path, remote, readOnlyButton.getSelection())
                .whenComplete((updated, throwable) -> Ui.async(() -> {
                    if (throwable != null) {
                        Ui.log("error", "add repository failed: " + throwable.getMessage());
                        return;
                    }
                    // Save only after a successful operation.
                    UiPreferences.put(workspace.id(), PREF_NAME, name);
                    UiPreferences.put(workspace.id(), PREF_PATH, path);
                    UiPreferences.put(workspace.id(), PREF_REMOTE, remote);
                    Ui.log("Repository '" + name + "' added to "
                            + workspace.name());
                    close();
                }));
    }

    private static String orEmpty(String value) {
        return value == null ? "" : value;
    }
}
