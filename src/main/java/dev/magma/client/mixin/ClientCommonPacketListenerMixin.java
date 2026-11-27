package dev.magma.client.mixin;

import dev.magma.client.modules.NoFall;
import net.minecraft.client.multiplayer.ClientCommonPacketListenerImpl;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** NoFall: while falling, tell the server we're on the ground so it never builds up fall damage. */
@Mixin(ClientCommonPacketListenerImpl.class)
public abstract class ClientCommonPacketListenerMixin {
    @Inject(method = "send(Lnet/minecraft/network/protocol/Packet;)V", at = @At("HEAD"))
    private void magma$noFall(Packet<?> packet, CallbackInfo ci) {
        if (packet instanceof ServerboundMovePlayerPacket && NoFall.shouldSpoof()) {
            ((MovePacketAccessor) packet).magma$setOnGround(true);
        }
    }
}
