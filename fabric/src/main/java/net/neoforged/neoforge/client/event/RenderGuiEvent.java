// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.neoforge.client.event;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.bus.api.Event;

public abstract class RenderGuiEvent extends Event {
    private final GuiGraphics graphics;
    private final DeltaTracker delta;

    protected RenderGuiEvent(GuiGraphics graphics, DeltaTracker delta) {
        this.graphics = graphics;
        this.delta = delta;
    }

    public GuiGraphics getGuiGraphics() { return graphics; }
    public DeltaTracker getPartialTick() { return delta; }

    public static class Post extends RenderGuiEvent {
        public Post(GuiGraphics graphics, DeltaTracker delta) { super(graphics, delta); }
    }
}
