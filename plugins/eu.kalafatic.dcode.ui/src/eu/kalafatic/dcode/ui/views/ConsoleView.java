package eu.kalafatic.dcode.ui.views;

import eu.kalafatic.dcode.ui.Ui;
import org.eclipse.jface.action.Action;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StyledText;
import org.eclipse.swt.dnd.Clipboard;
import org.eclipse.swt.dnd.TextTransfer;
import org.eclipse.swt.dnd.Transfer;
import org.eclipse.swt.events.SelectionAdapter;
import org.eclipse.swt.events.SelectionEvent;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Combo;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.FileDialog;
import org.eclipse.swt.widgets.Label;
import org.eclipse.swt.widgets.Text;
import org.eclipse.ui.part.ViewPart;

import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Structured console: categorized, filterable operational log with pause/autoscroll/
 * copy/export controls. All entries are retained in a bounded buffer even when the
 * visible console is cleared or filtered — diagnostics are never discarded.
 */
public final class ConsoleView extends ViewPart implements Ui.Refreshable {

    /** One structured console entry. */
    public record Entry(String timestamp, String category, String message) {
        public String line() {
            return "[" + timestamp + "] " + pad(category) + " " + message;
        }

        private static String pad(String category) {
            return (category + "               ").substring(0, 10);
        }
    }

    private static final int MAX_ENTRIES = 2000;

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final DateTimeFormatter timestampFormat = DateTimeFormatter
            .ofPattern("HH:mm:ss").withZone(ZoneOffset.UTC);

    private StyledText log;
    private Combo categoryFilter;
    private Combo severityFilter;
    private Text search;
    private Button pauseButton;
    private Button autoscrollButton;
    private Label status;
    private boolean paused;
    private boolean autoscroll = true;
    private int hiddenCount;

    @Override
    public void createPartControl(Composite parent) {
        Composite root = new Composite(parent, SWT.NONE);
        root.setLayout(new GridLayout(1, false));

        Composite toolbar = new Composite(root, SWT.NONE);
        toolbar.setLayout(new GridLayout(9, false));
        toolbar.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));

        new Label(toolbar, SWT.NONE).setText("Category:");
        categoryFilter = new Combo(toolbar, SWT.READ_ONLY);
        categoryFilter.setItems("ALL", "SYSTEM", "INFO", "SUCCESS", "WARNING", "ERROR",
                "DEBUG", "NETWORK", "HARNESS", "RUNTIME", "TOOL", "TASK", "INFERENCE",
                "EVENT");
        categoryFilter.select(0);
        categoryFilter.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                render();
            }
        });

        new Label(toolbar, SWT.NONE).setText("Severity:");
        severityFilter = new Combo(toolbar, SWT.READ_ONLY);
        severityFilter.setItems("ALL", "INFO+", "WARNING+", "ERROR");
        severityFilter.select(0);
        severityFilter.addSelectionListener(new SelectionAdapter() {
            @Override
            public void widgetSelected(SelectionEvent event) {
                render();
            }
        });

        new Label(toolbar, SWT.NONE).setText("Search:");
        search = new Text(toolbar, SWT.BORDER | SWT.SEARCH);
        search.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        search.setMessage("filter entries…");
        search.addModifyListener(event -> render());

        pauseButton = new Button(toolbar, SWT.TOGGLE);
        pauseButton.setText("Pause");
        pauseButton.setToolTipText("Pause/resume console output (entries are retained)");
        pauseButton.addListener(SWT.Selection, event -> {
            paused = pauseButton.getSelection();
            pauseButton.setText(paused ? "Resume" : "Pause");
            render();
        });

        autoscrollButton = new Button(toolbar, SWT.TOGGLE);
        autoscrollButton.setText("Auto-scroll");
        autoscrollButton.setSelection(true);
        autoscrollButton.setToolTipText("Automatically scroll to the newest entry");
        autoscrollButton.addListener(SWT.Selection, event -> {
            autoscroll = autoscrollButton.getSelection();
        });

        Button clearButton = new Button(toolbar, SWT.PUSH);
        clearButton.setText("Clear");
        clearButton.setToolTipText("Clear the visible console (the full buffer is kept "
                + "for export)");
        clearButton.addListener(SWT.Selection, event -> clearVisible());

        Button copyButton = new Button(toolbar, SWT.PUSH);
        copyButton.setText("Copy");
        copyButton.setToolTipText("Copy the selected text (or all visible entries)");
        copyButton.addListener(SWT.Selection, event -> copy());

        Button exportButton = new Button(toolbar, SWT.PUSH);
        exportButton.setText("Export…");
        exportButton.setToolTipText("Export the complete diagnostics buffer to a file");
        exportButton.addListener(SWT.Selection, event -> export());

        status = new Label(root, SWT.NONE);
        status.setLayoutData(new GridData(GridData.FILL_HORIZONTAL));
        status.setText("EVO-Code console ready.");

        log = new StyledText(root, SWT.MULTI | SWT.V_SCROLL | SWT.H_SCROLL | SWT.READ_ONLY);
        log.setLayoutData(new GridData(GridData.FILL_BOTH));
        log.setFont(org.eclipse.jface.resource.JFaceResources.getTextFont());

        getViewSite().getActionBars().getToolBarManager().add(new Action("Copy all") {
            @Override
            public void run() {
                copyAll();
            }
        });
        getViewSite().getActionBars().getToolBarManager().add(new Action("Export…") {
            @Override
            public void run() {
                export();
            }
        });
        hookContextMenu();
        render();
    }

    /** Context menu on the console text: the same operations as the toolbar. */
    private void hookContextMenu() {
        org.eclipse.jface.action.MenuManager manager =
                new org.eclipse.jface.action.MenuManager();
        manager.setRemoveAllWhenShown(true);
        manager.addMenuListener(menu -> {
            menu.add(new Action("Clear") {
                @Override
                public void run() {
                    clearVisible();
                }
            });
            menu.add(new Action("Copy") {
                @Override
                public void run() {
                    copy();
                }
            });
            menu.add(new Action("Save Output…") {
                @Override
                public void run() {
                    export();
                }
            });
        });
        log.setMenu(manager.createContextMenu(log));
    }

    /** Hides the currently buffered entries from the visible console (kept for export). */
    private void clearVisible() {
        hiddenCount = entries.size();
        render();
    }

    /** Appends one structured entry (called from Ui on the UI thread). */
    public void append(String category, String message) {
        entries.add(new Entry(timestampFormat.format(Instant.now()),
                normalize(category), message == null ? "" : message));
        while (entries.size() > MAX_ENTRIES) {
            entries.remove(0);
        }
        if (!paused) {
            render();
        }
    }

    /** Appends an entry with additional detail lines. */
    public void appendDetailed(String category, String message, List<String> details) {
        append(category, message);
        if (details != null) {
            for (String detail : details) {
                append("DEBUG", "    " + detail);
            }
        }
    }

    /** Appends a backend event (live SSE feed). */
    public void appendEvent(String type, String data) {
        append("EVENT", type + " " + truncate(data));
    }

    public static String normalize(String kind) {
        if (kind == null || kind.isBlank()) {
            return "INFO";
        }
        String normalized = kind.toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "SYSTEM", "INFO", "SUCCESS", "WARNING", "ERROR", "DEBUG", "NETWORK",
                    "HARNESS", "RUNTIME", "TOOL", "TASK", "INFERENCE", "EVENT" -> normalized;
            default -> "INFO";
        };
    }

    private static String truncate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 300 ? value.substring(0, 300) + "…" : value;
    }

    private boolean matches(Entry entry) {
        String category = categoryFilter.getText();
        if (!category.equals("ALL") && !entry.category().equals(category)) {
            return false;
        }
        int severity = severityOf(entry.category());
        String severitySelection = severityFilter.getText();
        switch (severitySelection) {
            case "INFO+" -> {
                if (severity < 1) {
                    return false;
                }
            }
            case "WARNING+" -> {
                if (severity < 2) {
                    return false;
                }
            }
            case "ERROR" -> {
                if (severity < 3) {
                    return false;
                }
            }
            default -> {
            }
        }
        String query = search.getText().trim().toLowerCase(Locale.ROOT);
        return query.isBlank() || entry.line().toLowerCase(Locale.ROOT).contains(query);
    }

    private static int severityOf(String category) {
        return switch (category) {
            case "ERROR" -> 3;
            case "WARNING" -> 2;
            case "SUCCESS", "INFO", "SYSTEM", "NETWORK", "HARNESS", "RUNTIME", "TOOL",
                    "TASK", "INFERENCE" -> 1;
            default -> 0;
        };
    }

    private void render() {
        if (log == null || log.isDisposed()) {
            return;
        }
        log.setRedraw(false);
        log.setText("");
        int visible = 0;
        for (int i = hiddenCount; i < entries.size(); i++) {
            Entry entry = entries.get(i);
            if (!matches(entry)) {
                continue;
            }
            log.append(entry.line() + "\n");
            visible++;
        }
        log.setRedraw(true);
        status.setText("console · " + entries.size() + " entries"
                + (hiddenCount > 0 ? " · " + hiddenCount + " cleared (kept for export)" : "")
                + (visible < entries.size() ? " · " + (entries.size() - visible)
                + " filtered" : "")
                + (paused ? " · PAUSED" : ""));
        if (autoscroll) {
            log.setTopIndex(Math.max(0, log.getLineCount() - 1));
        }
    }

    private void copy() {
        String selected = log.getSelectionText();
        String content = selected != null && !selected.isEmpty() ? selected : log.getText();
        if (content.isBlank()) {
            return;
        }
        Clipboard clipboard = new Clipboard(log.getDisplay());
        clipboard.setContents(new Object[]{content},
                new Transfer[]{TextTransfer.getInstance()});
        clipboard.dispose();
    }

    private void copyAll() {
        StringBuilder all = new StringBuilder();
        for (Entry entry : entries) {
            all.append(entry.line()).append('\n');
        }
        Clipboard clipboard = new Clipboard(log.getDisplay());
        clipboard.setContents(new Object[]{all.toString()},
                new Transfer[]{TextTransfer.getInstance()});
        clipboard.dispose();
    }

    private void export() {
        FileDialog dialog = new FileDialog(log.getShell(), SWT.SAVE);
        dialog.setText("Export console diagnostics");
        dialog.setFileName("dcode-console.txt");
        dialog.setFilterExtensions(new String[]{"*.txt", "*.log", "*.*"});
        String path = dialog.open();
        if (path == null) {
            return;
        }
        try (PrintWriter writer = new PrintWriter(
                Files.newBufferedWriter(Path.of(path), StandardCharsets.UTF_8))) {
            for (Entry entry : entries) {
                writer.println(entry.line());
            }
            Ui.log("success", "Console diagnostics exported to " + path);
        } catch (Exception e) {
            Ui.log("error", "Cannot export console: " + e.getMessage());
        }
    }

    @Override
    public void refreshContent() {
        render();
    }

    @Override
    public void setFocus() {
        log.setFocus();
    }
}
