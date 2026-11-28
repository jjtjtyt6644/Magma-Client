package dev.magma.client.mixin.compat;

import dev.magma.client.modules.Xray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Classic X-Ray with Sodium: draw every side of the blocks you can see (the stone around them is gone). */
@Pseudo
@Mixin(targets = "net.caffeinemc.mods.sodium.client.render.chunk.compile.pipeline.BlockOcclusionCache", remap = false)
public class SodiumOcclusionMixin {
    @Inject(method = "shouldDrawSide", at = @At("HEAD"), cancellable = true, remap = false, require = 0)
    private void magma$xrayFaces(BlockState state, BlockGetter level, BlockPos pos, Direction side, CallbackInfoReturnable<Boolean> cir) {
        if (Xray.classicActive && Xray.classicVisible(state)) cir.setReturnValue(true);
    }
}
