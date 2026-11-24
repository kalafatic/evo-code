package eu.kalafatic.dcode.ui.preference;

import org.eclipse.jface.preference.FieldEditorPreferencePage;
import org.eclipse.jface.preference.StringFieldEditor;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.MessageBox;

import org.eclipse.core.runtime.preferences.InstanceScope;
import org.eclipse.core.runtime.preferences.DefaultScope;
import org.eclipse.core.runtime.preferences.IEclipsePreferences;

import eu.kalafatic.dcode.core.config.CoreConfiguration;
import eu.kalafatic.dcode.ui.UiPreferences;

/**
 * DCode runtime configuration preference page.
 * Provides LLM, harness, provider, and agent configuration settings.
 * Persists to Eclipse preferences.
 */
public class DCodePreferencePage extends FieldEditorPreferencePage {

    private static final String NODE_ID = "dcode.nodeId";
    private static final String APP_VERSION = "dcode.appVersion";
    private static final String DEFAULT_PROVIDER = "dcode.defaultProvider";
    private static final String PROVIDER_ENDPOINT = "dcode.providerEndpoint";
    private static final String HARNESS_TYPE = "dcode.harnessType";
    private static final String AGENT_ROLE = "dcode.agentRole";
    private static final String DEFAULT_MODEL = "dcode.defaultModel";

    private StringFieldEditor nodeIdEditor;
    private StringFieldEditor appVersionEditor;
    private StringFieldEditor defaultProviderEditor;
    private StringFieldEditor providerEndpointEditor;
    private StringFieldEditor harnessTypeEditor;
    private StringFieldEditor agentRoleEditor;
    private StringFieldEditor defaultModelEditor;

    private IEclipsePreferences prefs;

    public DCodePreferencePage() {
        super(GRID);
        prefs = InstanceScope.INSTANCE.getNode("eu.kalafatic.dcode");
        setDescription("EVO-Code runtime configuration: LLM, harness, provider, and agent settings");
    }

    @Override
    protected void createFieldEditors() {
        nodeIdEditor = new StringFieldEditor(NODE_ID, "Node ID:", getFieldEditorParent());
        addField(nodeIdEditor);
        appVersionEditor = new StringFieldEditor(APP_VERSION, "App Version:", getFieldEditorParent());
        addField(appVersionEditor);
        defaultProviderEditor = new StringFieldEditor(DEFAULT_PROVIDER, "Default Provider:", getFieldEditorParent());
        addField(defaultProviderEditor);
        providerEndpointEditor = new StringFieldEditor(PROVIDER_ENDPOINT, "Provider Endpoint:", getFieldEditorParent());
        addField(providerEndpointEditor);
        harnessTypeEditor = new StringFieldEditor(HARNESS_TYPE, "Harness Type:", getFieldEditorParent());
        addField(harnessTypeEditor);
        agentRoleEditor = new StringFieldEditor(AGENT_ROLE, "Agent Role:", getFieldEditorParent());
        addField(agentRoleEditor);
        defaultModelEditor = new StringFieldEditor(DEFAULT_MODEL, "Default Model:", getFieldEditorParent());
        addField(defaultModelEditor);
    }

    @Override
    protected void performDefaults() {
        super.performDefaults();
        // Restore application defaults: clear every remembered form value.
        UiPreferences.resetAll();
        MessageBox box = new MessageBox(getShell(), SWT.ICON_INFORMATION | SWT.OK);
        box.setMessage("Application defaults restored — remembered form values "
                + "(runtimes, models, repository paths, tool arguments) cleared.");
        box.open();
    }

    @Override
    public boolean performOk() {
        // Persist the configuration values to Eclipse preferences
        if (nodeIdEditor != null) prefs.put(NODE_ID, nodeIdEditor.getStringValue());
        if (appVersionEditor != null) prefs.put(APP_VERSION, appVersionEditor.getStringValue());
        if (defaultProviderEditor != null) prefs.put(DEFAULT_PROVIDER, defaultProviderEditor.getStringValue());
        if (providerEndpointEditor != null) prefs.put(PROVIDER_ENDPOINT, providerEndpointEditor.getStringValue());
        if (harnessTypeEditor != null) prefs.put(HARNESS_TYPE, harnessTypeEditor.getStringValue());
        if (agentRoleEditor != null) prefs.put(AGENT_ROLE, agentRoleEditor.getStringValue());
        if (defaultModelEditor != null) prefs.put(DEFAULT_MODEL, defaultModelEditor.getStringValue());
        // Show success message
        Display.getDefault().asyncExec(() -> {
            try {
                MessageBox box = new MessageBox(getShell(), SWT.ICON_INFORMATION | SWT.OK);
                box.setMessage("EVO-Code configuration saved successfully");
                box.open();
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        return true;
    }
}
