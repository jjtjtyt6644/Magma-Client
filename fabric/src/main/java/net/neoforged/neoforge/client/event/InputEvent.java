// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.neoforge.client.event;

import net.minecraft.client.KeyMapping;
import net.minecraft.world.InteractionHand;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public abstract class InputEvent extends Event {
    public static class Key extends InputEvent {
        private final int key, scanCode, action, modifiers;
        public Key(int key, int scanCode, int action, int modifiers) {
            this.key = key; this.scanCode = scanCode; this.action = action; this.modifiers = modifiers;
        }
        public int getKey() { return key; }
        public int getScanCode() { return scanCode; }
        public int getAction() { return action; }
        public int getModifiers() { return modifiers; }
    }

    public static class MouseScrollingEvent extends InputEvent implements ICancellableEvent {
        private final double deltaX, deltaY, mouseX, mouseY;
        public MouseScrollingEvent(double deltaX, double deltaY, double mouseX, double mouseY) {
            this.deltaX = deltaX; this.deltaY = deltaY; this.mouseX = mouseX; this.mouseY = mouseY;
        }
        public double getScrollDeltaX() { return deltaX; }
        public double getScrollDeltaY() { return deltaY; }
        public double getMouseX() { return mouseX; }
        public double getMouseY() { return mouseY; }
    }

    public static class InteractionKeyMappingTriggered extends InputEvent implements ICancellableEvent {
        private final int button;
        private final KeyMapping keyMapping;
        private final InteractionHand hand;
        private boolean swing = true;
        public InteractionKeyMappingTriggered(int button, KeyMapping keyMapping, InteractionHand hand) {
            this.button = button; this.keyMapping = keyMapping; this.hand = hand;
        }
        public boolean isAttack() { return button == 0; }
        public boolean isUseItem() { return button == 1; }
        public boolean isPickBlock() { return button == 2; }
        public KeyMapping getKeyMapping() { return keyMapping; }
        public InteractionHand getHand() { return hand; }
        public void setSwingHand(boolean value) { swing = value; }
        public boolean shouldSwingHand() { return swing; }
    }
}
