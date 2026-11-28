// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.bus.api;

public abstract class Event {
    private boolean canceled;
    public void setCanceled(boolean canceled) { this.canceled = canceled; }
    public boolean isCanceled() { return canceled; }
}
