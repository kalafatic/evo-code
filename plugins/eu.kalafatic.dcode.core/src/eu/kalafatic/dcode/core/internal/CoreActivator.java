package eu.kalafatic.dcode.core.internal;

import eu.kalafatic.dcode.core.CoreRuntime;
import eu.kalafatic.dcode.core.config.ConfigurationService;
import eu.kalafatic.dcode.core.event.EventBus;
import eu.kalafatic.dcode.core.lifecycle.DCodeLifecycle;
import eu.kalafatic.dcode.core.log.DCodeLog;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;

/**
 * Registers the core runtime services in the OSGi service registry.
 *
 * <p>Manual registration follows the established family convention (TRYST
 * {@code ApplicationLifecycleCoordinator} pattern); DCode deliberately does not introduce a
 * second DI framework next to OSGi.</p>
 */
public final class CoreActivator implements BundleActivator {

    private CoreRuntime runtime;
    private ServiceRegistration<EventBus> eventBusRegistration;
    private ServiceRegistration<DCodeLifecycle> lifecycleRegistration;
    private ServiceRegistration<ConfigurationService> configurationRegistration;

    @Override
    public void start(BundleContext context) {
        runtime = new CoreRuntime();
        runtime.start();
        eventBusRegistration = context.registerService(EventBus.class, runtime, null);
        lifecycleRegistration = context.registerService(DCodeLifecycle.class, runtime, null);
        configurationRegistration = context.registerService(ConfigurationService.class, runtime, null);
        DCodeLog.info("core", "EVO-Code core services registered in OSGi");
    }

    @Override
    public void stop(BundleContext context) {
        if (eventBusRegistration != null) {
            eventBusRegistration.unregister();
        }
        if (lifecycleRegistration != null) {
            lifecycleRegistration.unregister();
        }
        if (configurationRegistration != null) {
            configurationRegistration.unregister();
        }
        if (runtime != null) {
            runtime.stop();
        }
        runtime = null;
    }
}
