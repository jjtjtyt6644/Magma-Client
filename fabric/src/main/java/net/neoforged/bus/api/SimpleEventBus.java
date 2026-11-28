// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.bus.api;

import java.lang.reflect.Method;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/** A tiny event bus: listeners by event class, ordered by priority. */
public final class SimpleEventBus implements IEventBus {
    private record Listener(Class<?> type, EventPriority priority, Consumer<Object> call) {}

    private final List<Listener> listeners = new CopyOnWriteArrayList<>();

    private void add(Listener l) {
        listeners.add(l);
        listeners.sort(Comparator.comparingInt(x -> x.priority().ordinal()));
    }

    @Override
    public void register(Object target) {
        for (Method m : target.getClass().getDeclaredMethods()) {
            SubscribeEvent s = m.getAnnotation(SubscribeEvent.class);
            if (s == null || m.getParameterCount() != 1) continue;
            m.setAccessible(true);
            Class<?> type = m.getParameterTypes()[0];
            add(new Listener(type, s.priority(), e -> {
                try { m.invoke(target, e); } catch (ReflectiveOperationException ex) { throw new RuntimeException(ex.getCause() != null ? ex.getCause() : ex); }
            }));
        }
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer) {
        add(new Listener(eventType, EventPriority.NORMAL, e -> consumer.accept((T) e)));
    }

    @Override
    public <T extends Event> T post(T event) {
        for (Listener l : listeners) {
            if (!l.type().isInstance(event)) continue;
            try {
                l.call().accept(event);
            } catch (Throwable t) {
                dev.magma.client.MagmaClient.LOGGER.error("[Magma] listener failed for {}", event.getClass().getSimpleName(), t);
            }
        }
        return event;
    }
}
