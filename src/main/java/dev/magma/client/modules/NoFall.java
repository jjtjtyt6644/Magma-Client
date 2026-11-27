package dev.magma.client.modules;

import dev.magma.client.module.Category;
import dev.magma.client.module.Module;
import dev.magma.client.module.ModuleManager;

/**
 * No fall damage: while you're falling, the movement packets tell the server you're on the ground, so it never
 * builds up a fall. Fly uses this too (and for a few seconds after you stop flying).
 */
public class NoFall extends Module {
    /** Fly sets this so landing after a flight is safe even with NoFall off. */
    public static long protectUntil;

    public NoFall() {
        super("NoFall", "Take no fall damage.", Category.MOVEMENT, -1);
    }

    public static boolean shouldSpoof() {
        if (!ModuleManager.ready() || mc.player == null) return false;
        if (mc.player.isFallFlying() || mc.player.getAbilities().invulnerable) return false; // elytra / creative
        boolean on = ModuleManager.get(NoFall.class).isEnabled() || ModuleManager.get(Fly.class).isEnabled()
                || System.currentTimeMillis() < protectUntil;
        return on && (mc.player.fallDistance > 1.5f || mc.player.getDeltaMovement().y < -0.5
                || ModuleManager.get(Fly.class).isEnabled());
    }
}
