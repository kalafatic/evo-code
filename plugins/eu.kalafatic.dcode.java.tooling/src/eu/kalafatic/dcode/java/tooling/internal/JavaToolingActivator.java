package eu.kalafatic.dcode.java.tooling.internal;

import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.log.DCodeLog;
import eu.kalafatic.dcode.core.service.ServiceAvailabilityGate;
import eu.kalafatic.dcode.harness.tool.ToolRegistry;
import eu.kalafatic.dcode.java.tooling.jdt.JavaWorkspaceManager;
import eu.kalafatic.dcode.java.tooling.tools.JavaAnalyzeDependenciesTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaAnalyzeImportsTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaAstSummaryTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaCallHierarchyTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaDiagnosticsTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaFindImplementationsTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaFindReferencesTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaProjectStructureTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaRenameTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaResolveTypeTool;
import eu.kalafatic.dcode.java.tooling.tools.JavaTypeHierarchyTool;
import eu.kalafatic.dcode.java.tooling.tools.PdeAnalyzeDependenciesTool;
import eu.kalafatic.dcode.java.tooling.tools.PdeInspectBundleTool;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;

/**
 * Registers the Java/PDE tools in the DCode tool registry. Startup is retried through a
 * service-availability gate; the harness tool registry is consumed, never duplicated.
 * All tools share one headless {@link JavaWorkspaceManager}.
 */
@SuppressWarnings("restriction")
public final class JavaToolingActivator implements BundleActivator {

    private ServiceAvailabilityGate gate;
    private BundleContext context;
    private JavaWorkspaceManager workspaceManager;

    @Override
    public void start(BundleContext context) {
        this.context = context;
        gate = new ServiceAvailabilityGate(context, new Class<?>[]{
                ToolRegistry.class, DCodeLifecycle.class}, this::wire);
        gate.runIfAvailable();
    }

    private void wire() {
        try {
            ToolRegistry toolRegistry = context.getService(
                    context.getServiceReference(ToolRegistry.class));
            DCodeLifecycle lifecycle = context.getService(
                    context.getServiceReference(DCodeLifecycle.class));
            workspaceManager = new JavaWorkspaceManager();
            // Open the Eclipse Resources + JDT workspace eagerly so the Java tools are
            // genuinely available in headless runtimes (no SWT display, no Workbench).
            // ensureStarted() opens the resources workspace before activating JDT Core so
            // JDT's lazy activator cannot crash. When the resources bundle is absent this
            // fails cleanly and the tools remain registered but report a clear diagnostic
            // at invocation time.
            try {
                workspaceManager.ensureStarted();
            } catch (Throwable e) {
                DCodeLog.warn("java.tooling",
                        "JDT workspace could not be initialized eagerly; tools will "
                                + "initialize lazily: " + e.getMessage());
            }

            toolRegistry.register(new JavaProjectStructureTool(workspaceManager));
            toolRegistry.register(new JavaDiagnosticsTool(workspaceManager));
            toolRegistry.register(new JavaAstSummaryTool(workspaceManager));
            toolRegistry.register(new JavaResolveTypeTool(workspaceManager));
            toolRegistry.register(new JavaTypeHierarchyTool(workspaceManager));
            toolRegistry.register(new JavaCallHierarchyTool(workspaceManager));
            toolRegistry.register(new JavaFindReferencesTool(workspaceManager));
            toolRegistry.register(new JavaFindImplementationsTool(workspaceManager));
            toolRegistry.register(new JavaAnalyzeImportsTool(workspaceManager));
            toolRegistry.register(new JavaAnalyzeDependenciesTool(workspaceManager));
            toolRegistry.register(new JavaRenameTool(workspaceManager));
            toolRegistry.register(new PdeInspectBundleTool(workspaceManager));
            toolRegistry.register(new PdeAnalyzeDependenciesTool(workspaceManager));

            lifecycle.registerAction(LifecycleStage.TOOLING, "java-tooling-services",
                    new DCodeLifecycle.LifecycleAction() {
                        @Override
                        public void start() {
                            DCodeLog.info("java.tooling",
                                    "Eclipse JDT/PDE tooling registered (13 tools)");
                        }

                        @Override
                        public void stop() {
                            DCodeLog.info("java.tooling", "Eclipse JDT/PDE tooling stopped");
                        }
                    });
            try {
                lifecycle.startThrough(LifecycleStage.TOOLING);
            } catch (Exception e) {
                DCodeLog.error("java.tooling", "Failed to advance lifecycle to TOOLING", e);
            }
        } catch (Throwable e) {
            // Headless runtimes without an initialized Eclipse workspace cannot host
            // JDT: tool registration must never prevent the runtime from starting.
            DCodeLog.error("java.tooling",
                    "JDT/PDE tooling unavailable in this runtime: " + e.getMessage(), e);
        }
    }

    @Override
    public void stop(BundleContext context) {
        if (gate != null) {
            gate.close();
        }
        workspaceManager = null;
    }
}
