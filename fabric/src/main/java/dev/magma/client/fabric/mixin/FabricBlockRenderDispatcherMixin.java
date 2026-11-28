package dev.magma.client.fabric.mixin;

import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import dev.magma.client.modules.Xray;
import net.minecraft.client.renderer.block.BlockRenderDispatcher;
import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.BlockAndTintGetter;
import net.minecraft.world.level.block.state.BlockState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Classic X-Ray: skip meshing every block that isn't an ore / lava (vanilla signature). */
@Mixin(BlockRenderDispatcher.class)
public class FabricBlockRenderDispatcherMixin {
    @Inject(method = "renderBatched", at = @At("HEAD"), cancellable = true, require = 0)
    private void magma$xrayHide(BlockState state, BlockPos pos, BlockAndTintGetter level, PoseStack poseStack, VertexConsumer consumer,
                                boolean checkSides, RandomSource random, CallbackInfo ci) {
        if (Xray.classicActive && !Xray.classicVisible(state)) ci.cancel();
    }
}
