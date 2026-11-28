// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.neoforge.event.entity.player;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.neoforged.bus.api.Event;
import net.neoforged.bus.api.ICancellableEvent;

public abstract class PlayerInteractEvent extends Event {
    private final Player player;
    private final BlockPos pos;
    private final Direction face;
    protected PlayerInteractEvent(Player player, BlockPos pos, Direction face) { this.player = player; this.pos = pos; this.face = face; }
    public Player getEntity() { return player; }
    public BlockPos getPos() { return pos; }
    public Direction getFace() { return face; }
    public Level getLevel() { return player.level(); }

    public static class LeftClickBlock extends PlayerInteractEvent implements ICancellableEvent {
        public LeftClickBlock(Player player, BlockPos pos, Direction face) { super(player, pos, face); }
    }
}
