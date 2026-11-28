package dev.magma.client.fabric.mixin;

import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Mining speed (FastBreak). */
@Mixin(Player.class)
public class PlayerBreakSpeedMixin {
    @Inject(method = "getDestroySpeed", at = @At("RETURN"), cancellable = true)
    private void magma$speed(BlockState state, CallbackInfoReturnable<Float> cir) {
        Player self = (Player) (Object) this;
        if (!self.level().isClientSide()) return;
        var e = NeoForge.EVENT_BUS.post(new PlayerEvent.BreakSpeed(self, state, cir.getReturnValue()));
        cir.setReturnValue(e.getNewSpeed());
    }
}
