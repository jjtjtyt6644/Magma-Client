package dev.magma.client.mixin;

import dev.magma.client.modules.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.Entity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Freecam: the mouse turns the free camera, not your character. */
@Mixin(Entity.class)
public abstract class EntityTurnMixin {
    @Inject(method = "turn(DD)V", at = @At("HEAD"), cancellable = true)
    private void magma$freecamTurn(double yRot, double xRot, CallbackInfo ci) {
        Entity self = (Entity) (Object) this;
        if (self == Minecraft.getInstance().player && Freecam.turnCamera(yRot, xRot)) ci.cancel();
    }
}
