package eu.kalafatic.dcode.ui;

import org.eclipse.swt.SWT;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Control;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;

/**
 * Small form-building helpers: consistent required / optional / auto-detected field
 * labels and muted description hints. Preserves the professional Eclipse RCP look;
 * the required marker is a red asterisk character (text), so it does not rely on
 * color alone and survives color-blind rendering.
 */
public final class FormFields {

    private FormFields() {
    }

    /** Label for a required field: the name plus a red asterisk. */
    public static void required(Composite parent, String text) {
        Composite cell = cell(parent);
        new Label(cell, SWT.NONE).setText(text);
        Label star = new Label(cell, SWT.NONE);
        star.setText("*");
        star.setForeground(parent.getDisplay().getSystemColor(SWT.COLOR_RED));
        star.setToolTipText("Required");
    }

    /** Label for an optional field: the name plus a muted "(optional)". */
    public static void optional(Composite parent, String text) {
        Composite cell = cell(parent);
        new Label(cell, SWT.NONE).setText(text);
        Label opt = new Label(cell, SWT.NONE);
        opt.setText("(optional)");
        opt.setForeground(muted(parent));
    }

    /** Label for an auto-detected field with a muted "(auto)" marker and tooltip. */
    public static void auto(Composite parent, String text, String detail) {
        Composite cell = cell(parent);
        new Label(cell, SWT.NONE).setText(text);
        Label marker = new Label(cell, SWT.NONE);
        marker.setText("(auto)");
        marker.setForeground(muted(parent));
        marker.setToolTipText(detail);
        cell.setToolTipText(detail);
    }

    /** Muted description / guidance line below a field or group. */
    public static Label hint(Composite parent, String text, int horizontalSpan) {
        Label hint = new Label(parent, SWT.WRAP);
        hint.setText(text);
        hint.setForeground(muted(parent));
        GridData data = new GridData(GridData.FILL_HORIZONTAL);
        data.horizontalSpan = horizontalSpan;
        hint.setLayoutData(data);
        return hint;
    }

    /** Returns the trimmed text of a {@link Text} control, or {@code null} if blank. */
    public static String text(Control control) {
        if (control instanceof Text text) {
            String value = text.getText().trim();
            return value.isEmpty() ? null : value;
        }
        if (control instanceof Combo combo) {
            String value = combo.getText().trim();
            return value.isEmpty() ? null : value;
        }
        return null;
    }

    private static Color muted(Composite parent) {
        return parent.getDisplay().getSystemColor(SWT.COLOR_DARK_GRAY);
    }

    private static Composite cell(Composite parent) {
        Composite cell = new Composite(parent, SWT.NONE);
        GridLayout layout = new GridLayout(2, false);
        layout.marginWidth = 0;
        layout.marginHeight = 0;
        layout.horizontalSpacing = 2;
        cell.setLayout(layout);
        return cell;
    }
}
