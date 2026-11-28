// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.neoforge.client.event;

import net.neoforged.bus.api.Event;

public abstract class ClientPlayerNetworkEvent extends Event {
    public static class LoggingIn extends ClientPlayerNetworkEvent {}
    public static class LoggingOut extends ClientPlayerNetworkEvent {}
}
