// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.neoforge.common;

import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SimpleEventBus;

public final class NeoForge {
    public static final IEventBus EVENT_BUS = new SimpleEventBus();
    private NeoForge() {}
}
