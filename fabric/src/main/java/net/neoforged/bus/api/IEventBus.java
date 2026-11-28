// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.bus.api;

import java.util.function.Consumer;

public interface IEventBus {
    void register(Object target);
    <T extends Event> void addListener(Class<T> eventType, Consumer<T> consumer);
    <T extends Event> T post(T event);
}
