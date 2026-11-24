package eu.kalafatic.dcode.ui.editors;

import org.eclipse.jface.resource.ImageDescriptor;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IPersistableElement;

/**
 * Editor input of the DCode harness editor (one per workbench session).
 *
 * <p>Deliberately workspace-independent: the embedded web page owns workspace and
 * session selection through the shared backend, so the input carries no mutable
 * state. Equality is by type — {@code IWorkbenchPage.openEditor()} therefore always
 * reuses the single editor instance (startup auto-open, restoration and the
 * Workspace Explorer double-click all converge on the same part; no duplicates).
 * The input is non-persistable; the workbench restores the editor part itself.</p>
 */
public final class DCodeMultiPageEditorInput implements IEditorInput {

    /** Stable display identity of the main editor input. */
    public static final String NAME = "EVO-Code Inference";

    @Override
    public boolean exists() {
        return true;
    }

    @Override
    public ImageDescriptor getImageDescriptor() {
        return null;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    public IPersistableElement getPersistable() {
        return null;
    }

    @Override
    public String getToolTipText() {
        return "EVO-Code harness workspace: web application + settings";
    }

    @Override
    public <T> T getAdapter(Class<T> adapter) {
        return null;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof DCodeMultiPageEditorInput;
    }

    @Override
    public int hashCode() {
        return DCodeMultiPageEditorInput.class.hashCode();
    }
}
