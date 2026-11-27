package dev.magma.client.mixin;

import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets NoFall mark outgoing movement packets as "on the ground". */
@Mixin(ServerboundMovePlayerPacket.class)
public interface MovePacketAccessor {
    @Mutable
    @Accessor("onGround")
    void magma$setOnGround(boolean onGround);
}
