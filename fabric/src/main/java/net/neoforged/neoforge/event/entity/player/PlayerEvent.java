// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.neoforge.event.entity.player;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.bus.api.Event;

public abstract class PlayerEvent extends Event {
    private final Player player;
    protected PlayerEvent(Player player) { this.player = player; }
    public Player getEntity() { return player; }

    public static class BreakSpeed extends PlayerEvent {
        private final BlockState state;
        private final float original;
        private float speed;
        public BreakSpeed(Player player, BlockState state, float original) {
            super(player);
            this.state = state; this.original = original; this.speed = original;
        }
        public BlockState getState() { return state; }
        public float getOriginalSpeed() { return original; }
        public float getNewSpeed() { return speed; }
        public void setNewSpeed(float speed) { this.speed = speed; }
    }
}
