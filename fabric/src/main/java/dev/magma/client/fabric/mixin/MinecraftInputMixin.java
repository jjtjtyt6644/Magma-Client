package dev.magma.client.fabric.mixin;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.phys.HitResult;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Attack / use clicks (LavaGuard blocking, Freecam, Builder's stick). */
@Mixin(Minecraft.class)
public class MinecraftInputMixin {
    private static boolean magma$fire(int button, KeyMapping key) {
        Minecraft mc = Minecraft.getInstance();
        var e = NeoForge.EVENT_BUS.post(new InputEvent.InteractionKeyMappingTriggered(button, key, InteractionHand.MAIN_HAND));
        if (!e.isCanceled()) return false;
        if (e.shouldSwingHand() && mc.player != null) mc.player.swing(InteractionHand.MAIN_HAND);
        return true;
    }

    @Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
    private void magma$attack(CallbackInfoReturnable<Boolean> cir) {
        if (magma$fire(0, ((Minecraft) (Object) this).options.keyAttack)) cir.setReturnValue(false);
    }

    @Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
    private void magma$use(CallbackInfo ci) {
        if (magma$fire(1, ((Minecraft) (Object) this).options.keyUse)) ci.cancel();
    }

    @Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
    private void magma$keepAttacking(boolean leftClick, CallbackInfo ci) {
        Minecraft mc = (Minecraft) (Object) this;
        if (!leftClick || mc.player == null || mc.player.isUsingItem() || mc.hitResult == null || mc.hitResult.getType() != HitResult.Type.BLOCK) return;
        if (magma$fire(0, mc.options.keyAttack)) {
            if (mc.gameMode != null) mc.gameMode.stopDestroyBlock();
            ci.cancel();
        }
    }
}
