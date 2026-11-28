// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.neoforge.client.event;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public abstract class ScreenEvent extends Event {
    private final Screen screen;
    protected ScreenEvent(Screen screen) { this.screen = screen; }
    public Screen getScreen() { return screen; }

    public abstract static class Render extends ScreenEvent {
        private final GuiGraphics graphics;
        private final int mouseX, mouseY;
        private final float partialTick;
        protected Render(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            super(screen);
            this.graphics = graphics; this.mouseX = mouseX; this.mouseY = mouseY; this.partialTick = partialTick;
        }
        public GuiGraphics getGuiGraphics() { return graphics; }
        public int getMouseX() { return mouseX; }
        public int getMouseY() { return mouseY; }
        public float getPartialTick() { return partialTick; }

        public static class Post extends Render {
            public Post(Screen screen, GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
                super(screen, graphics, mouseX, mouseY, partialTick);
            }
        }
    }

    public abstract static class MouseButtonPressed extends ScreenEvent {
        private final double mouseX, mouseY;
        private final int button;
        protected MouseButtonPressed(Screen screen, double mouseX, double mouseY, int button) {
            super(screen);
            this.mouseX = mouseX; this.mouseY = mouseY; this.button = button;
        }
        public double getMouseX() { return mouseX; }
        public double getMouseY() { return mouseY; }
        public int getButton() { return button; }

        public static class Pre extends MouseButtonPressed implements ICancellableEvent {
            public Pre(Screen screen, double mouseX, double mouseY, int button) { super(screen, mouseX, mouseY, button); }
        }
    }
}
