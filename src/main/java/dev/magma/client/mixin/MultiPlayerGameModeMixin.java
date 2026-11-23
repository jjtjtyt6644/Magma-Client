package dev.magma.client.mixin;

import dev.magma.client.module.ModuleManager;
import dev.magma.client.modules.FastBreak;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** FastBreak: removes the 5-tick pause between breaking blocks. */
@Mixin(MultiPlayerGameMode.class)
public class MultiPlayerGameModeMixin {
    @Shadow private int destroyDelay;

    @Inject(method = "continueDestroyBlock", at = @At("HEAD"))
    private void magma$noDelay(BlockPos pos, Direction dir, CallbackInfoReturnable<Boolean> cir) {
        if (!ModuleManager.ready()) return;
        FastBreak fb = ModuleManager.get(FastBreak.class);
        if (fb.isEnabled() && fb.noDelay.get()) destroyDelay = 0;
    }
}
