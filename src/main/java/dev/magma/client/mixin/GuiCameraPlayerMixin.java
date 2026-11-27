package dev.magma.client.mixin;

import dev.magma.client.modules.Freecam;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Gui;
import net.minecraft.world.entity.player.Player;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Freecam: keep showing your hotbar, health and food while the camera is detached. */
@Mixin(Gui.class)
public abstract class GuiCameraPlayerMixin {
    @Inject(method = "getCameraPlayer", at = @At("HEAD"), cancellable = true)
    private void magma$freecamHud(CallbackInfoReturnable<Player> cir) {
        if (Freecam.active() && Minecraft.getInstance().player != null) cir.setReturnValue(Minecraft.getInstance().player);
    }
}
