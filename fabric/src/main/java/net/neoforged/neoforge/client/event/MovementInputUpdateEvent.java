// Fabric stand-in for the NeoForge API Magma uses (so the shared code runs unchanged on Fabric).
package net.neoforged.neoforge.client.event;

import net.minecraft.client.player.Input;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.Event;

public class MovementInputUpdateEvent extends Event {
    private final Player player;
    private final Input input;
    public MovementInputUpdateEvent(Player player, Input input) { this.player = player; this.input = input; }
    public Player getEntity() { return player; }
    public Input getInput() { return input; }
}
