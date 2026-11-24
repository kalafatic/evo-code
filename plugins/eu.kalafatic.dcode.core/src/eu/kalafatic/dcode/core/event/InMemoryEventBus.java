package eu.kalafatic.dcode.core.event;

import java.util.List;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Thread-safe in-memory event bus.
 *
 * <p>Dispatch is asynchronous by default (single dispatcher thread, FIFO per publisher);
 * pass {@code null} executor for synchronous dispatch (useful in tests). A handler
 * throwing an exception never breaks the dispatch loop; the failure is reported to the
 * error consumer when configured.</p>
 */
public final class InMemoryEventBus implements EventBus {

    private record Entry(Predicate<DCodeEvent> filter, Consumer<DCodeEvent> handler,
                         AtomicBoolean active) implements Subscription {
        @Override
        public void unsubscribe() {
            active.set(false);
        }
    }

    private final List<Entry> entries = new CopyOnWriteArrayList<>();
    private final Executor dispatcher;
    private final Consumer<Throwable> errorConsumer;

    public InMemoryEventBus() {
        this(Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "dcode-event-dispatcher");
            t.setDaemon(true);
            return t;
        }), null);
    }

    public InMemoryEventBus(Executor dispatcher, Consumer<Throwable> errorConsumer) {
        this.dispatcher = dispatcher;
        this.errorConsumer = errorConsumer != null
                ? errorConsumer
                : t -> System.err.println("[EVO-Code] event handler failure: " + t);
    }

    @Override
    public void publish(DCodeEvent event) {
        Objects.requireNonNull(event, "event");
        Runnable dispatch = () -> {
            for (Entry entry : entries) {
                if (!entry.active().get()) {
                    continue;
                }
                try {
                    if (entry.filter().test(event)) {
                        entry.handler().accept(event);
                    }
                } catch (Throwable t) {
                    errorConsumer.accept(t);
                }
            }
        };
        if (dispatcher == null) {
            dispatch.run();
        } else {
            dispatcher.execute(dispatch);
        }
    }

    @Override
    public Subscription subscribe(Predicate<DCodeEvent> filter, Consumer<DCodeEvent> handler) {
        Objects.requireNonNull(filter, "filter");
        Objects.requireNonNull(handler, "handler");
        Entry entry = new Entry(filter, handler, new AtomicBoolean(true));
        entries.add(entry);
        return entry;
    }
}
