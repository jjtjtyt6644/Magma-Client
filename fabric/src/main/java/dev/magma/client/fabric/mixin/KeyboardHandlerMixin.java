package dev.magma.client.fabric.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.Minecraft;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.common.NeoForge;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Key presses (module keybinds, menu key, Builder's arrow keys). */
@Mixin(KeyboardHandler.class)
public class KeyboardHandlerMixin {
    @Inject(method = "keyPress", at = @At("TAIL"))
    private void magma$key(long window, int key, int scanCode, int action, int modifiers, CallbackInfo ci) {
        if (window == Minecraft.getInstance().getWindow().getWindow())
            NeoForge.EVENT_BUS.post(new InputEvent.Key(key, scanCode, action, modifiers));
    }
}
