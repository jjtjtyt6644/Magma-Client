package dev.magma.client.fabric.mixin;

import net.minecraft.client.Minecraft;
import net.minecraft.client.MouseHandler;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Mouse wheel in game (Builder's Shift+scroll nudge). */
@Mixin(MouseHandler.class)
public class MouseHandlerMixin {
    @Inject(method = "onScroll", at = @At("HEAD"), cancellable = true)
    private void magma$scroll(long window, double xOffset, double yOffset, CallbackInfo ci) {
        Minecraft mc = Minecraft.getInstance();
        if (window != mc.getWindow().getWindow() || mc.screen != null || mc.player == null) return;
        var e = NeoForge.EVENT_BUS.post(new InputEvent.MouseScrollingEvent(xOffset, yOffset, mc.mouseHandler.xpos(), mc.mouseHandler.ypos()));
        if (e.isCanceled()) ci.cancel();
    }
}
