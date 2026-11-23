package dev.magma.client.mixin;

import dev.magma.client.util.PlayerControl;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

/** Lets automation modules hold "attack" without the window needing focus / a grabbed mouse. */
@Mixin(Minecraft.class)
public class MinecraftMixin {
    @ModifyVariable(method = "continueAttack", at = @At("HEAD"), argsOnly = true)
    private boolean magma$forceAttack(boolean leftClick) {
        Minecraft mc = (Minecraft) (Object) this;
        return leftClick || (PlayerControl.forceAttack && mc.screen == null);
    }
}
