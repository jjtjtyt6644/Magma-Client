package dev.magma.client.mixin;

import dev.magma.client.modules.Xray;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Classic X-Ray: render every face of ore blocks even when buried in stone. */
@Mixin(Block.class)
public class BlockMixin {
    @Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true, require = 0)
    private static void magma$xrayFaces(BlockState state, BlockGetter level, BlockPos offset, Direction face, BlockPos pos,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (Xray.classicActive) cir.setReturnValue(Xray.classicVisible(state));
    }
}
