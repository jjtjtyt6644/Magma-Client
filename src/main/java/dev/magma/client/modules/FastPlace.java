package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;

/** No 4-tick pause between block placements while you hold right-click. */
public class FastPlace extends Module {
    public FastPlace() {
        super("FastPlace", "Place blocks as fast as you can hold right-click (no 4-tick delay).", Category.AUTOMATION, -1);
    }

    @Override
    public void onTick() {
        if (mc.player == null || !mc.options.keyUse.isDown()) return;
        if (!(mc.player.getMainHandItem().getItem() instanceof net.minecraft.world.item.BlockItem)
                && !(mc.player.getOffhandItem().getItem() instanceof net.minecraft.world.item.BlockItem)) return;
        try { ((dev.magma.client.mixin.MinecraftAccessor) mc).magma$setRightClickDelay(0); } catch (Throwable ignored) {}
    }
}
