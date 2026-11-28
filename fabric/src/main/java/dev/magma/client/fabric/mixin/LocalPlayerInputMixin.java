package dev.magma.client.fabric.mixin;

import net.minecraft.client.player.LocalPlayer;
import net.neoforged.neoforge.client.event.MovementInputUpdateEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Movement keys after they're read (SafeWalk stopping at edges, AutoCombat steering). */
@Mixin(LocalPlayer.class)
public class LocalPlayerInputMixin {
    @Inject(method = "aiStep", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/player/Input;tick(ZF)V", shift = At.Shift.AFTER))
    private void magma$input(CallbackInfo ci) {
        LocalPlayer self = (LocalPlayer) (Object) this;
        NeoForge.EVENT_BUS.post(new MovementInputUpdateEvent(self, self.input));
    }
}
