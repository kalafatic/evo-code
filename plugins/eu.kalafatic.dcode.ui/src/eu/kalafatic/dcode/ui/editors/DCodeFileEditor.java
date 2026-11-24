package eu.kalafatic.dcode.ui.editors;

import eu.kalafatic.dcode.ui.RuntimeConnection;
import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.source.CompositeRuler;
import org.eclipse.jface.text.source.LineNumberRulerColumn;
import org.eclipse.jface.text.source.SourceViewer;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.graphics.Color;
import org.eclipse.swt.graphics.RGB;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.IEditorInput;
import org.eclipse.ui.IEditorSite;
import org.eclipse.ui.PartInitException;
import org.eclipse.ui.part.EditorPart;

import java.util.regex.Pattern;

/**
 * DCode source editor: JFace text infrastructure (SourceViewer, line-number ruler) +
 * a lightweight Java syntax highlighter (keywords/strings/comments). Content is
 * backend-provided; saving goes through the audited {@code write_file} tool of the
 * active session. Full JDT editor embedding (content assist, quick fixes) is a
 * documented deferred enhancement.
 */
public final class DCodeFileEditor extends EditorPart {

    private SourceViewer viewer;
    private Text editArea;
    private boolean editing;

    @Override
    public void init(IEditorSite site, IEditorInput input) throws PartInitException {
        if (!(input instanceof DCodeFileEditorInput)) {
            throw new PartInitException("Invalid input: DCodeFileEditorInput expected");
        }
        setSite(site);
        setInput(input);
    }

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        CompositeRuler ruler = new CompositeRuler();
        LineNumberRulerColumn numbers = new LineNumberRulerColumn();
        // Theme-aware colors: follow the active workbench theme instead of a
        // hardcoded dark palette.
        numbers.setBackground(parent.getDisplay()
                .getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));
        numbers.setForeground(parent.getDisplay()
                .getSystemColor(SWT.COLOR_WIDGET_NORMAL_SHADOW));
        ruler.addDecorator(0, numbers);

        viewer = new SourceViewer(root, ruler, SWT.V_SCROLL | SWT.H_SCROLL);
        viewer.setDocument(new Document(""));
        viewer.getTextWidget().setFont(org.eclipse.jface.resource.JFaceResources
                .getFont(org.eclipse.jface.resource.JFaceResources.TEXT_FONT));
        viewer.getTextWidget().setLayoutData(new GridData(GridData.FILL_BOTH));
        StyledText viewerText = viewer.getTextWidget();
        viewerText.addLineStyleListener(event -> {
            highlight(viewerText, event.lineOffset, event.lineText);
        });

        editArea = new Text(root, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.BORDER);
        editArea.setLayoutData(new GridData(GridData.FILL_BOTH));
        editArea.setVisible(false);
        ((GridData) editArea.getLayoutData()).exclude = true;

        loadContent();
    }

    private void loadContent() {
        DCodeFileEditorInput input = (DCodeFileEditorInput) getEditorInput();
        Ui.run(RuntimeConnection.instance().api().readFile(input.workspace().id(),
                        input.path()),
                file -> Ui.async(() -> {
                    String content = file.path("content").asText("");
                    viewer.getDocument().set(content);
                    setPartName(input.getName());
                }), "read file");
    }

    @Override
    public boolean isDirty() {
        return editing;
    }

    @Override
    public boolean isSaveAsAllowed() {
        return false;
    }

    @Override
    public void doSave(IProgressMonitor monitor) {
        if (!editing) {
            return;
        }
        DCodeFileEditorInput input = (DCodeFileEditorInput) getEditorInput();
        var session = RuntimeConnection.instance().activeSession();
        if (session == null) {
            Ui.log("error", "Saving requires an active session (write_file tool)");
            return;
        }
        java.util.Map<String, Object> arguments = java.util.Map.of(
                "path", input.path(), "content", editArea.getText());
        Ui.run(RuntimeConnection.instance().api().executeTool("write_file",
                        session.getId(), arguments),
                result -> Ui.async(() -> {
                    if (!result.path("result").path("success").asBoolean(true)) {
                        Ui.log("error", "save failed: "
                                + result.path("result").path("error").asText());
                        return;
                    }
                    Ui.log("saved " + input.path() + " via write_file tool");
                    editing = false;
                    toggleEditMode();
                    loadContent();
                    firePropertyChange(PROP_DIRTY);
                }), "save file");
    }

    @Override
    public void doSaveAs() {
        // not supported: single backend file target
    }

    /** Toggles between viewer and editing mode. */
    public void toggleEditMode() {
        editing = !editing;
        GridData viewerData = (GridData) viewer.getTextWidget().getLayoutData();
        GridData editData = (GridData) editArea.getLayoutData();
        if (editing) {
            editArea.setText(viewer.getDocument().get());
            viewer.getTextWidget().setVisible(false);
            viewerData.exclude = true;
            editArea.setVisible(true);
            editData.exclude = false;
        } else {
            editArea.setVisible(false);
            editData.exclude = true;
            viewer.getTextWidget().setVisible(true);
            viewerData.exclude = false;
        }
        viewer.getTextWidget().getParent().layout();
        firePropertyChange(PROP_DIRTY);
    }

    @Override
    public void setFocus() {
        if (editing) {
            editArea.setFocus();
        } else {
            viewer.getTextWidget().setFocus();
        }
    }

    private static final Pattern KEYWORDS = Pattern.compile(
            "\\b(public|private|protected|class|interface|enum|record|extends|implements|"
                    + "static|final|void|int|long|boolean|char|byte|short|float|double|"
                    + "new|return|if|else|for|while|do|switch|case|break|continue|"
                    + "try|catch|finally|throw|throws|import|package|this|super|null|"
                    + "true|false|instanceof|synchronized|volatile|transient|abstract|"
                    + "native|default|var|sealed|permits|non-sealed|yield)\\b");
    private static final Pattern STRING = Pattern.compile("\"[^\"\\\\]*(\\\\.[^\"\\\\]*)*\"");
    private static final Pattern COMMENT = Pattern.compile("//.*|/\\*(.|\\n)*?\\*/",
            Pattern.DOTALL);

    private void highlight(StyledText text, int lineOffset, String lineText) {
        boolean darkBackground = isDark(text.getBackground());
        Color keyword = new Color(text.getDisplay(), darkBackground
                ? new RGB(122, 162, 214) : new RGB(0, 0, 180));
        Color string = new Color(text.getDisplay(), darkBackground
                ? new RGB(166, 205, 141) : new RGB(0, 128, 0));
        Color comment = new Color(text.getDisplay(), darkBackground
                ? new RGB(123, 130, 141) : new RGB(96, 96, 96));

        apply(text, lineOffset, lineText, COMMENT, comment);
        apply(text, lineOffset, lineText, STRING, string);
        apply(text, lineOffset, lineText, KEYWORDS, keyword);
    }

    /** True when a background color is dark enough to need light text. */
    private static boolean isDark(Color color) {
        double luminance = 0.299 * color.getRed()
                + 0.587 * color.getGreen() + 0.114 * color.getBlue();
        return luminance < 140;
    }

    private void apply(StyledText text, int lineOffset, String lineText, Pattern pattern,
                       Color color) {
        java.util.regex.Matcher matcher = pattern.matcher(lineText);
        while (matcher.find()) {
            text.setStyleRange(new org.eclipse.swt.custom.StyleRange(
                    lineOffset + matcher.start(), matcher.end() - matcher.start(),
                    color, null));
        }
    }
}
