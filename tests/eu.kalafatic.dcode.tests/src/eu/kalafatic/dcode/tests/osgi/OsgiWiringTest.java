package eu.kalafatic.dcode.tests.osgi;

import eu.kalafatic.dcode.config.provider.ProviderRegistry;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.lifecycle.LifecycleStage;
import eu.kalafatic.dcode.core.service.ReviewService;
import eu.kalafatic.dcode.core.service.SessionService;
import eu.kalafatic.dcode.core.service.WorkspaceService;
import eu.kalafatic.dcode.harness.execution.SessionExecutor;
import eu.kalafatic.dcode.harness.tool.ToolRegistry;
import eu.kalafatic.dcode.repositories.git.GitEngine;
import eu.kalafatic.dcode.repositories.service.RepositoryOperations;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;
import org.osgi.framework.FrameworkUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OSGi runtime wiring verification: the real bundle set (core, config, repositories,
 * harness, persistence, server) is started by the Tycho surefire runtime and every
 * service contract is registered and reachable through the OSGi service registry —
 * proving the headless runtime assembles itself without SWT or Workbench.
 */
class OsgiWiringTest {

    @BeforeAll
    static void startDCodeBundles() throws Exception {
        // A real product runtime lists all bundles in its configuration; the surefire
        // runtime starts bundles lazily, so start the DCode set explicitly to mirror
        // the product configuration.
        BundleContext context = FrameworkUtil.getBundle(OsgiWiringTest.class)
                .getBundleContext();
        for (Bundle bundle : context.getBundles()) {
            String name = bundle.getSymbolicName();
            if (name != null && name.startsWith("eu.kalafatic.dcode")) {
                bundle.start();
            }
        }
    }

    private BundleContext context() {
        return FrameworkUtil.getBundle(OsgiWiringTest.class).getBundleContext();
    }

    @Test
    void allServicesRegistered() {
        BundleContext context = context();
        assertNotNull(context.getServiceReference(eu.kalafatic.dcode.core.event.EventBus.class),
                "EventBus must be registered by the core bundle");

        assertNotNull(context.getServiceReference(SessionService.class),
                "SessionService (durable SQLite impl expected, higher ranking)");
        assertNotNull(context.getServiceReference(WorkspaceService.class));
        assertNotNull(context.getServiceReference(ReviewService.class));
        assertNotNull(context.getServiceReference(ProviderRegistry.class));
        assertNotNull(context.getServiceReference(ToolRegistry.class));
        assertNotNull(context.getServiceReference(SessionExecutor.class));
        assertNotNull(context.getServiceReference(GitEngine.class));
        assertNotNull(context.getServiceReference(RepositoryOperations.class));
    }

    @Test
    void durableImplementationIsPreferred() {
        SessionService sessionService = context().getService(
                context().getServiceReference(SessionService.class));
        assertNotNull(sessionService);
        assertTrue(sessionService.getClass().getName().contains("persistence"),
                "expected the SQLite-backed session service, got "
                        + sessionService.getClass().getName());

        WorkspaceService workspaceService = context().getService(
                context().getServiceReference(WorkspaceService.class));
        assertTrue(workspaceService.getClass().getName().contains("persistence"),
                "expected the SQLite-backed workspace service, got "
                        + workspaceService.getClass().getName());
    }

    @Test
    void fullLifecycleReachedServerStage() {
        DCodeLifecycle lifecycle = context().getService(
                context().getServiceReference(DCodeLifecycle.class));
        assertNotNull(lifecycle);
        if (lifecycle instanceof eu.kalafatic.dcode.core.CoreRuntime runtime) {
            System.out.println("[OsgiWiringTest] stages=" + runtime.describeStages());
            System.out.println("[OsgiWiringTest] lastFailure=" + runtime.lastFailure());
        }
        assertEquals(eu.kalafatic.dcode.core.lifecycle.StageState.STARTED,
                lifecycle.getStageState(LifecycleStage.HARNESS));
        assertEquals(eu.kalafatic.dcode.core.lifecycle.StageState.STARTED,
                lifecycle.getStageState(LifecycleStage.SERVER));
        assertTrue(lifecycle.isReady());
    }

    @Test
    void builtinToolsRegistered() {
        ToolRegistry registry = context().getService(
                context().getServiceReference(ToolRegistry.class));
        assertNotNull(registry);
        assertTrue(registry.tools().size() >= 10, "expected the built-in tool set, got "
                + registry.tools().size());
        assertTrue(registry.find("write_file").isPresent());
        // Git tools (git_status/git_diff/git_commit) were removed from the harness to
        // keep the project filesystem-oriented (sync-241126-1); the core file/shell/build
        // tools remain the harness surface.
        assertTrue(registry.find("shell").isPresent());
        assertTrue(registry.find("run_build").isPresent());
    }

    @Test
    void ollamaProviderRegisteredByDefault() {
        ProviderRegistry registry = context().getService(
                context().getServiceReference(ProviderRegistry.class));
        assertNotNull(registry);
        assertTrue(registry.find("ollama").isPresent());
    }
}
